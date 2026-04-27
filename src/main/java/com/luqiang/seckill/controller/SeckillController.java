package com.luqiang.seckill.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final StringRedisTemplate redisTemplate;

    public SeckillController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/do/{id}")
    public String doSeckill(@PathVariable Long id,
                            @RequestParam String userId) {

        String stockKey = "stock:" + id;
        String orderKey = "order:" + id;

        String lua =
                "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end;" +
                        "local stock = redis.call('GET', KEYS[1]);" +
                        "if (not stock) then return -1 end;" +
                        "if (tonumber(stock) <= 0) then return 0 end;" +
                        "redis.call('DECR', KEYS[1]);" +
                        "redis.call('SADD', KEYS[2], ARGV[1]);" +
                        "return 1;";

        Long result = redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                java.util.Arrays.asList(stockKey, orderKey),
                userId
        );

        if (result == null) return "系统异常";

        switch (result.intValue()) {
            case 1:
                return "✅ 秒杀成功";
            case 0:
                return "❌ 库存不足";
            case 2:
                return "❌ 你已经抢过了";
            case -1:
                return "❌ 商品不存在";
            default:
                return "❌ 未知错误";
        }
    }
}