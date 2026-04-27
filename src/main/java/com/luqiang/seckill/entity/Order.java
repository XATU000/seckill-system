package com.luqiang.seckill.entity;

public class Order {

    private Long id;        // 订单ID
    private Long userId;    // 用户ID
    private Long goodsId;   // 商品ID

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }
}