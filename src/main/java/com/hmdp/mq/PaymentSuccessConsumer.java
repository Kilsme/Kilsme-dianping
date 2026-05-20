package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = MqConstants.PAY_SUCCESS_TOPIC,
        consumerGroup = MqConstants.PAY_SUCCESS_CONSUMER_GROUP
)
public class PaymentSuccessConsumer implements RocketMQListener<PaymentSuccessMessage> {

    private final VoucherOrderServiceImpl voucherOrderService;

    public PaymentSuccessConsumer(VoucherOrderServiceImpl voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    public void onMessage(PaymentSuccessMessage message) {
        if (message == null || message.getOrderId() == null) {
            return;
        }
        boolean updated = voucherOrderService.update()
                .set("status", 2)
                .set("pay_type", message.getPayType())
                .set("pay_time", LocalDateTime.now())
                .eq("id", message.getOrderId())
                .eq("status", 1)
                .update();
        if (!updated) {
            log.info("支付回调忽略或重复处理, orderId={}", message.getOrderId());
        }
    }
}

