package com.luqiang.seckill.service;

import com.luqiang.seckill.common.ApiResponse;
import com.luqiang.seckill.entity.OrderInfo;

public interface SeckillService {
    ApiResponse<Void> executeSeckill(Long goodsId, String userId);

    ApiResponse<Integer> getStock(Long goodsId);

    ApiResponse<OrderInfo> getResult(Long goodsId, String userId);
}