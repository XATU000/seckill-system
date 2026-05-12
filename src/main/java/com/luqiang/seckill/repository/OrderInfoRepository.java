package com.luqiang.seckill.repository;

import com.luqiang.seckill.entity.OrderInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {
    Optional<OrderInfo> findByGoodsIdAndUserId(Long goodsId, String userId);
}
