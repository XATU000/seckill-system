package com.luqiang.seckill;

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

    @Bean
    public CommandLineRunner initStock(StringRedisTemplate redisTemplate) {
        return args -> {

            log.info("========== Redis库存初始化开始 ==========");

            List<Goods> list = goodsMapper.findAll();

            for (Goods g : list) {

                String key = "stock:" + g.getId();

                redisTemplate.opsForValue()
                        .set(key, String.valueOf(g.getStock()));

                log.info("初始化商品: {} 库存: {}", g.getId(), g.getStock());
            }

            log.info("========== Redis库存初始化完成 ==========");
        };
    }
}