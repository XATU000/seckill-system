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

    /** 库存分段数，将总库存拆成 N 段，分散 Lua 脚本竞争 */
    public static final int STOCK_SEGMENTS = 10;

    public static String stockKey(Long goodsId, int segment) {
        return String.format("{s:%d}:stock:%d", goodsId, segment);
    }

    public static String orderKey(Long goodsId, int segment) {
        return String.format("{s:%d}:order:%d", goodsId, segment);
    }

    /** 售罄标记，库存归零后设置，用于快速拒绝请求 */
    public static String soldOutKey(Long goodsId) {
        return String.format("{s:%d}:soldout", goodsId);
    }

    /** 售罄标记 TTL（秒），超时自动清除，防止库存回补后永久拦截 */
    public static final int SOLDOUT_TTL_SECONDS = 3600;

    /** 订单取消延迟队列 ZSET key */
    public static final String CANCEL_ZSET_KEY = "order:cancel:zset";

    /** 订单取消延迟时间（毫秒），15 分钟未支付自动取消 */
    public static final long CANCEL_DELAY_MS = 15 * 60 * 1000L;

    /** userId → segment 路由 */
    public static int segmentFor(String userId) {
        return Math.abs(userId.hashCode()) % STOCK_SEGMENTS;
    }
}
