package cn.ks.vms;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/visitor")
class VisitorController {
    record Claim(@NotBlank @Size(max = 80) String token) {}

    private final Catalog catalog;
    private final Db db;
    private final Visits visits;
    private final Access access;
    private final VisitorBookingAccess bookingAccess;

    VisitorController(Catalog catalog, Db db, Visits visits, Access access, VisitorBookingAccess bookingAccess) {
        this.catalog = catalog;
        this.db = db;
        this.visits = visits;
        this.access = access;
        this.bookingAccess = bookingAccess;
    }

    @GetMapping("/registration/{slug}")
    Map<String, Object> registration(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response) {
        var link = catalog.link(slug);
        var form = catalog.visitorForm(link.siteId());
        bookingAccess.issue(request, response);
        return Map.of("onsite", link.gateId() != null, "slug", slug, "form", form);
    }

    @GetMapping("/me")
    Map<String, Boolean> me(Authentication user, HttpServletRequest request) {
        var owner = bookingAccess.owner(user, request).require();
        return Map.of("authenticated", true, "verified", owner.verified());
    }

    @GetMapping("/visit-form")
    Map<String, Object> form(Authentication user, HttpServletRequest request, @RequestParam long site,
                             @RequestParam(required = false) Long visitId) {
        var owner = bookingAccess.owner(user, request).require();
        boolean allowed = visitId != null
            ? visits.owned(visits.get(visitId), owner).getSiteId() == site
            : owner.verified() && db.count("""
                SELECT COUNT(*) FROM invitation WHERE phone_key=? AND site_id=? AND claimed_at IS NOT NULL
                AND cancelled_at IS NULL AND (expires_at>UTC_TIMESTAMP(6) OR visit_id IS NOT NULL)
                """, owner.verifiedPhone(), site) > 0;
        if (!allowed) {
            throw Problem.missing();
        }
        return catalog.visitorForm(site);
    }

    @PostMapping("/invitations/claim")
    Visits.InvitePreview claim(Authentication user, @Valid @RequestBody Claim claim) {
        access.limit("invite-claim", user.getName(), 40, 300);
        return visits.claim(user.getName(), claim.token());
    }

    @PostMapping("/applications")
    Map<String, Object> apply(Authentication user, HttpServletRequest request, HttpServletResponse response,
                              @Valid @RequestBody Visits.Form form) {
        var owner = bookingAccess.owner(user, request);
        if (!Visits.hasText(form.inviteToken())) {
            access.limit("visit-guest-apply", request.getRemoteAddr(), 30, 3600);
            owner = bookingAccess.issue(request, response);
        } else if (owner.verified()) {
            access.limit("visit-apply", owner.verifiedPhone(), 30, 3600);
        }
        return visits.submit(form, owner, null);
    }

    @GetMapping("/applications")
    List<Map<String, Object>> mine(Authentication user, HttpServletRequest request) {
        return visits.mine(bookingAccess.owner(user, request).require());
    }

    @GetMapping("/applications/{id}")
    Map<String, Object> detail(Authentication user, HttpServletRequest request, @PathVariable long id) {
        visits.owned(visits.get(id), bookingAccess.owner(user, request).require());
        return visits.visitorView(id);
    }

    @PostMapping("/applications/{id}/revisions")
    Map<String, Object> revision(Authentication user, HttpServletRequest request, @PathVariable long id,
                                 @Valid @RequestBody Visits.Form form) {
        access.limit("visit-revise", request.getRemoteAddr(), 60, 3600);
        return visits.revise(id, form, bookingAccess.owner(user, request).require());
    }

    @PostMapping("/applications/{id}/cancel")
    void cancel(Authentication user, HttpServletRequest request, @PathVariable long id) {
        visits.cancel(id, bookingAccess.owner(user, request).require());
    }

    @PostMapping("/applications/{id}/passes")
    Map<String, Object> pass(Authentication user, HttpServletRequest request, @PathVariable long id,
                             @RequestParam Policy.Direction direction) {
        return visits.pass(id, bookingAccess.owner(user, request).require(), direction);
    }
}

@RestController
@RequestMapping("/api/staff")
class VisitController {
    private final Catalog catalog;
    private final Visits visits;
    private final Gates gates;
    private final Db db;

    VisitController(Catalog catalog, Visits visits, Gates gates, Db db) {
        this.catalog = catalog;
        this.visits = visits;
        this.gates = gates;
        this.db = db;
    }

    @GetMapping("/workspace")
    Map<String, Object> workspace(Authentication user) {
        var actor = catalog.actor(user);
        var sites = db.rows(Catalog.Site.class, "SELECT * FROM site WHERE enabled ORDER BY id");
        var availableSites = sites.stream().filter(site -> actor.grants().stream()
            .anyMatch(grant -> grant.siteId() == null || grant.siteId().equals(site.id()))).toList();
        var availableGates = db.rows(Catalog.Gate.class, "SELECT * FROM gate WHERE enabled ORDER BY id").stream()
            .filter(gate -> actor.can("GUARD", gate.siteId(), null, gate.id())
                && availableSites.stream().anyMatch(site -> site.id() == gate.siteId())).toList();
        return Map.of("sites", availableSites, "gates", availableGates);
    }

