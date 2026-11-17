package cn.ks.vms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff")
class SafetyController {
    record BlacklistForm(@Positive Long siteId, @NotBlank @Size(max = 24) String documentType,
                         @NotBlank @Size(max = 40) String documentNumber, @NotBlank @Size(max = 300) String reason,
                         @NotNull Instant startsAt, Instant endsAt) {}
    record BlacklistRow(long id, Long siteId, long identityId) {}
    record AlertAction(@NotBlank @Pattern(regexp = "PROCESSING|CLOSED") String state, @Size(max = 300) String resolution) {}
    record AlertRow(long siteId, String state) {}
    record CorrectionForm(@Positive long visitId, @Positive long gateId,
                          @NotBlank @Pattern(regexp = "ENTRY|EXIT|VEHICLE_EXIT") String action,
                          @NotNull Instant occurredAt, @NotBlank @Size(max = 300) String reason,
                          @Size(max = 24) String documentType, @Size(max = 40) String documentNumber) {}
    record CorrectionRow(long id, long visitId, long gateId, long requestedBy, String action,
                         Instant occurredAt, String state, Long identityId) {}
    record Review(boolean approve) {}

    private final Db db;
    private final Catalog catalog;
    private final Gates gates;
    private final Visits visits;
    private final Clock clock;

    SafetyController(Db db, Catalog catalog, Gates gates, Visits visits, Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.gates = gates;
        this.visits = visits;
        this.clock = clock;
    }

    @GetMapping("/blacklist")
    List<Map<String, Object>> blacklist(Authentication user, @RequestParam long site) {
        safety(user, site);
        return db.maps("""
            SELECT b.id,b.site_id siteId,b.reason,b.starts_at startsAt,b.ends_at endsAt,b.disabled,
            i.document_type documentType,i.document_tail documentTail FROM blacklist b
            JOIN identity_record i ON i.id=b.identity_id WHERE b.site_id=? OR b.site_id IS NULL ORDER BY b.id DESC LIMIT 100
            """, site);
    }

    @PostMapping("/blacklist")
    @Transactional
    Map<String, Long> add(Authentication user, @Valid @RequestBody BlacklistForm input) {
        var actor = catalog.actor(user);
        actor.require("SAFETY", input.siteId(), null, null);
        if (input.siteId() != null) {
            catalog.site(input.siteId());
        }
        if (input.endsAt() != null && !input.endsAt().isAfter(input.startsAt())) {
            throw Problem.bad("失效时间须晚于生效时间");
        }
        var identity = gates.identity(input.documentType(), input.documentNumber());
        gates.lockIdentity(identity.id());
        long id = db.insert("""
            INSERT INTO blacklist(identity_id,site_id,reason,starts_at,ends_at) VALUES (?,?,?,?,?)
            """, identity.id(), input.siteId(), input.reason().strip(), input.startsAt(), input.endsAt());
        if (!input.startsAt().isAfter(clock.instant()) && (input.endsAt() == null || input.endsAt().isAfter(clock.instant()))) {
            blacklistAlerts(id);
        }
        catalog.audit(actor, input.siteId(), "BLACKLIST_ADDED", id, null);
        return Map.of("id", id);
    }

    @DeleteMapping("/blacklist/{id}")
    @Transactional
    void remove(Authentication user, @PathVariable long id) {
        var row = db.one(BlacklistRow.class, "SELECT id,site_id,identity_id FROM blacklist WHERE id=?", id);
        if (row == null) {
            throw Problem.missing();
        }
        var actor = catalog.actor(user);
        actor.require("SAFETY", row.siteId(), null, null);
        gates.lockIdentity(row.identityId());
        db.update("UPDATE blacklist SET disabled=TRUE WHERE id=?", id);
        catalog.audit(actor, row.siteId(), "BLACKLIST_DISABLED", id, null);
    }

