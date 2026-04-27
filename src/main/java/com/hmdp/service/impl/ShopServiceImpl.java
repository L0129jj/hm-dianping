package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.ls.LSInput;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        Shop shop= cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if (shop==null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


//    public Shop queryShopWithMutex(Long id){
//        //1.提交商铺id
//        String key=CACHE_SHOP_KEY+id;
//        //2.从Redis查询商铺缓存
//        String shopJson1=stringRedisTemplate.opsForValue().get(key);
//        //3.判断是否命中
//        if (StrUtil.isNotBlank(shopJson1)) {
//            //命中正常数据，直接返回
//            return JSONUtil.toBean(shopJson1,Shop.class);
//        }
//        if (shopJson1!=null) {                //命中空值占位符 ""，不为空
//            return null;
//        }
//        //4.实现缓存重建
//        //4.1.获取互斥锁
//        String lockKey=LOCK_SHOP_KEY+id;
//        Shop shop=null;
//        boolean isLock=false;
//        try {
//            //4.2.判断是否获取成功
//            isLock=tryLock(lockKey);
//            if (!isLock) {
//                //4.3.失败，则休眠并重试
//                Thread.sleep(50);
//                return shop=queryShopWithMutex(id);
//            }
//            //4.4.抢锁成功，先再查一次缓存，避免直接查数据库写缓存
//            String shopJson2=stringRedisTemplate.opsForValue().get(key);
//            //3.判断是否命中
//            if (StrUtil.isNotBlank(shopJson2)) {
//                //命中正常数据，直接返回
//                return JSONUtil.toBean(shopJson2,Shop.class);
//            }
//            if (shopJson2!=null) {                //命中空值占位符 ""，不为空
//                return null;
//            }
//            //5.缓存为空，查数据库
//            shop=getById(id);
//            if (shop == null) {
//                //6.不存在，将空值写入redis
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //7.存在，将店铺数据写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }finally {
//            if (isLock) {     //有锁时才执行解锁
//                stringRedisTemplate.delete(lockKey);
//            }
//        }
//        //8.返回
//        return shop;
//    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        String key=CACHE_SHOP_KEY+shop.getId();
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x==null||y==null) {
            //不需要坐标查询，按数据查询
            Page<Shop>page=query()
                    .eq("type_id",typeId)
                    .page(new Page<>(current,SystemConstants.DEFAULT_PAGE_SIZE));
        }
        //2.计算分页参数
        int from =(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询Redis，按照距离排序，分页。结果：shopId、distance
        String key=SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()  // GEOSEARCH key BYLONLAT x y BYREADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end)
                );

        ///4.解析出id
        if (results==null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size()<=from) {
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        //4.1截取from~end部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //4.2获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5.根据id查询Shop
        List<Shop> shops = list(
                new LambdaQueryWrapper<Shop>()
                        .in(Shop::getId, ids)
                        .orderByDesc(Shop::getId)
        );
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance!=null) {
                shop.setDistance(distance.getValue());
            }
        }
        //6.返回
        return Result.ok(shops);
    }

    /**
     * 互斥锁
     * @param lockKey
     * @return
     */
    public boolean tryLock(String lockKey){
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS));
    }

    /**
     * 缓存预热
     */
    public void saveShop2Redis(Long id, Long expireTimeSeconds){
        //1.查询商铺信息
        Shop shop=getById(id);
        if (shop==null) {
            System.out.println("商铺为空，缓存空");
        }
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTimeSeconds));
        //3.写入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 逻辑过期
     */
    //线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public Shop queryShopWithLogicExpire(Long id){
        //1.提交商铺id
        String key=CACHE_SHOP_KEY+id;
        //2.判断缓存是否命中
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        //2.1.未命中，返回返回商铺信息
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //3.判断缓存是否过期
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Object rawData = redisData.getData();
        Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(rawData), Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //3.1.未过期，返回商铺信息
            return shop;
        }

        //3.2.过期，尝试获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        //4.判断是否获取互斥锁
        if (isLock){
            //4.2.是，开启独立线程
                CACHE_REBUILD_EXECUTOR.execute(()->{
                    try {
                        //重建缓存
                        this.saveShop2Redis(id,20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        stringRedisTemplate.delete(lockKey);
                    }
                });
        }
        //6.返回过期商户信息
        return shop;
    }
}
