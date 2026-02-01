package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private  final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID>R  queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){//进行返回一段逻辑 ID是参数 R是返回值
        //从redis中查询商户缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断存在
        if(!StrUtil.isBlank(json)) {
            //存在 直接返回
            return JSONUtil.toBean(json, type);
        }
        if(json != null){
            //缓存命中空值
            return null;
        }
        //不存在 根据id查询数据库
        R r=dbFallback.apply(id);
        //不存在直接返回错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }
        //存在返回redis
        this.set(key, r, time, unit);
        return r;
    }
    private static final ExecutorService executor = Executors.newFixedThreadPool(10);
   public <R,ID>R queryWithLoginExpire(String keyPrefix,ID  id,Class<R> type,Function<ID,R>dbFallback,Long time, TimeUnit unit) throws InterruptedException {
       String key = keyPrefix + id;
       //从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断存在
        if(!StrUtil.isNotBlank(json)) {
            //不存在
            return null;
        }
        //命中 需要进行判断过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //判断是否过期
        JSONObject data = (JSONObject) redisData.getData();
        R r= JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //已过期进行缓存重建
        //进行获取锁
        boolean lock = tryLock(key+ id);
        if(lock){
            //成功，开辟新线程进行缓存重建
            executor.submit(()->{
                try{
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e){
                    log.error("设置缓存异常",e);
                }
                    finally
                 {
                    //释放锁
                    unLock(key);
                }
            });
        }
        //失败   返回国企数据
        return r;
    }
    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);//setnx操作只有一个能获取到她,设置有效期进行兜底
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
