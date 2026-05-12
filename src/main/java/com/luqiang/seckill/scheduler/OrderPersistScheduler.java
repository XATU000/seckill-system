package com.luqiang.seckill.scheduler;

import com.luqiang.seckill.service.OrderQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class OrderPersistScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistScheduler.class);
    private static final int BATCH_SIZE = 30;

    private final OrderQueueService queueService;
    private final JdbcTemplate jdbcTemplate;

    public OrderPersistScheduler(OrderQueueService queueService, JdbcTemplate jdbcTemplate) {
        this.queueService = queueService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 200)
    public void consumeQueue() {
        List<String> batch = queueService.dequeueBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.info("批量落库 {} 条订单", batch.size());

        try {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO seckill_order (goods_id, user_id, create_time) VALUES (?, ?, ?)",
                    batch,
                    BATCH_SIZE,
                    (ps, item) -> {
                        String[] parts = item.split(":", 2);
                        ps.setLong(1, Long.parseLong(parts[0]));
                        ps.setString(2, parts[1]);
                        ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                    }
            );
        } catch (Exception e) {
            log.error("批量落库失败, 回补队列 {} 条", batch.size(), e);
            for (String item : batch) {
                queueService.enqueueRaw(item);
            }
        }
    }
}