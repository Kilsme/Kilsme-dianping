--比较线程标识与锁中是否一致
if(redis.call("get",KEYS[1])==ARGV[1]) then
    --一致则删除锁
    return redis.call("del",KEYS[1])
    --不一致则返回0
end
return 0