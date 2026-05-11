package com.luqiang.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luqiang.seckill.common.CacheConstants;
import com.luqiang.seckill.common.RedisUtil;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.repository.GoodsRepository;
import com.luqiang.seckill.service.GoodsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GoodsServiceImpl implements GoodsService {
    private static final Logger log = LoggerFactory.getLogger(GoodsServiceImpl.class);

    private final RedisUtil redisUtil;
    private final GoodsRepository goodsRepository;
    private final ObjectMapper objectMapper;

    public GoodsServiceImpl(RedisUtil redisUtil,
                            GoodsRepository goodsRepository,
                            ObjectMapper objectMapper) {
        this.redisUtil = redisUtil;
        this.goodsRepository = goodsRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Goods> listGoods() {

        try {
            String cache = redisUtil.get(CacheConstants.GOODS_LIST_KEY);

            if (cache != null) {
                if (CacheConstants.EMPTY_CACHE_MARKER.equals(cache)) {
                    return Collections.emptyList();
                }
                return Arrays.asList(objectMapper.readValue(cache, Goods[].class));
            }

            Boolean locked = redisUtil.setIfAbsent(CacheConstants.GOODS_LIST_LOCK_KEY, "1", 10);
            if (Boolean.TRUE.equals(locked)) {
                try {
                    String cacheAgain = redisUtil.get(CacheConstants.GOODS_LIST_KEY);
                    if (cacheAgain != null) {
                        if (CacheConstants.EMPTY_CACHE_MARKER.equals(cacheAgain)) {
                            return Collections.emptyList();
                        }
                        return Arrays.asList(objectMapper.readValue(cacheAgain, Goods[].class));
                    }

                    List<Goods> goodsList = goodsRepository.findAll();
                    if (goodsList == null || goodsList.isEmpty()) {
                        redisUtil.set(
                                CacheConstants.GOODS_LIST_KEY,
                                CacheConstants.EMPTY_CACHE_MARKER,
                                CacheConstants.GOODS_LIST_EMPTY_TTL_SECONDS
                        );
                        return Collections.emptyList();
                    }

                    int ttl = CacheConstants.GOODS_LIST_TTL_SECONDS + ThreadLocalRandom.current()
                            .nextInt(CacheConstants.GOODS_LIST_TTL_RANDOM_BOUND_SECONDS + 1);
                    redisUtil.set(CacheConstants.GOODS_LIST_KEY, objectMapper.writeValueAsString(goodsList), ttl);

                    return goodsList;
                } finally {
                    redisUtil.delete(CacheConstants.GOODS_LIST_LOCK_KEY);
                }
            }

            Thread.sleep(50L);
            String cacheRetry = redisUtil.get(CacheConstants.GOODS_LIST_KEY);
            if (cacheRetry != null) {
                if (CacheConstants.EMPTY_CACHE_MARKER.equals(cacheRetry)) {
                    return Collections.emptyList();
                }
                return Arrays.asList(objectMapper.readValue(cacheRetry, Goods[].class));
            }

            List<Goods> fallbackList = goodsRepository.findAll();
            if (fallbackList == null || fallbackList.isEmpty()) {
                return Collections.emptyList();
            }
            return fallbackList;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询商品被中断", e);
        } catch (Exception e) {
            log.error("查询商品失败", e);
            throw new RuntimeException("查询商品失败", e);
        }
    }
}