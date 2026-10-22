package com.chat.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TokenAuthInterceptor implements HandlerInterceptor {
    public static final String USERNAME_ATTRIBUTE = "authenticatedUsername";
    private final TokenService tokens;

    public TokenAuthInterceptor(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        String username = tokens.findUsername(token);
        if (username == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "A valid bearer token is required.");
            return false;
        }
        request.setAttribute(USERNAME_ATTRIBUTE, username);
        return true;
    }
}
