package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.annotation.SelectorType;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@RocketMQMessageListener(
        topic = VoucherOrderServiceImpl.SECKILL_TOPIC,
        selectorType = SelectorType.TAG,
        selectorExpression = VoucherOrderServiceImpl.SECKILL_CLOSE_TAG,
        consumerGroup = "seckill-order-close-group"
)
public class SeckillOrderCloseConsumer implements RocketMQListener<String> {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Override
    public void onMessage(String message) {
        voucherOrderService.consumeCloseMessage(message);
    }
}
