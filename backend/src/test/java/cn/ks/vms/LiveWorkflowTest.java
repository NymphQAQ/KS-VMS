package cn.ks.vms;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "VMS_TEST_URL", matches = ".+")
class LiveWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getenv("VMS_TEST_URL");
    private static final String PASSWORD = "KS-VMS-demo-2026";

    @Test
    void bookingUsesContactPhoneWithoutGrantingAccessToOtherBrowsers() throws Exception {
        var visitor = new Client("/api/visitor");
        var another = new Client("/api/visitor");
        var form = visitor.get("/registration/company").path("form");
        assertThat(visitor.get("/me").path("verified").asBoolean()).isFalse();
        String phone = "139" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String hostLookup = "/hosts?slug=company&name="
            + java.net.URLEncoder.encode("王嘉宁", java.nio.charset.StandardCharsets.UTF_8);
        visitor.get(hostLookup, 401);
        assertThat(form.has("areas")).isFalse();
        assertThat(form.has("hosts")).isFalse();
        assertThat(form.has("departments")).isFalse();
        var body = new HashMap<String, Object>(Map.of("slug", "company", "type", "SUPPLIER",
            "name", "预约联调访客", "company", "联调企业", "reason", "核对预约表",
            "visitDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()));
        for (JsonNode notice : form.path("notices")) {
            body.put("SAFETY".equals(notice.path("kind").asText()) ? "safetyNoticeId" : "privacyNoticeId",
                notice.path("id").asLong());
        }
        visitor.post("/applications", body, 400);
        body.put("phone", phone);
        long id = visitor.post("/applications", body).path("id").asLong();
        assertThat(id).isPositive();
        var detail = visitor.get("/applications/" + id);
        assertThat(detail.path("phone").asText()).isEqualTo(phone);
        assertThat(detail.path("source").asText()).isEqualTo("SELF");
        assertThat(detail.has("hostName")).isFalse();
        assertThat(detail.has("hostId")).isFalse();
        assertThat(detail.has("departmentName")).isFalse();
        assertThat(detail.has("departmentId")).isFalse();
        assertThat(visitor.get("/applications").get(0).path("id").asLong()).isEqualTo(id);

        another.get("/registration/company");
        String code = another.post("/otp", Map.of("phone", phone)).path("demoCode").asText();
        another.post("/sessions", Map.of("phone", phone, "code", code));
        another.get(hostLookup, 403);
        another.get("/visit-form?site=" + form.path("site").path("id").asLong(), 404);
        assertThat(another.get("/applications").isEmpty()).isTrue();
        another.get("/applications/" + id, 404);
        another.post("/applications/" + id + "/passes?direction=ENTRY", null, 404);
        another.post("/applications/" + id + "/cancel", null, 404);

        body.put("requestedHost", "访客自行填写的姓名");
        body.put("hostId", Long.MAX_VALUE);
        body.put("areaId", Long.MAX_VALUE);
        var revised = visitor.post("/applications/" + id + "/revisions", body);
        long replacement = revised.path("id").asLong();
        assertThat(revised.path("hostName").asText()).isEqualTo("访客自行填写的姓名");
        assertThat(revised.has("hostId")).isFalse();
        assertThat(revised.has("areaId")).isFalse();
        assertThat(visitor.get("/applications/" + replacement).path("phone").asText()).isEqualTo(phone);
        visitor.raw("/applications/" + replacement + "/cancel", "POST", null, null, 200);
    }

    @Test
    void invitationKeepsInternalHostPrivateAndIgnoresVisitorSuppliedStaffIds() throws Exception {
        var admin = staff("admin");
        var guest = new Client("/api/visitor");
        var form = guest.get("/registration/company").path("form");
        long site = form.path("site").path("id").asLong();
        var host = admin.get("/visit-form?site=" + site).path("hosts").get(0);
        String phone = "136" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String in = Instant.now().plusSeconds(86400).toString();
        String out = Instant.now().plusSeconds(90000).toString();
        var invitation = admin.post("/invitations", Map.of("siteId", site, "hostId", host.path("id").asLong(),
            "type", "SUPPLIER", "name", "受邀隐私联调", "reason", "验证访客权限", "phone", phone,
            "plannedIn", in, "plannedOut", out));
        String token = URI.create(invitation.path("url").asText()).getFragment().substring("invite=".length());
        String code = guest.post("/otp", Map.of("phone", phone)).path("demoCode").asText();
        guest.post("/sessions", Map.of("phone", phone, "code", code));
        var claimed = guest.post("/invitations/claim", Map.of("token", token));
        assertThat(claimed.has("hostId")).isFalse();
        assertThat(claimed.has("hostName")).isFalse();
        assertThat(guest.get("/visit-form?site=" + site).has("areas")).isFalse();
        var body = new HashMap<String, Object>(Map.of("inviteToken", token, "hostId", Long.MAX_VALUE, "type", "SUPPLIER",
            "name", "受邀隐私联调", "company", "虚构企业", "reason", "验证访客权限", "plannedIn", in, "plannedOut", out));
        for (var notice : form.path("notices")) {
            body.put("SAFETY".equals(notice.path("kind").asText()) ? "safetyNoticeId" : "privacyNoticeId", notice.path("id").asLong());
        }
        var created = guest.post("/applications", body);
        assertThat(created.has("hostId")).isFalse();
        assertThat(created.has("hostName")).isFalse();
        long id = created.path("id").asLong();
        assertThat(admin.get("/visits/" + id).path("visit").path("hostId").asLong()).isEqualTo(host.path("id").asLong());
        var repeated = guest.post("/applications", body);
        assertThat(repeated.path("id").asLong()).isEqualTo(id);
        assertThat(repeated.has("hostId")).isFalse();
        assertThat(guest.get("/applications").get(0).has("departmentName")).isFalse();
        guest.raw("/applications/" + id + "/cancel", "POST", null, null, 200);
    }

    @Test
    void registrationCodeOpensBookingAndCanBePaused() throws Exception {
        var visitor = new Client("/api/visitor");
        var admin = staff("admin");
        var guard = staff("guard");
        long site = visitor.get("/registration/company").path("form").path("site").path("id").asLong();
        var created = admin.post("/admin/registration-links", Map.of("siteId", site, "displayName", "预约二维码联调"));
        String slug = created.path("slug").asText();
        String adminPath = "/admin/registration-links/" + slug;
        String publicPath = "/registration/" + slug;
        var target = URI.create(qr(created));
        assertThat(target.getPath()).isEqualTo("/visitor");
        assertThat(target.getQuery()).isEqualTo("register=" + slug);
        assertThat(visitor.get(publicPath).path("onsite").asBoolean()).isFalse();
        assertThat(admin.get("/admin/registration-links?site=" + site).path("links").toString())
            .contains(slug, "预约二维码联调");
        guard.get("/admin/registration-links?site=" + site, 403);
        guard.raw(adminPath, "PATCH", Map.of("enabled", false), null, 403);
        admin.raw(adminPath, "PATCH", Map.of("enabled", false), null, 200);
        visitor.get(publicPath, 404);
        assertThat(admin.get(adminPath).path("url").asText()).isEqualTo(created.path("url").asText());
        admin.raw(adminPath, "PATCH", Map.of("enabled", true), null, 200);
        assertThat(visitor.get(publicPath).path("slug").asText()).isEqualTo(slug);
        admin.raw(adminPath, "PATCH", Map.of("enabled", false), null, 200);
    }

    @Test
    void onsiteDriverRequiresBothApprovalsAndScansInAndOut() throws Exception {
        var visitor = new Client("/api/visitor");
        var guard = staff("guard");
        var approver = staff("approver");
        var safety = staff("safety");
        var admin = staff("admin");
        var zone = ZoneId.of("Asia/Shanghai");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String phone = "138" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String registration = "/registration/east-gate";
        assertThat(visitor.get("/applications", 401).path("code").asText()).isEqualTo("AUTH_REQUIRED");
        JsonNode form = visitor.get(registration).path("form");
        long site = form.path("site").path("id").asLong();
        var workspace = guard.get("/workspace").path("gates");
        long entry = workspace.get(0).path("id").asLong();
        long exit = workspace.get(1).path("id").asLong();
        var receptionOptions = guard.get("/visit-form?site=" + site);
        long department = receptionOptions.path("departments").get(0).path("id").asLong();
        var request = new HashMap<String, Object>();
        request.put("slug", "east-gate");
        request.put("type", "DELIVERY");
        request.put("name", "虚构司机");
        request.put("company", "联调用虚构运输公司");
        request.put("reason", "测试送货入厂");
        String serial = String.valueOf(ThreadLocalRandom.current().nextInt(10_000, 99_999));
        request.put("plate", "粤B" + serial);
        request.put("referenceNo", "DEL-" + serial);
        request.put("visitDate", now.atZone(zone).toLocalDate().toString());
        request.put("phone", phone);
        for (JsonNode notice : form.path("notices")) {
            request.put("SAFETY".equals(notice.path("kind").asText()) ? "safetyNoticeId" : "privacyNoticeId",
                notice.path("id").asLong());
        }
        long visit = visitor.post("/applications", request).path("id").asLong();
        assertThat(visit).isPositive();
        assertThat(visitor.get("/applications/" + visit).path("source").asText()).isEqualTo("ONSITE");
        String document = "TEST" + serial;
        var entryPayload = new HashMap<String, Object>(Map.of("gateId", entry, "visitId", visit,
            "identityChecked", true, "documentType", "PASSPORT", "documentNumber", document, "plate", request.get("plate")));
        assertThat(guard.gate("/gate/entries", entryPayload, UUID.randomUUID().toString()).path("code").asText())
            .isEqualTo("NOT_APPROVED");
        assertThat(guard.get("/dashboard?site=" + site + "&gate=" + entry).has("present")).isTrue();
        assertThat(admin.get("/approval-rules/unassigned?site=" + site).toString()).contains("\"id\":" + visit);
        for (var item : approver.get("/approvals")) {
            assertThat(item.path("id").asLong()).isNotEqualTo(visit);
        }
        var reception = guard.post("/visits/" + visit + "/reception", Map.of("departmentId", department));
        assertThat(reception.path("visit").path("departmentId").asLong()).isEqualTo(department);
        assertThat(reception.path("visit").has("hostId")).isFalse();
        JsonNode publicDetail = visitor.get("/applications/" + visit);
        assertThat(publicDetail.has("departmentId")).isFalse();
        assertThat(publicDetail.has("departmentName")).isFalse();
        JsonNode first = step(approver.get("/approvals"), visit);
        assertThat(first.path("role").asText()).isEqualTo("HOST_SUPERVISOR");
        approver.post("/approvals/" + first.path("stepId").asLong() + "/decision", Map.of("approve", true));
        JsonNode second = step(approver.get("/approvals"), visit);
        assertThat(second.path("role").asText()).isEqualTo("WAREHOUSE_OR_PROCUREMENT");
        approver.post("/approvals/" + second.path("stepId").asLong() + "/decision", Map.of("approve", true));
        String entryCode = qr(visitor.post("/applications/" + visit + "/passes?direction=ENTRY", null));
        guard.post("/gate/verifications", Map.of("gateId", entry, "direction", "EXIT", "token", entryCode), 409);
        assertThat(guard.post("/gate/verifications", Map.of("gateId", entry, "direction", "ENTRY", "token", entryCode))
            .path("plate").asText()).isEqualTo(request.get("plate"));
        entryPayload.put("token", entryCode);
        String idempotency = UUID.randomUUID().toString();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstScan = pool.submit(() -> guard.gate("/gate/entries", entryPayload, idempotency));
            var retry = pool.submit(() -> guard.gate("/gate/entries", entryPayload, idempotency));
            JsonNode arrived = firstScan.get();
            assertThat(arrived.path("ok").asBoolean()).isTrue();
            assertThat(retry.get()).isEqualTo(arrived);
        }
        assertThat(guard.gate("/gate/entries", entryPayload, UUID.randomUUID().toString()).path("code").asText())
            .isEqualTo("PASS_EXPIRED");
        safety.post("/blacklist", Map.of("siteId", site, "documentType", "PASSPORT", "documentNumber", document,
            "startsAt", now.toString(), "reason", "联调验证"));
        assertThat(safety.get("/alerts?site=" + site).toString()).contains("BLACKLIST_PRESENT");
        String exitCode = qr(visitor.post("/applications/" + visit + "/passes?direction=EXIT", null));
        var left = guard.gate("/gate/exits", Map.of("gateId", exit, "token", exitCode, "identityChecked", true,
            "plate", request.get("plate")), UUID.randomUUID().toString());
        assertThat(left.path("ok").asBoolean()).isTrue();
        JsonNode departed = visitor.get("/applications/" + visit);
        assertThat(departed.path("state").asText()).isEqualTo("DEPARTED");
        assertThat(Instant.parse(departed.path("departedAt").asText()))
            .isBetween(now, Instant.now());
        assertThat(guard.get("/visits/" + visit).path("events").toString()).contains("QR", "EXIT");
        assertThat(guard.get("/visits/" + visit).path("visit").path("phone").asText()).isEqualTo(phone);
        assertThat(admin.get("/statistics?site=" + site).path("vehicles").toString()).contains(serial);
        assertThat(admin.get("/statistics?site=" + site + "&type=LOGISTICS").path("vehicles").toString()).doesNotContain(serial);
        assertThat(approver.get("/approvals?history=true").toString()).contains("WAREHOUSE_OR_PROCUREMENT");
        assertThat(admin.get("/approval-rules/unassigned?site=" + site).isArray()).isTrue();
        assertThat(guard.get("/exports?site=" + site, 403).path("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(admin.raw("/exports?site=" + site, "GET", null, null, 200)).contains("接访部门", "虚***");
    }

    private static Client staff(String username) throws Exception {
        var client = new Client("/api/staff");
        client.post("/sessions", Map.of("username", username, "password", PASSWORD));
        return client;
    }

    private JsonNode step(JsonNode queue, long visit) {
        for (var row : queue) {
            if (row.path("id").asLong() == visit) {
                return row;
            }
        }
        throw new AssertionError("No approval task for test visit");
    }

    private String qr(JsonNode answer) throws Exception {
        byte[] png = Base64.getDecoder().decode(answer.path("qr").asText().split(",", 2)[1]);
        var source = new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png)));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    private record Client(String prefix, HttpClient http) {
        Client(String prefix) {
            this(prefix, HttpClient.newBuilder().cookieHandler(new CookieManager())
                .version(HttpClient.Version.HTTP_1_1).build());
        }

        JsonNode get(String path) throws Exception {
            return get(path, 200);
        }

        JsonNode get(String path, int status) throws Exception {
            return JSON.readTree(raw(path, "GET", null, null, status));
        }

        JsonNode post(String path, Object input) throws Exception {
            return post(path, input, 200);
        }

        JsonNode post(String path, Object input, int status) throws Exception {
            return JSON.readTree(raw(path, "POST", input, null, status));
        }

        JsonNode gate(String path, Object input, String operation) throws Exception {
            return JSON.readTree(raw(path, "POST", input, operation, 200));
        }

        String raw(String path, String method, Object input, String operation, int status) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(BASE + prefix + path));
            if (!"GET".equals(method)) {
                var csrf = http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/csrf")).build(), HttpResponse.BodyHandlers.ofString());
                request.header("X-CSRF-TOKEN", JSON.readTree(csrf.body()).path("token").asText());
            }
            if (operation != null) {
                request.header("Idempotency-Key", operation);
            }
            if (input != null) {
                request.header("Content-Type", "application/json");
            }
            var response = http.send(request.method(method, input == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
            return response.body();
        }
    }
}
