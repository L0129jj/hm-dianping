-- 1.参数列表
-- 1.1优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId= ARGV[2]
-- 1.3 订单id
local orderId= ARGV[3]

-- 2.数据key
-- 2.1 库存
local stockKey='seckill:stock' .. voucherId
-- 2.2 订单
local orderKey='seckill:order' .. userId

-- 3.业务脚本
--3.1判断库存是否充足 get stockKey
if(toNumber(redis.call('get',stockKey))<=0) then
    --3.2库存不足，返回1
    return 1
end
--3.3 判断用户是否下单
if(redis.call('sismemeber',orderKey,userId)) then
--3.4 重复下单，返回2
    return 2
end

--3.5扣减库存
redis.call('incriby',stockKey,-1)
--3.6 将userId存入
redis.call('sadd',orderKey,userId)
-- 3.7.直接向消息队列发送消息
redis.call('xadd','stream.orders','*','voucherId',voucherId,'userId',userId,'orderId',orderId)



--  redis-cli XGROUP CREATE stream.orders g1 0 MKSTREAM
--   redis-cli XGROUP CREATECONSUMER stream.orders g1 c1
--   redis-cli XINFO GROUPS stream.orders
--   redis-cli XINFO CONSUMERS stream.orders g1