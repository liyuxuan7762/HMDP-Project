package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getTypeList() {
        // TODO 首先从Redis中查询根据type信息是否存在
        String typeListJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_LIST);
        // TODO 如果存在则直接返回
        if (StrUtil.isNotBlank(typeListJSON)) {
            return Result.ok(JSONUtil.toList(typeListJSON, ShopType.class));
        }
        // TODO 如果不存在则到数据库中查询
        List<ShopType> shopTypeList = super.query().orderByAsc("sort").list();
        // TODO 如果数据库查询不存在 则返回错误信息
        if (shopTypeList.isEmpty()) {
            return Result.fail("查询商户分类信息失败");
        }
        // TODO 如果数据库中查出来了 则将数据存储到Redis中
        // TODO 将查出的列表转化为JSON 存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_LIST, JSONUtil.toJsonPrettyStr(shopTypeList));
        // TODO 返回结果
        return Result.ok(shopTypeList);
    }
}
