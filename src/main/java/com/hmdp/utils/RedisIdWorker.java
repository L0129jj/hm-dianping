package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //这里使用2026-01-01-00-00作为初始时间戳
    private static final long BEGIN_STAMP=1767225600L;
    private static final int COUNTER_BITS=32;
    private final StringRedisTemplate  stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Long nextId(String keyPrefix) {
        //1.生成现在的时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_STAMP;

        //2.序列化
        //2.1.获取当前日期，将其作为redis key的一部分
        String date=now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        //2.2.使用redis的自增写入redis key
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        return timeStamp << COUNTER_BITS | count;
    }

}
