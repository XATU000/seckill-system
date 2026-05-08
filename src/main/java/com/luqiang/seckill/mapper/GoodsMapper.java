package com.luqiang.seckill.mapper;

import com.luqiang.seckill.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GoodsMapper {

    @Select("SELECT * FROM goods")
    List<Goods> findAll();
}