package com.luqiang.seckill.scheduler;

import com.luqiang.seckill.common.CacheConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class OrderCancelScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelScheduler.class);
    private static final int BATCH_SIZE = 20;

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public OrderCancelScheduler(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 3000)
    public void cancelExpiredOrders() {
        long now = System.currentTimeMillis();

        Set<String> members = redisTemplate.opsForZSet()
                .rangeByScore(CacheConstants.CANCEL_ZSET_KEY, 0, now, 0, BATCH_SIZE);

        if (members == null || members.isEmpty()) {
            return;
        }

        log.info("待取消订单 {} 条", members.size());

        for (String member : members) {
            processCancellation(member);
        }
    }

    private void processCancellation(String member) {
        // member = "goodsId:userId:segment"
        String[] parts = member.split(":", 3);
        if (parts.length != 3) {
            log.warn("非法取消成员格式: {}", member);
            redisTemplate.opsForZSet().remove(CacheConstants.CANCEL_ZSET_KEY, member);
            return;
        }

        long goodsId;
        int segment;
        try {
            goodsId = Long.parseLong(parts[0]);
            segment = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            log.warn("非法取消成员数字: {}", member);
            redisTemplate.opsForZSet().remove(CacheConstants.CANCEL_ZSET_KEY, member);
            return;
        }
        String userId = parts[1];

        // Step 1: Claim work by removing from ZSET
        Long removed = redisTemplate.opsForZSet()
                .remove(CacheConstants.CANCEL_ZSET_KEY, member);
        if (removed == null || removed == 0) {
            return;
        }

        try {
            // Step 2: Cancel in DB only if still pending
            int rows = jdbcTemplate.update(
                    "UPDATE seckill_order SET status = 2 WHERE goods_id = ? AND user_id = ? AND status = 0",
                    goodsId, userId);

            if (rows > 0) {
                // Step 3: Restore Redis state
                String stockKey = CacheConstants.stockKey(goodsId, segment);
                String orderKey = CacheConstants.orderKey(goodsId, segment);

                redisTemplate.opsForValue().increment(stockKey, 1);
                redisTemplate.opsForSet().remove(orderKey, userId);
                redisTemplate.delete(CacheConstants.soldOutKey(goodsId));

                log.info("订单已取消: goodsId={} userId={} segment={}", goodsId, userId, segment);
            }
        } catch (Exception e) {
            log.error("取消订单失败, member={}", member, e);
        }
    }
}
