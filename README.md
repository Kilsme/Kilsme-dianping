# Kilsme Dianping 高并发改造说明（Seckill / Like / Comment）

> 分支：`master`  
> 核心改造：Redis Lua 秒杀原子校验 + Sentinel 限流 + Redis Bitmap Bloom 防穿透 + RocketMQ 异步下单  
> 可靠性：Outbox 本地消息表 + 定时发布重试 + 延迟关单 + 1 分钟对账补偿  
> 互动：点赞 Redis 热写 + 定时刷库；评论游标分页 + 两级评论树 + 敏感词过滤 + 热评缓存

---

## 1. 项目目标与改造动机

### 1.1 改造前（Before）
- 秒杀链路依赖 Redis Stream 在应用内消费，入口缺少 **限流 / 防穿透**，高并发下容易：
  - 流量打爆应用与 DB
  - 无法形成统一的消息可靠投递策略
- 点赞：同步写 DB，热点内容容易产生写入瓶颈与锁竞争
- 评论：缺少完整实现或缺少热点优化策略

### 1.2 改造后（After）
- 秒杀链路升级为：
  - **Sentinel**：QPS 限流、快速失败
  - **BloomFilter（Redis Bitmap）**：非法 voucherId 拦截（防穿透）
  - **Lua 原子脚本**：库存校验/扣减 + 一人一单
  - **RocketMQ**：异步创建订单（削峰、解耦）
  - **Outbox**：本地消息表确保最终一致性，可重试
  - **延迟关单 + 对账补偿**：保证订单最终一致与库存回补
- 点赞链路：Redis 热写 + 3 分钟定时批量刷库
- 评论链路：游标分页、两级评论树、敏感词过滤、热评缓存

---

## 2. 架构概览（文本图）

```text
[Seckill API + Sentinel]
        |
        |-- BloomFilter(Redis Bitmap) 拦截非法 voucherId
        |-- Lua 原子校验：库存 & 一人一单
        v
   [Outbox tb_mq_message]   (status=UNSENT)
        |
        | (OutboxPublisherTask: fixedDelay=5s 重试投递)
        v
   [RocketMQ topic: seckill-order-topic]
      | create tag                      | close tag(delay)
      v                                 v
[订单创建消费者]                    [超时关单消费者]
      |                                 |
      v                                 v
[MySQL tb_voucher_order]  <----  [closeUnpaidOrder 回滚库存 + 清理Redis购买标记]

[支付回调接口：状态机 0->1 幂等更新]
        ^
        |
[OrderReconciliationTask 每1分钟扫描未支付订单，mock查询支付后触发回调补偿]
```

---

## 3. 模块与代码位置（便于阅读源码）

### 3.1 秒杀核心
- 接口入口（限流 + 回调）
  - `src/main/java/com/hmdp/controller/VoucherOrderController.java`
- 秒杀业务（Lua + Bloom + Outbox + MQ + 幂等/关单/回滚）
  - `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- Lua 原子脚本（库存 + 一人一单）
  - `src/main/resources/seckill.lua`

### 3.2 RocketMQ 消费者（异步创建/延迟关单）
- `src/main/java/com/hmdp/mq/SeckillOrderCreateConsumer.java`
- `src/main/java/com/hmdp/mq/SeckillOrderCloseConsumer.java`
- 消息体：
  - `src/main/java/com/hmdp/dto/SeckillOrderMessage.java`

### 3.3 Outbox（本地消息表）与发布重试
- `src/main/java/com/hmdp/entity/MqMessage.java`
- `src/main/java/com/hmdp/mapper/MqMessageMapper.java`
- `src/main/java/com/hmdp/task/OutboxPublisherTask.java`

### 3.4 补偿/对账
- `src/main/java/com/hmdp/task/OrderReconciliationTask.java`

### 3.5 防穿透 BloomFilter
- `src/main/java/com/hmdp/utils/RedisBloomFilterUtils.java`
- Bloom 初始化与写入：
  - `src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`

### 3.6 点赞（Redis 热写 + 刷库）
- 热写：
  - `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
- 刷库：
  - `src/main/java/com/hmdp/task/BlogLikeFlushTask.java`

### 3.7 评论（游标分页/两级树/敏感词/热评）
- Controller：
  - `src/main/java/com/hmdp/controller/BlogCommentsController.java`
- Service：
  - `src/main/java/com/hmdp/service/impl/BlogCommentsServiceImpl.java`
- 敏感词：
  - `src/main/java/com/hmdp/utils/SensitiveWordFilter.java`
- 热评缓存刷新：
  - `src/main/java/com/hmdp/task/CommentHotCacheTask.java`

---

## 4. 关键数据结构（Redis / DB）

### 4.1 Redis Key
见 `src/main/java/com/hmdp/utils/RedisConstants.java`，本次新增/使用关键项包括：

