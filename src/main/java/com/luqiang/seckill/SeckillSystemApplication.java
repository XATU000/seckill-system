package com.luqiang.seckill;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

@SpringBootApplication
public class SeckillSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillSystemApplication.class, args);
    }

    /**
     * ✅ 数据库连接检测（H2）
     */
    @Bean
    public CommandLineRunner checkDatabase(DataSource dataSource) {
        return args -> {
            System.out.println("========== 数据库连接检测开始 ==========");

            try (Connection conn = dataSource.getConnection()) {
                System.out.println("✅ 数据库连接成功！");
                System.out.println("URL: " + conn.getMetaData().getURL());
                System.out.println("User: " + conn.getMetaData().getUserName());
            } catch (Exception e) {
                System.out.println("❌ 数据库连接失败！");
                e.printStackTrace();
            }

            System.out.println("========== 数据库连接检测结束 ==========");
        };
    }

    /**
     * ✅ Redis库存初始化（只初始化一次，避免覆盖）
     */
    @Bean
    public CommandLineRunner initStock(StringRedisTemplate redisTemplate) {
        return args -> {
            try {
                String key = "stock:1";

                if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
                    redisTemplate.opsForValue().set(key, "10");
                    System.out.println("✅ 初始化库存：10");
                } else {
                    System.out.println("ℹ️ 库存已存在，不重复初始化");
                }

            } catch (Exception e) {
                System.out.println("❌ Redis 初始化失败！");
                e.printStackTrace();
            }
        };
    }
}