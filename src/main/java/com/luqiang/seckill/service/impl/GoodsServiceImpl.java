package com.luqiang.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luqiang.seckill.common.RedisUtil;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.repository.GoodsRepository;
import com.luqiang.seckill.service.GoodsService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GoodsServiceImpl implements GoodsService {
    private static final String GOODS_LIST_KEY = "goods:list";
    private static final String GOODS_LIST_LOCK_KEY = "lock:goods:list";
    private static final int GOODS_LIST_TTL_SECONDS = 60;
    private static final int GOODS_LIST_TTL_RANDOM_BOUND_SECONDS = 30;
    private static final int GOODS_LIST_EMPTY_TTL_SECONDS = 30;
    private static final String EMPTY_CACHE_MARKER = "__EMPTY__";

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
            String cache = redisUtil.get(GOODS_LIST_KEY);

            if (cache != null) {
                if (EMPTY_CACHE_MARKER.equals(cache)) {
                    return Collections.emptyList();
                }
                return Arrays.asList(objectMapper.readValue(cache, Goods[].class));
            }

            Boolean locked = redisUtil.setIfAbsent(GOODS_LIST_LOCK_KEY, "1", 10);
            if (Boolean.TRUE.equals(locked)) {
                try {
                    String cacheAgain = redisUtil.get(GOODS_LIST_KEY);
                    if (cacheAgain != null) {
                        if (EMPTY_CACHE_MARKER.equals(cacheAgain)) {
                            return Collections.emptyList();
                        }
                        return Arrays.asList(objectMapper.readValue(cacheAgain, Goods[].class));
                    }

                    List<Goods> goodsList = goodsRepository.findAll();
                    if (goodsList == null || goodsList.isEmpty()) {
                        redisUtil.set(GOODS_LIST_KEY, EMPTY_CACHE_MARKER, GOODS_LIST_EMPTY_TTL_SECONDS);
                        return Collections.emptyList();
                    }

                    int ttl = GOODS_LIST_TTL_SECONDS + ThreadLocalRandom.current()
                            .nextInt(GOODS_LIST_TTL_RANDOM_BOUND_SECONDS + 1);
                    redisUtil.set(GOODS_LIST_KEY, objectMapper.writeValueAsString(goodsList), ttl);

                    return goodsList;
                } finally {
                    redisUtil.delete(GOODS_LIST_LOCK_KEY);
                }
            }

            Thread.sleep(50L);
            String cacheRetry = redisUtil.get(GOODS_LIST_KEY);
            if (cacheRetry != null) {
                if (EMPTY_CACHE_MARKER.equals(cacheRetry)) {
                    return Collections.emptyList();
                }
                return Arrays.asList(objectMapper.readValue(cacheRetry, Goods[].class));
            }

            List<Goods> fallbackList = goodsRepository.findAll();
            if (fallbackList == null || fallbackList.isEmpty()) {
                return Collections.emptyList();
            }
            return fallbackList;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("查询商品失败");
        }
    }
}