- `seckill:stock:{voucherId}`：秒杀库存（String / number）
- `seckill:order:{voucherId}`：购买用户集合（Set，防重复）
- `seckill:bloom:voucher`：Bloom Bitmap key
- `blog:liked:{blogId}`：点赞用户（ZSet，score=时间戳）
- `blog:like_count`：点赞计数（Hash，field=blogId -> count）
- `blog:like:dirty`：脏数据集合（Set，存 blogId）
- `blog:hot_comments:{blogId}`：热评 ZSet（member=commentId, score=liked）

### 4.2 MySQL 表/索引变更
SQL 文件：
- `src/main/resources/sql/V20260517_01__seckill_like_comment_upgrade.sql`

包含：
- `tb_mq_message`：Outbox 消息表（支持状态、重试次数、下次重试时间）
- `tb_blog_like`：点赞明细表（`UNIQUE(blog_id,user_id)` 用于幂等）
- `tb_voucher_order`：新增唯一约束 `uk_user_voucher(user_id, voucher_id)` 防重复下单
- `tb_blog_comments`：新增索引用于游标分页与热评

---

## 5. 幂等与一致性策略（面试重点）

1. **Lua 原子性**：库存校验 + 扣减 + 一人一单在 Redis 一次完成，避免超卖与并发竞态
2. **订单消费幂等**：
   - DB 层唯一约束 `uk_user_voucher(user_id, voucher_id)`
   - 代码层：`getById(orderId)` 判断重复消息
3. **支付回调幂等**：仅允许 `status=0 -> status=1`（重复回调无副作用）
4. **Outbox 最终一致性**：
   - 先落库 `tb_mq_message`（UNSENT）
   - 定时任务重试发布 RocketMQ
   - 成功后标记 SENT；失败则延后重试/超过次数标记失败

---

## 6. 本地运行依赖与启动

### 6.1 依赖
- MySQL 5.7+（导入原始 `src/main/resources/db/hmdp.sql` 后再执行本次迁移 SQL）
- Redis 6+
- RocketMQ NameServer/Broker（默认 `127.0.0.1:9876`）
- Sentinel Dashboard（默认 `127.0.0.1:8858`）

### 6.2 配置
`src/main/resources/application.yaml` 增加了：
- `rocketmq.name-server`
- `rocketmq.producer.group`
- `sentinel.transport.dashboard`
- `sentinel.seckill.qps`
- `payment.callback.mock-sign`

可通过环境变量覆盖：
- `ROCKETMQ_NAME_SERVER`
- `SENTINEL_DASHBOARD`
- `SENTINEL_SECKILL_QPS`
- `PAYMENT_CALLBACK_SIGN`

---

## 7. 接口验证（curl 示例）

> 注意：部分接口依赖登录态（token）。如果你本地项目需要登录拦截，请先按原项目方式获取 token。

### 7.1 秒杀（快速返回排队）
```bash
curl -X POST "http://localhost:8081/voucher-order/seckill/1"
```
预期：
- 成功：返回 `orderId` + `success, queueing`
- 失败：库存不足/重复下单/限流/活动不存在

### 7.2 支付回调（幂等）
```bash
curl -X POST "http://localhost:8081/voucher-order/pay/callback" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"17280001","payStatus":"SUCCESS","sign":"mock-sign"}'
```

### 7.3 评论
新增评论：
```bash
curl -X POST "http://localhost:8081/blog-comments" \
  -H "Content-Type: application/json" \
  -d '{"blogId":1,"content":"hello","parentId":0}'
```

游标分页：
```bash
curl "http://localhost:8081/blog-comments?blogId=1&lastId=999999&size=10"
```

热评：
```bash
curl "http://localhost:8081/blog-comments/hot?blogId=1"
```

### 7.4 点赞
```bash
curl -X PUT "http://localhost:8081/blog/like/1"
```

---

## 8. 常见问题（Troubleshooting）

### 8.1 `mvn test` 失败
仓库测试用例依赖本地 Redis（默认 `127.0.0.1:6379`），未启动 Redis 会失败，属于环境依赖。

### 8.2 秒杀“活动不存在”
BloomFilter 会拦截不存在的 voucherId。请确认：
- 你已经初始化 Bloom（启动后会尝试加载秒杀券列表）
- 或在添加秒杀券时会写入 Bloom（见 `VoucherServiceImpl`）

### 8.3 RocketMQ 未消费/消息未投递
- 检查 `rocketmq.name-server` 配置与 NameServer/Broker 是否可用
- 检查 Outbox 表 `tb_mq_message`：status 是否从 0 变为 1
- 查看 `OutboxPublisherTask` 日志

---

## 9. 压测建议（可用于简历量化）
- 工具：JMeter / wrk / gatling
- 指标：QPS、RT(P95/P99)、成功率、库存一致性（是否超卖）、重复下单率（应为 0）
- 场景：单券高并发秒杀（1k/5k/10k 并发），观察 Sentinel 限流触发与系统稳定性

---

## 10. 更新记录
- 2026-05-17：合并 PR #1，完成秒杀链路、点赞/评论优化、Outbox/补偿任务与 README 完善
