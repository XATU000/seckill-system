package com.luqiang.seckill;

import com.luqiang.seckill.common.CacheConstants;
import com.luqiang.seckill.common.LocalStockCache;
import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.mapper.GoodsMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

@MapperScan("com.luqiang.seckill.mapper")
@SpringBootApplication
@EnableScheduling
public class SeckillSystemApplication {
    private static final Logger log = LoggerFactory.getLogger(SeckillSystemApplication.class);

    private final GoodsMapper goodsMapper;

    public SeckillSystemApplication(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    public static void main(String[] args) {
        SpringApplication.run(SeckillSystemApplication.class, args);
    }

    @Bean
    public CommandLineRunner checkDatabase(DataSource dataSource) {
        return args -> {
            log.info("========== 数据库连接检测开始 ==========");

            try (Connection conn = dataSource.getConnection()) {
                log.info("数据库连接成功");
                log.info("URL: {}", conn.getMetaData().getURL());
                log.info("User: {}", conn.getMetaData().getUserName());
            }

            log.info("========== 数据库连接检测结束 ==========");
        };
    }

    private static final int PRE_FILL_BATCH = 20;

    @Bean
    public CommandLineRunner initStock(StringRedisTemplate redisTemplate,
                                       LocalStockCache localStockCache) {
        return args -> {
            log.info("========== Redis库存初始化开始 ==========");

            List<Goods> list = goodsMapper.findAll();

            for (Goods g : list) {
                String sentinelKey = CacheConstants.stockKey(g.getId(), 0);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(sentinelKey))) {
                    // Redis 已有数据（仅应用重启），沿用并预填本地缓存
                    log.info("Redis库存已存在, goodsId={} 跳过覆盖,仅预填本地缓存", g.getId());
                    for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
                        localStockCache.preFill(CacheConstants.stockKey(g.getId(), i), PRE_FILL_BATCH);
                    }
                } else {
                    // Redis 无数据（首次启动或 Redis 重启），从 DB 初始化
                    int base = g.getStock() / CacheConstants.STOCK_SEGMENTS;
                    int remainder = g.getStock() % CacheConstants.STOCK_SEGMENTS;
                    for (int i = 0; i < CacheConstants.STOCK_SEGMENTS; i++) {
                        int segStock = base + (i < remainder ? 1 : 0);
                        String key = CacheConstants.stockKey(g.getId(), i);
                        redisTemplate.opsForValue().set(key, String.valueOf(segStock));
                        localStockCache.preFill(key, PRE_FILL_BATCH);
                    }
                    log.info("初始化商品: {} 总库存: {} 分段数: {} 每段基础: {} 余数: {}",
                            g.getId(), g.getStock(), CacheConstants.STOCK_SEGMENTS, base, remainder);
                }
            }

            log.info("========== Redis库存初始化完成 ==========");
        };
    }
}