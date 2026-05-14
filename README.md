# 高并发秒杀系统

基于 Spring Boot + Redis Sentinel + MySQL 的秒杀系统，通过 Lua 原子操作 + 削峰队列 + 批量落库实现 **~200 QPS** 稳定吞吐。

## 系统架构

```
                              限流 5000/s
  3000+ 并发用户 ──→ Tomcat (500 线程) ──────────→ SeckillController
                                                       │
                          ┌────────────────────────────┘
                          ▼
                   SeckillService
                          │
                 ┌────────┴────────┐
                 ▼                 ▼
           Redis Lua 脚本     JWT 鉴权拦截器
           (原子扣库存)        (HMAC-SHA384)
                 │
         ┌───────┴───────┐
         ▼               ▼
    扣减成功         库存不足/重复
         │             → 直接返回
         ▼
   OrderQueueService
   (Redis List 削峰)
         │
         ▼  @Scheduled 200ms
   OrderPersistScheduler
   (JDBC batchUpdate × 30)
         │
         ▼
       MySQL
```

### 基础设施

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ Sentinel-1   │    │ Sentinel-2   │    │ Sentinel-3   │
│ :26379       │◄──►│ :26380       │◄──►│ :26381       │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       │    quorum = 2     │                   │
       └───────────────────┼───────────────────┘
                           │ 监控 + 自动故障转移
              ┌────────────┴────────────┐
              ▼                         ▼
      ┌──────────────┐         ┌──────────────┐
      │ Redis Master │ ──sync──►│ Redis Replica│
      │ :6380        │         │ :6381        │
      └──────────────┘         └──────────────┘
              │
              │ RDB + AOF (everysec)
              ▼
             数据持久化
```

## 压测结果

**测试环境:** Mac + Colima VM (4C/4G), Docker Compose 全栈部署

| 并发数 | 秒杀 QPS | 平均延迟 | P95 延迟 | P99 延迟 | 错误率 |
|--------|----------|----------|----------|----------|--------|
| 300 | **~194/s** | 330ms | 802ms | 946ms | 0% |
| 1000 | **~203/s** | 1419ms | 2507ms | 3142ms | 0% |
| 2000 | ❌ | - | - | - | 连接拒绝 |

**延迟分布（300 并发）：**

| < 100ms | 100-500ms | 500-1000ms |
|---------|-----------|------------|
| 15.3% | 61.3% | 23.3% |

**瓶颈：** Redis Lua 脚本单线程串行执行 → 所有秒杀请求在 Redis 排队。详见 [`docs/adr/stress-test-qps-2026-05-14.md`](docs/adr/stress-test-qps-2026-05-14.md)。

## 核心设计

### 防超卖：Redis Lua 原子操作

```lua
-- 1 次网络往返完成：库存检查 + 去重 + 扣减
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end  -- 已抢过
local stock = redis.call('GET', KEYS[1])
if (not stock) then return -1 end      -- 库存 key 缺失
if (tonumber(stock) <= 0) then return 0 end  -- 库存不足
redis.call('DECR', KEYS[1])            -- 扣库存
redis.call('SADD', KEYS[2], ARGV[1])   -- 标记用户
return 1
```

### 削峰填谷：Redis List 队列

秒杀成功不是直接写 MySQL，而是推入 Redis List。定时任务每 200ms 批量拉取 30 条，通过 `JdbcTemplate.batchUpdate` 一次写入。避免瞬时流量击垮数据库。

### 补偿回滚

入队失败时执行补偿 Lua 脚本 `INCR` 回补库存 + `SREM` 移除用户，保证不超卖、不丢单。

### 缓存策略

- **防穿透** — 互斥锁（`setIfAbsent`）重建缓存 + 空值缓存
- **防雪崩** — 随机 TTL（60s + random 0-30s）
- **防击穿** — 库存 key 缺失时自动从 MySQL 预热回源

### Redis 高可用

- **Sentinel 哨兵集群** — 3 节点, quorum=2, 自动故障转移
- **RDB + AOF 双重持久化** — `appendfsync everysec`, 最多丢 1 秒数据
- **Lettuce autoReconnect** — 主从切换时客户端自动重连

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Spring Boot 3.2.5, Java 17 |
| Web | Tomcat (500 线程), Spring MVC |
| 缓存 | Redis 7 + Lettuce 连接池 (max-active=100) |
| 持久层 | MySQL 8.0 + JPA + JdbcTemplate |
| 批量写入 | `rewriteBatchedStatements` 真批量 |
| 鉴权 | JWT (HMAC-SHA384, 30min 过期) |
| 限流 | 令牌桶 (5000 req/s) |
| 部署 | Docker Compose, 7 容器编排 |
| 压测 | JMeter 5.6.3 |

## 项目结构

```
src/main/java/com/luqiang/seckill/
├── controller/
│   ├── SeckillController.java      # POST /seckill/do/{id}
│   ├── AuthController.java         # POST /auth/login (JWT 签发)
│   └── GoodsController.java        # GET /goods/list
├── service/
│   ├── SeckillService.java
│   ├── OrderQueueService.java      # Redis 队列操作
│   └── impl/
│       └── SeckillServiceImpl.java # Lua 脚本 + 削峰 + 补偿
├── scheduler/
│   └── OrderPersistScheduler.java  # @Scheduled 200ms 批量落库
├── interceptor/
│   ├── JwtAuthInterceptor.java     # JWT 鉴权
│   └── RateLimiterInterceptor.java # 令牌桶限流
├── config/
│   └── WebConfig.java              # 拦截器注册 + Redis 配置
├── common/
│   ├── JwtUtil.java                # JWT 工具
│   └── TokenBucketRateLimiter.java # 限流算法实现
├── entity/                         # Goods, OrderInfo
└── repository/                     # JPA Repository
```

## API

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/auth/login?userId=` | 无 | 获取 JWT |
| POST | `/seckill/do/{goodsId}` | JWT | 执行秒杀 |
| GET | `/seckill/stock/{goodsId}` | 无 | 查询库存 |
| GET | `/seckill/result/{goodsId}` | JWT | 查询秒杀结果 |
| GET | `/goods/list` | 无 | 商品列表 |

## 快速启动

```bash
# Docker Compose（MySQL + Redis Sentinel × 3 + App）
docker-compose up -d

# 或本地开发（需自行启动 MySQL 和 Redis）
mvn spring-boot:run

# 压测
jmeter -n -t jmeter/seckill-stress-test.jmx -l result.jtl
```

## 调优记录

| 项 | 调优前 | 调优后 |
|----|--------|--------|
| Tomcat 线程 | 200 | 500 |
| Lettuce 连接池 | max-active=20 | max-active=100 |
| Tomcat accept-count | 100 | 500 |

> 详见 `docs/adr/stress-test-qps-2026-05-14.md`
