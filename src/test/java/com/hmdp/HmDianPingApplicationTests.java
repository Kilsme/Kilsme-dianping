package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
     private ShopServiceImpl shopService;
    @Resource
     private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
     void test() throws InterruptedException {
         shopService.saveShop2Redis(1L,10L);
     }
     private ExecutorService es=java.util.concurrent.Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }
    @Test
    void loadShopData() {
       //查询所有店铺信息
        List<Shop> list = shopService.list();
        //通过typeId分组  id一致的放到同一个个集合中
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>>locations=new ArrayList<>(value.size());
            //写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
              //stringRedisTemplate.opsForGeo().add(key, new org.springframework.data.geo.Point(shop.getX(), shop.getY()), shop.getId().toString()); //因为每次写入redis都要进行网络通信，所以我们可以将所有的店铺信息先封装成一个list集合，然后一次性写入redis中，减少网络通信的次数，提高效率
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new org.springframework.data.geo.Point(shop.getX(), shop.getY())));
            }//批量进行添加
            stringRedisTemplate.opsForGeo().add(key,locations);

        }

     }
     //1515192   1579136
     @Test
    void testHyperLogLog(){
        String[] values=new String[1000];
        int j=0;
        for(int i=0;i<1000000;i++){
            j=i%1000;
            values[j]="user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hll1",values);
            }
        }
            long size = stringRedisTemplate.opsForHyperLogLog().size("hll1");
            System.out.println("size = " + size);

     }
}
