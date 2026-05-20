package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 基于 Redis Bitmap 的简易布隆过滤器
 * 用于解决缓存穿透问题（拦截数据库中绝对不存在的请求）
 */
@Component
public class RedisBloomFilterUtils {

    private static final int DEFAULT_SIZE = 1 << 24;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void add(String key, String value) {
        int[] offsets = hash(value);
        for (int offset : offsets) {
            stringRedisTemplate.opsForValue().setBit(key, offset, true);
        }
    }

    public boolean mightContain(String key, String value) {
        int[] offsets = hash(value);
        for (int offset : offsets) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(key, offset);
            if (Boolean.FALSE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    private int[] hash(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int h1 = 0;
        int h2 = 0;
        for (byte b : bytes) {
            h1 = h1 * 31 + b;
            h2 = h2 * 131 + b;
        }
        int h3 = h1 ^ (h2 << 1);
        return new int[]{Math.abs(h1) % DEFAULT_SIZE, Math.abs(h2) % DEFAULT_SIZE, Math.abs(h3) % DEFAULT_SIZE};
    }
}
