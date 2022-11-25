local key = KEYS[1]-- 使用这个key到redis读取标识 UUID + ThreadId
local threadId = ARGV[1] -- 传递过来的当前的 UUID + ThreadId

-- 从redis中根据key读取出id
local id = redis.call("get", key)
if(id == threadId) then
	-- 如果相等则释放锁
	return redis.call("del", key)
end

return 0;