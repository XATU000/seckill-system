package com.luqiang.seckill.service.impl;

import com.luqiang.seckill.common.ApiResponse;
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
        String stockKey = "{s:" + goodsId + "}:stock";
        String orderKey = "{s:" + goodsId + "}:order";

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
                return ApiResponse.fail(0, "库存不足");
            case 2:
                return ApiResponse.fail(2, "你已经抢过了");
            case -1:
                // 库存 key 缺失，尝试从 DB 回源预热，再重试一次
                if (!warmupStock(goodsId)) {
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
                // 重试后递归处理结果（仅处理简单情况，避免再次 -1 递归）
                switch (result.intValue()) {
                    case 1:
                        if (!orderQueueService.enqueue(goodsId, userId)) {
                            redisTemplate.execute(COMPENSATE_SCRIPT,
                                    Arrays.asList(stockKey, orderKey), userId);
                            return ApiResponse.fail(-4, "下单失败,请重试");
                        }
                        return ApiResponse.success("秒杀成功", null);
                    case 0:
                        return ApiResponse.fail(0, "库存不足");
                    case 2:
                        return ApiResponse.fail(2, "你已经抢过了");
                    default:
                        return ApiResponse.fail(-3, "未知错误");
                }
            default:
                return ApiResponse.fail(-3, "未知错误");
        }
    }

    @Override
    public ApiResponse<Integer> getStock(Long goodsId) {
        String key = "{s:" + goodsId + "}:stock";
        String stock = redisTemplate.opsForValue().get(key);
        if (stock == null) {
            if (!warmupStock(goodsId)) {
                return ApiResponse.fail(-1, "商品不存在");
            }
            stock = redisTemplate.opsForValue().get(key);
            if (stock == null) {
                return ApiResponse.fail(-1, "商品不存在");
            }
        }
        return ApiResponse.success("查询成功", Integer.parseInt(stock));
    }

    @Override
    public ApiResponse<OrderInfo> getResult(Long goodsId, String userId) {
        Optional<OrderInfo> order = orderInfoRepository.findByGoodsIdAndUserId(goodsId, userId);
        if (order.isPresent()) {
            return ApiResponse.success("已抢到", order.get());
        }
        // 检查是否在 Redis 已购集合中（已入队但可能尚未落库）
        Boolean inSet = redisTemplate.opsForSet().isMember("{s:" + goodsId + "}:order", userId);
        if (Boolean.TRUE.equals(inSet)) {
            return ApiResponse.fail(3, "订单处理中");
        }
        return ApiResponse.fail(4, "未查询到订单");
    }

    /**
     * 从 DB 加载商品库存到 Redis。成功返回 true，商品不存在返回 false。
     */
    private boolean warmupStock(Long goodsId) {
        Optional<Goods> opt = goodsRepository.findById(goodsId);
        if (opt.isEmpty()) {
            return false;
        }
        Goods goods = opt.get();
        String key = "{s:" + goodsId + "}:stock";
        redisTemplate.opsForValue().set(key, String.valueOf(goods.getStock()));
        log.info("库存预热: goodsId={} stock={}", goodsId, goods.getStock());
        return true;
    }
}