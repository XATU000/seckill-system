package com.luqiang.seckill.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis Bitmap 的布隆过滤器，防止不存在的 goodsId 穿透到 DB。
 */
@Component
public class BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(BloomFilter.class);

    private static final String BITMAP_KEY = "bloom:goods";
    private static final int M = 1 << 20;       // 1,048,576 bits = 128 KB
    private static final int K = 7;              // 哈希函数个数

    private final StringRedisTemplate redisTemplate;

    public BloomFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 将 goodsId 加入布隆过滤器 */
    public void add(long goodsId) {
        int[] pos = positions(goodsId);
        for (int p : pos) {
            redisTemplate.opsForValue().setBit(BITMAP_KEY, p, true);
        }
    }

    /**
     * 检查 goodsId 是否可能存在。
     * @return false = 一定不存在，true = 可能存在（极小概率误判）
     */
    public boolean mightContain(long goodsId) {
        int[] pos = positions(goodsId);
        for (int p : pos) {
            Boolean bit = redisTemplate.opsForValue().getBit(BITMAP_KEY, p);
            if (Boolean.FALSE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /** 批量初始化所有已有 goodsId，仅在 bitmap 不存在时执行 */
    public void initAll(List<Long> goodsIds) {
        Boolean exists = redisTemplate.hasKey(BITMAP_KEY);
        if (Boolean.TRUE.equals(exists)) {
            log.info("布隆过滤器已存在, 跳过初始化 (key={})", BITMAP_KEY);
            return;
        }
        for (Long id : goodsIds) {
            add(id);
        }
        log.info("布隆过滤器初始化完成: goodsCount={} key={}", goodsIds.size(), BITMAP_KEY);
    }

    private int[] positions(long value) {
        int[] pos = new int[K];
        long h = value;
        for (int i = 0; i < K; i++) {
            h = h * 0x9e3779b97f4a7c15L + 0xbf58476d1ce4e5b9L;
            h = Long.rotateLeft(h, 17);
            pos[i] = Math.abs((int) (h % M));
        }
        return pos;
    }
}
