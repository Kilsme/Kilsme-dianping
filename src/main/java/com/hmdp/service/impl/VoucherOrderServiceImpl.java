package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.MqConstants;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //执行lua脚本
        UserDTO user = UserHolder.getUser();
        long orderId = redisIdWorker.nextId("order");
        Long execute = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString(),
                String.valueOf(orderId)
        );
        //判断结果是否为0
        int r = execute.intValue();
        if (r != 0) {
            //不为0没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 发送秒杀订单创建消息（异步落库）
        SeckillOrderMessage message = new SeckillOrderMessage(orderId, user.getId(), voucherId);
        try {
            rocketMQTemplate.syncSend(MqConstants.SECKILL_ORDER_TOPIC, message);
            // 发送延迟消息到超时关单 Topic，delayLevel=4（约15分钟后检查订单状态）
            // 注意：延迟发送需要 org.springframework.messaging.Message 类型，POJO 需用 MessageBuilder 包装
            rocketMQTemplate.syncSend(MqConstants.ORDER_TIMEOUT_TOPIC,
                    MessageBuilder.withPayload(message).build(), 3000, 4);
        } catch (Exception e) {
            // 发送失败，回滚 Redis 预扣库存与下单标记
            rollbackSeckillReservation(voucherId, user.getId());
            log.error("发送秒杀订单消息失败", e);
            return Result.fail("下单失败，请重试");
        }
        return Result.ok(orderId);
    }

    private void rollbackSeckillReservation(Long voucherId, Long userId) {
        String stockKey = "seckill:stock:" + voucherId;
        String orderKey = "seckill:order:" + voucherId;
        stringRedisTemplate.opsForValue().increment(stockKey);
        stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
    }

    @Transactional
    public void createVoucherOrderWithLock(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = lock.tryLock();
        if (!locked) {
            log.warn("不允许重复下单");
            return;
        }
        try {
            createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //一人一单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            //用户已经购买过了
            log.error("用户已经购买过一次");
            return;
        }
        //扣减库存 使用乐观锁在减少库存的同时进行查询，保证线程安全
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        //创建订单
        voucherOrder.setStatus(1);
        save(voucherOrder);
    }
}