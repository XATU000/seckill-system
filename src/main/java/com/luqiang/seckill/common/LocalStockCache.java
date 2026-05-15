package com.luqiang.seckill.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地库存缓存：批发一批库存到 JVM 内，大部分请求直接扣本地 AtomicLong，
 * 扣完再找 Redis 批发下一批。大幅减少 Redis 网络往返。
 *
 * 使用示例：
 * <pre>
 *   int got = cache.acquire(stockKey, 20, redisTemplate);
 *   if (got > 0)  → 秒杀成功
 *   if (got == 0) → 库存不足
 *   if (got == -1) → 重试（已自动补货，呼叫方自旋一次即可）
 * </pre>
 */
@Component
public class LocalStockCache {

    private static final Logger log = LoggerFactory.getLogger(LocalStockCache.class);

    /** 每段本地库存 */
    private final ConcurrentHashMap<String, AtomicLong> localStock = new ConcurrentHashMap<>();
    /** 批发锁 key 前缀，防止并发批发同一段 */
    private static final String RESTOCK_LOCK_PREFIX = "lock:restock:";

    private final StringRedisTemplate redisTemplate;

    public LocalStockCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 从本地库存扣减 1。如果本地不足，尝试从 Redis 批发一批。
     *
     * @param stockKey      Redis 库存 key (含分段)
     * @param batchSize     Redis 单次批发数量
     * @param redisTemplate Redis template
     * @return 1 成功, 0 库存不足, -1 需要重试（呼叫方自旋一次即可）
     */
    public int acquire(String stockKey, int batchSize, StringRedisTemplate redisTemplate) {
        // 1. 从本地缓存直接扣
        AtomicLong local = localStock.computeIfAbsent(stockKey, k -> new AtomicLong(0));
        while (true) {
            long current = local.get();
            if (current > 0) {
                if (local.compareAndSet(current, current - 1)) {
                    return 1; // 秒杀成功，零网络开销
                }
                // CAS 失败，竞争重试
                continue;
            }
            break;
        }

        // 2. 本地库存耗尽，尝试从 Redis 批发
        String lockKey = RESTOCK_LOCK_PREFIX + stockKey;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(locked)) {
            try {
                // 再次检查（可能其他线程已补货）
                if (local.get() > 0) {
                    return -1; // 让呼叫方重试
                }

                // 从 Redis 批量 DECR
                String currentStockStr = redisTemplate.opsForValue().get(stockKey);
                if (currentStockStr == null) {
                    return 0;
                }
                int currentStock = Integer.parseInt(currentStockStr);
                if (currentStock <= 0) {
                    return 0;
                }

                int toFetch = Math.min(batchSize, currentStock);
                Long remain = redisTemplate.opsForValue().decrement(stockKey, toFetch);
                if (remain == null || remain < 0) {
                    // 并发被抢光，回滚
                    if (remain != null) {
                        redisTemplate.opsForValue().increment(stockKey, toFetch);
                    }
                    return 0;
                }

                local.addAndGet(toFetch);
                log.debug("批发库存: key={} fetched={} remain={}", stockKey, toFetch, remain);
                return -1; // 批发成功，让呼叫方重试
            } finally {
                redisTemplate.delete(lockKey);
            }
        }

        // 3. 没拿到锁，短暂等待后重试本地
        if (local.get() > 0) {
            return -1;
        }
        // 本地确实没了，且锁被别人持有（正在补货），让呼叫方判断
        return -1;
    }

    /**
     * 回滚本地库存（SADD 去重失败时用）。
     */
    public void rollback(String stockKey) {
        AtomicLong local = localStock.get(stockKey);
        if (local != null) {
            local.incrementAndGet();
        }
    }

    /**
     * 预热时预填充一批库存到本地，避免冷启动时所有请求争抢批发锁。
     */
    public void preFill(String stockKey, int batchSize) {
        AtomicLong local = localStock.computeIfAbsent(stockKey, k -> new AtomicLong(0));
        if (local.get() > 0) {
            return;
        }
        String lockKey = RESTOCK_LOCK_PREFIX + stockKey;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked)) {
            try {
                if (local.get() > 0) {
                    return;
                }
                String currentStockStr = redisTemplate.opsForValue().get(stockKey);
                if (currentStockStr == null) {
                    return;
                }
                int currentStock = Integer.parseInt(currentStockStr);
                if (currentStock <= 0) {
                    return;
                }
                int toFetch = Math.min(batchSize, currentStock);
                Long remain = redisTemplate.opsForValue().decrement(stockKey, toFetch);
                if (remain != null && remain >= 0) {
                    local.addAndGet(toFetch);
                    log.debug("预填充本地库存: key={} amount={}", stockKey, toFetch);
                }
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * 归还本地剩余库存到 Redis（定时任务调用）。
     */
    public void returnStock(String stockKey, StringRedisTemplate redisTemplate) {
        AtomicLong local = localStock.get(stockKey);
        if (local == null) {
            return;
        }
        long remaining = local.getAndSet(0);
        if (remaining > 0) {
            redisTemplate.opsForValue().increment(stockKey, remaining);
            log.debug("归还库存: key={} amount={}", stockKey, remaining);
        }
    }

    /**
     * 查询本地缓存的库存数（调试用）。
     */
    public long localRemaining(String stockKey) {
        AtomicLong local = localStock.get(stockKey);
        return local != null ? local.get() : 0;
    }

    /**
     * 定时归还本地剩余库存到 Redis，避免库存滞留在单机。
     */
    @Scheduled(fixedDelay = 5000)
    public void returnUnusedStock() {
        for (String stockKey : localStock.keySet()) {
            AtomicLong local = localStock.get(stockKey);
            if (local == null) {
                continue;
            }
            long remaining = local.get();
            // 只归还超过 batchSize 的部分，保留一批给后续请求
            if (remaining > 100) {
                long toReturn = remaining - 100;
                if (local.compareAndSet(remaining, remaining - toReturn)) {
                    redisTemplate.opsForValue().increment(stockKey, toReturn);
                    log.debug("归还库存: key={} amount={}", stockKey, toReturn);
                }
            }
        }
    }

    /**
     * 清空本地缓存（应用关闭时调用）。
     */
    public void clear() {
        localStock.clear();
    }

    int localStockCount() {
        return localStock.size();
    }
}
