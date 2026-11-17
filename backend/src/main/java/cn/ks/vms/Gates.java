package cn.ks.vms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Gates {
    record Lookup(@Positive long gateId, @NotNull Policy.Direction direction,
                  @Size(max = 100) String token, @Positive Long visitId) {}
    record Confirm(@Positive long gateId, @Size(max = 100) String token, @Positive Long visitId,
                   boolean identityChecked, @Size(max = 24) String documentType, @Size(max = 40) String documentNumber,
                   @Size(max = 20) String plate) {}
    record Result(boolean ok, String code, String message, Map<String, Object> visit) {}
    record Identity(long id, String documentType, String documentTail) {}
    record Pass(long visitId, String direction, boolean active, Instant expiresAt) {}
    record Presence(long id, Instant inAt, Instant outAt, Instant vehicleOutAt, String plate, long identityId) {}
    record GateRequest(String payloadKey, String resultJson) {}

    private final Db db;
    private final Catalog catalog;
    private final Visits visits;
    private final Access access;
    private final Crypto crypto;
    private final Clock clock;
    private final ObjectMapper json;

    Gates(Db db, Catalog catalog, Visits visits, Access access, Crypto crypto, Clock clock, ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.visits = visits;
        this.access = access;
        this.crypto = crypto;
        this.clock = clock;
        this.json = json;
    }

    Map<String, Object> preview(Catalog.Actor actor, Lookup input) {
        var gate = catalog.requireGate(actor, input.gateId());
        access.limit("gate-preview", Long.toString(actor.id()), 180, 60);
        String key = tokenKey(input.token());
        Pass pass = key == null ? null : pass(key, false);
        Visit visit = visits.get(resolve(pass, input.visitId(), key, input.direction()));
        if (visit.getSiteId() != gate.siteId()) {
            throw Problem.conflict("该访问不属于当前厂区");
        }
        var view = visits.view(visit.getId());
        var identity = known(visit);
        if (identity != null) {
            view.put("documentType", identity.documentType());
            view.put("documentTail", identity.documentTail());
        }
        view.put("identityKnown", identity != null);
        return view;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Result confirm(Catalog.Actor actor, Policy.Direction direction, String requestKey, Confirm input) {
        var gate = catalog.requireGate(actor, input.gateId());
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{12,64}")) {
            throw Problem.bad("操作编号无效");
        }
        String fingerprint = crypto.digest("gate-request", encode(input));
        db.update("""
            INSERT INTO gate_request(staff_id,direction,request_key,payload_key) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE request_key=request_key
            """, actor.id(), direction.name(), requestKey, fingerprint);
        var saved = db.one(GateRequest.class, """
            SELECT payload_key,result_json FROM gate_request WHERE staff_id=? AND direction=? AND request_key=? FOR UPDATE
            """, actor.id(), direction.name(), requestKey);
        if (!saved.payloadKey().equals(fingerprint)) {
            throw Problem.conflict("该操作编号已用于其他核验");
        }
        if (saved.resultJson() != null) {
            try {
                return json.readValue(saved.resultJson(), Result.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Invalid stored gate result", error);
            }
        }
        String key = tokenKey(input.token());
        Pass previous = key == null ? null : pass(key, false);
        Long visitId = null;
        Result result;
        try {
            long target = resolve(previous, input.visitId(), key, direction);
            var visit = visits.lock(target);
            if (visit.getSiteId() != gate.siteId()) {
                throw new Problem(409, "WRONG_SITE", "该访问不属于当前厂区");
            }
            visitId = target;
            if (key != null) {
                resolve(pass(key, true), input.visitId(), key, direction);
            }
            result = process(actor, gate, input, direction, key, visit);
        } catch (Problem error) {
            event(actor, gate.id(), visitId, direction.name(), error.code, clock.instant());
            if (java.util.Set.of("BLACKLIST", "DUPLICATE_ENTRY", "IDENTITY_MISMATCH", "PLATE_MISMATCH", "NO_ENTRY")
                .contains(error.code)) {
                alert(gate.siteId(), visitId, error.code, visitId == null ? "gate:" + gate.id() : visitId.toString());
            }
            result = new Result(false, error.code, error.getMessage(), null);
        }
        db.update("UPDATE gate_request SET result_json=? WHERE staff_id=? AND direction=? AND request_key=?",
            encode(result), actor.id(), direction.name(), requestKey);
        return result;
    }

    private Result process(Catalog.Actor actor, Catalog.Gate gate, Confirm input, Policy.Direction direction,
                           String key, Visit visit) {
        var now = clock.instant();
        var present = presence(visit.getId());
        if (!input.identityChecked()) {
            throw Problem.bad("请先核对来访人身份");
        }
        if (direction == Policy.Direction.ENTRY) {
            enter(gate, input, visit, present, now);
        } else {
            leave(input, visit, present, now);
        }
        if (key != null) {
            db.update("UPDATE gate_pass SET active=FALSE WHERE token_key=?", key);
        } else {
            db.update("UPDATE gate_pass SET active=FALSE WHERE visit_id=? AND direction=? AND active",
                visit.getId(), direction.name());
        }
        event(actor, gate.id(), visit.getId(), direction.name(), key == null ? "MANUAL" : "QR", now);
        return new Result(true, "OK", direction == Policy.Direction.ENTRY ? "入厂已登记，请放行" : "离厂已登记",
            visits.view(visit.getId()));
    }

    private void enter(Catalog.Gate gate, Confirm input, Visit visit, Presence present, Instant now) {
        catalog.site(gate.siteId());
        if (present != null) {
            blocked("DUPLICATE_ENTRY", "该访问已登记入厂");
        }
        if (!"APPROVED".equals(visit.getState())) {
            blocked("NOT_APPROVED", "该申请尚未批准或已撤销");
        }
        if (visit.getDepartmentId() == null || visit.getType().needsArea() && visit.getAreaId() == null) {
            blocked("RECEPTION_INCOMPLETE", "接待信息尚未确认，请联系门岗或行政人员");
        }
        if (now.isBefore(visit.getWindowStart()) || !now.isBefore(visit.getWindowEnd())) {
            blocked("WINDOW_CLOSED", "当前不在批准入厂时段");
        }
        if (!catalog.activeArea(gate.siteId(), visit.getAreaId())) {
            blocked("AREA_CLOSED", "获准访问区域已停用，请联系接访人员");
        }
        Instant due = Policy.earlier(visit.getPlannedOut(), now.plus(visit.getMaxMinutes(), ChronoUnit.MINUTES));
        if (visit.getType() == Policy.Type.CONTRACTOR) {
            if (visit.getPermitUntil() == null || !now.isBefore(visit.getPermitUntil())
                || visit.getTrainingUntil() == null || !now.isBefore(visit.getTrainingUntil())) {
                blocked("CLEARANCE_EXPIRED", "施工许可或安全培训已失效");
            }
            due = Policy.earlier(due, visit.getPermitUntil());
        }
        verifyPlate(visit.getPlate(), input.plate());
        var identity = verifyIdentity(visit, input);
        lockIdentity(identity.id());
        if (db.count("""
            SELECT COUNT(*) FROM blacklist WHERE identity_id=? AND (site_id IS NULL OR site_id=?) AND NOT disabled
            AND starts_at<=? AND (ends_at IS NULL OR ends_at>?)
            """, identity.id(), gate.siteId(), now, now) > 0) {
            blocked("BLACKLIST", "黑名单命中，请联系安全管理人员");
        }
        try {
            db.update("""
                INSERT INTO presence(visit_id,site_id,identity_id,plate,in_at,vehicle_in_at,due_at)
                VALUES (?,?,?,?,?,?,?)
                """, visit.getId(), gate.siteId(), identity.id(), visit.getPlate(), now, visit.getPlate() == null ? null : now, due);
        } catch (DuplicateKeyException error) {
            blocked("DUPLICATE_ENTRY", "该人员或车辆已有在厂记录");
        }
        db.update("UPDATE visit SET identity_id=? WHERE id=?", identity.id(), visit.getId());
    }

    private void leave(Confirm input, Visit visit, Presence present, Instant now) {
        if (present == null) {
            blocked("NO_ENTRY", "找不到入厂记录，请核实并提交补录");
        }
        if (present.outAt() != null) {
            blocked("DUPLICATE_EXIT", "该访客已登记离厂");
        }
        if (present.vehicleOutAt() == null) {
            verifyPlate(present.plate(), input.plate());
        }
        verifyIdentity(visit, input);
        db.update("""
            UPDATE presence SET out_at=?, vehicle_out_at=IF(plate IS NOT NULL AND vehicle_out_at IS NULL,?,vehicle_out_at)
            WHERE id=?
            """, now, now, present.id());
    }

    private Identity verifyIdentity(Visit visit, Confirm input) {
        var known = known(visit);
        if (Visits.hasText(input.documentNumber())) {
            var actual = identity(input.documentType(), input.documentNumber());
            if (known != null && known.id() != actual.id()) {
                blocked("IDENTITY_MISMATCH", "证件与已核验记录不一致");
            }
            return actual;
        }
        if (known == null) {
            throw Problem.bad("请录入现场核对的证件号码");
        }
        return known;
    }

    Presence presence(long visit) {
        return db.one(Presence.class, """
            SELECT id,in_at,out_at,vehicle_out_at,plate,identity_id FROM presence WHERE visit_id=? FOR UPDATE
            """, visit);
    }

    private Identity known(Visit visit) {
        if (visit.getIdentityId() != null) {
            return db.one(Identity.class, "SELECT id,document_type,document_tail FROM identity_record WHERE id=?",
                visit.getIdentityId());
        }
        if (visit.getPhoneKey() == null) {
            return null;
        }
        return db.one(Identity.class, """
            SELECT i.id,i.document_type,i.document_tail FROM visit v JOIN identity_record i ON v.identity_id=i.id
            WHERE v.phone_key=? AND v.name=? ORDER BY v.id DESC LIMIT 1
            """, visit.getPhoneKey(), visit.getName());
    }

    Identity identity(String type, String number) {
        if (!java.util.Set.of("ID", "PASSPORT", "OTHER").contains(type == null ? "" : type)
            || !Visits.hasText(number)) {
            throw Problem.bad("请选择证件类型并录入号码");
        }
        String value = number.strip().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9-]{4,40}")) {
            throw Problem.bad("证件号码格式有误");
        }
        String hash = crypto.digest("identity", type + ":" + value);
        db.update("""
            INSERT INTO identity_record(document_type,document_key,document_tail) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE document_key=document_key
            """, type, hash, value.substring(value.length() - 4));
        return db.one(Identity.class, "SELECT id,document_type,document_tail FROM identity_record WHERE document_key=?", hash);
    }

    void lockIdentity(long id) {
        db.count("SELECT id FROM identity_record WHERE id=? FOR UPDATE", id);
    }

    void event(Catalog.Actor actor, long gate, Long visit, String action, String outcome, Instant time) {
        db.update("""
            INSERT INTO gate_event(visit_id,gate_id,staff_id,action,outcome,occurred_at) VALUES (?,?,?,?,?,?)
            """, visit, gate, actor.id(), action, outcome, time);
    }

    void alert(long site, Long visit, String kind, String key) {
        var now = clock.instant();
        db.update("""
            INSERT INTO alert(site_id,visit_id,kind,event_key,created_at,last_at) VALUES (?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE last_at=IF(state='CLOSED',last_at,VALUES(last_at))
            """, site, visit, kind, key, now, now);
    }

    private long resolve(Pass pass, Long visitId, String key, Policy.Direction direction) {
        if (key == null && visitId != null) {
            return visitId;
        }
        if (pass == null || !pass.active() || !pass.expiresAt().isAfter(clock.instant())) {
            blocked("PASS_EXPIRED", "通行码无效或已过期，请刷新后重扫");
        }
        if (!pass.direction().equals(direction.name())) {
            blocked("WRONG_DIRECTION", "请切换到该通行码对应的入厂或离厂入口");
        }
        return pass.visitId();
    }

    private Pass pass(String key, boolean lock) {
        return db.one(Pass.class, """
            SELECT visit_id,direction,active,expires_at FROM gate_pass WHERE token_key=?
            """ + (lock ? " FOR UPDATE" : ""), key);
    }

    private String tokenKey(String token) {
        if (!Visits.hasText(token)) {
            return null;
        }
        if (!token.strip().matches("KSVMS:[A-Za-z0-9_-]{32}")) {
            throw Problem.bad("请扫描访客临时通行码");
        }
        return crypto.digest("pass", token.strip().substring(6));
    }

    private void verifyPlate(String expected, String actual) {
        if (expected != null && (!Visits.hasText(actual) || !expected.equals(Visits.normalizePlate(actual)))) {
            blocked("PLATE_MISMATCH", "车牌与已批准或在厂记录不一致");
        }
    }

    private void blocked(String code, String message) {
        throw new Problem(409, code, message);
    }

    private String encode(Object data) {
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode gate result", error);
        }
    }
}
