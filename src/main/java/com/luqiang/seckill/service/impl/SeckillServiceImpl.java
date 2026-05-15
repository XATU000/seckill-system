package com.luqiang.seckill.service.impl;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.common.CacheConstants;
import com.luqiang.seckill.common.LocalStockCache;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.entity.OrderInfo;
import com.luqiang.seckill.repository.GoodsRepository;
import com.luqiang.seckill.repository.OrderInfoRepository;
import com.luqiang.seckill.service.OrderQueueService;
import com.luqiang.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    /** 单次从 Redis 批发的库存数量 */
    private static final int BATCH_SIZE = 20;

    private final StringRedisTemplate redisTemplate;
    private final OrderQueueService orderQueueService;
    private final OrderInfoRepository orderInfoRepository;
    private final GoodsRepository goodsRepository;
    private final LocalStockCache localStockCache;

    /** 已确认预热过的商品，避免重复 hasKey 检查 */
    private final Set<Long> warmedUpGoods = ConcurrentHashMap.newKeySet();

    public SeckillServiceImpl(StringRedisTemplate redisTemplate,
                              OrderQueueService orderQueueService,
                              OrderInfoRepository orderInfoRepository,
                              GoodsRepository goodsRepository,
                              LocalStockCache localStockCache) {
        this.redisTemplate = redisTemplate;
        this.orderQueueService = orderQueueService;
        this.orderInfoRepository = orderInfoRepository;
        this.goodsRepository = goodsRepository;
        this.localStockCache = localStockCache;
    }

    @Override
    public ApiResponse<Void> executeSeckill(Long goodsId, String userId) {
        // 延迟预热：首次请求时确保 Redis 库存 key 存在
        if (!warmedUpGoods.contains(goodsId)) {
            if (Boolean.FALSE.equals(redisTemplate.hasKey(CacheConstants.stockKey(goodsId, 0)))) {
                if (!warmupStock(goodsId)) {
                    return ApiResponse.fail(-1, "商品不存在");
                }
            }
            warmedUpGoods.add(goodsId);
        }

        int seg = CacheConstants.segmentFor(userId);

        for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
            int currentSeg = (seg + i) % CacheConstants.STOCK_SEGMENTS;
            String stockKey = CacheConstants.stockKey(goodsId, currentSeg);
            String orderKey = CacheConstants.orderKey(goodsId, currentSeg);

            // Phase 1: 本地库存扣减（自动从 Redis 批发补货）
            int retries = 0;
            int got;
            do {
                got = localStockCache.acquire(stockKey, BATCH_SIZE, redisTemplate);
            } while (got == -1 && ++retries < 3);

            if (got <= 0) {
                continue;
            }

            // Phase 2: Redis 全局去重（跨实例必须走 Redis）
            Long added = redisTemplate.opsForSet().add(orderKey, userId);
            redisTemplate.expire(orderKey, 7200, TimeUnit.SECONDS);

            if (added != null && added == 0) {
                localStockCache.rollback(stockKey);
                return ApiResponse.fail(2, "你已经抢过了");
            }

            // Phase 3: 异步入队
            if (!orderQueueService.enqueue(goodsId, userId)) {
                localStockCache.rollback(stockKey);
                redisTemplate.opsForSet().remove(orderKey, userId);
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
            String stockKey = CacheConstants.stockKey(goodsId, i);
            String v = redisTemplate.opsForValue().get(stockKey);
            if (v != null) {
                anyExists = true;
                total += Integer.parseInt(v);
            }
            // 加上本地缓存的库存，避免低估
            total += localStockCache.localRemaining(stockKey);
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

}
