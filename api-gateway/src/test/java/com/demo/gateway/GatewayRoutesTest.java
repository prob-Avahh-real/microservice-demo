package com.demo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * 这个测试存在的意义：证明 application.yml 里的路由**真的被解析了**。
 *
 * <p>Spring Cloud Gateway 5.x 改了配置前缀（spring.cloud.gateway.server.webflux.*）。
 * 如果前缀写错，进程照样能启动、接口也不报错，只是「一条路由都没有」——
 * 这是最容易静默失败的地方，所以要用断言把它钉住。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.config.enabled=false",
                "spring.config.import="
        })
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    @DisplayName("两条业务路由都从配置加载出来了，且指向服务名而不是写死地址")
    void routesAreLoadedFromConfig() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).extracting(Route::getId)
                .contains("order-service", "inventory-service");

        assertThat(routes).extracting(route -> route.getUri().toString())
                .contains("lb://order-service", "lb://inventory-service");
    }

    @Test
    @DisplayName("路由没有硬编码的 host:port（服务地址必须由注册中心解析）")
    void routesUseServiceDiscoveryNotHardcodedHosts() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).allSatisfy(route ->
                assertThat(route.getUri().toString()).startsWith("lb://"));
    }
}
