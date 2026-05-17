package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.MqMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.MqMessageMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisBloomFilterUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    public static final String SECKILL_TOPIC = "seckill-order-topic";
    public static final String SECKILL_CREATE_TAG = "create";
    public static final String SECKILL_CLOSE_TAG = "close";
    private static final int CLOSE_ORDER_DELAY_LEVEL = 14;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisBloomFilterUtils redisBloomFilterUtils;
    @Resource
    private MqMessageMapper mqMessageMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Value("${payment.callback.mock-sign:mock-sign}")
    private String callbackMockSign;

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (!redisBloomFilterUtils.mightContain(RedisConstants.SECKILL_BLOOM_KEY, voucherId.toString())) {
            return Result.fail("活动不存在");
        }

        Long execute = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString()
        );
        int code = execute == null ? -1 : execute.intValue();
        if (code == 1) {
            return Result.fail("库存不足");
        }
        if (code == 2) {
            return Result.fail("不能重复下单");
        }
        if (code != 0) {
            return Result.fail("秒杀请求失败");
        }

        long orderId = redisIdWorker.nextId("order");
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(user.getId());
        message.setVoucherId(voucherId);
        saveOutbox(SECKILL_CREATE_TAG, JSONUtil.toJsonStr(message));
        saveOutbox(SECKILL_CLOSE_TAG, JSONUtil.toJsonStr(message));

        Map<String, Object> data = new HashMap<>(2);
        data.put("orderId", orderId);
        data.put("message", "success, queueing");
        return Result.ok(data);
    }

    private void saveOutbox(String tag, String body) {
        MqMessage mqMessage = new MqMessage();
        mqMessage.setTopic(SECKILL_TOPIC);
        mqMessage.setTag(tag);
        mqMessage.setBody(body);
        mqMessage.setStatus(0);
        mqMessage.setRetryCount(0);
        mqMessage.setNextRetryTime(LocalDateTime.now());
        mqMessageMapper.insert(mqMessage);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        if (getById(voucherOrder.getId()) != null) {
            return;
        }
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count != null && count > 0) {
            return;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            return;
        }
        voucherOrder.setStatus(0);
        save(voucherOrder);
    }

    @Transactional
    public void closeUnpaidOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return;
        }
        boolean closed = lambdaUpdate()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 0)
                .set(VoucherOrder::getStatus, 4)
                .update();
        if (!closed) {
            return;
        }
        seckillVoucherService.update().setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId());
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId(), order.getUserId().toString());
    }

    @Override
    @Transactional
    public Result handlePaymentCallback(Map<String, String> payload) {
        if (!verifySignature(payload)) {
            return Result.fail("签名校验失败");
        }
        String orderIdStr = payload.get("orderId");
        if (orderIdStr == null) {
            return Result.fail("缺少orderId");
        }
        String payStatus = payload.getOrDefault("payStatus", "SUCCESS");
        if (!"SUCCESS".equalsIgnoreCase(payStatus)) {
            return Result.ok("忽略非成功支付回调");
        }
        long orderId = Long.parseLong(orderIdStr);
        boolean updated = lambdaUpdate()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 0)
                .set(VoucherOrder::getStatus, 1)
                .set(VoucherOrder::getPayTime, LocalDateTime.now())
                .update();
        return Result.ok(updated ? "支付成功" : "回调已处理");
    }

    private boolean verifySignature(Map<String, String> payload) {
        String sign = payload.get("sign");
        return sign != null && callbackMockSign.equals(sign);
    }

    public void consumeOrderMessage(String body) {
        SeckillOrderMessage message = JSONUtil.toBean(body, SeckillOrderMessage.class);
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        createVoucherOrder(voucherOrder);
    }

    public void consumeCloseMessage(String body) {
        SeckillOrderMessage message = JSONUtil.toBean(body, SeckillOrderMessage.class);
        closeUnpaidOrder(message.getOrderId());
    }

    public void publishOutboxMessage(MqMessage message) {
        String destination = message.getTopic() + ":" + message.getTag();
        if (SECKILL_CLOSE_TAG.equals(message.getTag())) {
            rocketMQTemplate.syncSend(destination, MessageBuilder.withPayload(message.getBody()).build(), 3000, CLOSE_ORDER_DELAY_LEVEL);
        } else {
            rocketMQTemplate.convertAndSend(destination, message.getBody());
        }
        message.setStatus(1);
        message.setUpdateTime(LocalDateTime.now());
        mqMessageMapper.updateById(message);
    }
}
