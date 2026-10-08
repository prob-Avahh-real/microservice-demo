package com.demo.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * 统一配置中心。
 *
 * <p>用 native profile（配置源是 classpath:/config-repo）而不是 git/文件目录：
 * 好处是启动不依赖工作目录，任何位置都能起、能打包进 jar。
 * 代价是配置改动需要重新打包才生效（放弃热更新）——本阶段是「最小闭环」，热更新不在范围。
 */
@EnableConfigServer
@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
