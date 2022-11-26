package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void myTest() throws InterruptedException {
        shopService.rebuildCacheByShopId(1L, 10L);
    }

    @Test
    public void testIdWork() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                redisIdWorker.nextId("order");
            }
            latch.countDown();
        };
        Long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    /**
     * 向redis中写入商家信息用来查询附近商铺
     */
    @Test
    public void loadDataToRedis() {
        // 获取所有商家信息
        List<Shop> shopList = shopService.list();
        // 按照商家类型分组
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 遍历所有商家 将商家添加到redis中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> list = entry.getValue();
            // 这里面的那个泛型指的是geo里面保存的那个member的值的类型 这里我们保存的是shopId
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(list.size());
            // 遍历当前typeId的所有商铺信息
            for (Shop shop : list) {
                RedisGeoCommands.GeoLocation<String> geoLocation = new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                );
                locations.add(geoLocation);
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
