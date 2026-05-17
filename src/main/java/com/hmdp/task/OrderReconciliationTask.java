package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OrderReconciliationTask {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Scheduled(cron = "0 */1 * * * ?")
    public void reconcileUnpaidOrder() {
        List<VoucherOrder> orders = voucherOrderMapper.selectList(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getStatus, 0)
                .lt(VoucherOrder::getCreateTime, LocalDateTime.now().minusMinutes(5))
                .last("limit 100"));
        for (VoucherOrder order : orders) {
            if (mockQueryPayment(order.getId())) {
                Map<String, String> callback = new HashMap<>(3);
                callback.put("orderId", order.getId().toString());
                callback.put("payStatus", "SUCCESS");
                callback.put("sign", "mock-sign");
                voucherOrderService.handlePaymentCallback(callback);
            }
        }
    }

    private boolean mockQueryPayment(Long orderId) {
        return orderId % 2 == 0;
    }
}
