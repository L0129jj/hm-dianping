package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long>SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private static final String STREAM_KEY="stream.orders";
    private static final String GROUP_NAME="g1";
    private static final String CONSUMER_NAME="c1";
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 停机方法
     */
    @PreDestroy
    private void destroy(){
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdown();
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1) 读新消息（消费者组）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME,CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                    );

                    // 2) 没消息就下一轮
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    // 3) 处理消息
                    MapRecord<String, Object, Object> record = list.getFirst();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);

                    // 4) 处理成功后 ack确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                }
                log.error("处理新消息异常，开始处理pending-list", e);
                handlePendingList();
            }
        }
    }
        private void handlePendingList() {
            while (true){
                try {
                    //1.处理队列中的订单 XREADGROUP g1 c1 count1 block 2000 STREAMS stream_orders
                    List<MapRecord<String, Object,Object>>list=stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                    );
                    //2.判断消息是否获取成功
                    if (list==null||list.isEmpty()) {
                        //如果获取失败,说明pending-list没有异常消息，结束循环
                        break;
                    }
                    //解析订单中的信息
                    MapRecord<String,Object,Object> record=list.getFirst();
                    Map<Object,Object> value=record.getValue();
                    VoucherOrder voucherOrder= BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                    //确认信息
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY,GROUP_NAME,record.getId());
                } catch (Exception e) {
                    if (!running) {
                        break; //正在退出系统
                    }
                    log.error("处理pending-list异常",e);
                    try {
                        Thread.sleep(200);
                    }catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单
        Long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //2.判断是否为0
        int r = result.intValue();
        //2.1 不为0，代表没有购买资格
        if (r!=0) {
            return Result.fail(r==1?"已售罄":"不能重复下单");
        }
        //2.2 为0，有购买资格
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //2.3. 用户id
//        voucherOrder.setUserId(userId);
//        //2.4. 订单id
//        voucherOrder.setId(orderId);
//        //2.5. 优惠券id
//        voucherOrder.setVoucherId(voucherId);
//        //2.6保存到阻塞队列
//        orderTasks.add(voucherOrder);
        //3.获取代理对象
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        //4. 返回订单id
        return Result.ok(orderId);
//        //1.查询优惠券信息
//        SeckillVoucher skVoucher=seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        LocalDateTime now=LocalDateTime.now();
//        LocalDateTime beginTime = skVoucher.getBeginTime();
//        LocalDateTime endTime= skVoucher.getEndTime();
//        if (now.isBefore(beginTime)) {
//            return Result.fail("秒杀未开始！");
//        }
//        //2.1.未开始，返回异常结果
//        if (now.isAfter(endTime)) {
//            return  Result.fail("秒杀已结束！");
//        }
//        //3.开始，判断库存是否充足
//        Integer stock = skVoucher.getStock();
//        //3.1无库存，返回异常结果
//        if (stock<1) {
//            return Result.fail("已抢光！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////      SimpleRedisLock lock=new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            // 获取当前类的代理对象，确保事务生效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
    }

    @Resource
    private RedissonClient redissonClient;


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //实现一人一单
        Long userId = voucherOrder.getUserId();
        //获取下单次数
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count>0) {
            log.error("该用户已达下单上限！");
            return;
        }
        //5.扣减库存
        boolean success=seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }
}
