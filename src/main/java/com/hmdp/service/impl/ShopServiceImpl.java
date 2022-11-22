package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // 线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // System.out.print("1");
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> this.getById(id2), CACHE_NULL_TTL, TimeUnit.SECONDS);
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
     * 使用空对象解决缓存穿透的商铺查询方法
     *
     * @param id
     * @return
     */
//    private Shop queryWithPassThrough(Long id) {
//        // TODO 根据ID到Redis中去查询数据
//        String shopJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        // TODO 如果查到了且不是空数据 则直接返回 isNotBlack ""返回false
//        if (StrUtil.isNotBlank(shopJSON)) {
//            // 将Json转化成对象返回
//            return JSONUtil.toBean(shopJSON, Shop.class);
//        }
//        // TODO 判断redis中存储的是否是空对象 如果是空数据 返回错误信息
//        if (Objects.equals(shopJSON, "")) {
//            return null;
//        }
//        // TODO 如果没查到 则去数据库查询
//        Shop shopObj = super.getById(id);
//        // TODO 数据库中如果没查到，则创建一个空对象存储到redis中
//        if (shopObj == null) {
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // TODO 数据库中如果查到了，则将结果保存到Redis中
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shopObj), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shopObj;
//    }

    /**
     * 使用互斥锁解决缓存击穿问题 (缓存穿透使用空对象法解决)
     *
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        // TODO 1.根据ID到Redis中去查询数据
        String shopJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // TODO 2.如果查到了且不是空数据 则直接返回 isNotBlack ""返回false
        if (StrUtil.isNotBlank(shopJSON)) {
            // 将Json转化成对象返回
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        // TODO 3.判断redis中存储的是否是空对象 如果是空数据 返回错误信息
        if (Objects.equals(shopJSON, "")) {
            return null;
        }
        // TODO 4.如果没查到 则去获取锁，如果获取到锁，则查询数据库重建缓存，如果没有获取到锁，则等待后重新到缓存查询
        Shop shopObj = null;
        try {
            if (!tryLock(id.toString())) {
                // 如果没有获取到锁，那么线程等待后重新调用该方法
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // TODO 5.如果获取到锁，则去数据库查询
            shopObj = super.getById(id);
            // TODO 6.数据库中如果没查到，则创建一个空对象存储到redis中
            if (shopObj == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // TODO 7.数据库中如果查到了，则将结果保存到Redis中
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shopObj), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // TODO 8.释放锁
            unlock(id.toString());
        }
        return shopObj;
    }

    /**
     * 使用逻辑过期方法解决缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id) {
        // TODO 1.根据id到redis中查询数据 如果查不到数据 返回空
        String shopJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isBlank(shopJSON)) {
            // 这里在缓存中查找不到就直接返回null是因为热点数据在使用之前肯定已经存在于缓存之中
            // 如果查找不到则返回null
            return null;
        }
        // TODO 2.如果查到数据，则需要判断数据是否过期
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())) {
            // TODO 3.如果数据没过期，则直接返回
            return shop;
        }
        // TODO 4.如果数据过期了，则需要创建一个信息线程查询数据库更新缓存
        if (tryLock(LOCK_SHOP_KEY + id)) {
            // TODO 4.1 获取锁 如果获取成功，则开辟新的线程查询数据库并更新到redis
            CACHE_REBUILD_EXECUTOR.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        rebuildCacheByShopId(id, 10L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        // TODO 4.2 释放锁
                        unlock(LOCK_SHOP_KEY + id);
                    }
                }
            });
        }
        // TODO 5.返回过期数据
        return shop;
    }

    /**
     * 获取锁
     *
     * @return
     */
    private boolean tryLock(String key) {
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lock);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(LOCK_SHOP_KEY + key);
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
