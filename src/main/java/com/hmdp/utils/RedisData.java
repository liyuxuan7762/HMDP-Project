package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在使用逻辑过期时间解决缓存击穿问题中用来封装数据
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
