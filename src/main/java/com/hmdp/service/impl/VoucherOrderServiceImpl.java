package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
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
    private IVoucherOrderService proxy;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//创建xgroup create stream.orders g1 0 mkstream 发送消息组
    @PostConstruct//当前类初始化完成后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建后台线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    //创建一个内部类处理订单
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //获取stream消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费组和消费者
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //获取失败继续下一次循环
                    if (read == null || read.isEmpty()) {
                        continue;
                    }
                    //成功,解析消息中的订单信息
                    MapRecord<String, Object, Object> record = read.get(0);
                    //成功创建订单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ack确定 sack
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    //获取stream pnedinglist消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费组和消费者
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //pendinglist中没有消息，结束循环
                    if (read == null || read.isEmpty()) {
                        break;
                    }
                    //成功,解析消息中的订单信息
                    MapRecord<String, Object, Object> record = read.get(0);
                    //成功创建订单
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //ack确定 sack
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (Exception ex) {
                        log.error("休眠异常", ex);
                    }
                }
            }
        }
//    //创建阻塞队列
//    private BlockingQueue<VoucherOrder>orderTask=new ArrayBlockingQueue<>(1024*1024);
//
//    //创建一个内部类处理订单
//    private  class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    //获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTask.take();//发生异常会导致消息丢失
//                    //创建订单
//                   handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                  log.error("处理订单异常",e);
//                }
//            }
//        }

            private void handleVoucherOrder (VoucherOrder voucherOrder){
                Long userId = voucherOrder.getUserId();
                //创建锁对象
                RLock lock = redissonClient.getLock("lock:order:" + userId);
                //获取锁
                boolean lock1 = lock.tryLock();
                if (!lock1) {
                    //获取锁失败
                    log.error("不允许重复下单");
                    return;
                }
                try {
                    //获取代理对象
                    //但是线程池中的线程不是被spring管理的，所以不能用AopContext获取
                    //     proxy = (IVoucherOrderService) AopContext.currentProxy();
                    //调用创建订单方法
                    proxy.createVoucherOrder(voucherOrder);
                } finally {
                    //释放锁
                    lock.unlock();
                }
            }
        }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //执行lua脚本
        //获取用户
        UserDTO user = UserHolder.getUser();
        long orderId = redisIdWorker.nextId("order");//获取订单id
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
        //是0有购买资格，把下单信息保存到队列中
        //返回订单id
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //执行lua脚本
//        //获取用户
//        UserDTO user = UserHolder.getUser();
//        Long execute = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                user.getId().toString()
//        );
//        //判断结果是否为0
//        int r=execute.intValue();
//        if(r!=0){
//            //不为0没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //是0有购买资格，把下单信息保存到队列中
//
//        long orderId = redisIdWorker.nextId("order");
//        Long userId = user.getId();
//        //6创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTask.add(voucherOrder);
//        //获取代理对象
//
//        //返回订单id
//         return Result.ok(orderId);
//    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还没开始");
//        }
//        //3判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //4判断库存是否充足
//        int stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象 锁住的是i当前用用户下单的用户 脚本小子
//      //  SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        //使用redisson创建锁对象
//        RLock lock = redissonClient.getLock("lock:order:" + userId);


    /// /        //但是synchronized锁的是方法所在的类的对象，而如果是分布式系统，多个服务实例，锁不住
    /// /        synchronized (userId.toString().intern()) {//toString是创建一个全新的对象，要将其变为常量池中的对象使用intern
    /// /        //    return createVoucherOrder(voucherId);//但是这个不是代理对象，所以事务失效
    /// /            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();//带到动态代理对象
    /// /            return o.createVoucherOrder(voucherId);
    /// /        }
//        //获取锁
//      //  boolean isLock = simpleRedisLock.tryLock(1200);
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();//带到动态代理对象
//            return o.createVoucherOrder(voucherId);
//        }finally {
//            //释放锁
//         //   simpleRedisLock.unlock();
//            lock.unlock();
//        }
//    }
    //为了解决这个问题，锁的范围必须大于事务的范围。即： 先加锁 -> 开启事务 -> 执行业务 -> 提交事务 -> 释放锁。
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//
//        //一人一单
//        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            //用户已经购买过了
//            return Result.fail("用户已经购买过一次");
//        }
//        //扣减库存 使用乐观锁在减少库存的同时进行查询，保证线程安全
//        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //6创建订单
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        //7返回订单id
//        return Result.ok(orderId);
//    }
    @Transactional
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
        ///创建订单
        save(voucherOrder);
    }
}