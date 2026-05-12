# Seckill System

高并发秒杀系统，基于 Spring Boot + Redis + MySQL，支持 10w+ QPS 的瞬时流量处理。

## 架构

```
Request → Controller → SeckillService (Lua 脚本扣库存)
                              │
                    ┌─────────┴──────────┐
                    │  Redis List Queue   │  ← 削峰缓冲
                    └─────────┬──────────┘
                              │ @Scheduled 200ms
                    ┌─────────┴──────────┐
                    │  JdbcTemplate      │  ← 批量落库
                    │  batchUpdate(30)   │
                    └─────────┬──────────┘
                              │
                           MySQL
```

## 技术栈

| 组件 | 版本/方案 |
|------|----------|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Redis | 7.0 + Lettuce + Lua 脚本 |
| MySQL | 8.0 + JPA + MyBatis + JDBC |
| 构建 | Maven |

## 核心特性

- **防超卖** — Redis Lua 脚本原子扣减库存（DECR + SISMEMBER + SADD 单次执行）
- **防重复** — 用户 ID 写入 Redis Set，重复购买直接拦截
- **削峰填谷** — Lua 成功后推入 Redis List 队列，定时任务批量写入 MySQL
- **补偿回滚** — 入队失败自动 INCR 回补库存 + SREM 移除用户
- **批量落库** — JdbcTemplate batchUpdate，配合 `rewriteBatchedStatements` 真批量
- **缓存防穿透** — 商品列表 Redis 缓存 + 互斥锁重建 + 随机 TTL 防雪崩
- **库存预热** — 启动时从 MySQL 加载库存到 Redis
- **优雅关闭** — Key TTL 自动过期，不积压内存

## Redis 高可用

- **Sentinel 哨兵** — 主从自动故障转移，Lettuce `autoReconnect` 无缝切换
- **RDB + AOF** — 快照 + 日志双重持久化，AOF `everysec` 最多丢 1 秒数据
- **连接池** — max-active=20, min-idle=5，预热连接应对突发

## 项目结构

```
src/main/java/com/luqiang/seckill/
├── controller/
│   ├── SeckillController.java     # POST /seckill/do/{id}
│   └── GoodsController.java       # GET /goods
├── service/
│   ├── SeckillService.java
│   ├── OrderQueueService.java     # Redis 队列操作
│   ├── GoodsService.java
│   └── impl/
│       ├── SeckillServiceImpl.java # Lua 脚本 + 削峰逻辑
│       └── GoodsServiceImpl.java   # 缓存策略
├── scheduler/
│   └── OrderPersistScheduler.java # 定时批量落库
├── config/
│   └── RedisConfig.java           # Lettuce 配置
├── common/
│   ├── ApiResponse.java           # 统一响应
│   ├── CacheConstants.java        # 缓存常量
│   ├── RedisUtil.java             # Redis 工具
│   └── GlobalExceptionHandler.java
├── entity/
│   ├── Goods.java
│   └── OrderInfo.java
├── repository/                    # JPA
├── mapper/                        # MyBatis
└── SeckillSystemApplication.java
```

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/seckill/do/{goodsId}?userId=xxx` | 执行秒杀 |
| GET | `/goods` | 商品列表 |

响应格式：

```json
{"code": 1, "message": "秒杀成功", "data": null}
```

| code | 含义 |
|------|------|
| 1 | 秒杀成功（已入队） |
| 0 | 库存不足 |
| 2 | 已经抢过了 |
| -1 | 商品不存在 |
| -2 | 系统异常 |
| -4 | 下单失败，请重试 |

## 快速启动

```bash
# 1. 启动 Redis（单机或 Sentinel）
redis-server

# 2. 初始化 MySQL 库表
# src/main/resources/schema.sql 自动执行

# 3. 启动应用
./mvnw spring-boot:run
```

生产环境切换到 Sentinel：取消 `application.yml` 中 Sentinel 配置的注释，注释掉单机 host/port。

## 压测

```bash
# JMeter
jmeter -n -t jmeter/seckill-stress-test.jmx -l result.jtl
```

> 注意：JMeter 测试计划中 HTTP Method 需改为 POST，与 Controller 的 `@PostMapping` 匹配。