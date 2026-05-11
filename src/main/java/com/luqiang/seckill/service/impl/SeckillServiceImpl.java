package com.luqiang.seckill.service.impl;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.repository.OrderInfoRepository;
import com.luqiang.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

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

        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setResultType(Long.class);
        COMPENSATE_SCRIPT.setScriptText(
                "redis.call('INCR', KEYS[1]);" +
                        "redis.call('SREM', KEYS[2], ARGV[1]);" +
                        "return 1;"
        );
    }

    private final StringRedisTemplate redisTemplate;
    private final OrderInfoRepository orderInfoRepository;

    public SeckillServiceImpl(StringRedisTemplate redisTemplate,
                              OrderInfoRepository orderInfoRepository) {
        this.redisTemplate = redisTemplate;
        this.orderInfoRepository = orderInfoRepository;
    }

    @Override
    public ApiResponse<Void> executeSeckill(Long goodsId, String userId) {
        String stockKey = "stock:" + goodsId;
        String orderKey = "order:" + goodsId;

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
                try {
                    persistOrder(goodsId, userId);
                } catch (Exception e) {
                    log.error("持久化订单失败, goodsId={}, userId={}", goodsId, userId, e);
                    redisTemplate.execute(
                            COMPENSATE_SCRIPT,
                            Arrays.asList(stockKey, orderKey),
                            userId
                    );
                    return ApiResponse.fail(-4, "下单失败,请重试");
                }
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