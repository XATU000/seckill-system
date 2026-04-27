package com.luqiang.seckill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class SeckillService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    public String seckill(Long productId, Long userId) {

        // 1. 防重复下单
        String userKey = "order:user:" + userId + ":" + productId;
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(userKey, "1", 5, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(success)) {
            return "Duplicate request";
        }

        // 2. 扣库存（核心）
        Long stock = redisTemplate.opsForValue().decrement("stock:" + productId);

        if (stock == null || stock < 0) {
            return "Sold Out";
        }

        return "Success";
    }
}