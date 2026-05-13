package com.luqiang.seckill.controller;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.common.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestParam String userId) {
        String token = JwtUtil.generate(userId);
        return ApiResponse.success("登录成功", Map.of("token", token, "userId", userId));
    }
}
