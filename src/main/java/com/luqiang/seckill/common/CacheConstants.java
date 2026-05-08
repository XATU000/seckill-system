package com.luqiang.seckill.common;

public final class CacheConstants {
    private CacheConstants() {
    }

    public static final String GOODS_LIST_KEY = "goods:list";
    public static final String GOODS_LIST_LOCK_KEY = "lock:goods:list";
    public static final int GOODS_LIST_TTL_SECONDS = 60;
    public static final int GOODS_LIST_TTL_RANDOM_BOUND_SECONDS = 30;
    public static final int GOODS_LIST_EMPTY_TTL_SECONDS = 30;
    public static final String EMPTY_CACHE_MARKER = "__EMPTY__";
}
