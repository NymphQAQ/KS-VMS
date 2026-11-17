package cn.ks.vms;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

final class Policy {
    enum Type {
        CUSTOMER("客户", "OWNER"), SUPPLIER("供应商", "PROCUREMENT"),
        DELIVERY("送货司机", "WAREHOUSE_OR_PROCUREMENT"), LOGISTICS("物流司机", "LOGISTICS"),
        CONTRACTOR("外协施工人员", "PROJECT", "SAFETY"), MAINTENANCE("设备维修人员", "EQUIPMENT"),
        INTERVIEW("面试人员", "HR"), SOFTWARE("软件维护人员", "IT");

        final String label;
        final List<String> roles;

        Type(String label, String... roles) {
            this.label = label;
            this.roles = List.of(roles);
        }

        boolean needsArea() {
            return this == CUSTOMER || this == CONTRACTOR || this == MAINTENANCE || this == SOFTWARE;
        }

        boolean needsReference() {
            return this == DELIVERY || this == LOGISTICS || this == MAINTENANCE || this == SOFTWARE;
        }

        boolean needsVehicle() {
            return this == DELIVERY || this == LOGISTICS;
        }
    }

    enum Direction { ENTRY, EXIT }

    record Window(boolean onsite, Instant start, Instant end) {}
    record DayPlan(Window window, Instant departure) {}

    static List<String> steps(Type type, boolean onsite) {
        var result = new ArrayList<String>();
        if (onsite) {
            result.add("HOST_SUPERVISOR");
        }
        result.addAll(type.roles);
        return result;
    }

    static Window window(Catalog.Site site, Instant now, Instant in, Instant out, boolean onsite) {
        var zone = ZoneId.of(site.zone());
        var today = now.atZone(zone).toLocalDate();
        var plannedDay = in.atZone(zone).toLocalDate();
        if (!out.isAfter(in) || !out.isAfter(now) || in.isAfter(now.plus(site.advanceDays(), ChronoUnit.DAYS))) {
            throw Problem.bad("请选择有效的到厂和离厂时间");
        }
        if (!onsite && in.isBefore(now.plus(site.leadMinutes(), ChronoUnit.MINUTES))) {
            if (!today.equals(plannedDay)) {
                throw Problem.bad("请按厂区要求提前预约");
            }
            onsite = true;
        }
        if (onsite && !today.equals(plannedDay)) {
            throw Problem.bad("现场登记限当日来访");
        }
        Instant start = onsite ? now : in;
        Instant end = onsite ? earlier(out, now.plus(site.waitMinutes(), ChronoUnit.MINUTES)) : out;
        LocalTime opening = site.openTime();
        LocalTime closing = site.closeTime();
        if (!opening.equals(closing)) {
            ZonedDateTime localStart = start.atZone(zone);
            var day = localStart.toLocalDate();
            boolean overnight = closing.isBefore(opening);
            if (overnight && localStart.toLocalTime().isBefore(closing)) {
                day = day.minusDays(1);
            }
            Instant opens = day.atTime(opening).atZone(zone).toInstant();
            Instant closes = (overnight ? day.plusDays(1) : day).atTime(closing).atZone(zone).toInstant();
            start = start.isAfter(opens) ? start : opens;
            end = earlier(end, closes);
        }
        if (!end.isAfter(start)) {
            throw Problem.bad("请选择厂区开放时间内的来访时段");
        }
        return new Window(onsite, start, end);
    }

    static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    static DayPlan bookingDay(Catalog.Site site, Instant now, LocalDate date, boolean onsite) {
        var zone = ZoneId.of(site.zone());
        var today = now.atZone(zone).toLocalDate();
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(site.advanceDays()))
            || (onsite && !date.equals(today))) {
            throw Problem.bad("请选择允许预约的来访日期");
        }
        if (onsite) {
            var window = window(site, now, now, now.plus(site.maxMinutes(), ChronoUnit.MINUTES), true);
            return new DayPlan(window, now.plus(site.maxMinutes(), ChronoUnit.MINUTES));
        }

        boolean allDay = site.openTime().equals(site.closeTime());
        Instant opening = allDay ? date.atStartOfDay(zone).toInstant() : date.atTime(site.openTime()).atZone(zone).toInstant();
        var closingDate = allDay || site.closeTime().isBefore(site.openTime()) ? date.plusDays(1) : date;
        Instant closing = allDay ? closingDate.atStartOfDay(zone).toInstant()
            : closingDate.atTime(site.closeTime()).atZone(zone).toInstant();
        Instant earliest = now.plus(site.leadMinutes(), ChronoUnit.MINUTES);
        Instant start = opening.isAfter(earliest) ? opening : earliest;
        if (!start.isBefore(closing)) {
            if (date.equals(today) && now.isBefore(closing)) {
                return new DayPlan(window(site, now, now, closing, true), closing);
            }
            throw Problem.bad("所选日期已无法提前预约，请选择其他日期");
        }
        return new DayPlan(new Window(false, start, closing), closing);
    }
}
