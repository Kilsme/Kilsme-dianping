package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) throws InterruptedException {
        //缓存穿透
        //  Shop shop = queryWithPassThrough(id);
        // Shop shop=cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //缓存击穿
        // Shop shop = queryWithMutex(id);
        //用逻辑过期来解决
        //Shop shop = queryWithLoginExpire(id);
        Shop shop = cacheClient.queryWithLoginExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商户不存在");
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),30L,TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    //缓存穿透
    private Shop queryWithPassThrough(Long id) {
        //从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断存在
        if (!StrUtil.isBlank(shopJson)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            //缓存命中空值
            return null;
        }
        //不存在 根据id查询数据库
        Shop shop = getById(id);
        //不存在直接返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在返回redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    private Shop queryWithLoginExpire(Long id) throws InterruptedException {
        //从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断存在
        if (!StrUtil.isNotBlank(shopJson)) {
            //不存在
            return null;
        }
        //命中 需要进行判断过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //判断是否过期
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //已过期进行缓存重建
        //进行获取锁
        boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        if (lock) {
            //成功，开辟新线程进行缓存重建
            executor.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //失败   返回国企数据
        return shop;
    }

    private Shop queryWithMutex(Long id) throws InterruptedException {
        //从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断存在
        if (!StrUtil.isBlank(shopJson)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            //缓存命中空值
            return null;
        }
        //进行实现缓存重建
        //获取互斥锁
        Shop shop = null;
        try {
            boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            //判断是否成功
            if (!lock) {
                //失败,并且重试
                Thread.sleep(50);
                queryWithMutex(id);
                //失败获取锁，则休眠并重试
            }
            //获取锁成功，根据id查询数据库
            shop = getById(id);
            //不存在直接返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在返回redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        //返回
        return shop;
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

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1,查询店铺数据
        Shop shop = getById(id);
        //2,封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3,写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商户id不能为空");
        }
        //进行更新数据库 先更新数据库再删除缓存
        updateById(shop);
        //进行删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1,是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2，分页查询
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3,查询redis，按照距离排序，分页。结果中包含id和距离
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.
                                newGeoSearchArgs().includeDistance().
                                limit(end));
        //4解析id
        if (search == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        List<Long> ids = new ArrayList<>(content.size());
        if(from>=content.size()){
            return Result.ok();//防止跳到空页，返回空结果
        }
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            Long shopId = Long.valueOf(shopIdStr);
            ids.add(shopId);
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
       String idStr= StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            String distanceKey = shop.getId().toString();
            Distance distance = distanceMap.get(distanceKey);
            shop.setDistance(distance.getValue());
        }
        //返回结果
        return Result.ok(shops);

    }
}
