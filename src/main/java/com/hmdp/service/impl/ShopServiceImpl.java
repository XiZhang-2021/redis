package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jodd.util.StringUtil;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.lang.reflect.Proxy;
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
//mybatis plus -> IShopService是一个接口，里面有需要实现的方法 正常ShopMapper是dao层，但是在mybatis plus中将其封装进ServiceImpl<ShopMapper, Shop>
//在service impl层需要继承ServiceImpl<ShopMapper, Shop>来实现与数据库的交互

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//         互斥锁解决缓存击穿
//         Shop shop = cacheClient
//                 .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES, LOCK_SHOP_KEY);

//         逻辑过期解决缓存击穿
//        Shop shop = cacheClient
//                 .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS, LOCK_SHOP_KEY);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
        //----------------------------------------------------------------
    }
    //缓存穿透，封装在cacheclient
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//redis中shop是一个json字符串, 如果没有返回null

        //2.判断是否存在, isNotBlank 在shopJson.length() > 0 时为 true
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // ""空字符串需要处理
        if(shopJson != null){
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = this.getById(id);
        if(shop == null){
            //5.不存在，返回空值，同时写进redis,解决缓存穿透，缓存null，或者用布隆过滤解决
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }

        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);//TTL加上随机数，防止雪崩
        //缓存雪崩：1. Key 失效时间抖动（TTL Jitter）2.缓存预热（Cache Warm-up） 3. 多级缓存架构  4. 请求打散 + 限流降级

        //7.返回
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //封装缓存穿透，封装在cacheclient
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//redis中shop是一个json字符串, 如果没有返回null

        //2.判断是否存在, isNotBlank 在shopJson.length() > 0 时为 true
        if(StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回
            return null;
        }
        //4 命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }

        //5.2已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);

        //6.2 判断是否获取成功
        if(isLock){
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }

        //6.4 返回过期的商铺信息
        return shop;
    }

    //缓存逻辑过期，封装在cacheclient
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//redis中shop是一个json字符串, 如果没有返回null

        //2.判断是否存在, isNotBlank 在shopJson.length() > 0 时为 true，这是命中的处理
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 命中空值，""空字符串需要处理,这是穿透的结果
        if(shopJson != null){
            return null;
        }

        //4实现缓存重建
        Shop shop = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            //4.1 获取互斥锁
            boolean isLock = tryLock(lockKey);

            //4.2 判断是否成功
            if(!isLock){
                // 4.3失败 则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4 成功，根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            if(shop == null){
                //5.不存在，返回空值，同时写进redis,解决缓存穿透，缓存null，或者用布隆过滤解决
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
                return null;
            }

            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);//可以加上随机数，防止雪崩
            //缓存雪崩：1. Key 失效时间抖动（TTL Jitter）2.缓存预热（Cache Warm-up） 3. 多级缓存架构  4. 请求打散 + 限流降级
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放互斥锁
            unlock(lockKey);
        }

        //8 返回
        return shop;
    }

    //尝试获取锁
    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expireSeconds){
        //1. 查询店铺数据
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        //2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("id is null");
        }
        System.out.println(" = " + shop.getImages());
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }

/*
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            System.out.println(page.getRecords().toString());
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);

    }

 */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            List<Shop> list = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE))
                    .getRecords();
            return Result.ok(list);
        }
        //2. 计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;

        //3. 查询redis，按照距离排序，分页，结果，shopid，distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );


        //4. 解析出id
        if(results == null || results.getContent().isEmpty()) {
            List<ShopDTO> res = baseMapper.selectByDistance(typeId, x, y, 5000.0, from, SystemConstants.DEFAULT_PAGE_SIZE);
            if(res.size() == 0){
                return Result.ok("reach the end");
            }
            List<Long> ids = new ArrayList<>();
            Map<String, Distance> distanceMap = new HashMap<>();
            for(ShopDTO shop : res){
                ids.add(shop.getId());
                distanceMap.put(String.valueOf(shop.getId()), new Distance(shop.getDistance()));
            }
            String idStr = StrUtil.join(",", ids);
            List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
            for (Shop shop : shops) {
                shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
            }
            return Result.ok(shops);
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        //4.1截取from-end的部分
        //因为 Redis 自身不支持“OFFSET + LIMIT”分页，你在 Java 端先拿到距中心点最近的 end 条，再用 stream().skip(from) 去掉前 from 条，从而实现伪分页。
        List<Long> ids = new ArrayList<>();
        Map<String, Distance> distanceMap = new HashMap<>();
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        //5. 根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //5.1合并距离和店铺
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);

    }


}
