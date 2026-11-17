package cn.ks.vms;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class VisitorBookingAccess {
    record Owner(String verifiedPhone, String browserKey) {
        boolean verified() {
            return verifiedPhone != null;
        }

        Owner require() {
            if (!verified() && browserKey == null) {
                throw new Problem(401, "AUTH_REQUIRED", "请使用提交预约的浏览器查看记录");
            }
            return this;
        }
    }

    private final Crypto crypto;
    private final boolean secure;
    private final String cookieName;

    VisitorBookingAccess(Crypto crypto, @Value("${server.servlet.session.cookie.secure}") boolean secure) {
        this.crypto = crypto;
        this.secure = secure;
        this.cookieName = secure ? "__Host-vms-booking" : "vms-booking";
    }

    Owner owner(Authentication user, HttpServletRequest request) {
        String phone = user != null && user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VISITOR"))
            ? user.getName() : null;
        String token = cookie(request);
        return new Owner(phone, token == null ? null : crypto.digest("browser-booking", token));
    }

    Owner issue(HttpServletRequest request, HttpServletResponse response) {
        String token = cookie(request);
        if (token == null) {
            token = crypto.token();
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, token).httpOnly(true).secure(secure)
            .sameSite("Strict").path("/").maxAge(Duration.ofDays(365)).build().toString());
        return new Owner(null, crypto.digest("browser-booking", token));
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? null : Arrays.stream(cookies).filter(cookie -> cookieName.equals(cookie.getName()))
            .map(Cookie::getValue).filter(value -> value.matches("[A-Za-z0-9_-]{32}")).findFirst().orElse(null);
    }
}
