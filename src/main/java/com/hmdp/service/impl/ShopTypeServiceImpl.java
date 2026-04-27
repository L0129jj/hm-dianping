package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key=CACHE_SHOP_TYPE_KEY;
        //1.从redis中读cache:shop:type
        List<String>typeJsonList=stringRedisTemplate.opsForList().range(key,0,-1);
        //2.命中，把redis中的json字符串列表转回ShopType列表直接返回
        if (typeJsonList!=null&&!typeJsonList.isEmpty()) {
            List<ShopType>shopTypeList=typeJsonList.stream()
                    .map(json->JSONUtil.toBean(json,ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        //3.没命中，查数据库，按sort排序
        List<ShopType>typeList=query().orderByAsc("sort").list();
        if (typeList==null||typeList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //4.写入redis，设置过期时间
        for (ShopType shopType : typeList) {
            stringRedisTemplate.opsForList().rightPush(key,JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.expire(key,CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
        }
}
