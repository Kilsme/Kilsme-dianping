package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = MqConstants.SECKILL_ORDER_TOPIC,
        consumerGroup = MqConstants.SECKILL_ORDER_CONSUMER_GROUP
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    private final VoucherOrderServiceImpl voucherOrderService;

    public SeckillOrderConsumer(VoucherOrderServiceImpl voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    public void onMessage(SeckillOrderMessage message) {
        if (message == null || message.getOrderId() == null) {
            return;
        }
        // 幂等性检查：如果订单已存在则跳过
        VoucherOrder existing = voucherOrderService.getById(message.getOrderId());
        if (existing != null) {
            return;
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        try {
            voucherOrderService.createVoucherOrderWithLock(voucherOrder);
        } catch (DuplicateKeyException e) {
            // 极端并发下主键冲突，说明订单已被其他消费者创建，忽略即可
            log.warn("订单已存在（主键冲突），幂等跳过, orderId={}", message.getOrderId());
        }
    }
}

