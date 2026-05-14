package com.luqiang.seckill.service.impl;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.common.CacheConstants;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.repository.GoodsRepository;
import com.luqiang.seckill.repository.OrderInfoRepository;
import com.luqiang.seckill.service.OrderQueueService;
import com.luqiang.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillServiceImpl implements SeckillService {

    /**
     * 补偿脚本：入队失败时回滚库存 + 清除已购标记。
     * CAS 模式下不再有 Lua 秒杀脚本，仅保留补偿用。
     */
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;

    static {
        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setResultType(Long.class);
        COMPENSATE_SCRIPT.setScriptText(
                "redis.call('INCR', KEYS[1]);" +
                        "redis.call('SREM', KEYS[2], ARGV[1]);" +
                        "return 1;"
        );
    }

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final OrderQueueService orderQueueService;
    private final OrderInfoRepository orderInfoRepository;
    private final GoodsRepository goodsRepository;

    public SeckillServiceImpl(StringRedisTemplate redisTemplate,
                              OrderQueueService orderQueueService,
                              OrderInfoRepository orderInfoRepository,
                              GoodsRepository goodsRepository) {
        this.redisTemplate = redisTemplate;
        this.orderQueueService = orderQueueService;
        this.orderInfoRepository = orderInfoRepository;
        this.goodsRepository = goodsRepository;
    }

    @Override
    public ApiResponse<Void> executeSeckill(Long goodsId, String userId) {
        int seg = CacheConstants.segmentFor(userId);

        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            int currentSeg = (seg + i) % CacheConstants.STOCK_SEGMENTS;
            String stockKey = CacheConstants.stockKey(goodsId, currentSeg);
            String orderKey = CacheConstants.orderKey(goodsId, currentSeg);

            // Phase 1: CAS 预减库存 (DECR 自身原子，无需 Lua)
            Long postStock = redisTemplate.opsForValue().decrement(stockKey);
            if (postStock == null) {
                // 库存 key 缺失，尝试预热当前段
                if (!warmupSegment(goodsId, currentSeg)) {
                    return ApiResponse.fail(-1, "商品不存在");
                }
                postStock = redisTemplate.opsForValue().decrement(stockKey);
                if (postStock == null) {
                    return ApiResponse.fail(-2, "系统异常");
                }
            }

            if (postStock < 0) {
                // 库存不足，回滚 DECR
                redisTemplate.opsForValue().increment(stockKey);
                continue;
            }

            // Phase 2: CAS 去重 (SADD 返回 0 表示已存在)
            Long added = redisTemplate.opsForSet().add(orderKey, userId);
            redisTemplate.expire(orderKey, 7200, TimeUnit.SECONDS);

            if (added != null && added == 0) {
                // 重复购买，回滚库存
                redisTemplate.opsForValue().increment(stockKey);
                return ApiResponse.fail(2, "你已经抢过了");
            }

            // 两阶段都成功，入队
            if (!orderQueueService.enqueue(goodsId, userId)) {
                redisTemplate.execute(
                        COMPENSATE_SCRIPT,
                        java.util.Arrays.asList(stockKey, orderKey),
                        userId
                );
                return ApiResponse.fail(-4, "下单失败,请重试");
            }
            return ApiResponse.success("秒杀成功", null);
        }

        return ApiResponse.fail(0, "库存不足");
    }

    @Override
    public ApiResponse<Integer> getStock(Long goodsId) {
        int total = 0;
        boolean anyExists = false;
        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            String v = redisTemplate.opsForValue().get(CacheConstants.stockKey(goodsId, i));
            if (v != null) {
                anyExists = true;
                total += Integer.parseInt(v);
            }
        }
        if (!anyExists) {
            if (!warmupStock(goodsId)) {
                return ApiResponse.fail(-1, "商品不存在");
            }
            total = goodsRepository.findById(goodsId)
                    .map(Goods::getStock)
                    .orElse(0);
        }
        return ApiResponse.success("查询成功", total);
    }

    @Override
    public ApiResponse<OrderInfo> getResult(Long goodsId, String userId) {
        Optional<OrderInfo> order = orderInfoRepository.findByGoodsIdAndUserId(goodsId, userId);
        if (order.isPresent()) {
            return ApiResponse.success("已抢到", order.get());
        }
        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            Boolean inSet = redisTemplate.opsForSet().isMember(
                    CacheConstants.orderKey(goodsId, i), userId);
            if (Boolean.TRUE.equals(inSet)) {
                return ApiResponse.fail(3, "订单处理中");
            }
        }
        return ApiResponse.fail(4, "未查询到订单");
    }

    private boolean warmupStock(Long goodsId) {
        Optional<Goods> opt = goodsRepository.findById(goodsId);
        if (opt.isEmpty()) {
            return false;
        }
        Goods goods = opt.get();
        int base = goods.getStock() / CacheConstants.STOCK_SEGMENTS;
        int remainder = goods.getStock() % CacheConstants.STOCK_SEGMENTS;
        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            int segStock = base + (i < remainder ? 1 : 0);
            redisTemplate.opsForValue().set(
                    CacheConstants.stockKey(goodsId, i),
                    String.valueOf(segStock));
        }
        log.info("库存预热分段: goodsId={} totalStock={} segments={} base={} remainder={}",
                goodsId, goods.getStock(), CacheConstants.STOCK_SEGMENTS, base, remainder);
        return true;
    }

    private boolean warmupSegment(Long goodsId, int segment) {
        Optional<Goods> opt = goodsRepository.findById(goodsId);
        if (opt.isEmpty()) {
            return false;
        }
        Goods goods = opt.get();
        int base = goods.getStock() / CacheConstants.STOCK_SEGMENTS;
        int remainder = goods.getStock() % CacheConstants.STOCK_SEGMENTS;
        int segStock = base + (segment < remainder ? 1 : 0);
        redisTemplate.opsForValue().set(
                CacheConstants.stockKey(goodsId, segment),
                String.valueOf(segStock));
        log.info("单段预热: goodsId={} segment={} stock={}", goodsId, segment, segStock);
        return true;
    }
}
