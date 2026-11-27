package com.nineouting.backend.admin.auth;

import com.nineouting.backend.admin.error.AdminException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 1. 읽기가 아닌 요청은 X-Requested-With 머리글이 있어야 한다. 다른 사이트는 CORS 허락 없이 이 머리글을 못 붙여 CSRF를 막는다
 * 2. 로그인 말고는 로그인 쿠키가 있어야 한다
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String COOKIE = "nineouting_admin";
    public static final String REQUEST_HEADER = "X-Requested-With";
    public static final String REQUEST_HEADER_VALUE = "nineouting-admin";
    public static final String SESSION_ATTRIBUTE = "adminSession";

    private final SessionTokens tokens;

    public AuthInterceptor(SessionTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (!HttpMethod.GET.matches(method) && !REQUEST_HEADER_VALUE.equals(request.getHeader(REQUEST_HEADER))) {
            throw new AdminException(HttpStatus.FORBIDDEN, "MISSING_REQUEST_HEADER", "관리자 화면에서 보낸 요청이 아닙니다");
        }
        if (isLogin(request)) {
            return true;
        }
        SessionTokens.Session session = tokens.verify(cookie(request).orElse(null))
                .orElseThrow(() -> new AdminException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다"));
        request.setAttribute(SESSION_ATTRIBUTE, session);
        return true;
    }

    private static boolean isLogin(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && request.getRequestURI().equals("/api/admin/session");
    }

    static Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst();
    }
}
