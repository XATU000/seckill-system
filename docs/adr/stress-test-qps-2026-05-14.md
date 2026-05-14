# 秒杀系统 QPS 压测报告

**日期:** 2026-05-14  
**分支:** feature/queue  
**JMeter 测试计划:** `jmeter/seckill-stress-test.jmx` (含登录) / `jmeter/seckill-pure-test.jmx` (纯秒杀)

## 1. 测试环境

| 组件 | 配置 |
|------|------|
| Host | Mac (Apple Silicon), JMeter 5.6.3 |
| VM | Colima (macOS Virtualization.Framework), 4 CPU / 4 GB |
| 应用 | Spring Boot 3.x, Docker 容器 (`eclipse-temurin:17-jre`), Tomcat 默认线程池 |
| Redis | Redis 7.4.9 (Docker), 1 主 1 从 + 3 Sentinel, Lettuce 连接池 max-active=20 |
| MySQL | MySQL 8.0 (Docker), HikariCP 默认连接池 |
| 网络 | Docker bridge, 无额外延迟 |

## 2. 压测结果

### 2.1 含 JWT 登录（真实业务流程）

每个线程：登录获取 Token → 带 Token 秒杀。

| 并发 | 总请求 | 秒杀 QPS | 平均延迟 | 中位延迟 | P95 | P99 | 最大 | 错误 |
|------|--------|----------|----------|----------|-----|-----|------|------|
| 300 | 600 | ~194/s | 330ms | 262ms | 802ms | 946ms | 967ms | 0 |
| 1000 | 2000 | ~203/s | 1419ms | 1325ms | 2507ms | 3142ms | 3892ms | 0 |

### 2.2 纯秒杀（无认证，消除登录瓶颈）

绕过 JWT 拦截器，直接带 `?userId=` 参数压测秒杀接口。

| 并发 | 秒杀 QPS | 平均延迟 | P95 | P99 | 最大 | 错误 |
|------|----------|----------|-----|-----|------|------|
| 500 | ~162/s | 2330ms | 3479ms | 3554ms | 3626ms | 0 |
| 1000 | ~148/s | 3076ms | ~5000ms | ~5000ms | 5075ms | 0 |
| 2000 | ❌ | - | - | - | - | Connection reset |

### 2.3 延迟分布（300 并发，含登录）

| 区间 | 占比 |
|------|------|
| < 100ms | 15.3% |
| 100-500ms | 61.3% |
| 500-1000ms | 23.3% |

## 3. 关键发现

**当前系统可稳定支撑的 QPS：~200/s。**

- 300 并发时体验良好，P95 延迟 < 1s
- 1000 并发时 QPS 仍能维持 ~200/s，但 P95 延迟恶化至 2.5s
- 2000 并发直接打爆服务（Tomcat 线程池耗尽，连接重置）

**反直觉现象：纯秒杀 QPS（162/s）< 含登录 QPS（203/s）。**

原因：登录步骤充当了天然的"错峰"机制。300 个线程先登录再秒杀，使得秒杀请求在时间轴上自然分散，降低了 Redis Lua 脚本的瞬时竞争。直接 500+ 线程同时秒杀时，所有请求同时争抢 Redis，导致排队加剧。

## 4. 瓶颈定位

| 瓶颈层 | 说明 | 严重程度 |
|--------|------|----------|
| **Redis Lua 脚本串行执行** | 库存检查、去重、扣减在单线程中原子执行，所有秒杀请求排队 | 主要 |
| **Tomcat 线程池** | 默认 ~200 线程，超过则请求堆积在 accept queue | 次要 |
| **Lettuce 连接池** | max-active=20，高并发下可能不够 | 低 |

当前限流器（5000/s）未触发，不是瓶颈。

## 5. 改进建议

| 优先级 | 措施 | 预期效果 |
|--------|------|----------|
| P0 | Tomcat 线程池从默认 200 调到 500+ | 提升并发承载，避免 2000 并发时雪崩 |
| P0 | Lettuce max-active 从 20 调到 50-100 | 减少连接等待 |
| P1 | 库存分段（如分 10 个 slot，每段 10% 库存） | 减少 Lua 脚本竞争，理论 QPS ×10 |
| P1 | 引入消息队列解耦（如 Kafka/RabbitMQ） | 削峰填谷，保护下游 MySQL |
| P2 | 应用层预热 + 本地缓存热点库存 | 减少 Redis 调用 |
| P2 | 增加 JVM 参数调优（GC 策略、堆大小） | 减少 STW 停顿 |

## 6. 测试命令

```bash
# 含 JWT 登录
jmeter -n -t jmeter/seckill-stress-test.jmx -l result.jtl

# 纯秒杀（需先注释 WebConfig 中的 JWT 认证排除路径）
jmeter -n -t jmeter/seckill-pure-test.jmx -l result.jtl

# 分析结果
grep ',秒杀,' result.jtl | awk -F',' '{print $2}' | sort -n | \
  awk 'BEGIN{sum=0;c=0}{a[c]=$1;sum+=$1;c++}END{...}'
```
