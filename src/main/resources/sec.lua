local voucherId = ARGV[1]
local userId    = ARGV[2]
local orderId = ARGV[3]

local stockKey  = 'seckill:stock:' .. voucherId
local orderKey  = 'seckill:order:' .. voucherId

-- 1) 读取库存；key 不存在时返回 0
local stock = tonumber(redis.call('get', stockKey) or '0')
if stock <= 0 then
    return 1              -- 库存不足
end

-- 2) 一人一单校验
if redis.call('sismember', orderKey, userId) == 1 then
    return 2              -- 已经下过单
end

-- 3) 扣减库存并记录下单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
-- 4) 发送消息到队列
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0                  -- 下单成功