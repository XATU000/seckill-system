package com.luqiang.seckill.repository;

import com.luqiang.seckill.entity.Goods;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsRepository extends JpaRepository<Goods, Long> {
}