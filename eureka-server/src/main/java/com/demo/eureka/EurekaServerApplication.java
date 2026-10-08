package com.demo.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * 注册中心。
 *
 * <p>刻意不引入 Config Client：注册中心是别的服务的依赖，它自己不应再去依赖配置中心，
 * 否则会形成「配置中心没起来 → 注册中心没起来 → 谁也起不来」的启动死锁。
 */
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
