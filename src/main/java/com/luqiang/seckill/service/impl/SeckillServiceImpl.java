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

import java.util.Arrays;
import java.util.Optional;

@Service
public class SeckillServiceImpl implements SeckillService {

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
                        "redis.call('EXPIRE', KEYS[2], 7200);" +
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

        // 先试命中的分段，失败则遍历剩余分段
        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            int currentSeg = (seg + i) % CacheConstants.STOCK_SEGMENTS;
            String stockKey = CacheConstants.stockKey(goodsId, currentSeg);
            String orderKey = CacheConstants.orderKey(goodsId, currentSeg);

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
                    if (!orderQueueService.enqueue(goodsId, userId)) {
                        redisTemplate.execute(
                                COMPENSATE_SCRIPT,
                                Arrays.asList(stockKey, orderKey),
                                userId
                        );
                        return ApiResponse.fail(-4, "下单失败,请重试");
                    }
                    return ApiResponse.success("秒杀成功", null);
                case 0:
                    // 当前段库存不足，尝试下一段
                    continue;
                case 2:
                    return ApiResponse.fail(2, "你已经抢过了");
                case -1:
                    // 库存 key 缺失，尝试预热当前段
                    if (!warmupSegment(goodsId, currentSeg)) {
                        return ApiResponse.fail(-1, "商品不存在");
                    }
                    result = redisTemplate.execute(
                            SECKILL_SCRIPT,
                            Arrays.asList(stockKey, orderKey),
                            userId
                    );
                    if (result == null) {
                        return ApiResponse.fail(-2, "系统异常");
                    }
                    switch (result.intValue()) {
                        case 1:
                            if (!orderQueueService.enqueue(goodsId, userId)) {
                                redisTemplate.execute(COMPENSATE_SCRIPT,
                                        Arrays.asList(stockKey, orderKey), userId);
                                return ApiResponse.fail(-4, "下单失败,请重试");
                            }
                            return ApiResponse.success("秒杀成功", null);
                        case 0:
                            continue;
                        case 2:
                            return ApiResponse.fail(2, "你已经抢过了");
                        default:
                            return ApiResponse.fail(-3, "未知错误");
                    }
                default:
                    return ApiResponse.fail(-3, "未知错误");
            }
        }

        // 所有分段都无库存
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

    /**
     * 从 DB 加载商品库存，均分到所有分段。余数给第 0 段。
     */
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

    /**
     * 预热单个分段（从 DB 重新加载后按比例分配）。
     */
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
