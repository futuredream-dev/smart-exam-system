package com.smartexam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ApiResponse;
import com.smartexam.common.ErrorCode;
import com.smartexam.entity.Users;
import com.smartexam.service.UsersService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private final UsersService users;
    private final ObjectMapper json;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Users user = users.findByUsername(authentication.getName());
        if (user == null || !Boolean.TRUE.equals(user.getMustChangePassword()) || allowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), ApiResponse.failure("请先修改初始密码", ErrorCode.FORBIDDEN.name()));
    }

    private static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ("/api/auth/me".equals(path) && "GET".equals(request.getMethod()))
                || ("/api/auth/password".equals(path) && "PUT".equals(request.getMethod()));
    }
}
