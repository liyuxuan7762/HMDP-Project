package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private static final String KEY_PREFIX = "lock:";
    // 使用final修饰 保证每一个集群中的一个tomcat只有一个UUID
    public static final String ID_PREFIX = UUID.randomUUID().toString(true);
    private StringRedisTemplate stringRedisTemplate;
    private String name; // 业务名称 在秒杀中就是"order"
    private static DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 尝试获取锁 利用Redis中的setnx命令
     *
     * @param timeout 防止Redis服务宕机 设置锁的超时时间，超时后自动释放锁
     * @return 是否获取锁成功
     */
    @Override
    public boolean tryLock(Long timeout) {
        // 获取线程ID 作为value
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 使用setnx命令写入redis
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    /**
     * 使用Lua脚本解锁
     */
    @Override
    public void unlock() {
        // 需要判断是否是自己加的锁，根据redis中lock对应的value来判断
        // value为UUID + threadId
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }


        // 使用Lua脚本实现解锁功能
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());

    }
}
