package com.luqiang.seckill.controller;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.interceptor.JwtAuthInterceptor;
import com.luqiang.seckill.service.SeckillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping("/do/{id}")
    public ApiResponse<Void> doSeckill(@PathVariable Long id,
                                       HttpServletRequest request,
                                       @RequestParam(required = false) String userId) {
        String uid = resolveUserId(request, userId);
        if (uid == null) {
            return ApiResponse.fail(401, "userId 缺失");
        }
        return seckillService.executeSeckill(id, uid);
    }

    @GetMapping("/stock/{id}")
    public ApiResponse<Integer> getStock(@PathVariable Long id) {
        return seckillService.getStock(id);
    }

    @GetMapping("/result/{id}")
    public ApiResponse<OrderInfo> getResult(@PathVariable Long id,
                                            HttpServletRequest request,
                                            @RequestParam(required = false) String userId) {
        String uid = resolveUserId(request, userId);
        if (uid == null) {
            return ApiResponse.fail(401, "userId 缺失");
        }
        return seckillService.getResult(id, uid);
    }

    private String resolveUserId(HttpServletRequest request, String queryUserId) {
        // 优先从 JWT 拦截器注入的属性中获取
        String jwtUserId = (String) request.getAttribute(JwtAuthInterceptor.USER_ID_ATTR);
        if (jwtUserId != null) {
            return jwtUserId;
        }
        // 兼容 URL 参数方式（过渡期）
        if (queryUserId != null && !queryUserId.isBlank()) {
            return queryUserId;
        }
        return null;
    }
}