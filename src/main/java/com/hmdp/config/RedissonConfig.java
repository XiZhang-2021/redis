package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class  RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
//        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("");
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }

//    //设置multi master集群解决一致性问题
//    @Bean
//    public RedissonClient redissonClient2(){
//        // 配置
//        Config config = new Config();
////        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("");
//        config.useSingleServer().setAddress("redis://127.0.0.1:6380");
//        // 创建RedissonClient对象
//        return Redisson.create(config);
//    }
//
//    //设置multi master集群解决一致性问题
//    @Bean
//    public RedissonClient redissonClient3(){
//        // 配置
//        Config config = new Config();
////        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("");
//        config.useSingleServer().setAddress("redis://127.0.0.1:6381");
//        // 创建RedissonClient对象
//        return Redisson.create(config);
//    }
}
