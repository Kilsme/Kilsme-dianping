# 核心链路重构记录

> 重构日期：2026-05-20

---

## 一、重构概览

本次重构涉及三大核心链路：

| 任务 | 中间件 | 改动范围 |
|---|---|---|
| 秒杀接口限流防护 | Alibaba Sentinel | pom.xml, application.yaml, VoucherOrderController |
| 秒杀交易闭环完善 | Apache RocketMQ | MqConstants, VoucherOrderServiceImpl, SeckillOrderConsumer, OrderTimeoutListener(新), PaymentController |
| 点赞 Write-Behind 优化 | Redis + XXL-JOB | RedisConstants, BlogServiceImpl, LikeSyncJobHandler(新), BlogMapper |

---

## 二、Sentinel 限流

### 2.1 背景

秒杀接口 `POST /voucher-order/seckill/{id}` 此前无任何限流保护，高并发下可能击垮服务。

### 2.2 改动文件

| 文件 | 改动 |
|---|---|
| `pom.xml` | 添加 `spring-cloud-alibaba-dependencies` BOM + `spring-cloud-starter-alibaba-sentinel` 依赖 |
| `application.yaml` | 新增 `spring.cloud.sentinel.transport.dashboard` 和 `eager` 配置 |
| `VoucherOrderController.java` | `seckillVoucher` 方法添加 `@SentinelResource(value = "seckill", blockHandler = "seckillBlockHandler")`；新增 `seckillBlockHandler` 兜底方法 |

### 2.3 配置参考

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080   # Sentinel 控制台
      eager: true                    # 启动时立即初始化
```

### 2.4 限流规则

通过 Sentinel 控制台配置流控规则：
- 资源名：`seckill`
- QPS 阈值：根据实际压测设定（建议 100-500）
- 流控效果：快速失败

触发限流时返回：`Result.fail("活动太火爆，请稍后再试")`

---

## 三、RocketMQ 秒杀交易闭环

### 3.1 背景

此前秒杀流程中 RocketMQ 仅用于异步创建订单，缺少：
- 超时未支付订单的自动取消
- 关单后的库存回滚（MySQL + Redis）
- 直接的支付回调接口

### 3.2 消息流转图

```
用户发起秒杀
    │
    ▼
VoucherOrderController.seckillVoucher()
    │
    ▼
VoucherOrderServiceImpl.seckillVoucher()
    │ 1. Redis Lua 脚本预扣库存 + 生成 orderId
    │ 2. 发送 SeckillOrderMessage ─────────────► SECKILL_ORDER_TOPIC
    │ 3. 发送 SeckillOrderMessage (delayLevel=4) ► ORDER_TIMEOUT_TOPIC (延迟15分钟)
    │
    ├──► SeckillOrderConsumer (实时消费)
    │       创建订单到 MySQL (status=1 待支付)
    │       幂等保护：getById + DuplicateKeyException
    │
    └──► OrderTimeoutListener (延迟消费)
            查询订单状态
            ├── status=1 → 关闭订单 (status=4)
            │              回滚 MySQL 库存 +1
            │              回滚 Redis 库存 INCR + SREM
            └── status≠1 → 跳过

用户支付
    │
    ▼
POST /api/pay/callback?orderId=xxx
    │ 直接更新订单 status=2 (已支付)
    │
    ▼
OrderTimeoutListener 再次触发 → status≠1 → 跳过
```

### 3.3 Topic 设计

| Topic | 消费者 | 消费组 | 延迟级别 |
|---|---|---|---|
| `seckill-order-topic` | SeckillOrderConsumer | seckill-order-consumer-group | 无 |
| `order-timeout-topic` | OrderTimeoutListener | order-timeout-consumer-group | delayLevel=4 |
| `pay-success-topic` | PaymentSuccessConsumer | pay-success-consumer-group | 无 |

### 3.4 订单状态流转

```
1 (待支付) ──支付──► 2 (已支付)
     │
     └──超时──► 4 (已取消) + 库存回滚