    @GetMapping("/alerts")
    List<Map<String, Object>> alerts(Authentication user, @RequestParam long site) {
        safety(user, site);
        return db.maps("""
            SELECT a.id,a.visit_id visitId,a.kind,a.state,a.resolution,a.created_at createdAt,a.last_at lastAt,
            v.name,v.plate,u.name assignee FROM alert a LEFT JOIN visit v ON v.id=a.visit_id
            LEFT JOIN staff u ON u.id=a.assignee_id WHERE a.site_id=?
            ORDER BY a.state='CLOSED',a.last_at DESC LIMIT 100
            """, site);
    }

    @PostMapping("/alerts/{id}")
    @Transactional
    void updateAlert(Authentication user, @PathVariable long id, @Valid @RequestBody AlertAction input) {
        var row = db.one(AlertRow.class, "SELECT site_id,state FROM alert WHERE id=? FOR UPDATE", id);
        if (row == null) {
            throw Problem.missing();
        }
        var actor = safety(user, row.siteId());
        if ("CLOSED".equals(row.state())) {
            throw Problem.conflict("该预警已结案");
        }
        if ("CLOSED".equals(input.state()) && !Visits.hasText(input.resolution())) {
            throw Problem.bad("请填写处置结论");
        }
        db.update("UPDATE alert SET state=?,assignee_id=?,resolution=? WHERE id=?", input.state(), actor.id(), input.resolution(), id);
        catalog.audit(actor, row.siteId(), "ALERT_" + input.state(), id, null);
    }

    @GetMapping("/corrections")
    List<Map<String, Object>> corrections(Authentication user, @RequestParam long site) {
        safety(user, site);
        return db.maps("""
            SELECT c.id,c.visit_id visitId,v.name,c.action,c.occurred_at occurredAt,c.reason,c.state,s.name requestedBy
            FROM correction c JOIN visit v ON v.id=c.visit_id JOIN staff s ON s.id=c.requested_by WHERE v.site_id=?
            ORDER BY c.state<>'PENDING',c.id DESC LIMIT 100
            """, site);
    }

    @PostMapping("/corrections")
    @Transactional
    Map<String, Long> propose(Authentication user, @Valid @RequestBody CorrectionForm input) {
        var actor = catalog.actor(user);
        var gate = catalog.gate(input.gateId());
        var visit = visits.get(input.visitId());
        if (gate.siteId() != visit.getSiteId() || input.occurredAt().isAfter(clock.instant())) {
            throw Problem.bad("请选择对应厂区及有效的实际发生时间");
        }
        if (!actor.siteRole(gate.siteId(), "ADMINISTRATION")) {
            catalog.requireGate(actor, gate.id());
        }
        Long identity = visit.getIdentityId();
        if ("ENTRY".equals(input.action()) && identity == null) {
            identity = gates.identity(input.documentType(), input.documentNumber()).id();
        }
        long id = db.insert("""
            INSERT INTO correction(visit_id,gate_id,requested_by,action,occurred_at,reason,identity_id) VALUES (?,?,?,?,?,?,?)
            """, visit.getId(), gate.id(), actor.id(), input.action(), input.occurredAt(), input.reason().strip(), identity);
        catalog.audit(actor, gate.siteId(), "CORRECTION_REQUESTED", id, null);
        return Map.of("id", id);
    }

    @PostMapping("/corrections/{id}/review")
    @Transactional
    void review(Authentication user, @PathVariable long id, @Valid @RequestBody Review input) {
        var change = db.one(CorrectionRow.class, """
            SELECT id,visit_id,gate_id,requested_by,action,occurred_at,state,identity_id FROM correction WHERE id=? FOR UPDATE
            """, id);
        if (change == null) {
            throw Problem.missing();
        }
        var visit = visits.lock(change.visitId());
        var actor = safety(user, visit.getSiteId());
        if (actor.id() == change.requestedBy() || !"PENDING".equals(change.state())) {
            throw Problem.conflict("请由另一名安全人员核准未处理的更正");
        }
        if (input.approve()) {
            applyCorrection(visit, change);
            gates.event(actor, change.gateId(), visit.getId(), "CORRECT_" + change.action(), "APPROVED", change.occurredAt());
        }
        String state = input.approve() ? "APPROVED" : "REJECTED";
        db.update("UPDATE correction SET state=?,reviewed_by=?,reviewed_at=? WHERE id=?", state, actor.id(), clock.instant(), id);
        catalog.audit(actor, visit.getSiteId(), "CORRECTION_" + state, id, null);
    }

