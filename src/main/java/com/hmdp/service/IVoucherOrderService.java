package com.hmdp.service;

import com.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucherWithLock(Long voucherId);

    Result saleVoucherWithLock(Long voucherId);

    Result seckillVoucher(Long voucherId);

    @Transactional
    void saleVoucher(VoucherOrder order);

    Result seckillVoucherWithMQ(Long voucherId);
}