```

### 3.5 新增/修改文件

| 文件 | 操作 | 说明 |
|---|---|---|
| `MqConstants.java` | 改 | 新增 `ORDER_TIMEOUT_TOPIC`、`ORDER_TIMEOUT_CONSUMER_GROUP` |
| `VoucherOrderServiceImpl.java` | 改 | 新增延迟消息发送逻辑 |
| `SeckillOrderConsumer.java` | 改 | 添加 `DuplicateKeyException` 幂等保护 |
| `OrderTimeoutListener.java` | 新 | 超时关单 + 库存回滚（MySQL + Redis） |
| `PaymentController.java` | 改 | `@RequestMapping` 改为 `/api/pay`；新增 `POST /api/pay/callback` 直接支付回调 |

---

## 四、点赞 Write-Behind 优化

### 4.1 背景

此前 `BlogServiceImpl.likeBlog()` 每次点赞/取消点赞都同步执行 MySQL `UPDATE`：
```java
update().setSql("liked=liked+1").eq("id", id).update(); // 每次点赞都写DB
```
高并发下 MySQL 成为瓶颈。

### 4.2 Write-Behind 架构

```
用户点赞 / 取消点赞
    │
    ▼
BlogServiceImpl.likeBlog()  ← 纯 Redis，不写 MySQL
    │ 1. INCR/DECR  blog:like:count:{blogId}  (String 计数器)
    │ 2. ZADD/ZREM  blog:liked:{blogId}        (ZSet 点赞用户记录)
    │ 3. SADD       blog:like:changed           (Set 脏数据池)
    │
    ▼
XXL-JOB 定时任务 (likeSyncJob)
    │ SPOP blog:like:changed → 取出所有脏 blogId
    │ GET blog:like:count:{id} → 获取最新点赞数
    │ 批量写 MySQL:
    │   - upsertLikeCounts → tb_blog_like_count (ON DUPLICATE KEY UPDATE)
    │   - updateBlogLikedBatch → tb_blog.liked (CASE WHEN)
    │
    ▼
查询时: GET blog:like:count:{id} → 覆盖 blog.liked 字段
```

### 4.3 Redis 数据结构

| Key | 类型 | 说明 |
|---|---|---|
| `blog:liked:{blogId}` | ZSet | 点赞用户集合，score=时间戳（维持原有）|
| `blog:like:count:{blogId}` | String | 点赞总数计数器，INCR/DECR 操作 |
| `blog:like:changed` | Set | 脏数据池，存储发生过变更的 blogId |

### 4.4 改动文件

| 文件 | 操作 | 说明 |
|---|---|---|
| `RedisConstants.java` | 改 | 新增 `BLOG_LIKE_COUNT_KEY`、`BLOG_LIKE_CHANGED_KEY` |
| `BlogServiceImpl.java` | 改 | `likeBlog()` 改为纯 Redis 操作；`queryBlogById()` 从 Redis 读取点赞数 |
| `LikeSyncJobHandler.java` | 新 | `@XxlJob("likeSyncJob")` 定时刷盘处理器 |
| `BlogLikeSyncJob.java` | 删 | 旧版 Hash 扫描实现，已被 `LikeSyncJobHandler` 替代 |

### 4.5 XXL-JOB 配置

在 XXL-JOB Admin 中配置：
- JobHandler：`likeSyncJob`
- Cron：建议 `0 */5 * * * ?`（每 5 分钟执行一次）
- 执行器：`hmdp-like-executor`

---

## 五、中间件配置汇总

```yaml
# RocketMQ
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: hmdp-producer-group

# XXL-JOB
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8080/xxl-job-admin
    executor:
      appname: hmdp-like-executor
      port: 9999
      logpath: logs/xxl-job
      logretentiondays: 30

# Sentinel
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080
      eager: true
```

### Maven 依赖

```xml
<!-- Sentinel -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>

<!-- RocketMQ (已存在) -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.2.3</version>
</dependency>

<!-- XXL-JOB (已存在) -->
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
    <version>2.4.1</version>
</dependency>
```

BOM 管理：
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>2021.0.5.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```
