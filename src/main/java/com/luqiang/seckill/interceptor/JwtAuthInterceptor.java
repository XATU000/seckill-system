package com.luqiang.seckill.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.common.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    public static final String USER_ID_ATTR = "userId";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResponse.fail(401, "未登录，请先获取 Token"))
            );
            return false;
        }

        String token = header.substring(7);
        String userId = JwtUtil.parseUserId(token);
        if (userId == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResponse.fail(401, "Token 无效或已过期"))
            );
            return false;
        }

        request.setAttribute(USER_ID_ATTR, userId);
        return true;
    }
}
