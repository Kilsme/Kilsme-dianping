package com.hmdp.controller;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @SentinelResource(value = "seckill", blockHandler = "seckillBlockHandler")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * Sentinel 限流/降级兜底方法
     * 当秒杀接口触发流控或降级规则时，返回友好提示
     *
     * @param voucherId 优惠券id
     * @param ex        限流/降级异常
     * @return 限流提示结果
     */
    public Result seckillBlockHandler(Long voucherId, BlockException ex) {
        return Result.fail("活动太火爆，请稍后再试");
    }
}
