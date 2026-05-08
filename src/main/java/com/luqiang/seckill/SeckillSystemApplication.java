package com.luqiang.seckill;

import com.luqiang.seckill.entity.Goods;
import com.luqiang.seckill.mapper.GoodsMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

@MapperScan("com.luqiang.seckill.mapper")
@SpringBootApplication
public class SeckillSystemApplication {

    @Autowired
    private GoodsMapper goodsMapper;

    public static void main(String[] args) {
        SpringApplication.run(SeckillSystemApplication.class, args);
    }

    @Bean
    public CommandLineRunner checkDatabase(DataSource dataSource) {
        return args -> {
            System.out.println( "========== 数据库连接检测开始 ==========");

            try (Connection conn = dataSource.getConnection()) {
                System.out.println("✅ 数据库连接成功！");
                System.out.println("URL: " + conn.getMetaData().getURL());
                System.out.println("User: " + conn.getMetaData().getUserName());
            }

            System.out.println("========== 数据库连接检测结束 ==========");
        };
    }

    @Bean
    public CommandLineRunner initStock(StringRedisTemplate redisTemplate) {
        return args -> {

            System.out.println("========== Redis库存初始化开始 ==========");

            List<Goods> list = goodsMapper.findAll();

            for (Goods g : list) {

                String key = "stock:" + g.getId();

                redisTemplate.opsForValue()
                        .set(key, String.valueOf(g.getStock()));

                System.out.println("初始化商品：" + g.getId()
                        + " 库存：" + g.getStock());
            }

            System.out.println("========== Redis库存初始化完成 ==========");
        };
    }
}