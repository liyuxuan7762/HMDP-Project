package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 利用Redis自增实现全局唯一ID
 * 0 + 31位时间戳 + 32位序列号 满足1秒中同时下单2^32条订单
 */
@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static final Long BEGIN_TIMESTAMP = 1640995200L;

    public Long nextId(String prefixKey) {
        // 1.获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentTimeStamp = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentTimeStamp - BEGIN_TIMESTAMP;
        // 2.计算序列号
        // 这里需要说明一下Redis中保存这个自增值的键的命名 首
        // [1]先应该有前缀 "icr:" [2]业务名字，比如订单就是order
        // [3]日期 因为Redis单个键中值的最大值就是2^64 如果时间久了 肯定会超过这个数值，导致序列号无法继续自增
        // 因此可以每一天设置一个key 一天中订单不可能超过2^64
        // icr:order:2022:11:23 就是一个键，设置成这样的好处是可以根据月份，天和年进行分别查找，因为都是用：隔开了
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 序列号从0自增，每次增加1
        Long serialCode = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey + data);
        // 3.拼接结果 时间戳由于需要到高32位，因此使用位运算 << 向左移动32位
        // 那么此时时间戳的第32位都为0，然后进行和序列号按位与运算，可以将序列号拼接到时间戳的低32位中，最终形成id
        return timeStamp << 32 | serialCode;
    }

}
