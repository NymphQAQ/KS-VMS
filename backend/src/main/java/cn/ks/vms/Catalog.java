package cn.ks.vms;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
record Catalog(Db db, @Value("${vms.demo}") boolean demo) {
    record Site(long id, String name, String zone, int leadMinutes, int advanceDays,
                int waitMinutes, int maxMinutes, LocalTime openTime, LocalTime closeTime, boolean enabled) {}
    record Gate(long id, long siteId, String name, boolean enabled) {}
    record Host(long id, String name, Long departmentId, boolean hostPublic) {}
    record Link(long siteId, Long gateId) {}
    record Notice(long id, String kind, String text) {}
    record Grant(String role, Long siteId, Long departmentId, Long gateId) {}

    record Actor(long id, String username, String name, List<Grant> grants) {
        boolean can(String role, Long site, Long department, Long gate) {
            return grants.stream().anyMatch(grant -> grant.role().equals(role)
                && matches(site, grant.siteId()) && matches(department, grant.departmentId())
                && matches(gate, grant.gateId()));
        }

        boolean has(String role) {
            return grants.stream().anyMatch(grant -> grant.role().equals(role));
        }

        boolean siteRole(long site, String... roles) {
            return grants.stream().anyMatch(grant -> Arrays.asList(roles).contains(grant.role())
                && matches(site, grant.siteId()));
        }

        void require(String role, Long site, Long department, Long gate) {
            if (!can(role, site, department, gate)) {
                throw Problem.denied();
            }
        }

        private boolean matches(Long requested, Long granted) {
            return granted == null || granted.equals(requested);
        }
    }

    Actor actor(Authentication authentication) {
        var user = staff(authentication.getName());
        var grants = grants(user.id());
        if (!(authentication.getDetails() instanceof Access.SignIn session) || session.version() != user.authVersion()
            || !demo && !session.mfa() && grants.stream().anyMatch(grant -> Totp.PRIVILEGED_ROLES.contains(grant.role()))) {
            throw new Problem(401, "AUTH_REQUIRED", "登录权限已变更，请重新登录");
        }
        return new Actor(user.id(), user.username(), user.name(), grants);
    }

    Actor actor(String username) {
        var user = staff(username);
        return new Actor(user.id(), user.username(), user.name(), grants(user.id()));
    }

    private Staff staff(String username) {
        var user = db.one(Staff.class, "SELECT id,username,name,auth_version FROM staff WHERE username=? AND enabled", username);
        if (user == null) {
            throw Problem.denied();
        }
        return user;
    }

    List<Grant> grants(long staff) {
        return db.rows(Grant.class, """
            SELECT role,site_id,department_id,gate_id FROM staff_grant WHERE staff_id=?
            AND (start_at IS NULL OR start_at<=UTC_TIMESTAMP(6)) AND (end_at IS NULL OR end_at>UTC_TIMESTAMP(6))
            """, staff);
    }

    Site site(long id) {
        var site = db.one(Site.class, "SELECT * FROM site WHERE id=?", id);
        if (site == null || !site.enabled()) {
            throw Problem.missing();
        }
        return site;
    }

    Gate gate(long id) {
        var gate = db.one(Gate.class, "SELECT * FROM gate WHERE id=? AND enabled", id);
        if (gate == null) {
            throw Problem.missing();
        }
        return gate;
    }

    Gate requireGate(Actor actor, long id) {
        var gate = gate(id);
        actor.require("GUARD", gate.siteId(), null, gate.id());
        return gate;
    }

    Host host(long id) {
        var host = db.one(Host.class, """
            SELECT id,name,department_id,host_public FROM staff WHERE id=? AND enabled AND department_id IS NOT NULL
            """, id);
        if (host == null) {
            throw Problem.bad("请选择有效的接待人员");
        }
        return host;
    }

    boolean activeArea(long site, Long area) {
        return area == null || db.count("SELECT COUNT(*) FROM area WHERE id=? AND site_id=? AND enabled", area, site) > 0;
    }

    Link link(String slug) {
        var link = db.one(Link.class, "SELECT site_id,gate_id FROM registration_link WHERE slug=? AND enabled", slug);
        if (link == null) {
            throw Problem.missing();
        }
        if (link.gateId() != null && gate(link.gateId()).siteId() != link.siteId()) {
            throw Problem.missing();
        }
        return link;
    }

    List<Notice> notices(long site) {
        return db.rows(Notice.class, """
            SELECT id,kind,text FROM notice WHERE site_id=? AND id IN
            (SELECT MAX(id) FROM notice WHERE site_id=? GROUP BY kind) ORDER BY kind
            """, site, site);
    }

    Map<String, Object> form(long siteId) {
        return Map.of("site", site(siteId), "notices", notices(siteId), "areas", db.maps(
            "SELECT id,name FROM area WHERE site_id=? AND enabled ORDER BY name", siteId),
            "types", Arrays.stream(Policy.Type.values()).map(type -> Map.of("code", type.name(), "label", type.label,
                "area", type.needsArea(), "reference", type.needsReference(), "driver", type.needsVehicle())).toList());
    }

    Map<String, Object> visitorForm(long siteId) {
        var site = site(siteId);
        return Map.of("site", Map.of("id", site.id(), "name", site.name(), "zone", site.zone(),
            "leadMinutes", site.leadMinutes(), "advanceDays", site.advanceDays()),
            "notices", notices(siteId),
            "types", Arrays.stream(Policy.Type.values()).map(type -> Map.of("code", type.name(), "label", type.label,
                "area", false, "reference", type.needsReference(), "driver", type.needsVehicle())).toList());
    }

    void audit(Actor actor, Long site, String action, Long reference, String detail) {
        db.update("INSERT INTO audit_log(staff_id,site_id,action,reference_id,detail) VALUES (?,?,?,?,?)",
            actor == null ? null : actor.id(), site, action, reference, detail);
    }

    private record Staff(long id, String username, String name, long authVersion) {}
}