    @GetMapping("/visit-form")
    Map<String, Object> form(Authentication user, @RequestParam long site) {
        var actor = catalog.actor(user);
        requireSite(actor, site, "GUARD", "EMPLOYEE", "ADMIN", "ADMINISTRATION", "SAFETY");
        var form = new java.util.HashMap<>(catalog.form(site));
        form.put("hosts", db.maps("""
            SELECT s.id,s.name,s.department_id departmentId,d.name departmentName FROM staff s JOIN department d ON d.id=s.department_id
            WHERE s.enabled ORDER BY d.name,s.name LIMIT 1000
            """));
        form.put("departments", db.maps("SELECT id,name FROM department ORDER BY name"));
        return form;
    }

    @PostMapping("/visits/{id}/reception")
    Map<String, Object> reception(Authentication user, @PathVariable long id, @Valid @RequestBody Visits.Reception input) {
        return visits.reception(catalog.actor(user), id, input);
    }

    @PostMapping("/invitations")
    Map<String, Object> invite(Authentication user, @Valid @RequestBody Visits.InviteForm form) {
        return visits.invite(catalog.actor(user), form);
    }

    @GetMapping("/invitations")
    List<Map<String, Object>> invitations(Authentication user) {
        return visits.invitations(catalog.actor(user));
    }

    @PostMapping("/invitations/{id}/cancel")
    void cancelInvite(Authentication user, @PathVariable long id) {
        visits.cancelInvite(catalog.actor(user), id);
    }

    @GetMapping("/approvals")
    List<Map<String, Object>> approvals(Authentication user, @RequestParam(defaultValue = "false") boolean history) {
        return visits.approvals(catalog.actor(user), history);
    }

    @GetMapping("/approval-rules/unassigned")
    List<Map<String, Object>> unassigned(Authentication user, @RequestParam long site) {
        requireSite(catalog.actor(user), site, "ADMIN", "ADMINISTRATION");
        return visits.unassigned(site);
    }

    @PostMapping("/approvals/{id}/decision")
    Map<String, Object> decide(Authentication user, @PathVariable long id, @Valid @RequestBody Visits.Decision decision) {
        return visits.decide(catalog.actor(user), id, decision);
    }

    @PostMapping("/walk-ins")
    Map<String, Object> walkIn(Authentication user, @Valid @RequestBody Visits.Form form) {
        return visits.submit(form, null, catalog.actor(user));
    }

    @GetMapping("/walk-ins/{id}")
    Map<String, Object> walkIn(Authentication user, @PathVariable long id) {
        return details(user, id);
    }

    @GetMapping("/visits")
    List<Map<String, Object>> list(Authentication user, @RequestParam long site, @RequestParam(required = false) Long gate,
                                  @RequestParam(defaultValue = "") @Size(max = 80) String search,
                                  @RequestParam(defaultValue = "") String state,
                                  @RequestParam(defaultValue = "9223372036854775807") long before) {
        listScope(catalog.actor(user), site, gate);
        return visits.staffList(site, search, state, before);
    }

    @GetMapping("/visits/{id}")
    Map<String, Object> details(Authentication user, @PathVariable long id) {
        var actor = catalog.actor(user);
        var visit = visits.get(id);
        requireSite(actor, visit.getSiteId(), "GUARD", "ADMINISTRATION", "SAFETY");
        catalog.audit(actor, visit.getSiteId(), "VISIT_VIEWED", id, null);
        return visits.timeline(visit);
    }

    @GetMapping("/gate/presences")
    List<Map<String, Object>> present(Authentication user, @RequestParam long gate,
                                      @RequestParam(defaultValue = "") @Size(max = 80) String search) {
        var location = catalog.requireGate(catalog.actor(user), gate);
        return visits.staffList(location.siteId(), search, "PRESENT", Long.MAX_VALUE);
    }

    @PostMapping("/gate/verifications")
    Map<String, Object> check(Authentication user, @Valid @RequestBody Gates.Lookup input) {
        return gates.preview(catalog.actor(user), input);
    }

    @PostMapping("/gate/entries")
    Gates.Result enter(Authentication user, @RequestHeader("Idempotency-Key") String key,
                       @Valid @RequestBody Gates.Confirm input) {
        return gates.confirm(catalog.actor(user), Policy.Direction.ENTRY, key, input);
    }

    @PostMapping("/gate/exits")
    Gates.Result exit(Authentication user, @RequestHeader("Idempotency-Key") String key,
                      @Valid @RequestBody Gates.Confirm input) {
        return gates.confirm(catalog.actor(user), Policy.Direction.EXIT, key, input);
    }

    private void listScope(Catalog.Actor actor, long site, Long gate) {
        if (actor.siteRole(site, "ADMINISTRATION", "SAFETY")) {
            return;
        }
        if (gate == null || catalog.requireGate(actor, gate).siteId() != site) {
            throw Problem.denied();
        }
    }

    static void requireSite(Catalog.Actor actor, long site, String... roles) {
        if (!actor.siteRole(site, roles)) {
            throw Problem.denied();
        }
    }
}
