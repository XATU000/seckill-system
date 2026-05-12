package com.luqiang.seckill.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class OrderQueueService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueService.class);

    private static final String ORDER_QUEUE_KEY = "order:queue";

    private static final DefaultRedisScript<List> DEQUEUE_SCRIPT;

    static {
        DEQUEUE_SCRIPT = new DefaultRedisScript<>();
        DEQUEUE_SCRIPT.setResultType(List.class);
        DEQUEUE_SCRIPT.setScriptText(
                "local batch = redis.call('LRANGE', KEYS[1], -ARGV[1], -1); " +
                        "if #batch > 0 then " +
                        "  redis.call('LTRIM', KEYS[1], 0, -ARGV[1] - 1); " +
                        "end; " +
                        "return batch;"
        );
    }

    private final StringRedisTemplate redisTemplate;

    public OrderQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean enqueue(Long goodsId, String userId) {
        try {
            redisTemplate.opsForList().leftPush(ORDER_QUEUE_KEY, goodsId + ":" + userId);
            return true;
        } catch (Exception e) {
            log.error("订单入队失败, goodsId={}, userId={}", goodsId, userId, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> dequeueBatch(int batchSize) {
        List<String> result = redisTemplate.execute(
                DEQUEUE_SCRIPT,
                Collections.singletonList(ORDER_QUEUE_KEY),
                String.valueOf(batchSize)
        );
        return result != null ? result : Collections.emptyList();
    }

    public void enqueueRaw(String raw) {
        redisTemplate.opsForList().rightPush(ORDER_QUEUE_KEY, raw);
    }

    public long queueSize() {
        Long size = redisTemplate.opsForList().size(ORDER_QUEUE_KEY);
        return size != null ? size : 0;
    }
}
