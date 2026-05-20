# 高并发秒杀系统

基于 Spring Boot + Redis Sentinel + MySQL 的秒杀系统，通过库存分段 + Pipeline 批量操作 + 削峰队列 + 批量落库实现 **~370 QPS** 稳定吞吐。

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
      LocalStockCache (10段)   JWT 鉴权拦截器
      CAS 本地扣减 → 不足时    (HMAC-SHA384)
      Redis Pipeline 补货            │
                 │
         ┌───────┴───────┐
         ▼               ▼
     扣减成功         库存不足/重复/soldout
         │             → 直接返回
         ▼
   Redis Pipeline (1 RTT)
   SADD + EXPIRE + LPUSH + EXPIRE
         │
         ▼  @Scheduled 200ms × 4 线程
   OrderPersistScheduler
   (JDBC batchUpdate × 200)
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

**测试环境:** Mac + Colima VM (4C/4G), Docker Compose 全栈部署, 200 并发 × 100 循环, 总 20,000 请求, 初始库存 5,879

| 指标 | 数值 |
|------|------|
| 吞吐 | **370.9 req/s** |
| 成功秒杀 | 5,879 (29.4%) |
| 库存不足 | 14,121 (70.6%) |
| 错误 | 0 |
| 持续时间 | 54s |

| 延迟 | 数值 | 分布区间 | 占比 |
|------|------|----------|------|
| 平均 | 504ms | ≤ 200ms | 4.0% |
| P50 | 432ms | ≤ 500ms | 65.4% |
| P90 | 832ms | ≤ 1000ms | 93.6% |
| P95 | 1,102ms | ≤ 2000ms | 99.7% |
| P99 | 1,675ms | | |

### 与早期版本对比

| 指标 | 05-14 (500并发, 优化前) | 05-18 (200并发, 优化后) | 改善 |
|------|------------------------|------------------------|------|
| 纯秒杀 QPS | 912/s | 370.9/s | 本次库存仅 5879，70% 请求命中 soldout |
| P50 延迟 | 2,841ms | 432ms | **6.6×** |
| 落库速度 | 150/s | ~1000/s | **6.7×** |
| 错误率 | 0 | 0 | 一致 |

**当前瓶颈：** 库存耗尽后失败请求遍历全部 10 段做无效 Redis 往返，P0 方案为添加 `soldout` 标记位短路。

详见 [`docs/adr/stress-test-qps-2026-05-14.md`](docs/adr/stress-test-qps-2026-05-14.md) 和 [`docs/adr/stress-test-pipeline-2026-05-18.md`](docs/adr/stress-test-pipeline-2026-05-18.md)。

## 核心设计

### 防超卖：库存分段 + CAS 本地扣减

将库存分配到 10 个分段（`stock_seg:0` ~ `stock_seg:9`），每个分段对应一个 Redis key。请求通过 `hash(userId) % 10` 路由到固定分段，`LocalStockCache` 使用 `AtomicInteger` CAS 在本地内存扣减，无需每次请求都访问 Redis。

```java
// LocalStockCache: CAS 本地扣减，不足时触发 Redis Pipeline 补货
int seg = Math.abs(userId.hashCode() % 10);
AtomicInteger localStock = localCache[seg];
while (true) {
    int cur = localStock.get();
    if (cur <= 0) {
        acquireFromRedis(seg);  // Redis Pipeline 拉取新一批
        continue;
    }
    if (localStock.compareAndSet(cur, cur - 1)) break;
}
```

### Redis Pipeline：4 次网络往返 → 1 次

秒杀成功后将 SADD（标记用户）+ EXPIRE（设置过期）+ LPUSH（入队）+ EXPIRE（队列过期）4 个命令合并为 1 次 Pipeline 调用，减少 3 次网络往返。

```java
// 1 RTT 完成：去重标记 + 入队列 + 过期时间
redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
    conn.sAdd(userKeyBytes, userIdBytes);
    conn.expire(userKeyBytes, 3600);
    conn.listCommands().lPush(queueKeyBytes, contentBytes);
    conn.expire(queueKeyBytes, 3600);
    return null;
});
```

