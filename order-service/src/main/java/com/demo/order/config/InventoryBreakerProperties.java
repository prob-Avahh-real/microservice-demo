package com.demo.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 熔断器参数，来自 config-server 的 order-service.yml。
 *
 * <p>本地 application.yml 的兜底值刻意「几乎不会跳」（minimum-number-of-calls: 100），
 * 于是 E2E 里「熔断器真的打开了」这件事同时也证明了配置是从配置中心下发的。
 */
@ConfigurationProperties(prefix = "demo.circuitbreaker.inventory")
public record InventoryBreakerProperties(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("100") int slidingWindowSize,
        @DefaultValue("100") int minimumNumberOfCalls,
        @DefaultValue("60s") Duration waitDurationInOpenState,
        @DefaultValue("10") int permittedNumberOfCallsInHalfOpenState,
        @DefaultValue("5s") Duration timeLimiterTimeout) {
}
