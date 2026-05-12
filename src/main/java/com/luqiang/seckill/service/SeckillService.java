package com.luqiang.seckill.service;

import com.luqiang.seckill.common.ApiResponse;

public interface SeckillService {
    ApiResponse<Void> executeSeckill(Long goodsId, String userId);
}