package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    // 注入 StringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 封装逻辑过期对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis (逻辑过期不需要设置Redis物理TTL)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 3.判断命中的是否是空值 (缓存穿透设置的空字符串)
        if (json != null) {
            return null; // 返回错误信息或者直接返回空
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        // 5.不存在，缓存空值解决穿透问题
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
            return null;
        }

        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 方法4：根据指定的key查询缓存，利用逻辑过期解决缓存击穿问题
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在 (逻辑过期一般保证数据长久有效，如果没有命中直接返回空，因为通常搭配应用启动前缓存预热一起使用)
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3.命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.未过期，直接返回商铺信息
            return r;
        }

        // 6.已过期，需要缓存重建
        String lockKey = "lock:" + keyPrefix + id;
        boolean isLock = tryLock(lockKey);

        // 7.获取锁成功
        if (isLock) {
            // 这就是你代码里最缺乏的：在获取锁成功后，一定要再次检查redis缓存是否已经被更新过了（DoubleCheck）
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData checkData = JSONUtil.toBean(json, RedisData.class);
                if (checkData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 别人已经重建过了，直接返回
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) checkData.getData(), type);
                }
            }

            // 8. 双重检查通过开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    // 查询数据库
                    R new1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, new1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 9. 获取不到锁直接返回过期商铺信息
        return r;
    }

    // 辅助方法：获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 辅助方法：释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
