package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
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
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 实现优惠券的秒杀
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
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
        Long userId = UserHolder.getUser().getId();
        // 使用userId作为锁 保证userId一样的锁
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.saleVoucher(voucherId);
        }
    }

    /**
     * 实现秒杀优惠券核心方法
     *
     * @param voucherId
     * @return
     */
    @Transactional
    public Result saleVoucher(Long voucherId) {
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

}
