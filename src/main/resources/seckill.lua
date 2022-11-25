-- 1.参数
-- 优惠券ID
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]

-- 2.key
-- 库存
-- stockKey String类型 以优惠券作为key，以库存数值作为value
local stockKey = "seckill:stock:" .. voucherId

-- orderKey Set类型，以优惠券作为key，以购买过这个优惠券的用户ID的列表作为值
local orderKey = "seckill:order:" .. voucherId

-- 3.业务
-- 3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 3.2 判断用户是否已经下过单
if (redis.call("sismember", orderKey, userId) == 1) then
    -- 重复下单
    return 2
end

-- 3.3 扣库存 下单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
