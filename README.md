## 小众点评项目

## 项目简介

项目简介：本项目深度整合Redis的各种应用，包括使用Redis解决Session共享问题，使用Redis作为缓存的应用，使用Redis实现分布式锁，使用Redis的Set和Zset实现点赞，共同好友和排行榜和动态Feed流等功能，使用GEO数据类型实现附近商铺功能等。

## 项目特色

* 使用Redis解决分布式系统下Session共享问题
* 使用Redis作为缓存并解决相关问题
    * 使用缓存空对象和布隆过滤解决缓存穿透问题
    * 使用Redis集群配合随机TTL解决缓存雪崩问题
    * 使用互斥锁和逻辑过期方法解决缓存击穿问题
* 代金券秒杀业务 （优化迭代三次）
    * 基于Redis和Lua脚本实现了分布式可重入锁解决秒杀业务
    * 使用Redisson分布式锁解决秒杀业务
    * 利用Lua脚本+Redisson+Stream消息队列实现异步下单，进一步提高业务的吞吐量。
* 基于Redis Set和Sorted Set
    * 实现好友关注，取关和查找共同关注的功能
    * 实现用户点赞以及点赞排行榜功能
    * 实现基于推模式的用户动态Feed流
* 基于Redis GEO实现附近商铺功能

## 项目架构和技术选型

* 前端：ElementUI + Vue

* 网关：Nginx
* 应用层：Springboot + SpringMVC
* 持久层：Mysql + Redis + Mybatis Plus

![image-20221127214810649](README.asset/image-20221127214810649.png)

## 项目界面

![image-20221127220229632](README.asset/image-20221127220229632.png)