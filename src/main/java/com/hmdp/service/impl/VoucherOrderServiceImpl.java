package com.hmdp.service.impl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没开始");
        }
        //3判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //4判断库存是否充足
        int stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象 锁住的是i当前用用户下单的用户 脚本小子
      //  SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        //使用redisson创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //但是synchronized锁的是方法所在的类的对象，而如果是分布式系统，多个服务实例，锁不住
//        synchronized (userId.toString().intern()) {//toString是创建一个全新的对象，要将其变为常量池中的对象使用intern
//        //    return createVoucherOrder(voucherId);//但是这个不是代理对象，所以事务失效
//            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();//带到动态代理对象
//            return o.createVoucherOrder(voucherId);
//        }
        //获取锁
      //  boolean isLock = simpleRedisLock.tryLock(1200);
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();//带到动态代理对象
            return o.createVoucherOrder(voucherId);
        }finally {
            //释放锁
         //   simpleRedisLock.unlock();
            lock.unlock();
        }
    }
    //为了解决这个问题，锁的范围必须大于事务的范围。即： 先加锁 -> 开启事务 -> 执行业务 -> 提交事务 -> 释放锁。
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

            //一人一单
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                //用户已经购买过了
                return Result.fail("用户已经购买过一次");
            }
            //扣减库存 使用乐观锁在减少库存的同时进行查询，保证线程安全
            boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                return Result.fail("库存不足");
            }
            //6创建订单
            long orderId = redisIdWorker.nextId("order");
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //7返回订单id
            return Result.ok(orderId);
    }
}