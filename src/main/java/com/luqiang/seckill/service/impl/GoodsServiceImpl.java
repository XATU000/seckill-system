package com.luqiang.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luqiang.seckill.common.RedisUtil;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.repository.GoodsRepository;
import com.luqiang.seckill.service.GoodsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class GoodsServiceImpl implements GoodsService {

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
            // ✅ 1. 查缓存
            String cache = redisUtil.get("goods:list");

            if (cache != null) {
                System.out.println("🔥 走缓存");
                return Arrays.asList(
                        objectMapper.readValue(cache, Goods[].class)
                );
            }

            // ✅ 2. 查数据库
            System.out.println("💾 走数据库");
            List<Goods> goodsList = goodsRepository.findAll();

            // ✅ 3. 写入缓存（60秒）
            redisUtil.set(
                    "goods:list",
                    objectMapper.writeValueAsString(goodsList),
                    60
            );

            return goodsList;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("查询商品失败");
        }
    }
}