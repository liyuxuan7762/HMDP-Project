package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * Redis工具类，用来实现对redis中的查询和写入
 * 提供解决缓存穿透和缓存击穿的方法
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    /**
     * 将任意Java类型的数据序列化成JSON并存储在String类型的key中，并可以设置TTL时间
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将任意Java类型的数据序列化成JSON并存储在String类型的key中，并可以设置逻辑过期时间，用于解决缓存击穿的问题
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, timeUnit);
    }

    /**
     * 根据指定的可key查询缓存，并反序列化成指定类型，利用缓存空对象方法解决缓存穿透问题
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        // TODO 首先根据ID查询Redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // TODO 如果查询到，判断是否为空对象，如果为空对象 则返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // TODO 如果查询到，且不为空对象，则直接返回该对象
        if (!json.equals("")) {
            return JSONUtil.toBean(json, type);
        }
        // TODO 如果查询不到，则去数据库查询
        R r = dbFallback.apply(id);
        // TODO 如果在数据库中查询不到，则返回空对象，并设置空对象的过期时间
        if (r == null) {
            this.set(key, "", time, timeUnit);
            return null;
        }
        // TODO 如果查询到，则将该对象保存到Redis中，并返回该对象
        this.set(key, JSONUtil.toJsonStr(r), time, timeUnit);
        return r;
    }

    /**
     * 根据指定的可key查询缓存，并反序列化成指定类型，利用逻辑过期方法解决缓存击穿问题
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit, String lockKeyPrefix) {
        // TODO 根据ID到redis中查询数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // TODO 如果查询不到，则直接返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // TODO 如果查询到，判断是否过期 如果没有过期，则直接返回
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // TODO 如果已经过期，则尝试获取锁 如果获取到，则创建新的线程来重建数据
        if (tryLock(lockKeyPrefix + id)) {
            // 获取锁成功
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 创建线程 查询数据库
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKeyPrefix + id);
                }
            });
        }
        // TODO 返回旧数据
        return r;
    }


    /**
     * 根据指定的可key查询缓存，并反序列化成指定类型，利用互斥锁方法解决缓存击穿问题
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @param lockKeyPrefix
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit, String lockKeyPrefix) {
        // TODO 1.根据ID到Redis中去查询数据
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        // TODO 2.如果查到了且不是空数据 则直接返回 isNotBlack ""返回false
        if (StrUtil.isNotBlank(json)) {
            // 将Json转化成对象返回
            return JSONUtil.toBean(json, type);
        }
        // TODO 3.判断redis中存储的是否是空对象 如果是空数据 返回错误信息
        if ("".equals(json)) {
            return null;
        }
        // TODO 4.如果没查到 则去获取锁，如果获取到锁，则查询数据库重建缓存，如果没有获取到锁，则等待后重新到缓存查询
        R r = null;
        try {
            if (!tryLock(keyPrefix + id)) {
                // 如果没有获取到锁，那么线程等待后重新调用该方法
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit, lockKeyPrefix);
            }
            // TODO 5.如果获取到锁，则去数据库查询
            r = dbFallback.apply(id);
            // TODO 6.数据库中如果没查到，则创建一个空对象存储到redis中
            if (r == null) {
                this.set(keyPrefix + id, "", time, timeUnit);
                return null;
            }
            // TODO 7.数据库中如果查到了，则将结果保存到Redis中
            this.set(keyPrefix + id, JSONUtil.toJsonStr(r), time, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // TODO 8.释放锁
            unlock(keyPrefix + id);
        }
        return r;
    }

    /**
     * 获取锁
     *
     * @param key
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

}
