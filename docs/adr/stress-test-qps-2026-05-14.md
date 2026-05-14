# 秒杀系统 QPS 压测报告

**日期:** 2026-05-14  
**分支:** feature/queue  
**JMeter 测试计划:** `jmeter/seckill-stress-test.jmx` (含登录) / `jmeter/seckill-pure-test.jmx` (纯秒杀)

## 1. 测试环境

| 组件 | 配置 |
|------|------|
| Host | Mac (Apple Silicon), JMeter 5.6.3 |
| VM | Colima (macOS Virtualization.Framework), 4 CPU / 4 GB |
| 应用 | Spring Boot 3.x, Docker 容器 (`eclipse-temurin:17-jre`) |
| Redis | Redis 7.4.9 (Docker), 1 主 1 从 + 3 Sentinel, Lettuce 连接池 |
| MySQL | MySQL 8.0 (Docker), HikariCP 默认连接池 |
| 网络 | Docker bridge, 无额外延迟 |

## 2. 压测结果

### 2.1 优化前（单 key 库存，Tomcat 200 线程，连接池 20）

#### 含 JWT 登录

| 并发 | 总请求 | 秒杀 QPS | 平均延迟 | P95 | 错误 |
|------|--------|----------|----------|-----|------|
| 300 | 600 | ~194/s | 330ms | 802ms | 0 |
| 1000 | 2000 | ~203/s | 1419ms | 2507ms | 0 |

#### 纯秒杀

| 并发 | 秒杀 QPS | 平均延迟 | P95 | 错误 |
|------|----------|----------|-----|------|
| 500 | ~162/s | 2330ms | 3479ms | 0 |
| 1000 | ~148/s | 3076ms | ~5000ms | 0 |
| 2000 | ❌ | - | - | Connection reset |

### 2.2 优化后（10 段库存，Tomcat 500 线程，连接池 100）

#### 纯秒杀（无认证）

| 并发 | 秒杀 QPS | 平均延迟 | P95 | P99 | 错误 |
|------|----------|----------|-----|-----|------|
| 500 | **~912/s** | 2841ms | 4012ms | 4054ms | 0 |
| 1000 | **~787/s** | 3748ms | 4903ms | 5314ms | 0 |
| 2000 | ❌ | - | - | - | Docker VM 崩溃 |

### 2.3 对比汇总

| 并发 | 优化前 QPS | 优化后 QPS | 提升 |
|------|-----------|-----------|------|
| 500 | 162/s | **912/s** | **5.6×** |
| 1000 | 148/s | **787/s** | **5.3×** |
| 2000 | ❌ | ❌ | 瓶颈转移至 Docker VM |

## 3. 关键发现

**库存分段对 QPS 的提升是决定性的：5~6 倍。**

- Lua 脚本竞争从 1 个 key 分散到 10 个 key，Redis 单线程瓶颈大幅缓解
- 高并发下 QPS 有所下降（912 → 787），符合预期（线程增多 → 上下文切换开销增大）
- 2000 并发仍然打崩 Docker VM，下一步瓶颈在基础设施层（Colima 4GB 内存不足）

## 4. 瓶颈演变

| 阶段 | 主要瓶颈 | 解决方式 |
|------|----------|----------|
| 优化前 | Redis Lua 单 key 串行 (200 QPS) | 库存分段 |
| 优化后 (纯秒杀) | Colima Docker VM (4GB) | 增加 VM 内存 或 上 K8s 多实例 |
| 优化后 (2000 并发) | Tomcat 连接数 > 500 | accept-count 继续上调 或 前置 Nginx |

## 5. 后续优化方向

| 优先级 | 措施 | 预期效果 |
|--------|------|----------|
| P0 | Colima 内存 4GB → 8GB | 撑住 2000+ 并发 |
| P1 | 前置 Nginx 反向代理 + 连接复用 | 减少 Tomcat 连接数压力 |
| P1 | 引入消息队列替换 Redis List | 削峰更可控、支持回溯 |
| P2 | Spring Boot 多实例 + 负载均衡 | 水平扩展 |
| P2 | 库存分段数从 10 调到 20 | 进一步减少 Lua 竞争 |

## 6. 测试命令

```bash
# 含 JWT 登录
jmeter -n -t jmeter/seckill-stress-test.jmx -l result.jtl

# 纯秒杀（需先注释 WebConfig 中的 JWT 认证排除路径）
jmeter -n -t jmeter/seckill-pure-test.jmx -l result.jtl

# 分析结果
grep ',纯秒杀,' result.jtl | awk -F',' '{print $2}' | sort -n | \
  awk 'BEGIN{sum=0;c=0}{a[c]=$1;sum+=$1;c++}END{...}'
```
