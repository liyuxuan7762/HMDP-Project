package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 加载秒杀的异步脚本
    private static DefaultRedisScript<Long> SECKILL_SCRIPT;

    // 使用消息队列代替阻塞队列后Lua脚本
    private static DefaultRedisScript<Long> SECKILL_SCRIPT_WITH_MQ;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_SCRIPT_WITH_MQ = new DefaultRedisScript<>();
        SECKILL_SCRIPT_WITH_MQ.setLocation(new ClassPathResource("seckillWithMQ.lua"));
        SECKILL_SCRIPT_WITH_MQ.setResultType(Long.class);
    }

    // 用于向数据库写入订单信息的阻塞队列
    private BlockingQueue<VoucherOrder> ordertasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 创建一个线程任务 用于从阻塞队列中取出订单，并写入数据库中 适用于使用阻塞队列方法
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 从阻塞队列中取出订单
                try {
                    VoucherOrder order = ordertasks.take();
                    handlerVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    /**
     * 创建一个线程任务 用于从消息队列中取出订单，并写入数据库中 适用于使用消息队列方法
     */
    private class VoucherOrderHandlerWithMQ implements Runnable {
        String queueName = "stream.orders";

        /**
         * 负责一直从消息队列中取消息
         */
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.从消息队列中一条消息 XREADGROUP g1 c1 Count 1 block 2000 STREAMS stream.orders >
                    // 返回list原因是可能一次读取多个消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            // ReadOffset.lastConsumed代表 >
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 当前无消息 继续循环
                        continue;
                    }
                    // 3.执行下单业务
                    // 解析消息 封装成对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 获取键值对 这个就是我们保存的对象
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单 将结果保存到数据库
                    handlerVoucherOrder(voucherOrder);
                    // 4.发送ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常");
                    handPendingList();
                }
            }
        }

        private void handPendingList() {
            while (true) {
                try {
                    // 1.从消息队列的pendingList中一条消息 XREADGROUP g1 c1 Count 1  STREAMS stream.orders 0
                    // 返回list原因是可能一次读取多个消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            // ReadOffset.lastConsumed代表 >
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // pending-List中没有消息，那么所有消息都已经正常接收 退出当前循环
                        break;
                    }
                    // 3.执行下单业务
                    // 解析消息 封装成对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    // 获取键值对 这个就是我们保存的对象
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单 将结果保存到数据库
                    handlerVoucherOrder(voucherOrder);
                    // 4.发送ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    try {
                        log.error("处理pendingList异常");
                        // 暂停20ms防止处理太频繁 类似于 block 2000
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /**
     * 当构造方法执行完毕后将监听阻塞队列的方法运行起来
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        // 使用消息队列实现秒杀 由于Windows版本的Redis没有Stream队列 因此这里我们还是使用之前的基于阻塞队列的方式实现秒杀
        // SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandlerWithMQ());
    }

    // 当前类的代理类对象
    IVoucherOrderService proxy = null;

    /**
     * 实现优惠券的秒杀 使用分布式锁的方式解决
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucherWithLock(Long voucherId) {
        // TODO 用户提交优惠券ID 查看该优惠券是否存在 如果不存在返回错误信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        // TODO 查询优惠券售卖是否已经开始 如果不在返回错误信息
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没有开始");
        }
        // TODO 查询优惠券售卖是否已经结束 如果不在返回错误信息
        if (!endTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还已经结束");
        }
        // TODO 查询优惠券是否还有库存 如果没有返回错误信息
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("卖光了！");
        }

        // 手动获取锁 这里锁的key是order+用户id，因为只有相同的用户才需要获取锁，不同的用户不需要进行判断
        // 这里的锁的粒度是用户级别的
        Long userId = UserHolder.getUser().getId();
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 如果获取锁失败，说明已经有一个订单计入到了生成订单阶段，也就是已经下单成功了
            // 因此返回一个人只能购买一次
            // 注意 这里的一人一单并不能只通过这个锁来取保证
            // 还需要在saleVoucher方法中查询数据库订单表是否有该记录的存在来最终判断
            // 因为即使获取到锁，saleVoucher方法不一定执行成功，可
            return Result.fail("一个人只能下单一次");
        }
        // 如果获取锁成功，则下单
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.saleVoucherWithLock(voucherId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 实现秒杀优惠券核心方法
     *
     * @param voucherId
     * @return
     */
    @Transactional
    @Override
    public Result saleVoucherWithLock(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // TODO 根据当前的用户ID和优惠券ID到订单表中查询是否已经存在，如果已经存在则返回错误信息 保证一人一单
        int count = this.query().eq("user_Id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("每人限购一件");
        }
        // TODO 库存-1
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if (!success) {
            return Result.fail("卖光了！");
        }
        // TODO 创建订单对象
        VoucherOrder order = new VoucherOrder();
        order.setVoucherId(voucherId);
        order.setUserId(userId);
        Long id = redisIdWorker.nextId("order");
        order.setId(id);
        // TODO 将订单写入到表中
        super.save(order);
        // TODO 返回订单ID
        return Result.ok(id);
    }

    /**
     * 使用Lua脚本+阻塞队列异步下单
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // TODO 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString()
        );
        // TODO 判断结果
        // 如果不是0 返回错误信息
        int r = result.intValue();
        if (r != 0) {
            // 下单失败
            return Result.fail(r == 1 ? "库存不足" : "一个用户只能下一单");
        }
        // 如果为0 则将下单信息存入阻塞队列
        // TODO 保存到阻塞队列 创建阻塞队列 创建线程池 创建线程任务，在初始化时将线程任务提交到线程池中
        // 封装order对象
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);

        ordertasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();


        // TODO 返回订单ID
        return Result.ok(orderId);
    }

    /**
     * 用于将订单信息写入到数据库中的前置操作
     *
     * @param order
     */
    private void handlerVoucherOrder(VoucherOrder order) {
        // 获取用户ID 由于执行这个方法是一个全新的线程，不是主线程
        // 因此用户ID不可以从ThreadLocal中获取，这里从order中获取
        // TODO 实际上用户重复下单在Redis中已经判断过了，并且负责写入数据库的线程是单线程，因此这里使用锁是非必要的
        Long userId = order.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 将订单保存到数据库
            this.saleVoucher(order);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    @Override
    public void saleVoucher(VoucherOrder order) {
        Long userId = order.getUserId();
        // TODO 根据当前的用户ID和优惠券ID到订单表中查询是否已经存在，如果已经存在则返回错误信息 保证一人一单
        int count = this.query().eq("user_Id", userId).eq("voucher_id", order).count();
        if (count > 0) {
            log.error("每人限购一件");
        }
        // TODO 库存-1
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", order)
                .gt("stock", 0).update();
        if (!success) {
            log.error("卖光了！");
        }
        // TODO 创建订单对象
        super.save(order);
        // TODO 返回订单ID
    }

    /**
     * 使用消息队列解决秒杀问题
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucherWithMQ(Long voucherId) {
        Long orderId = redisIdWorker.nextId("order");
        // TODO 执行Lua脚本 脚本内容是判断购买资格 如果有资格就将订单信息保存到消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId)
        );
        // TODO 判断结果
        // 如果不是0 返回错误信息
        int r = result.intValue();
        if (r != 0) {
            // 下单失败
            return Result.fail(r == 1 ? "库存不足" : "一个用户只能下一单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // TODO 返回订单ID
        return Result.ok(orderId);
    }

}
