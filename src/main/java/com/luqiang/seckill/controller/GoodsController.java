package com.luqiang.seckill.controller;

import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.service.GoodsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/goods")
public class GoodsController {

    private final GoodsService goodsService;

    // ✅ 构造器注入（推荐）
    public GoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @GetMapping("/list")
    public List<Goods> list() {
        return goodsService.listGoods();
    }
}