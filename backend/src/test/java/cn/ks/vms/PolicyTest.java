package cn.ks.vms;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PolicyTest {
    private final Catalog.Site site = new Catalog.Site(1, "测试厂区", "Asia/Shanghai", 120, 30, 60,
        720, LocalTime.of(8, 0), LocalTime.of(22, 0), true);
    private final Instant now = Instant.parse("2026-11-17T02:00:00Z");

    @Test
    void onsiteAddsSupervisorAndKeepsRequiredDepartmentApprovals() {
        assertThat(Policy.steps(Policy.Type.DELIVERY, true)).containsExactly("HOST_SUPERVISOR", "WAREHOUSE_OR_PROCUREMENT");
        assertThat(Policy.steps(Policy.Type.CONTRACTOR, true)).containsExactly("HOST_SUPERVISOR", "PROJECT", "SAFETY");
        assertThat(Policy.steps(Policy.Type.SOFTWARE, false)).containsExactly("IT");
    }

    @Test
    void appliesLeadTimeAndCapsOnsiteWindow() {
        var onsite = Policy.window(site, now, now.plusSeconds(3600), now.plusSeconds(7200), false);
        assertThat(onsite).isEqualTo(new Policy.Window(true, now, now.plusSeconds(3600)));
        var ahead = Policy.window(site, now, now.plusSeconds(7200), now.plusSeconds(10800), false);
        assertThat(ahead).isEqualTo(new Policy.Window(false, now.plusSeconds(7200), now.plusSeconds(10800)));
    }

    @Test
    void checksClosingTimeAndOvernightHours() {
        var evening = Instant.parse("2026-11-17T12:00:00Z");
        assertThat(Policy.window(site, now, evening, evening.plusSeconds(14_400), false).end())
            .isEqualTo(Instant.parse("2026-11-17T14:00:00Z"));
        var overnight = new Catalog.Site(1, "测试夜班", "Asia/Shanghai", 0, 30, 60, 720,
            LocalTime.of(20, 0), LocalTime.of(6, 0), true);
        var early = Instant.parse("2026-11-17T20:00:00Z");
        assertThat(Policy.window(overnight, early, early, early.plusSeconds(14400), false).end())
            .isEqualTo(Instant.parse("2026-11-17T22:00:00Z"));
    }

    @Test
    void dateOnlyBookingUsesOpeningHoursAndPreservesAdvanceNotice() {
        var today = LocalDate.parse("2026-11-17");
        assertThat(Policy.bookingDay(site, now, today, false).window())
            .isEqualTo(new Policy.Window(false, now.plusSeconds(7200), Instant.parse("2026-11-17T14:00:00Z")));
        assertThat(Policy.bookingDay(site, now, today.plusDays(1), false).window().start())
            .isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
        var shortlyBeforeClosing = Instant.parse("2026-11-17T13:40:00Z");
        assertThat(Policy.bookingDay(site, shortlyBeforeClosing, today, false).window())
            .isEqualTo(new Policy.Window(true, shortlyBeforeClosing, Instant.parse("2026-11-17T14:00:00Z")));
        assertThatThrownBy(() -> Policy.bookingDay(site, now, today.plusDays(31), false)).isInstanceOf(Problem.class);
    }

    @Test
    void totpMatchesRfcTestVectorAndRejectsExpiredCounter() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(Totp.at(secret, 1)).isEqualTo("287082");
        assertThat(Totp.counter(secret, "287082", Instant.ofEpochSecond(60))).isEqualTo(1);
        assertThat(Totp.counter(secret, "287082", Instant.ofEpochSecond(90))).isEqualTo(-1);
    }
}
