package cn.ks.vms;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.validation.constraints.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Visits {
    record Form(@Positive Long hostId, @Positive Long gateId, @Size(max = 100) String slug,
                @Size(max = 80) String inviteToken, @NotNull Policy.Type type,
                @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 100) String company,
                @NotBlank @Size(max = 500) String reason, @Size(max = 20) String plate,
                @Size(max = 100) String referenceNo, @Positive Long areaId, Instant plannedIn,
                Instant plannedOut, @Positive long safetyNoticeId, @Positive long privacyNoticeId,
                boolean onsiteConfirmed, @Pattern(regexp = "1[3-9][0-9]{9}") String phone, LocalDate visitDate,
                @Size(max = 100) String requestedHost) {}
    record InviteForm(@Positive long siteId, @Positive long hostId, @NotNull Policy.Type type,
                      @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 500) String reason,
                      @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
                      @NotNull Instant plannedIn, @NotNull Instant plannedOut) {}
    record Invite(long id, long inviterId, long hostId, long siteId, String guestName, String reason,
                  String phoneKey, Policy.Type type, Instant plannedIn, Instant plannedOut, Instant expiresAt,
                  Instant claimedAt, Instant cancelledAt, Long visitId) {}
    record InvitePreview(long id, long siteId, String name,
                         String reason, Policy.Type type, Instant plannedIn, Instant plannedOut, Long visitId) {}
    record Step(long id, long visitId, int sequenceNo, String role) {}
    record Decision(boolean approve, @Size(max = 300) String comment, @Size(max = 100) String permitNo,
                    Instant trainingUntil, Instant permitUntil, boolean completeNext) {}
    record Reception(@Positive long departmentId, @Positive Long hostId, @Positive Long areaId) {}

    private static final String VIEW = """
        SELECT v.id,v.version,v.previous_id previousId,v.site_id siteId,v.host_id hostId,v.department_id departmentId,
        v.requested_host requestedHost,v.type,
        v.name,v.company,v.reason,v.plate,v.reference_no referenceNo,v.area_id areaId,a.name areaName,
        COALESCE(h.name,v.requested_host) hostName,d.name departmentName,s.name siteName,v.source,v.channel,v.rejection,
        v.planned_in plannedIn,v.planned_out plannedOut,v.window_start windowStart,v.window_end windowEnd,
        p.in_at enteredAt,p.out_at departedAt,p.vehicle_out_at vehicleDepartedAt,p.due_at dueAt,
        CASE WHEN p.out_at IS NOT NULL THEN 'DEPARTED' WHEN p.in_at IS NOT NULL THEN 'PRESENT'
          WHEN v.state IN ('APPROVED','PENDING') AND v.window_end<=UTC_TIMESTAMP(6) THEN 'EXPIRED'
          ELSE v.state END state
        FROM visit v LEFT JOIN staff h ON v.host_id=h.id LEFT JOIN department d ON v.department_id=d.id
        JOIN site s ON s.id=v.site_id LEFT JOIN area a ON a.id=v.area_id LEFT JOIN presence p ON p.visit_id=v.id
        """;

    private static final String GRANT_MATCH = """
        v.department_id IS NOT NULL AND (g.site_id IS NULL OR g.site_id=v.site_id) AND g.gate_id IS NULL
        AND (g.start_at IS NULL OR g.start_at<=UTC_TIMESTAMP(6)) AND (g.end_at IS NULL OR g.end_at>UTC_TIMESTAMP(6))
        AND ((g.role=s.role AND ((s.role IN ('HOST_SUPERVISOR','PROJECT') AND
            (g.department_id IS NULL OR g.department_id=v.department_id))
            OR (s.role NOT IN ('HOST_SUPERVISOR','PROJECT') AND g.department_id IS NULL)))
            OR (s.role='WAREHOUSE_OR_PROCUREMENT' AND g.role IN ('WAREHOUSE','PROCUREMENT')
            AND g.department_id IS NULL))
        """;

    private final Db db;
    private final VisitMapper mapper;
    private final Catalog catalog;
    private final Crypto crypto;
    private final Clock clock;
    private final Access access;
    private final String visitorUrl;

    Visits(Db db, VisitMapper mapper, Catalog catalog, Crypto crypto, Clock clock, Access access,
           @Value("${vms.visitor-url}") String visitorUrl) {
        this.db = db;
        this.mapper = mapper;
        this.catalog = catalog;
        this.crypto = crypto;
        this.clock = clock;
        this.access = access;
        this.visitorUrl = visitorUrl.replaceFirst("/$", "");
    }

    @Transactional
    Map<String, Object> submit(Form form, VisitorBookingAccess.Owner owner, Catalog.Actor actor) {
        Catalog.Link link = null;
        Invite invite = null;
        if (actor != null) {
            if (form.gateId() == null) {
                throw Problem.bad("请选择登记门岗");
            }
            var gate = catalog.requireGate(actor, form.gateId());
            link = new Catalog.Link(gate.siteId(), gate.id());
        } else if (hasText(form.inviteToken())) {
            if (!owner.verified()) {
                throw new Problem(401, "AUTH_REQUIRED", "请先核验受邀手机号");
            }
            invite = readInvite(form.inviteToken(), owner.verifiedPhone());
            if (invite.visitId() != null) {
                return visitorView(invite.visitId());
            }
            if (invite.claimedAt() == null) {
                throw Problem.bad("请先领取邀请");
            }
            link = new Catalog.Link(invite.siteId(), null);
        } else if (hasText(form.slug())) {
            link = catalog.link(form.slug());
        }
        if (link == null) {
            throw Problem.bad("请扫描厂区登记码或打开邀请链接");
        }
        var visit = makeVisit(form, owner, actor, invite, link, null);
        if (invite != null) {
            db.update("UPDATE invitation SET visit_id=? WHERE id=?", visit.getId(), invite.id());
        }
        return actor == null ? visitorView(visit.getId()) : view(visit.getId());
    }

    @Transactional
    Map<String, Object> revise(long id, Form form, VisitorBookingAccess.Owner owner) {
        var original = owned(get(id), owner);
        if (original.getInvitationId() != null) {
            db.count("SELECT id FROM invitation WHERE id=? FOR UPDATE", original.getInvitationId());
        }
        Visit old = owned(lock(id), owner);
        requireEditable(old);
        var link = new Catalog.Link(old.getSiteId(), old.getSubmittedGateId());
        var replacement = makeVisit(form, owner, null, null, link, old);
        close(old.getId(), "SUPERSEDED");
        if (old.getInvitationId() != null) {
            db.update("UPDATE invitation SET visit_id=? WHERE id=?", replacement.getId(), old.getInvitationId());
        }
        return visitorView(replacement.getId());
    }

    private Visit makeVisit(Form form, VisitorBookingAccess.Owner owner, Catalog.Actor actor, Invite invite,
                            Catalog.Link link, Visit previous) {
        var now = clock.instant();
        var site = catalog.site(link.siteId());
        Long hostId = actor != null ? form.hostId() : invite != null ? Long.valueOf(invite.hostId())
            : previous != null && previous.getInvitationId() != null ? previous.getHostId() : null;
        if (actor != null && hostId == null) {
            throw Problem.bad("请选择接待人员");
        }
        var host = hostId == null ? null : catalog.host(hostId);
        boolean guest = previous != null ? previous.getGuestKey() != null : actor == null && invite == null;
        boolean onsite = link.gateId() != null || previous != null && "ONSITE".equals(previous.getSource());
        Instant plannedIn;
        Instant plannedOut;
        Policy.Window window;
        if (guest) {
            if (owner.browserKey() == null || !hasText(form.phone())) {
                throw Problem.bad("请输入手机号码");
            }
            var plan = Policy.bookingDay(site, now, form.visitDate(), onsite);
            window = plan.window();
            plannedIn = window.start();
            plannedOut = plan.departure();
        } else {
            plannedIn = link.gateId() != null ? now : invite == null ? form.plannedIn() : invite.plannedIn();
            plannedOut = invite == null ? form.plannedOut() : invite.plannedOut();
            if (plannedIn == null || plannedOut == null) {
                throw Problem.bad("请选择到厂和离厂时间");
            }
            window = Policy.window(site, now, plannedIn, plannedOut, onsite);
        }
        var type = invite == null ? form.type() : invite.type();
        if (actor == null && window.onsite() && !onsite && !form.onsiteConfirmed()) {
            throw new Problem(409, "ONSITE_CONFIRMATION", "当前时间不足以提前预约，请确认改为现场登记");
        }
        String plate = hasText(form.plate()) ? normalizePlate(form.plate()) : null;
        String reference = hasText(form.referenceNo()) ? form.referenceNo().strip() : null;
        Long area = actor == null ? null : form.areaId();
        if (actor != null && ((type.needsArea() && area == null) || !catalog.activeArea(site.id(), area))) {
            throw Problem.bad("请选择有效的访问或作业区域");
        }
        if (type.needsVehicle() && plate == null) {
            throw Problem.bad("请填写车牌号码");
        }
        if (type.needsReference() && reference == null) {
            throw Problem.bad("请填写对应的业务单号");
        }
        var notices = catalog.notices(site.id());
        if (notices.stream().noneMatch(n -> n.kind().equals("SAFETY") && n.id() == form.safetyNoticeId())
            || notices.stream().noneMatch(n -> n.kind().equals("PRIVACY") && n.id() == form.privacyNoticeId())) {
            throw Problem.bad("请重新阅读并确认当前须知和个人信息处理说明");
        }
        var visit = new Visit();
        visit.setVersion(previous == null ? 1 : previous.getVersion() + 1);
        visit.setPreviousId(previous == null ? null : previous.getId());
        visit.setSiteId(site.id());
        visit.setSubmittedGateId(link.gateId());
        visit.setHostId(hostId);
        visit.setRequestedHost(hasText(form.requestedHost()) ? form.requestedHost().strip() : null);
        visit.setDepartmentId(host == null ? null : host.departmentId());
        visit.setCreatorId(actor == null ? null : actor.id());
        visit.setInvitationId(invite != null ? Long.valueOf(invite.id()) : previous == null ? null : previous.getInvitationId());
        visit.setSource(window.onsite() ? "ONSITE" : visit.getInvitationId() == null ? "SELF" : "INVITED");
        visit.setChannel(previous != null ? previous.getChannel() : actor != null ? "GUARD"
            : invite != null ? "INVITATION" : link.gateId() != null ? "GATE_QR" : "COMPANY_QR");
        visit.setType(type);
        visit.setState("PENDING");
        visit.setName(invite == null ? form.name().strip() : invite.guestName());
        if (guest) {
            visit.setGuestKey(owner.browserKey());
            visit.setContactPhone(crypto.encrypt("contact-phone-v1", form.phone()));
        } else if (owner != null) {
            visit.setPhoneKey(owner.verifiedPhone());
        }
        visit.setCompany(form.company().strip());
        visit.setReason(invite == null ? form.reason().strip() : invite.reason());
        visit.setPlate(plate);
        visit.setReferenceNo(reference);
        visit.setAreaId(area);
        visit.setPlannedIn(plannedIn);
        visit.setPlannedOut(plannedOut);
        visit.setWindowStart(window.start());
        visit.setWindowEnd(window.end());
        visit.setMaxMinutes(site.maxMinutes());
        visit.setSafetyNoticeId(form.safetyNoticeId());
        visit.setPrivacyNoticeId(form.privacyNoticeId());
        visit.setSubmittedAt(now);
        mapper.insert(visit);
        var steps = Policy.steps(type, window.onsite());
        for (int index = 0; index < steps.size(); index++) {
            db.update("INSERT INTO approval_step(visit_id,sequence_no,role) VALUES (?,?,?)", visit.getId(), index + 1, steps.get(index));
        }
        catalog.audit(actor, site.id(), "VISIT_CREATED", visit.getId(), visit.getChannel());
        return visit;
    }

    @Transactional
    Map<String, Object> invite(Catalog.Actor actor, InviteForm form) {
        actor.require("EMPLOYEE", form.siteId(), null, null);
        var site = catalog.site(form.siteId());
        catalog.host(form.hostId());
        var now = clock.instant();
        Policy.window(site, now, form.plannedIn(), form.plannedOut(), false);
        var token = crypto.token();
        long id = db.insert("""
            INSERT INTO invitation(token_key,phone_key,inviter_id,host_id,site_id,guest_name,reason,type,planned_in,
            planned_out,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, crypto.digest("invite", token), access.phoneKey(form.phone()), actor.id(), form.hostId(), form.siteId(),
            form.name().strip(), form.reason().strip(), form.type().name(), form.plannedIn(), form.plannedOut(),
            Policy.earlier(now.plusSeconds(172800), form.plannedOut()));
        catalog.audit(actor, form.siteId(), "INVITE_CREATED", id, null);
        return Map.of("id", id, "url", visitorUrl + "/visitor#invite=" + token);
    }

    @Transactional
    InvitePreview claim(String phone, String token) {
        var invite = readInvite(token, phone);
        if (invite.claimedAt() == null) {
            db.update("UPDATE invitation SET claimed_at=? WHERE id=?", clock.instant(), invite.id());
            catalog.audit(null, invite.siteId(), "INVITE_CLAIMED", invite.id(), null);
        }
        return new InvitePreview(invite.id(), invite.siteId(),
            invite.guestName(), invite.reason(), invite.type(), invite.plannedIn(), invite.plannedOut(), invite.visitId());
    }

    @Transactional
    void cancelInvite(Catalog.Actor actor, long id) {
        var invite = db.one(Invite.class, "SELECT * FROM invitation WHERE id=? AND inviter_id=? FOR UPDATE", id, actor.id());
        if (invite == null) {
            throw Problem.missing();
        }
        if (invite.visitId() != null) {
            var visit = lock(invite.visitId());
            if (!"CANCELLED".equals(visit.getState())) {
                requireEditable(visit);
                close(visit.getId(), "CANCELLED");
            }
        }
        db.update("UPDATE invitation SET cancelled_at=? WHERE id=?", clock.instant(), id);
        catalog.audit(actor, invite.siteId(), "INVITE_CANCELLED", id, null);
    }

    private Invite readInvite(String token, String phone) {
        var item = db.one(Invite.class, """
            SELECT * FROM invitation WHERE token_key=? AND phone_key=? AND cancelled_at IS NULL FOR UPDATE
            """, crypto.digest("invite", token), phone);
        if (item == null || item.visitId() == null && !item.expiresAt().isAfter(clock.instant())) {
            throw Problem.missing();
        }
        return item;
    }

    List<Map<String, Object>> invitations(Catalog.Actor actor) {
        return db.maps("""
            SELECT i.id,i.guest_name name,i.planned_in plannedIn,i.claimed_at claimedAt,i.cancelled_at cancelledAt,
            i.expires_at expiresAt,i.visit_id visitId,v.state FROM invitation i LEFT JOIN visit v ON i.visit_id=v.id
            WHERE i.inviter_id=? ORDER BY id DESC LIMIT 100
            """, actor.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Map<String, Object> decide(Catalog.Actor actor, long id, Decision decision) {
        var step = db.one(Step.class, """
            SELECT id,visit_id,sequence_no,role FROM approval_step WHERE id=?
            """, id);
        if (step == null) {
            throw Problem.missing();
        }
        var visit = lock(step.visitId());
        var current = next(visit.getId());
        if (!"PENDING".equals(visit.getState()) || !visit.getWindowEnd().isAfter(clock.instant())
            || current == null || current.id() != id) {
            throw Problem.conflict("该审批已处理或已失效");
        }
        if (!canApprove(actor, visit, step)) {
            throw Problem.denied();
        }
        if (decision.approve() && visit.getType().needsArea() && visit.getAreaId() == null) {
            throw Problem.conflict("请先由门岗或行政确认访问区域");
        }
        if (!decision.approve() && !hasText(decision.comment())) {
            throw Problem.bad("请填写驳回理由");
        }
        applyDecision(actor, visit, current, decision);
        if (!decision.approve()) {
            db.update("UPDATE visit SET rejection=? WHERE id=?", decision.comment().strip(), visit.getId());
            close(visit.getId(), "REJECTED");
        } else {
            Step remaining = next(visit.getId());
            if (decision.completeNext() && current.role().equals("HOST_SUPERVISOR") && remaining != null
                && canApprove(actor, visit, remaining)) {
                applyDecision(actor, visit, remaining, decision);
                remaining = next(visit.getId());
            }
            if (remaining == null) {
                db.update("UPDATE visit SET state='APPROVED' WHERE id=?", visit.getId());
            }
        }
        return view(visit.getId());
    }

    private void applyDecision(Catalog.Actor actor, Visit visit, Step step, Decision input) {
        if ("SAFETY".equals(step.role()) && input.approve()) {
            if (db.count("""
                SELECT COUNT(*) FROM approval_action a JOIN approval_step s ON s.id=a.step_id
                WHERE s.visit_id=? AND s.role='PROJECT' AND a.staff_id=?
                """, visit.getId(), actor.id()) > 0) {
                throw Problem.denied();
            }
            if (!hasText(input.permitNo()) || input.trainingUntil() == null || input.permitUntil() == null
                || !input.trainingUntil().isAfter(visit.getWindowStart())
                || !input.permitUntil().isAfter(visit.getWindowStart())) {
                throw Problem.bad("请核实安全培训和施工许可，填写覆盖入厂时间的有效期");
            }
            db.update("UPDATE visit SET permit_no=?,training_until=?,permit_until=? WHERE id=?",
                input.permitNo().strip(), input.trainingUntil(), input.permitUntil(), visit.getId());
        }
        String status = input.approve() ? "APPROVED" : "REJECTED";
        db.update("UPDATE approval_step SET state=? WHERE id=?", status, step.id());
        db.update("INSERT INTO approval_action(step_id,staff_id,decision,comment,acted_at) VALUES (?,?,?,?,?)",
            step.id(), actor.id(), status, input.comment(), clock.instant());
        catalog.audit(actor, visit.getSiteId(), "APPROVAL_" + status, visit.getId(), step.role());
    }

    Step next(long visit) {
        return db.one(Step.class, """
            SELECT id,visit_id,sequence_no,role FROM approval_step WHERE visit_id=? AND state='PENDING'
            ORDER BY sequence_no LIMIT 1
            """, visit);
    }

    boolean canApprove(Catalog.Actor actor, Visit visit, Step step) {
        if (visit.getDepartmentId() == null) {
            return false;
        }
        if ("WAREHOUSE_OR_PROCUREMENT".equals(step.role())) {
            return actor.can("WAREHOUSE", visit.getSiteId(), null, null)
                || actor.can("PROCUREMENT", visit.getSiteId(), null, null);
        }
        Long department = List.of("HOST_SUPERVISOR", "PROJECT").contains(step.role()) ? visit.getDepartmentId() : null;
        return actor.can(step.role(), visit.getSiteId(), department, null);
    }

    List<Map<String, Object>> approvals(Catalog.Actor actor, boolean history) {
        if (history) {
            return db.maps("SELECT w.*,a.decision,a.acted_at actedAt,s.role,s.id stepId FROM (" + VIEW + """
                ) w JOIN approval_step s ON w.id=s.visit_id JOIN approval_action a ON a.step_id=s.id
                JOIN visit v ON v.id=w.id
                WHERE a.staff_id=? AND EXISTS (SELECT 1 FROM staff_grant g WHERE g.staff_id=a.staff_id AND
                """ + GRANT_MATCH + ") ORDER BY a.id DESC LIMIT 100", actor.id());
        }
        return db.maps("SELECT w.*,s.role,s.id stepId FROM (" + VIEW + """
            ) w JOIN approval_step s ON s.id=(SELECT p.id FROM approval_step p WHERE p.visit_id=w.id
               AND p.state='PENDING' ORDER BY p.sequence_no LIMIT 1)
            JOIN visit v ON v.id=w.id
            WHERE w.state='PENDING' AND EXISTS (
                SELECT 1 FROM staff_grant g WHERE g.staff_id=? AND
            """ + GRANT_MATCH + ") ORDER BY w.windowEnd,w.id LIMIT 100", actor.id());
    }

    List<Map<String, Object>> unassigned(long site) {
        return db.maps("""
            SELECT v.id,v.type,s.role,d.name departmentName,v.window_end windowEnd
            FROM visit v LEFT JOIN department d ON d.id=v.department_id
            JOIN approval_step s ON s.id=(SELECT p.id FROM approval_step p WHERE p.visit_id=v.id
                AND p.state='PENDING' ORDER BY p.sequence_no LIMIT 1)
            WHERE v.site_id=? AND v.state='PENDING' AND v.window_end>UTC_TIMESTAMP(6)
                AND NOT EXISTS (SELECT 1 FROM staff_grant g JOIN staff u ON u.id=g.staff_id
                    WHERE u.enabled AND
            """ + GRANT_MATCH + ") ORDER BY v.window_end LIMIT 100", site);
    }

    List<Map<String, Object>> mine(VisitorBookingAccess.Owner owner) {
        return db.maps(VIEW + " WHERE v.phone_key=? OR v.guest_key=? ORDER BY v.id DESC LIMIT 100",
            owner.verifiedPhone(), owner.browserKey()).stream().map(this::publicView).toList();
    }

    List<Map<String, Object>> staffList(long site, String search, String state, long before) {
        String query = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.maps("SELECT * FROM (" + VIEW + """
            WHERE v.site_id=? AND v.id<? AND (v.name LIKE ? ESCAPE '!' OR v.plate LIKE ? ESCAPE '!'
              OR v.reference_no LIKE ? ESCAPE '!')
            ) w WHERE (?='' OR state=?) ORDER BY id DESC LIMIT 100
            """, site, before, query, query, query, state, state);
    }

    Map<String, Object> view(long id) {
        return db.maps(VIEW + " WHERE v.id=?", id).stream().findFirst().orElseThrow(Problem::missing);
    }

    Map<String, Object> visitorView(long id) {
        return publicView(withContact(get(id)));
    }

    private Map<String, Object> publicView(Map<String, Object> row) {
        var allowed = java.util.Set.of("id", "version", "previousId", "siteId", "siteName", "type", "name", "company",
            "reason", "phone", "plate", "referenceNo", "source", "rejection", "plannedIn", "plannedOut",
            "windowStart", "windowEnd", "enteredAt", "departedAt", "dueAt", "state");
        var result = new java.util.HashMap<String, Object>();
        row.forEach((key, value) -> {
            if (allowed.contains(key)) {
                result.put(key, value);
            }
        });
        if (row.get("requestedHost") != null) {
            result.put("hostName", row.get("requestedHost"));
        }
        return result;
    }

    @Transactional
    Map<String, Object> reception(Catalog.Actor actor, long id, Reception input) {
        var visit = lock(id);
        VisitController.requireSite(actor, visit.getSiteId(), "GUARD", "ADMINISTRATION");
        requireEditable(visit);
        if (!"PENDING".equals(visit.getState()) || !visit.getWindowEnd().isAfter(clock.instant())
            || db.count("""
                SELECT COUNT(*) FROM approval_action a JOIN approval_step s ON s.id=a.step_id WHERE s.visit_id=?
                """, id) > 0) {
            throw Problem.conflict("请在审批开始前确认接待信息");
        }
        if (db.count("SELECT COUNT(*) FROM department WHERE id=?", input.departmentId()) == 0
            || input.hostId() != null && catalog.host(input.hostId()).departmentId() != input.departmentId()) {
            throw Problem.bad("请选择有效的接访部门和接待人员");
        }
        if (visit.getType().needsArea() && input.areaId() == null
            || !catalog.activeArea(visit.getSiteId(), input.areaId())) {
            throw Problem.bad("请选择有效的访问或作业区域");
        }
        db.update("UPDATE visit SET department_id=?,host_id=?,area_id=? WHERE id=?",
            input.departmentId(), input.hostId(), input.areaId(), id);
        catalog.audit(actor, visit.getSiteId(), "RECEPTION_CONFIRMED", id, null);
        return timeline(get(id));
    }

    Map<String, Object> withContact(Visit visit) {
        var view = view(visit.getId());
        if (visit.getContactPhone() != null) {
            view.put("phone", crypto.decrypt("contact-phone-v1", visit.getContactPhone()));
        }
        return view;
    }

    Map<String, Object> timeline(Visit visit) {
        long id = visit.getId();
        return Map.of("visit", withContact(visit), "approvals", db.maps("""
            SELECT s.role,s.state,a.acted_at actedAt,u.name approver,a.comment FROM approval_step s
            LEFT JOIN approval_action a ON a.step_id=s.id LEFT JOIN staff u ON a.staff_id=u.id
            WHERE s.visit_id=? ORDER BY s.sequence_no
            """, id), "events", db.maps("""
            SELECT e.action,e.outcome,e.occurred_at occurredAt,e.recorded_at recordedAt,g.name gateName,s.name staffName
            FROM gate_event e JOIN gate g ON e.gate_id=g.id JOIN staff s ON e.staff_id=s.id
            WHERE e.visit_id=? ORDER BY e.id
            """, id));
    }

    @Transactional
    void cancel(long id, VisitorBookingAccess.Owner owner) {
        var visit = owned(lock(id), owner);
        requireEditable(visit);
        close(id, "CANCELLED");
        catalog.audit(null, visit.getSiteId(), "VISIT_CANCELLED", id, null);
    }

    @Transactional
    Map<String, Object> pass(long id, VisitorBookingAccess.Owner owner, Policy.Direction direction) {
        var visit = owned(lock(id), owner);
        var now = clock.instant();
        boolean present = db.count("SELECT COUNT(*) FROM presence WHERE visit_id=? AND out_at IS NULL", id) > 0;
        if (direction == Policy.Direction.EXIT && !present || direction == Policy.Direction.ENTRY
            && (!"APPROVED".equals(visit.getState()) || now.isBefore(visit.getWindowStart())
                || !now.isBefore(visit.getWindowEnd()) || hasPresence(id))) {
            throw Problem.conflict("当前状态无法领取该通行码");
        }
        var token = crypto.token();
        var expires = now.plusSeconds(300);
        if (direction == Policy.Direction.ENTRY) {
            expires = Policy.earlier(expires, visit.getWindowEnd());
        }
        db.update("UPDATE gate_pass SET active=FALSE WHERE visit_id=? AND direction=? AND active", id, direction.name());
        db.update("INSERT INTO gate_pass(token_key,visit_id,direction,expires_at) VALUES (?,?,?,?)",
            crypto.digest("pass", token), id, direction.name(), expires);
        return Map.of("qr", qr("KSVMS:" + token), "expiresAt", expires, "direction", direction);
    }

    String qr(String payload) {
        try (var bytes = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 260, 260), "PNG", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (WriterException | IOException error) {
            throw new IllegalStateException("Cannot encode QR", error);
        }
    }

    Visit get(long id) {
        var visit = mapper.selectById(id);
        if (visit == null) {
            throw Problem.missing();
        }
        return visit;
    }

    Visit lock(long id) {
        var visit = mapper.lock(id);
        if (visit == null) {
            throw Problem.missing();
        }
        return visit;
    }

    Visit owned(Visit visit, VisitorBookingAccess.Owner owner) {
        if (!(owner.verified() && owner.verifiedPhone().equals(visit.getPhoneKey()))
            && !(owner.browserKey() != null && owner.browserKey().equals(visit.getGuestKey()))) {
            throw Problem.missing();
        }
        return visit;
    }

    boolean hasPresence(long id) {
        return db.count("SELECT COUNT(*) FROM presence WHERE visit_id=?", id) > 0;
    }

    private void requireEditable(Visit visit) {
        if (!List.of("PENDING", "APPROVED").contains(visit.getState()) || hasPresence(visit.getId())) {
            throw Problem.conflict("当前申请状态无法修改或撤销");
        }
    }

    private void close(long id, String state) {
        db.update("UPDATE visit SET state=? WHERE id=?", state, id);
        db.update("UPDATE approval_step SET state='CANCELLED' WHERE visit_id=? AND state='PENDING'", id);
        db.update("UPDATE gate_pass SET active=FALSE WHERE visit_id=? AND active", id);
    }

    static String normalizePlate(String plate) {
        String value = plate.strip().toUpperCase(Locale.ROOT);
        if (!value.matches("[\\p{IsHan}A-Z0-9·-]{4,20}")) {
            throw Problem.bad("车牌号码格式有误");
        }
        return value;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
