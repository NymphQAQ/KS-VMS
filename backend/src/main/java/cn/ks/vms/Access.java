package cn.ks.vms;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Configuration
class SecurityConfig {
    @Bean
    HttpSessionSecurityContextRepository securityRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository sessions,
                                 HttpSessionCsrfTokenRepository csrf,
                                 @Value("${springdoc.api-docs.enabled}") boolean docs) throws Exception {
        return http.securityContext(config -> config.securityContextRepository(sessions))
            .csrf(config -> config.csrfTokenRepository(csrf))
            .headers(config -> config.referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(config -> {
                config.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/csrf", "/api/health", "/api/visitor/registration/*",
                        "/api/visitor/me", "/api/visitor/visit-form",
                        "/api/visitor/applications", "/api/visitor/applications/*").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/staff/sessions", "/api/visitor/sessions", "/api/visitor/otp",
                        "/api/visitor/applications", "/api/visitor/applications/*/revisions",
                        "/api/visitor/applications/*/cancel", "/api/visitor/applications/*/passes").permitAll()
                    .requestMatchers("/api/staff/**").hasAuthority("STAFF")
                    .requestMatchers("/api/visitor/hosts").denyAll()
                    .requestMatchers("/api/visitor/**").hasAuthority("VISITOR");
                if (docs) {
                    config.requestMatchers("/v3/api-docs/**", "/doc.html", "/webjars/**",
                        "/swagger-ui/**", "/swagger-ui.html").hasAuthority("STAFF");
                }
                config.anyRequest().denyAll();
            })
            .exceptionHandling(config -> config.authenticationEntryPoint((request, response, error) ->
                authError(response, 401, "请先登录")).accessDeniedHandler((request, response, error) ->
                authError(response, 403, "权限不足或登录已失效")))
            .build();
    }

    private static void authError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"AUTH_REQUIRED\",\"message\":\"" + message + "\"}");
    }
}

@Service
class Access {
    record StaffLogin(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password,
                      @Pattern(regexp = "[0-9]{6}") String code) {}
    record SignIn(long version, boolean mfa) {}
    record Phone(@NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone) {}
    record OtpLogin(@NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
                    @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
    private record Credential(long id, String passwordHash, String mfaSecret, long authVersion) {}
    private record Challenge(String codeKey, Instant sentAt, Instant expiresAt, int attempts, boolean consumed) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;
    private final PasswordEncoder passwords;
    private final Catalog catalog;
    private final boolean demo;
    private final String smsUrl;
    private final String smsToken;
    private final String dummyPassword;

    Access(Db db, Crypto crypto, Clock clock, PasswordEncoder passwords, Catalog catalog, @Value("${vms.demo}") boolean demo,
           @Value("${vms.sms-url}") String smsUrl, @Value("${vms.sms-token}") String smsToken) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
        this.passwords = passwords;
        this.catalog = catalog;
        this.demo = demo;
        this.smsUrl = smsUrl;
        this.smsToken = smsToken;
        this.dummyPassword = passwords.encode(crypto.token());
    }

    void limit(String scope, String value, int max, long seconds) {
        var key = crypto.digest(scope, value);
        long window = clock.instant().getEpochSecond() / seconds * seconds;
        db.update("""
            INSERT INTO request_limit(scope,key_hash,window_start) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE hits=hits+1
            """, scope, key, window);
        if (db.count("SELECT hits FROM request_limit WHERE scope=? AND key_hash=? AND window_start=?",
            scope, key, window) > max) {
            throw new Problem(429, "TOO_MANY_REQUESTS", "操作过于频繁，请稍后重试");
        }
    }

