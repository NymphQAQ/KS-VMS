package cn.ks.vms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff/admin")
class AdminController {
    static final Set<String> ROLES = Set.of("ADMIN", "ADMINISTRATION", "EMPLOYEE", "GUARD", "MANAGER", "SAFETY",
        "HOST_SUPERVISOR", "OWNER", "PROCUREMENT", "WAREHOUSE", "LOGISTICS", "PROJECT", "EQUIPMENT", "HR", "IT", "EXPORT");
    record SiteForm(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 64) String zone,
                    @Min(0) @Max(1440) int leadMinutes, @Min(1) @Max(180) int advanceDays,
                    @Min(1) @Max(1440) int waitMinutes, @Min(1) @Max(10080) int maxMinutes,
                    @NotNull LocalTime openTime, @NotNull LocalTime closeTime, boolean enabled) {}
    record Name(@NotBlank @Size(max = 100) String name) {}
    record Location(@Positive long siteId, @NotBlank @Size(max = 100) String name, boolean enabled) {}
    record UserForm(@NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,64}") String username,
                    @NotBlank @Size(max = 100) String name, @Size(min = 12, max = 64) String password,
                    @Positive Long departmentId, boolean hostPublic, boolean enabled, @Size(max = 80) String mfaSecret) {}
    record GrantForm(@NotBlank String role, @Positive Long siteId, @Positive Long departmentId, @Positive Long gateId,
                     Instant startAt, Instant endAt) {}
    record NoticeForm(@NotBlank @Pattern(regexp = "SAFETY|PRIVACY") String kind,
                      @NotBlank @Size(max = 16000) String text) {}
    record LinkForm(@Positive long siteId, @Positive Long gateId, @Size(max = 80) String displayName) {}
    record LinkStatus(@NotNull Boolean enabled) {}
    record RegistrationLink(long siteId, Long gateId, boolean enabled) {}

    private final Catalog catalog;
    private final Db db;
    private final Crypto crypto;
    private final Visits visits;
    private final PasswordEncoder passwords;
    private final String visitorUrl;

    AdminController(Catalog catalog, Db db, Crypto crypto, Visits visits, PasswordEncoder passwords,
                    @Value("${vms.visitor-url}") String visitorUrl) {
        this.catalog = catalog;
        this.db = db;
        this.crypto = crypto;
        this.visits = visits;
        this.passwords = passwords;
        this.visitorUrl = visitorUrl.replaceFirst("/$", "");
    }

    @GetMapping("/configuration")
    Map<String, Object> configuration(Authentication user) {
        admin(user);
        return Map.of("sites", db.rows(Catalog.Site.class, "SELECT * FROM site ORDER BY id"),
            "gates", db.rows(Catalog.Gate.class, "SELECT * FROM gate ORDER BY id"),
            "areas", db.maps("SELECT id,site_id siteId,name,enabled FROM area ORDER BY id"),
            "departments", db.maps("SELECT id,name FROM department ORDER BY name"),
            "users", db.maps("""
                SELECT id,username,name,department_id departmentId,host_public hostPublic,enabled FROM staff ORDER BY id
                """),
            "roles", ROLES.stream().sorted().toList(),
            "grants", db.maps("""
                SELECT id,staff_id staffId,role,site_id siteId,department_id departmentId,gate_id gateId,
                start_at startAt,end_at endAt FROM staff_grant ORDER BY id
                """),
            "links", db.maps("""
                SELECT slug,site_id siteId,gate_id gateId FROM registration_link WHERE enabled ORDER BY site_id,gate_id
                """));
    }

    @PostMapping("/sites")
    Map<String, Long> site(Authentication user, @Valid @RequestBody SiteForm input,
                           @RequestParam(required = false) Long id) {
        var actor = admin(user);
        try {
            ZoneId.of(input.zone());
        } catch (java.time.DateTimeException error) {
            throw Problem.bad("厂区时区无效");
        }
        var args = new java.util.ArrayList<>(java.util.Arrays.asList(input.name(), input.zone(), input.leadMinutes(),
            input.advanceDays(), input.waitMinutes(), input.maxMinutes(), input.openTime(), input.closeTime(), input.enabled()));
        if (id == null) {
            id = db.insert("""
                INSERT INTO site(name,zone,lead_minutes,advance_days,wait_minutes,max_minutes,open_time,close_time,enabled)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, args.toArray());
        } else {
            args.add(id);
            if (db.update("""
                UPDATE site SET name=?,zone=?,lead_minutes=?,advance_days=?,wait_minutes=?,max_minutes=?,
                open_time=?,close_time=?,enabled=? WHERE id=?
                """, args.toArray()) == 0) {
                throw Problem.missing();
            }
        }
        catalog.audit(actor, id, "SITE_SAVED", id, null);
        return Map.of("id", id);
    }

    @PostMapping("/departments")
    Map<String, Long> department(Authentication user, @Valid @RequestBody Name input) {
        var actor = admin(user);
        long id = db.insert("INSERT INTO department(name) VALUES (?)", input.name().strip());
        catalog.audit(actor, null, "DEPARTMENT_CREATED", id, null);
        return Map.of("id", id);
    }

    @PostMapping("/gates")
    Map<String, Long> gate(Authentication user, @Valid @RequestBody Location input, @RequestParam(required = false) Long id) {
        var actor = admin(user);
        catalog.site(input.siteId());
        if (id == null) {
            id = db.insert("INSERT INTO gate(site_id,name,enabled) VALUES (?,?,?)", input.siteId(), input.name(), input.enabled());
        } else if (db.update("UPDATE gate SET name=?,enabled=? WHERE id=? AND site_id=?",
            input.name(), input.enabled(), id, input.siteId()) == 0) {
            throw Problem.missing();
        }
        catalog.audit(actor, input.siteId(), "GATE_SAVED", id, null);
        return Map.of("id", id);
    }

    @GetMapping("/areas")
    List<Map<String, Object>> areas(Authentication user, @RequestParam long site) {
        var actor = catalog.actor(user);
        VisitController.requireSite(actor, site, "ADMIN", "SAFETY", "ADMINISTRATION");
        return db.maps("SELECT id,site_id siteId,name,enabled FROM area WHERE site_id=? ORDER BY name", site);
    }

    @PostMapping("/areas")
    Map<String, Long> area(Authentication user, @Valid @RequestBody Location input, @RequestParam(required = false) Long id) {
        var actor = catalog.actor(user);
        VisitController.requireSite(actor, input.siteId(), "ADMIN", "SAFETY", "ADMINISTRATION");
        catalog.site(input.siteId());
        if (id == null) {
            id = db.insert("INSERT INTO area(site_id,name,enabled) VALUES (?,?,?)", input.siteId(), input.name(), input.enabled());
        } else if (db.update("UPDATE area SET name=?,enabled=? WHERE id=? AND site_id=?",
            input.name(), input.enabled(), id, input.siteId()) == 0) {
            throw Problem.missing();
        }
        catalog.audit(actor, input.siteId(), "AREA_SAVED", id, null);
        return Map.of("id", id);
    }

    @PostMapping("/users")
    Map<String, Long> user(Authentication user, @Valid @RequestBody UserForm input, @RequestParam(required = false) Long id) {
        var actor = admin(user);
        if (input.departmentId() != null && db.count("SELECT COUNT(*) FROM department WHERE id=?", input.departmentId()) == 0) {
            throw Problem.bad("请选择有效部门");
        }
        if (input.password() != null && input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw Problem.bad("密码过长，请使用 12 至 64 位 ASCII 字符");
        }
        String mfa = Visits.hasText(input.mfaSecret()) ? crypto.encryptAuthenticator(Totp.normalize(input.mfaSecret())) : null;
        if (id == null) {
            if (!Visits.hasText(input.password())) {
                throw Problem.bad("请输入初始密码");
            }
            id = db.insert("""
                INSERT INTO staff(username,name,password_hash,department_id,host_public,enabled,mfa_secret) VALUES (?,?,?,?,?,?,?)
                """, input.username(), input.name(), passwords.encode(input.password()), input.departmentId(),
                input.hostPublic(), input.enabled(), mfa);
        } else {
            if (id == actor.id() && !input.enabled()) {
                throw Problem.bad("无法停用当前登录账号");
            }
            if (db.update("""
                UPDATE staff SET name=?,department_id=?,host_public=?,enabled=?,password_hash=COALESCE(?,password_hash),
                mfa_secret=COALESCE(?,mfa_secret),mfa_counter=IF(? IS NULL,mfa_counter,-1),
                auth_version=auth_version+IF(? OR ? OR NOT ?,1,0)
                WHERE id=? AND username=?
                """, input.name(), input.departmentId(), input.hostPublic(), input.enabled(),
                input.password() == null ? null : passwords.encode(input.password()), mfa, mfa, input.password() != null,
                mfa != null, input.enabled(), id, input.username()) == 0) {
                throw Problem.missing();
            }
        }
        catalog.audit(actor, null, "STAFF_SAVED", id, null);
        return Map.of("id", id);
    }

    @PostMapping("/users/{id}/grants")
    Map<String, Long> grant(Authentication user, @PathVariable long id, @Valid @RequestBody GrantForm input) {
        var actor = admin(user);
        if (!ROLES.contains(input.role()) || input.endAt() != null && input.startAt() != null
            && !input.endAt().isAfter(input.startAt())) {
            throw Problem.bad("角色或授权起止时间无效");
        }
        if (db.count("SELECT COUNT(*) FROM staff WHERE id=?", id) == 0) {
            throw Problem.missing();
        }
        if (input.siteId() != null) {
            catalog.site(input.siteId());
        }
        if (input.gateId() != null && (!"GUARD".equals(input.role()) || input.siteId() == null
            || catalog.gate(input.gateId()).siteId() != input.siteId())) {
            throw Problem.bad("门岗授权须关联对应厂区");
        }
        if (input.departmentId() != null && (!List.of("HOST_SUPERVISOR", "PROJECT").contains(input.role())
            || db.count("SELECT COUNT(*) FROM department WHERE id=?", input.departmentId()) == 0)) {
            throw Problem.bad("请选择适用部门");
        }
        if ("ADMIN".equals(input.role()) && (input.siteId() != null || input.endAt() != null || input.startAt() != null)) {
            throw Problem.bad("系统管理员须使用全局长期授权");
        }
        long grant = db.insert("""
            INSERT INTO staff_grant(staff_id,role,site_id,department_id,gate_id,start_at,end_at)
            VALUES (?,?,?,?,?,?,?)
            """, id, input.role(), input.siteId(), input.departmentId(), input.gateId(), input.startAt(), input.endAt());
        catalog.audit(actor, input.siteId(), "GRANT_ADDED", grant, input.role());
        return Map.of("id", grant);
    }

    @DeleteMapping("/grants/{id}")
    void removeGrant(Authentication user, @PathVariable long id) {
        var actor = admin(user);
        if (db.update("DELETE FROM staff_grant WHERE id=? AND NOT (staff_id=? AND role='ADMIN')", id, actor.id()) == 0) {
            throw Problem.bad("该授权不可撤销");
        }
        catalog.audit(actor, null, "GRANT_REMOVED", id, null);
    }

    @GetMapping("/notices")
    List<Catalog.Notice> notices(Authentication user, @RequestParam long site) {
        VisitController.requireSite(catalog.actor(user), site, "ADMIN", "SAFETY", "ADMINISTRATION");
        return catalog.notices(site);
    }

    @PostMapping("/notices")
    Map<String, Long> notice(Authentication user, @RequestParam long site, @Valid @RequestBody NoticeForm input) {
        var actor = catalog.actor(user);
        VisitController.requireSite(actor, site, "ADMIN", "SAFETY", "ADMINISTRATION");
        catalog.site(site);
        long id = db.insert("INSERT INTO notice(site_id,kind,text) VALUES (?,?,?)", site, input.kind(), input.text());
        catalog.audit(actor, site, "NOTICE_PUBLISHED", id, input.kind());
        return Map.of("id", id);
    }

    @GetMapping("/registration-links")
    Map<String, Object> links(Authentication user, @RequestParam long site) {
        VisitController.requireSite(catalog.actor(user), site, "ADMIN", "ADMINISTRATION");
        catalog.site(site);
        return Map.of("links", db.maps("""
            SELECT l.slug,l.display_name displayName,l.gate_id gateId,g.name gateName,l.enabled
            FROM registration_link l LEFT JOIN gate g ON l.gate_id=g.id
            WHERE l.site_id=? ORDER BY l.enabled DESC,l.gate_id,l.slug
            """, site), "gates", db.maps("SELECT id,name FROM gate WHERE site_id=? AND enabled ORDER BY name", site));
    }

    @PostMapping("/registration-links")
    @Transactional
    Map<String, String> link(Authentication user, @Valid @RequestBody LinkForm input) {
        var actor = catalog.actor(user);
        VisitController.requireSite(actor, input.siteId(), "ADMIN", "ADMINISTRATION");
        validateLocation(input.siteId(), input.gateId());
        String slug = crypto.token();
        String name = Visits.hasText(input.displayName()) ? input.displayName().strip() : null;
        db.update("INSERT INTO registration_link(slug,site_id,gate_id,display_name) VALUES (?,?,?,?)",
            slug, input.siteId(), input.gateId(), name);
        catalog.audit(actor, input.siteId(), "REGISTRATION_LINK_CREATED", null, name);
        return linkView(slug);
    }

    @GetMapping("/registration-links/{slug}")
    Map<String, String> showLink(Authentication user, @PathVariable String slug) {
        var actor = catalog.actor(user);
        var location = registrationLink(slug, false);
        if (!actor.siteRole(location.siteId(), "ADMIN", "ADMINISTRATION")) {
            VisitController.requireSite(actor, catalog.link(slug).siteId(), "GUARD");
        }
        return linkView(slug);
    }

    @PatchMapping("/registration-links/{slug}")
    @Transactional
    void linkStatus(Authentication user, @PathVariable String slug, @Valid @RequestBody LinkStatus input) {
        var actor = catalog.actor(user);
        var location = registrationLink(slug, true);
        VisitController.requireSite(actor, location.siteId(), "ADMIN", "ADMINISTRATION");
        if (input.enabled() == location.enabled()) {
            return;
        }
        if (input.enabled()) {
            validateLocation(location.siteId(), location.gateId());
        }
        db.update("UPDATE registration_link SET enabled=? WHERE slug=?", input.enabled(), slug);
        catalog.audit(actor, location.siteId(), input.enabled() ? "REGISTRATION_LINK_ENABLED" : "REGISTRATION_LINK_DISABLED",
            null, slug);
    }

    private RegistrationLink registrationLink(String slug, boolean lock) {
        var link = db.one(RegistrationLink.class, "SELECT site_id,gate_id,enabled FROM registration_link WHERE slug=?"
            + (lock ? " FOR UPDATE" : ""), slug);
        if (link == null) {
            throw Problem.missing();
        }
        return link;
    }

    private void validateLocation(long site, Long gate) {
        catalog.site(site);
        if (gate != null && catalog.gate(gate).siteId() != site) {
            throw Problem.bad("门岗不属于所选厂区");
        }
    }

    private Map<String, String> linkView(String slug) {
        String url = visitorUrl + "/visitor?register=" + slug;
        return Map.of("slug", slug, "url", url, "qr", visits.qr(url));
    }

    @GetMapping("/audit")
    List<Map<String, Object>> audit(Authentication user) {
        admin(user);
        return db.maps("""
            SELECT a.id,a.action,a.reference_id referenceId,a.detail,a.created_at createdAt,u.name staffName
            FROM audit_log a LEFT JOIN staff u ON u.id=a.staff_id ORDER BY a.id DESC LIMIT 100
            """);
    }

    private Catalog.Actor admin(Authentication user) {
        var actor = catalog.actor(user);
        actor.require("ADMIN", null, null, null);
        return actor;
    }
}
