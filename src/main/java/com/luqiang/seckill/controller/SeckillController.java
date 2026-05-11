package com.luqiang.seckill.controller;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.repository.OrderInfoRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@Validated
@RequestMapping("/seckill")
public class SeckillController {

    private final StringRedisTemplate redisTemplate;
    private final OrderInfoRepository orderInfoRepository;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setScriptText(
                "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end;" +
                        "local stock = redis.call('GET', KEYS[1]);" +
                        "if (not stock) then return -1 end;" +
                        "if (tonumber(stock) <= 0) then return 0 end;" +
                        "redis.call('DECR', KEYS[1]);" +
                        "redis.call('SADD', KEYS[2], ARGV[1]);" +
                        "return 1;"
        );
    }

    public SeckillController(StringRedisTemplate redisTemplate,
                             OrderInfoRepository orderInfoRepository) {
        this.redisTemplate = redisTemplate;
        this.orderInfoRepository = orderInfoRepository;
    }

    @GetMapping("/do/{id}")
    public ApiResponse<Void> doSeckill(@PathVariable Long id,
                                       @RequestParam @NotBlank(message = "userId不能为空") String userId) {
        return executeSeckill(id, userId);
    }

    @PostMapping("/do/{id}")
    public ApiResponse<Void> doSeckillPost(@PathVariable Long id,
                                           @RequestParam @NotBlank(message = "userId不能为空") String userId) {
        return executeSeckill(id, userId);
    }

    private ApiResponse<Void> executeSeckill(Long id, String userId) {
        String stockKey = "stock:" + id;
        String orderKey = "order:" + id;

        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                userId
        );

        if (result == null) {
            return ApiResponse.fail(-2, "系统异常");
        }

        switch (result.intValue()) {
            case 1:
                persistOrder(id, userId);
                return ApiResponse.success("秒杀成功", null);
            case 0:
                return ApiResponse.fail(0, "库存不足");
            case 2:
                return ApiResponse.fail(2, "你已经抢过了");
            case -1:
                return ApiResponse.fail(-1, "商品不存在");
            default:
                return ApiResponse.fail(-3, "未知错误");
        }
    }

    @Transactional
    void persistOrder(Long goodsId, String userId) {
        orderInfoRepository.save(new OrderInfo(goodsId, userId));
    }
}