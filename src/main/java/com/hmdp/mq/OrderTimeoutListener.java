package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 超时关单消费者
 * 监听 ORDER_TIMEOUT_TOPIC 的延迟消息，检查订单是否超时未支付
 * 若订单仍为"待支付"状态，则取消订单并回滚库存（MySQL + Redis）
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = MqConstants.ORDER_TIMEOUT_TOPIC,
        consumerGroup = MqConstants.ORDER_TIMEOUT_CONSUMER_GROUP
)
public class OrderTimeoutListener implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(SeckillOrderMessage message) {
        if (message == null || message.getOrderId() == null) {
            return;
        }
        Long orderId = message.getOrderId();
        Long voucherId = message.getVoucherId();
        Long userId = message.getUserId();

        // 查询订单当前状态
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            log.warn("超时关单：订单不存在, orderId={}", orderId);
            return;
        }
        // 只有"待支付"状态（status=1）才需要关单
        if (order.getStatus() != null && order.getStatus() != 1) {
            log.info("超时关单：订单状态已变更，跳过, orderId={}, status={}", orderId, order.getStatus());
            return;
        }

        // 更新订单状态为"已取消"（status=4）
        boolean updated = voucherOrderService.update()
                .set("status", 4)
                .eq("id", orderId)
                .eq("status", 1)  // 乐观锁：仅当仍为待支付时才更新
                .update();
        if (!updated) {
            log.info("超时关单：订单状态已被并发修改，跳过, orderId={}", orderId);
            return;
        }

        // 回滚 MySQL 库存：tb_seckill_voucher.stock + 1
        boolean stockRollback = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .update();
        if (stockRollback) {
            log.info("超时关单：MySQL库存回滚成功, voucherId={}", voucherId);
        } else {
            log.error("超时关单：MySQL库存回滚失败, voucherId={}", voucherId);
        }

        // 回滚 Redis 预扣库存
        String stockKey = "seckill:stock:" + voucherId;
        String orderKey = "seckill:order:" + voucherId;
        stringRedisTemplate.opsForValue().increment(stockKey);
        stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());

        log.info("超时关单完成, orderId={}, voucherId={}, userId={}", orderId, voucherId, userId);
    }
}
