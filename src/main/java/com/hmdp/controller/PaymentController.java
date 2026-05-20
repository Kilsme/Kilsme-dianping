package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.MqConstants;
import com.hmdp.mq.PaymentSuccessMessage;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/pay")
public class PaymentController {
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @PostMapping("/mock/{orderId}")
    public Result mockPay(@PathVariable("orderId") Long orderId) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() != 1) {
            return Result.fail("订单状态不支持支付");
        }
        PaymentSuccessMessage message = new PaymentSuccessMessage(orderId, 1);
        rocketMQTemplate.syncSend(MqConstants.PAY_SUCCESS_TOPIC, message);
        return Result.ok();
    }

    /**
     * 支付回调接口（模拟）
     * 直接将订单状态更新为"已支付"，完成交易闭环
     *
     * @param orderId 订单id
     * @return 支付结果
     */
    @PostMapping("/callback")
    public Result payCallback(@RequestParam("orderId") Long orderId) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() != 1) {
            return Result.fail("订单状态不支持支付");
        }
        // 直接更新订单状态为"已支付"（status=2）
        boolean updated = voucherOrderService.update()
                .set("status", 2)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", 1)
                .update();
        if (!updated) {
            return Result.fail("支付失败，订单状态异常");
        }
        return Result.ok();
    }
}

