package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.MqMessage;
import com.hmdp.mapper.MqMessageMapper;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class OutboxPublisherTask {

    @Resource
    private MqMessageMapper mqMessageMapper;
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Scheduled(fixedDelay = 5000)
    public void publishUnsentMessages() {
        List<MqMessage> messages = mqMessageMapper.selectList(new LambdaQueryWrapper<MqMessage>()
                .eq(MqMessage::getStatus, 0)
                .le(MqMessage::getNextRetryTime, LocalDateTime.now())
                .last("limit 50"));
        for (MqMessage message : messages) {
            try {
                voucherOrderService.publishOutboxMessage(message);
            } catch (Exception e) {
                log.error("publish outbox message failed, id={}", message.getId(), e);
                message.setRetryCount((message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1);
                message.setNextRetryTime(LocalDateTime.now().plusSeconds(30));
                mqMessageMapper.updateById(message);
            }
        }
    }
}
