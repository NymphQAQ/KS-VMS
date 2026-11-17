package cn.ks.vms;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/staff")
class Reports {
    record Range(Instant start, Instant end) {}
    record Filter(Policy.Type type, String source, String department, String plate) {
        Object[] args(Object... base) {
            if (!List.of("", "SELF", "INVITED", "ONSITE").contains(source) || department.length() > 100 || plate.length() > 20) {
                throw Problem.bad("统计筛选条件无效");
            }
            var values = new ArrayList<>(java.util.Arrays.asList(base));
            String typeCode = type == null ? "" : type.name();
            String plateKey = plate.isBlank() ? "" : Visits.normalizePlate(plate);
            values.addAll(List.of(typeCode, typeCode, source, source, department, department, plateKey, plateKey));
            return values.toArray();
        }
    }

    private static final String FILTER = """
        AND (?='' OR v.type=?) AND (?='' OR v.source=?) AND (?='' OR d.name=?) AND (?='' OR v.plate=?)
        """;
    private final Db db;
    private final Catalog catalog;
    private final Clock clock;

    Reports(Db db, Catalog catalog, Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.clock = clock;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard(Authentication user, @RequestParam long site, @RequestParam(required = false) Long gate) {
        var actor = catalog.actor(user);
        var location = catalog.site(site);
        if (!actor.siteRole(site, "MANAGER", "ADMINISTRATION", "SAFETY")
            && (gate == null || catalog.requireGate(actor, gate).siteId() != site)) {
            throw Problem.denied();
        }
        LocalDate today = clock.instant().atZone(ZoneId.of(location.zone())).toLocalDate();
        var day = range(location, today, today);
        return db.maps("""
            SELECT
              (SELECT COUNT(*) FROM visit WHERE site_id=? AND planned_in>=? AND planned_in<?
                AND state IN ('APPROVED','PENDING') AND (window_end>UTC_TIMESTAMP(6) OR id IN
                    (SELECT visit_id FROM presence WHERE site_id=?))) booked,
              COALESCE(SUM(in_at>=? AND in_at<?),0) entered,
              COALESCE(SUM(out_at>=? AND out_at<?),0) departed,
              COALESCE(SUM(out_at IS NULL),0) present,
              COALESCE(SUM(out_at IS NULL AND due_at<=UTC_TIMESTAMP(6)),0) overdue
            FROM presence WHERE site_id=?
            """, site, day.start(), day.end(), site, day.start(), day.end(), day.start(), day.end(), site).getFirst();
    }

    @GetMapping("/statistics")
    Map<String, Object> stats(Authentication user, @RequestParam long site,
                              @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                              @RequestParam(required = false) Policy.Type type, @RequestParam(defaultValue = "") String source,
                              @RequestParam(defaultValue = "") String department, @RequestParam(defaultValue = "") String plate) {
        VisitController.requireSite(catalog.actor(user), site, "MANAGER", "ADMINISTRATION", "SAFETY");
        var period = range(catalog.site(site), from, to);
        var filter = new Filter(type, source, department.strip(), plate.strip());
        var visits = db.maps("""
            SELECT v.type,COALESCE(SUM(v.planned_in>=? AND v.planned_in<? AND v.state IN ('APPROVED','PENDING')
                AND (v.window_end>UTC_TIMESTAMP(6) OR p.in_at IS NOT NULL)),0) total,
              SUM(p.in_at>=? AND p.in_at<?) entered,SUM(p.out_at>=? AND p.out_at<?) departed
            FROM visit v LEFT JOIN department d ON d.id=v.department_id LEFT JOIN presence p ON p.visit_id=v.id
            WHERE v.site_id=? AND v.state NOT IN ('CANCELLED','REJECTED','SUPERSEDED')
              AND ((v.planned_in>=? AND v.planned_in<?) OR (p.in_at>=? AND p.in_at<?) OR (p.out_at>=? AND p.out_at<?))
            """ + FILTER + " GROUP BY v.type ORDER BY total DESC", filter.args(statsArgs(site, period)));
        var departments = db.maps("""
            SELECT COALESCE(d.name,'待确认接访部门') name,COALESCE(SUM(v.planned_in>=? AND v.planned_in<? AND v.state IN ('APPROVED','PENDING')
                AND (v.window_end>UTC_TIMESTAMP(6) OR p.in_at IS NOT NULL)),0) total,
              SUM(p.in_at>=? AND p.in_at<?) entered,SUM(p.out_at>=? AND p.out_at<?) departed
            FROM visit v LEFT JOIN department d ON d.id=v.department_id LEFT JOIN presence p ON p.visit_id=v.id
            WHERE v.site_id=? AND v.state NOT IN ('CANCELLED','REJECTED','SUPERSEDED')
              AND ((v.planned_in>=? AND v.planned_in<?) OR (p.in_at>=? AND p.in_at<?) OR (p.out_at>=? AND p.out_at<?))
            """ + FILTER + " GROUP BY d.id,d.name ORDER BY total DESC", filter.args(statsArgs(site, period)));
        var vehicles = db.maps("""
            SELECT p.plate,v.type,COALESCE(SUM(vehicle_in_at>=? AND vehicle_in_at<?),0) entered,
              COALESCE(SUM(vehicle_out_at>=? AND vehicle_out_at<?),0) departed
            FROM presence p JOIN visit v ON v.id=p.visit_id LEFT JOIN department d ON d.id=v.department_id
            WHERE p.site_id=? AND p.plate IS NOT NULL AND
              ((vehicle_in_at>=? AND vehicle_in_at<?) OR (vehicle_out_at>=? AND vehicle_out_at<?))
            """ + FILTER + " GROUP BY p.plate,v.type ORDER BY entered DESC LIMIT 250",
            filter.args(period.start(), period.end(), period.start(), period.end(), site,
                period.start(), period.end(), period.start(), period.end()));
        return Map.of("visitors", visits, "departments", departments, "vehicles", vehicles);
    }

    @GetMapping("/exports")
    ResponseEntity<byte[]> export(Authentication user, @RequestParam long site,
                                  @RequestParam(defaultValue = "visits") String kind,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) Policy.Type type, @RequestParam(defaultValue = "") String source,
                                  @RequestParam(defaultValue = "") String department, @RequestParam(defaultValue = "") String plate) {
        var actor = catalog.actor(user);
        actor.require("EXPORT", site, null, null);
        var location = catalog.site(site);
        var dates = range(location, from, to);
        var filter = new Filter(type, source, department.strip(), plate.strip());
        List<Map<String, Object>> rows = switch (kind) {
            case "visits" -> db.maps("""
                SELECT v.id 编号,v.type 类型,v.name 姓名,v.company 来访公司,v.source 来源,
                  d.name 接访部门,v.state 审批状态,p.in_at 入厂时间,p.out_at 离厂时间
                FROM visit v LEFT JOIN department d ON d.id=v.department_id LEFT JOIN presence p ON p.visit_id=v.id
                WHERE v.site_id=? AND ((v.planned_in>=? AND v.planned_in<?) OR (p.in_at>=? AND p.in_at<?)
                  OR (p.out_at>=? AND p.out_at<?))
                """ + FILTER + " ORDER BY v.id LIMIT 10001",
                filter.args(site, dates.start(), dates.end(), dates.start(), dates.end(), dates.start(), dates.end()));
            case "vehicles" -> db.maps("""
                SELECT p.visit_id 访问编号,p.plate 车牌,v.type 类型,p.vehicle_in_at 入厂时间,p.vehicle_out_at 离厂时间
                FROM presence p JOIN visit v ON p.visit_id=v.id LEFT JOIN department d ON d.id=v.department_id WHERE p.site_id=? AND p.plate IS NOT NULL
                AND ((p.vehicle_in_at>=? AND p.vehicle_in_at<?) OR (p.vehicle_out_at>=? AND p.vehicle_out_at<?))
                """ + FILTER + " ORDER BY p.id LIMIT 10001", filter.args(site, dates.start(), dates.end(), dates.start(), dates.end()));
            case "alerts" -> db.maps("""
                SELECT a.id 编号,a.visit_id 访问编号,a.kind 预警类型,a.state 状态,a.created_at 触发时间,a.last_at 最近触发
                FROM alert a LEFT JOIN visit v ON v.id=a.visit_id LEFT JOIN department d ON d.id=v.department_id
                WHERE a.site_id=? AND a.created_at>=? AND a.created_at<?
                """ + FILTER + " ORDER BY a.id LIMIT 10001", filter.args(site, dates.start(), dates.end()));
            default -> throw Problem.bad("导出类型无效");
        };
        if (rows.size() > 10_000) {
            throw Problem.bad("导出超过一万条，请缩短时间范围");
        }
        var csv = new StringBuilder("\ufeff");
        List<String> headings = switch (kind) {
            case "visits" -> List.of("编号", "类型", "姓名", "来访公司", "来源", "接访部门", "审批状态", "入厂时间", "离厂时间");
            case "vehicles" -> List.of("访问编号", "车牌", "类型", "入厂时间", "离厂时间");
            default -> List.of("编号", "访问编号", "预警类型", "状态", "触发时间", "最近触发");
        };
        line(csv, headings);
        for (var row : rows) {
            var values = new ArrayList<String>();
            for (String heading : headings) {
                Object value = row.get(heading);
                if (value instanceof Instant time) {
                    value = time.atZone(ZoneId.of(location.zone())).toOffsetDateTime();
                }
                String text = value == null ? "" : value.toString();
                values.add(switch (heading) {
                    case "姓名", "车牌" -> text.isBlank() ? text : text.substring(0, 1) + "***";
                    default -> text;
                });
            }
            line(csv, values);
        }
        catalog.audit(actor, site, "EXPORT", null, kind + " " + dates.start() + " " + dates.end());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(kind + ".csv").build().toString())
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Object[] statsArgs(long site, Range range) {
        return new Object[]{range.start(), range.end(), range.start(), range.end(), range.start(), range.end(), site,
            range.start(), range.end(), range.start(), range.end(), range.start(), range.end()};
    }

    private Range range(Catalog.Site site, LocalDate first, LocalDate last) {
        var zone = ZoneId.of(site.zone());
        LocalDate to = last == null ? clock.instant().atZone(zone).toLocalDate() : last;
        LocalDate from = first == null ? to.minusDays(6) : first;
        if (from.isAfter(to) || from.plusDays(366).isBefore(to)) {
            throw Problem.bad("请选择一年以内的有效日期范围");
        }
        return new Range(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private void line(StringBuilder csv, List<String> values) {
        csv.append(values.stream().map(value -> {
            String trimmed = value.stripLeading();
            if (!trimmed.isEmpty() && "=+-@\t\r\n".indexOf(trimmed.charAt(0)) >= 0) {
                value = "'" + value;
            }
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
    }
}
