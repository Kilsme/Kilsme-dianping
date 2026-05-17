# Kilsme Dianping 高并发改造说明

## 1. 架构概览（文本图）

```text
[Seckill API + Sentinel]
        |
        |-- BloomFilter(Redis Bitmap) 拦截非法 voucherId
        |-- Lua 原子校验库存&一人一单
        v
   [Outbox tb_mq_message]
        v (Scheduled Publisher)
   [RocketMQ topic: seckill-order-topic]
      | create tag                  | close tag(delay)
      v                             v
[订单创建消费者]                 [超时关单消费者]
      |                             |
      v                             v
[MySQL tb_voucher_order] <---- [补偿定时任务每1分钟对账]
      |
      v
[支付回调接口：状态机 0->1 幂等更新]
```

## 2. Before vs After

| 模块 | Before | After |
|---|---|---|
| 秒杀链路 | Redis Stream 本地消费，入口未限流/防穿透 | Bloom + Sentinel + Lua + RocketMQ + Outbox + 支付状态机 |
| 订单可靠性 | 下单消息未做本地消息表 | Outbox + 定时重试发布，消费端幂等（唯一约束/主键） |
| 支付/关单 | 无统一回调状态机与超时关单 | 回调按 `status=0` 更新为 `1`，延迟关单+库存回补 |
| 点赞模块 | 点赞立即写DB | Redis ZSet/Hash 写入，3分钟批量回刷DB |
| 评论模块 | 空实现 | 游标分页、两级评论树、敏感词过滤、热门评论缓存 |
| 韧性补偿 | 无主动补偿 | 1分钟未支付对账任务 + Outbox发布重试 |

## 3. 关键幂等策略

1. **Lua 原子性**：库存校验+扣减+一人一单在 Redis 一次执行。
2. **订单消费幂等**：`tb_voucher_order` 主键 `id` + `uk_user_voucher(user_id,voucher_id)`。
3. **支付回调幂等**：仅允许 `status=0 -> status=1`。
4. **Outbox 最终一致性**：`tb_mq_message` 先落库，定时发布成功后标记 SENT。

## 4. 本地运行依赖

- MySQL 5.7+（导入 `src/main/resources/db/hmdp.sql`）
- Redis 6+
- RocketMQ NameServer/Broker（默认 `127.0.0.1:9876`）
- Sentinel Dashboard（默认 `127.0.0.1:8858`）

配置见 `src/main/resources/application.yaml`：
- `spring.datasource.*`
- `spring.redis.*`
- `rocketmq.name-server`
- `rocketmq.producer.group`
- `sentinel.transport.dashboard`

## 5. SQL 变更

执行：
- `src/main/resources/sql/V20260517_01__seckill_like_comment_upgrade.sql`

包含：
- `tb_mq_message`（Outbox）
- `tb_blog_like`（点赞明细幂等）
- `tb_voucher_order` 唯一索引
- 评论分页/热评索引

## 6. 接口验证建议

### 6.1 秒杀
- `POST /voucher-order/seckill/{id}`
- 预期：快速返回 `success, queueing` 与 `orderId`

### 6.2 支付回调
- `POST /voucher-order/pay/callback`
```json
{"orderId":"17280001","payStatus":"SUCCESS","sign":"mock-sign"}
```

### 6.3 评论
- 新增：`POST /blog-comments`
- 游标分页：`GET /blog-comments?blogId=1&lastId=999999&size=10`
- 热评：`GET /blog-comments/hot?blogId=1`

### 6.4 点赞
- 点赞/取消：`PUT /blog/like/{id}`
- 定时任务每3分钟回刷到 MySQL（`tb_blog` 与 `tb_blog_like`）

## 7. 测试说明

当前仓库测试用例依赖本地 Redis 连接（`127.0.0.1:6379`）。在未启动 Redis 时，`mvn test` 会失败，属于环境依赖问题。
