package com.hmdp.mq;

public final class MqConstants {
    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";
    public static final String PAY_SUCCESS_TOPIC = "pay-success-topic";
    public static final String ORDER_TIMEOUT_TOPIC = "order-timeout-topic";

    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer-group";
    public static final String PAY_SUCCESS_CONSUMER_GROUP = "pay-success-consumer-group";
    public static final String ORDER_TIMEOUT_CONSUMER_GROUP = "order-timeout-consumer-group";

    private MqConstants() {
    }
}

