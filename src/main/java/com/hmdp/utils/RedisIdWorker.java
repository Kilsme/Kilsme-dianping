package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.swing.text.DateFormatter;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //开始时间戳 2024-01-01 00:00:00
    private static final long BEGIN_TIMESTAMP = 1704067200L;
    //序列号位数
    private static final int COUNT_BITS=32;
     public long nextId(String keyPrefix) {
         //生成时间戳
            long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            long timestamp = now - BEGIN_TIMESTAMP;
         //生成序列号
         //获取当前日期，请确到天
         String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
         Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
         //拼接返回
         return (timestamp << COUNT_BITS) | count;
     }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
