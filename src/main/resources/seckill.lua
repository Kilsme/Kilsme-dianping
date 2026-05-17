--参数列表
local voucherId=ARGV[1]
--用户id
local userId=ARGV[2]
--数据id
--库存key
local stockKey='seckill:stock:'..voucherId
--订单key
local orderKey='seckill:order:'..voucherId
--1.判断库存是否充足
if(tonumber(redis.call('get',stockKey))<=0) then
    return 1
end
--判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1) then
    return 2
end
--扣库存
redis.call('incrby',stockKey,-1)
--保存用户id到订单中
redis.call('sadd',orderKey,userId)
--3.返回0表示下单成功
return 0
