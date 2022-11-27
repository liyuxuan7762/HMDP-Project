package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // System.out.print("1");
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, id2 -> this.getById(id2), CACHE_NULL_TTL, TimeUnit.SECONDS, LOCK_SHOP_KEY);
        if (shop == null) {
            return Result.fail("商户ID不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("商铺ID不能为空");
        }
        // 1.更新数据库 P38讲解
        super.updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * @param typeId  商铺类型ID
     * @param current 页号
     * @param x       经度
     * @param y       纬度
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // TODO 如果没有位置坐标，则按照之前的查询方式查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = super.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 计算分页信息
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        // TODO 如果有位置坐标 则需要根据位置坐标到Redis中查询店铺的ID
        GeoResults<RedisGeoCommands.GeoLocation<String>> result = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(5, Metrics.KILOMETERS)), // 方圆5KM
                RedisGeoCommands.GeoRadiusCommandArgs.
                        newGeoRadiusArgs().
                        includeDistance(). // 查询结果包含距离
                        sortAscending(). // 按照距离从近到远
                        limit(end) // 分页 显示从第一条到第end条
        );

        // TODO 根据从redis中查询到的结果封装
        if (result == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = result.getContent();

        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> shopIdList = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(geoResult -> {
            String shopIdStr = geoResult.getContent().getName();
            shopIdList.add(Long.valueOf(shopIdStr));
            Distance distance = geoResult.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 根据shopId查询商铺信息

        String idsStr = StrUtil.join(",", shopIdList);
        List<Shop> shopList = super.query().in("id", shopIdList).last("order by field(id, " + idsStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }


    /**
     * 根据商铺ID重建该记录的Redis缓存
     *
     * @param id
     */
    public void rebuildCacheByShopId(Long id, Long expiredSecond) throws InterruptedException {
        RedisData redisData = new RedisData();
        // 模拟缓存重建延迟
        Thread.sleep(200);
        Shop shop = super.getById(id);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSecond));
        redisData.setData(shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
