package com.luqiang.seckill.controller;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.service.SeckillService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping("/do/{id}")
    public ApiResponse<Void> doSeckill(@PathVariable Long id,
                                       @RequestParam @NotBlank(message = "userId不能为空") String userId) {
        return seckillService.executeSeckill(id, userId);
    }

    @GetMapping("/stock/{id}")
    public ApiResponse<Integer> getStock(@PathVariable Long id) {
        return seckillService.getStock(id);
    }

    @GetMapping("/result/{id}")
    public ApiResponse<OrderInfo> getResult(@PathVariable Long id,
                                            @RequestParam @NotBlank(message = "userId不能为空") String userId) {
        return seckillService.getResult(id, userId);

    }
}