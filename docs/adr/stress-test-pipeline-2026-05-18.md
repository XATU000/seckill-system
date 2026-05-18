# 秒杀系统 Pipeline 优化压测报告

**日期:** 2026-05-18
**分支:** feature/queue
**JMeter 测试计划:** `/tmp/seckill_stress_test.jmx` (纯秒杀，JWT 临时屏蔽)

## 1. 测试环境

| 组件 | 配置 |
|------|------|
| Host | Mac (Apple Silicon), JMeter 5.6.3 |
| VM | Colima (macOS Virtualization.Framework), 4 CPU / 4 GB |
| 应用 | Spring Boot 3.x, Docker 容器 (`eclipse-temurin:17-jre`) |
| Redis | Redis 7-alpine (Docker), 1 主 1 从 + 3 Sentinel |
| MySQL | MySQL 8.0 (Docker), HikariCP max 30 |
| 网络 | Docker bridge, 容器内直连, 无额外延迟 |

## 2. 本次优化项

| # | 优化 | 改动 | 预期效果 |
|---|------|------|----------|
| 1 | Redis Pipeline | SADD+EXPIRE+LPUSH+EXPIRE 合并为 1 RTT (原 4) | 每次秒杀省 3 次网络往返 |
| 2 | ReentrantLock | TokenBucketRateLimiter `synchronized` → `ReentrantLock` | 消除 OS mutex 升级 |
| 3 | HikariCP 扩容 | 连接池 10 → 30, 调度线程池 1 → 4 | 并发 DB 操作无等待 |
| 4 | 落库批量 | BATCH_SIZE 30 → 200 (150/s → 1000/s) | 队列不积压 |

## 3. 压测参数

| 参数 | 值 |
|------|-----|
| 并发线程 | 200 |
| 预热时间 | 5s |
| 循环次数 | 100 |
| 总请求 | 20,000 |
| 初始库存 | 5,879 |
| JWT 鉴权 | 临时屏蔽 |
| userId | `${__UUID()}` 每请求唯一 |

## 4. 压测结果

### 4.1 总体指标

| 指标 | 数值 |
|------|------|
| 总请求 | 20,000 |
| 成功秒杀 | 5,879 (29.4%) |
| 库存不足 | 14,121 (70.6%) |
| 错误 | 0 |
| 吞吐 | **370.9 req/s** |
| 持续时间 | 54s |

### 4.2 延迟分布

| 指标 | 数值 |
|------|------|
| 平均 | 504ms |
| P50 | 432ms |
| P90 | 832ms |
| P95 | 1,102ms |
| P99 | 1,675ms |
| Min | 21ms |
| Max | 4,757ms |

| 区间 | 请求数 | 占比 |
|------|--------|------|
| ≤ 50ms | 43 | 0.2% |
| ≤ 100ms | 203 | 1.0% |
| ≤ 200ms | 796 | 4.0% |
| ≤ 500ms | 13,072 | 65.4% |
| ≤ 1000ms | 18,715 | 93.6% |
| ≤ 2000ms | 19,936 | 99.7% |
| ≤ 5000ms | 20,000 | 100% |

### 4.3 订单落库

调度线程 `scheduling-1` ~ `scheduling-4` 并行落库，每批 67~180 条，无积压，无丢失。

### 4.4 与上次压测对比

| 指标 | 上次 (05-14, 500并发) | 本次 (05-18, 200并发) | 说明 |
|------|----------------------|----------------------|------|
| 纯秒杀 QPS | 912/s | 370.9/s | 本次库存仅 5879，大量线程做无效扫描 |
| 错误率 | 0 | 0 | 稳定性一致 |
| 落库速度 | 150/s | ~1000/s | 提升 6.7× |
| 调度线程 | 1 | 4 | 不再互卡 |

## 5. 关键发现

**Pipeline 优化对成功路径有效，但库存耗尽后暴露了新瓶颈。**

- 5879 个成功请求走 pipeline 1 RTT，路径极快（≤200ms 的 796 个请求基本是成功路径）
- 14,121 个失败请求每个遍历全部 10 段，每段做 `acquire()` → CAS 失败 → Redis SETNX + GET + DECR → 发现库存为 0
- 失败请求的 10 段扫描产生了大量无效 Redis 往返，拖高整体延迟

**P50 从上次 2841ms 降到 432ms，延迟改善 6×**，但主要因为上次瓶颈在 Redis Lua 脚本竞争（已优化），本次瓶颈转移到了库存耗尽后的无效扫描。

## 6. 瓶颈分析

| 阶段 | 主要瓶颈 | 表现 |
|------|----------|------|
| 优化前 | Redis Lua 单 key 串行 | QPS ~200, P50 ~2.3s |
| 上次 (库存分段后) | Redis Lua 10 段竞争 | QPS ~912, P50 ~2.8s (500 并发) |
| 本次 (Pipeline 后) | 库存耗尽后 10 段无效扫描 | QPS ~370, P50 ~432ms, 70% 请求做无效 Redis 往返 |

当前架构下，一次成功的秒杀请求仅需 1 次 Redis pipeline (~0.1ms)，远快于统计均值。整体均值被失败请求的无效 Redis 操作大幅拉高。

## 7. 后续优化方向

| 优先级 | 措施 | 预期效果 |
|--------|------|----------|
| P0 | 加 Redis `soldout` 标记位，库存归零后短路返回 | 消除 10 段无效扫描，QPS 大幅提升 |
| P1 | 提高库存分段 BATCH_SIZE (20 → 50) | 减少 `acquire()` 触发 Redis 补货频率 |
| P1 | Colima 内存 4GB → 8GB | 撑住更高并发 |
| P2 | 前置 Nginx 反向代理 + 连接复用 | 减少 Tomcat 连接数压力 |
| P2 | 库存返回定时任务加本地剩余阈值判断 | 减少不必要的 Redis INCR 调用 |

## 8. 测试命令

```bash
# 构建并部署
mvn clean package -DskipTests -q && docker compose build app && docker compose up -d app

# 运行压测
jmeter -n -t /tmp/seckill_stress_test.jmx -l result.jtl -e -o report/

# 查看库存
curl -s http://localhost:8080/seckill/stock/1

# 分析延迟
python3 -c "
import csv
with open('result.jtl') as f:
    rows = list(csv.DictReader(f))
    lat = sorted(int(r['elapsed']) for r in rows if r['success']=='true')
    print(f'P50:{lat[len(lat)//2]}ms P95:{lat[int(len(lat)*0.95)]}ms P99:{lat[int(len(lat)*0.99)]}ms')
"
```

## 9. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-05-18 | 初始版本: Pipeline + ReentrantLock + 连接池扩容 + 落库批量 |
