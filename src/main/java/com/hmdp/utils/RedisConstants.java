package com.hmdp.utils;

import net.sf.jsqlparser.util.deparser.UpsertDeParser;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 1800L;

    public static final Long CACHE_NULL_TTL = 10L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    // 用于缓存店铺类型列表
    public static final String CACHE_SHOP_LIST = "cache:shopList";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
}
