package com.demo.order.client;

import com.demo.common.ApiResponse;
import com.demo.common.DeductCommand;
import com.demo.common.DeductResult;
import com.demo.common.InventoryView;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 库存服务的调用契约。
 *
 * <p>写的是服务名 {@code inventory-service}，不是 host:port —— 地址由 Eureka + LoadBalancer 解析，
 * 所以库存服务扩容/换端口不需要改这里（低耦合）。
 *
 * <p>刻意**不用** Feign 的 fallback 属性：降级改由 OrderService 里的 CircuitBreakerFactory 显式处理，
 * 这样降级逻辑可单测、可读，且不依赖 feign.circuitbreaker.enabled 这类会随版本变动的开关。
 */
@FeignClient(name = "inventory-service", path = "/inventory")
public interface InventoryClient {

    @GetMapping("/{sku}")
    ApiResponse<InventoryView> query(@PathVariable("sku") String sku);

    @PostMapping("/deduct")
    ApiResponse<DeductResult> deduct(@RequestBody DeductCommand command);
}
