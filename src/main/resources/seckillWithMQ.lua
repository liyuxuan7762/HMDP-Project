-- 1.参数
-- 优惠券ID
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 订单ID 由Java传入
local orderId = ARGV[3]

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

-- 3.3 将订单信息保存到消息队列中
redis.call('XADD', 'steam.orders', '*', 'userId', userId, 'voucherId', voucherId, "id", orderId)
return 0


-- 创建消息队列的命令
-- XGROUP CREATE stream.orders g1 0 MKSTREAM