    SignIn staffLogin(StaffLogin input, String address) {
        limit("staff-ip", address, 30, 300);
        limit("staff-user", input.username().toLowerCase(java.util.Locale.ROOT), 12, 300);
        var user = db.one(Credential.class, """
            SELECT id,password_hash,mfa_secret,auth_version FROM staff WHERE username=? AND enabled
            """, input.username());
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || !passwords.matches(input.password(), user == null ? dummyPassword : user.passwordHash()) || user == null) {
            throw new Problem(401, "BAD_CREDENTIALS", "账号或密码错误");
        }
        boolean needsMfa = user.mfaSecret() != null || !demo && catalog.grants(user.id()).stream()
            .anyMatch(grant -> Totp.PRIVILEGED_ROLES.contains(grant.role()));
        if (needsMfa) {
            if (user.mfaSecret() == null) {
                throw new Problem(401, "MFA_SETUP", "请联系管理员为该账号配置验证器");
            }
            if (input.code() == null) {
                throw new Problem(401, "MFA_REQUIRED", "请输入验证器中的一次性验证码");
            }
            long counter = Totp.counter(crypto.decryptAuthenticator(user.mfaSecret()), input.code(), clock.instant());
            if (counter < 0 || db.update("""
                UPDATE staff SET mfa_counter=? WHERE id=? AND auth_version=? AND mfa_counter<?
                """, counter, user.id(), user.authVersion(), counter) != 1) {
                throw new Problem(401, "MFA_REQUIRED", "验证码不正确、过期或已经使用");
            }
        }
        return new SignIn(user.authVersion(), needsMfa);
    }

    @Transactional(noRollbackFor = Problem.class)
    Map<String, Object> otp(Phone input, String address) {
        limit("otp-ip", address, 18, 600);
        limit("otp-phone", input.phone(), 5, 1800);
        if (!demo && !smsUrl.startsWith("https://")) {
            throw new Problem(503, "SMS_UNAVAILABLE", "短信服务暂不可用");
        }
        String phone = phoneKey(input.phone());
        var now = clock.instant();
        var code = crypto.code();
        db.update("""
            INSERT INTO otp_challenge(phone_key,code_key,sent_at,expires_at,consumed) VALUES (?,?,?,?,TRUE)
            ON DUPLICATE KEY UPDATE phone_key=phone_key
            """, phone, crypto.digest("otp", phone + code), now.minusSeconds(60), now);
        var previous = challenge(phone);
        if (previous.sentAt().plusSeconds(60).isAfter(now)) {
            throw new Problem(429, "OTP_COOLDOWN", "请在 60 秒后重新发送");
        }
        if (!demo) {
            var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
            factory.setReadTimeout(Duration.ofSeconds(8));
            try {
                RestClient.builder().requestFactory(factory).build().post().uri(smsUrl)
                    .headers(headers -> headers.setBearerAuth(smsToken))
                    .body(Map.of("phone", input.phone(), "code", code, "expiresInSeconds", 300))
                    .retrieve().toBodilessEntity();
            } catch (RestClientException error) {
                throw new Problem(503, "SMS_UNAVAILABLE", "短信暂未送达，请稍后重试");
            }
        }
        db.update("""
            UPDATE otp_challenge SET code_key=?,sent_at=?,expires_at=?,attempts=0,consumed=FALSE WHERE phone_key=?
            """, crypto.digest("otp", phone + code), now, now.plusSeconds(300), phone);
        return demo ? Map.of("expiresInSeconds", 300, "demoCode", code) : Map.of("expiresInSeconds", 300);
    }

    @Transactional(noRollbackFor = Problem.class)
    String verify(OtpLogin input, String address) {
        limit("otp-verify-ip", address, 35, 600);
        String phone = phoneKey(input.phone());
        var candidate = challenge(phone);
        if (candidate == null || !candidate.expiresAt().isAfter(clock.instant()) || candidate.consumed()
            || candidate.attempts() >= 5) {
            throw Problem.bad("验证码已失效，请重新获取");
        }
        db.update("UPDATE otp_challenge SET attempts=attempts+1 WHERE phone_key=?", phone);
        if (!candidate.codeKey().equals(crypto.digest("otp", phone + input.code()))) {
            throw Problem.bad("验证码不正确");
        }
        db.update("UPDATE otp_challenge SET consumed=TRUE WHERE phone_key=?", phone);
        return phone;
    }

    String phoneKey(String phone) {
        return crypto.digest("phone", phone);
    }

    private Challenge challenge(String phone) {
        return db.one(Challenge.class, """
            SELECT code_key,sent_at,expires_at,attempts,consumed FROM otp_challenge WHERE phone_key=? FOR UPDATE
            """, phone);
    }
}

@RestController
@RequestMapping("/api")
class AccessController {
    private final Access access;
    private final Catalog catalog;
    private final HttpSessionSecurityContextRepository sessions;
    private final HttpSessionCsrfTokenRepository csrf;

    AccessController(Access access, Catalog catalog, HttpSessionSecurityContextRepository sessions,
                     HttpSessionCsrfTokenRepository csrf) {
        this.access = access;
        this.catalog = catalog;
        this.sessions = sessions;
        this.csrf = csrf;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "up");
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/staff/sessions")
    Catalog.Actor staff(@Valid @RequestBody Access.StaffLogin input, HttpServletRequest request, HttpServletResponse response) {
        var signin = access.staffLogin(input, request.getRemoteAddr());
        var actor = catalog.actor(input.username());
        start(request, response, actor.username(), "STAFF", signin);
        return actor;
    }

    @GetMapping("/staff/me")
    Catalog.Actor me(Authentication authentication) {
        return catalog.actor(authentication);
    }

    @PostMapping("/visitor/otp")
    Map<String, Object> otp(@Valid @RequestBody Access.Phone input, HttpServletRequest request) {
        return access.otp(input, request.getRemoteAddr());
    }

    @PostMapping("/visitor/sessions")
    Map<String, Boolean> visitor(@Valid @RequestBody Access.OtpLogin input, HttpServletRequest request, HttpServletResponse response) {
        start(request, response, access.verify(input, request.getRemoteAddr()), "VISITOR", null);
        return Map.of("authenticated", true);
    }

    @DeleteMapping({"/staff/sessions/current", "/visitor/sessions/current"})
    void logout(HttpServletRequest request) {
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    private void start(HttpServletRequest request, HttpServletResponse response, String principal, String authority, Access.SignIn signin) {
        request.getSession();
        request.changeSessionId();
        csrf.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(authority)));
        authentication.setDetails(signin);
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
    }
}