    private void applyCorrection(Visit visit, CorrectionRow change) {
        var presence = gates.presence(visit.getId());
        Instant when = change.occurredAt();
        if ("ENTRY".equals(change.action())) {
            if (presence != null && (presence.outAt() != null && !when.isBefore(presence.outAt())
                || presence.vehicleOutAt() != null && !when.isBefore(presence.vehicleOutAt()))) {
                throw Problem.bad("入厂时间须早于已登记的离厂时间");
            }
            var vehicleTime = visit.getPlate() == null ? null : when;
            var due = Policy.earlier(visit.getPlannedOut(), when.plusSeconds(visit.getMaxMinutes() * 60L));
            if (visit.getPermitUntil() != null) {
                due = Policy.earlier(due, visit.getPermitUntil());
            }
            if (presence == null) {
                gates.lockIdentity(change.identityId());
                db.update("""
                    INSERT INTO presence(visit_id,site_id,identity_id,plate,in_at,vehicle_in_at,due_at) VALUES (?,?,?,?,?,?,?)
                    """, visit.getId(), visit.getSiteId(), change.identityId(), visit.getPlate(), when, vehicleTime, due);
                db.update("UPDATE visit SET identity_id=? WHERE id=?", change.identityId(), visit.getId());
            } else {
                db.update("UPDATE presence SET in_at=?,vehicle_in_at=?,due_at=? WHERE id=?", when, vehicleTime, due, presence.id());
            }
        } else {
            if (presence == null || !when.isAfter(presence.inAt())) {
                throw Problem.bad("离厂时间须晚于入厂时间");
            }
            if ("EXIT".equals(change.action())) {
                db.update("UPDATE presence SET out_at=? WHERE id=?", when, presence.id());
            } else if (presence.plate() == null) {
                throw Problem.bad("该访问无车辆记录");
            } else {
                db.update("UPDATE presence SET vehicle_out_at=? WHERE id=?", when, presence.id());
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    void scan() {
        db.update("""
            INSERT INTO alert(site_id,visit_id,kind,event_key,created_at,last_at)
            SELECT site_id,visit_id,'OVERDUE',CAST(visit_id AS CHAR),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
            FROM presence WHERE out_at IS NULL AND due_at<=UTC_TIMESTAMP(6)
            ON DUPLICATE KEY UPDATE last_at=IF(alert.state='CLOSED',alert.last_at,VALUES(last_at))
            """);
        blacklistAlerts(null);
        db.update("DELETE FROM request_limit WHERE window_start < ?", clock.instant().minusSeconds(7200).getEpochSecond());
        db.update("DELETE FROM otp_challenge WHERE expires_at < ?", clock.instant().minusSeconds(86400));
    }

    private void blacklistAlerts(Long id) {
        db.update("""
            INSERT INTO alert(site_id,visit_id,kind,event_key,created_at,last_at)
            SELECT p.site_id,p.visit_id,'BLACKLIST_PRESENT',CONCAT(p.visit_id,':',b.id),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
            FROM presence p JOIN blacklist b ON b.identity_id=p.identity_id AND (b.site_id IS NULL OR b.site_id=p.site_id)
            WHERE p.out_at IS NULL AND NOT b.disabled AND b.starts_at<=UTC_TIMESTAMP(6)
                AND (b.ends_at IS NULL OR b.ends_at>UTC_TIMESTAMP(6)) AND (? IS NULL OR b.id=?)
            ON DUPLICATE KEY UPDATE last_at=IF(alert.state='CLOSED',alert.last_at,VALUES(last_at))
            """, id, id);
    }

    private Catalog.Actor safety(Authentication user, long site) {
        var actor = catalog.actor(user);
        actor.require("SAFETY", site, null, null);
        return actor;
    }
}
