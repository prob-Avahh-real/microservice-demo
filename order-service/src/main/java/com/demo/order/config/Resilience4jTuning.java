package com.demo.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把配置中心的熔断参数**真的接到** Resilience4j 上。
 *
 * <p>为什么必须有这个类：Spring Cloud Circuit Breaker 的 Resilience4j 实现默认
 * 不读 {@code demo.*} 这类自定义前缀的属性。如果不写这个 Customizer，
 * 配置中心里的熔断参数就是「看着有、其实没人读」的死配置 —— 那是在骗自己。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryBreakerProperties.class)
public class Resilience4jTuning {

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> inventoryBreakerCustomizer(InventoryBreakerProperties props) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .failureRateThreshold(props.failureRateThreshold())
                        .slidingWindowSize(props.slidingWindowSize())
                        .minimumNumberOfCalls(props.minimumNumberOfCalls())
                        .waitDurationInOpenState(props.waitDurationInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(props.permittedNumberOfCallsInHalfOpenState())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(props.timeLimiterTimeout())
                        .build())
                .build());
    }
}
