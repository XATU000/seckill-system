package com.luqiang.seckill.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.common.TokenBucketRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

public class RateLimiterInterceptor implements HandlerInterceptor {

    private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(5000);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (rateLimiter.tryAcquire()) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        writeBody(response, ApiResponse.fail(429, "请求过于频繁，请稍后再试"));
        return false;
    }

    private void writeBody(HttpServletResponse response, Object body) throws IOException {
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