### 削峰填谷：Redis List 队列 + 4 线程并行落库

秒杀成功不是直接写 MySQL，而是推入 Redis List。4 个调度线程每 200ms 批量拉取最多 200 条，通过 `JdbcTemplate.batchUpdate` 并行写入。落库速度从 150/s 提升至 ~1000/s，队列不再积压。

### 补偿回滚

入队失败时执行补偿 Lua 脚本 `INCR` 回补库存 + `SREM` 移除用户，保证不超卖、不丢单。

### Soldout 标记（规划中）

库存全局归零后在 Redis 设置 `soldout` 标记位，后续请求直接返回「已售罄」，消除库存耗尽后 10 段无效扫描。当前 70% 的失败请求都在做无效 Redis 往返，是实现后 QPS 大幅提升的关键。

### 缓存策略

- **防穿透** — 布隆过滤器拦截不存在的 goodsId + 空值缓存（`__EMPTY__`），防止恶意随机 key 攻击
- **防击穿** — 互斥锁（`setIfAbsent`）保证仅一线程回源重建缓存，其余线程 sleep 后重试
- **防雪崩** — 随机 TTL（60s + random 0-30s），避免大量缓存同时过期
- **预热回源** — 库存 key 缺失时自动从 MySQL 加载并写入 Redis，启动时预填充本地库存

### Redis 高可用

- **Sentinel 哨兵集群** — 3 节点, quorum=2, 自动故障转移
- **RDB + AOF 双重持久化** — `appendfsync everysec`, 最多丢 1 秒数据
- **Lettuce autoReconnect** — 主从切换时客户端自动重连
- **Pipeline 批量操作** — SADD + EXPIRE + LPUSH + EXPIRE 合并为 1 RTT

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Spring Boot 3.2.5, Java 17 |
| Web | Tomcat (500 线程), Spring MVC |
| 缓存 | Redis 7 + Lettuce 连接池 (max-active=100) + Pipeline |
| 持久层 | MySQL 8.0 + JPA + JdbcTemplate + HikariCP (max 30) |
| 批量写入 | `rewriteBatchedStatements` 真批量, 200 条/批, 4 线程 |
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
│       └── SeckillServiceImpl.java # 分段扣减 + Pipeline + 削峰 + 补偿
├── scheduler/
│   └── OrderPersistScheduler.java  # @Scheduled 200ms 批量落库
├── interceptor/
│   ├── JwtAuthInterceptor.java     # JWT 鉴权
│   └── RateLimiterInterceptor.java # 令牌桶限流
├── config/
│   └── WebConfig.java              # 拦截器注册 + Redis 配置
├── common/
│   ├── ApiResponse.java             # 统一响应体
│   ├── BloomFilter.java             # Redis Bitmap 布隆过滤器（防穿透）
│   ├── CacheConstants.java          # 缓存 key 常量 + 分段路由
│   ├── GlobalExceptionHandler.java  # 全局异常处理
│   ├── JwtUtil.java                 # JWT 工具
│   ├── LocalStockCache.java         # 本地库存预留（热点 key 防护）
│   ├── RedisUtil.java               # Redis 工具封装
│   └── TokenBucketRateLimiter.java  # 限流算法实现
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

| 项 | 调优前 | 调优后 | 来源 |
|----|--------|--------|------|
| Tomcat 线程 | 200 | 500 | 05-14 |
| Lettuce 连接池 | max-active=20 | max-active=100 | 05-14 |
| Tomcat accept-count | 100 | 500 | 05-14 |
| Redis 网络往返/秒杀 | 4 RTT | 1 RTT (Pipeline) | 05-18 |
| TokenBucketRateLimiter | synchronized | ReentrantLock | 05-18 |
| HikariCP | 10 | 30 | 05-18 |
| 落库批量 | 30 条/批, 1 线程 | 200 条/批, 4 线程 | 05-18 |
| 库存分段 | 单 key (Lua) | 10 段 + CAS 本地扣减 | 05-18 |

> 详见 `docs/adr/stress-test-qps-2026-05-14.md` 和 `docs/adr/stress-test-pipeline-2026-05-18.md`
