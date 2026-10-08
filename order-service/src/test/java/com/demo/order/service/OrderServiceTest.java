package com.demo.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Function;
import java.util.function.Supplier;

import com.demo.common.ApiResponse;
import com.demo.common.DeductCommand;
import com.demo.common.DeductResult;
import com.demo.common.ErrorCodes;
import com.demo.common.OrderStatus;
import com.demo.order.client.InventoryClient;
import com.demo.order.domain.OrderEntity;
import com.demo.order.domain.OrderRepository;
import com.demo.order.web.OrderResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 下单编排三条路径的单测 —— 纯逻辑，不起 Spring 上下文。
 *
 * <p>熔断器用**手写的直通替身**：调用成功就返回、抛异常就走 fallback。
 * 这正是此处要验的语义（「技术失败有没有被降级接住、业务拒绝有没有被误当失败」）。
 *
 * <p>「真实熔断器在连续失败后真的打开」不在这里假装验证 —— 那需要真实进程间调用，
 * 由 scripts/e2e.sh 的第 7 条断言负责（会观察到 CallNotPermittedException）。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderRepository repository;

    @Mock
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    /** CircuitBreaker 的 run 是泛型方法，lambda 实现不了，必须写成匿名类。 */
    private static final CircuitBreaker PASS_THROUGH = new CircuitBreaker() {
        @Override
        public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
            try {
                return toRun.get();
            } catch (Throwable throwable) {
                return fallback.apply(throwable);
            }
        }
    };

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // lenient：验证「超上限直接拒绝」的用例根本不会走到熔断器
        lenient().when(circuitBreakerFactory.create(anyString())).thenReturn(PASS_THROUGH);
        orderService = new OrderService(inventoryClient, repository, circuitBreakerFactory);
        ReflectionTestUtils.setField(orderService, "maxQuantityPerOrder", 20);
    }

    @Test
    @DisplayName("路径1：库存扣减成功 → CREATED，剩余量来自下游返回值")
    void placeOrder_created() {
        when(inventoryClient.deduct(any(DeductCommand.class)))
                .thenReturn(ApiResponse.ok(DeductResult.success("APPLE", 3, 97)));

        OrderResult result = orderService.placeOrder("APPLE", 3);

        assertThat(result.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.reason()).isEqualTo(ErrorCodes.OK);
        assertThat(result.remaining()).isEqualTo(97);
        assertThat(result.orderNo()).startsWith("ORD-");
        verify(repository, times(1)).save(any(OrderEntity.class));
    }

    @Test
    @DisplayName("路径2：库存不足 → REJECTED（业务拒绝，不抛异常、不计入熔断失败率）")
    void placeOrder_rejectedByBusiness() {
        when(inventoryClient.deduct(any(DeductCommand.class)))
                .thenReturn(ApiResponse.ok(DeductResult.rejected("APPLE", 500, 100, ErrorCodes.INSUFFICIENT_STOCK)));

        OrderResult result = orderService.placeOrder("APPLE", 5);

        assertThat(result.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(result.reason()).isEqualTo(ErrorCodes.INSUFFICIENT_STOCK);
        assertThat(result.remaining()).isEqualTo(100);
    }

    @Test
    @DisplayName("路径3：下游技术失败 → DEGRADED + UPSTREAM_UNAVAILABLE，且 errorType 记录了异常类型")
    void placeOrder_degradedWhenUpstreamFails() {
        when(inventoryClient.deduct(any(DeductCommand.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        OrderResult result = orderService.placeOrder("APPLE", 2);

        assertThat(result.status()).isEqualTo(OrderStatus.DEGRADED);
        assertThat(result.reason()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
        assertThat(result.errorType()).isEqualTo("IllegalStateException");
        // 降级也必须落库，不能静默丢弃
        verify(repository, times(1)).save(any(OrderEntity.class));
    }

    @Test
    @DisplayName("下游返回空 body 也算技术失败（不会被当成成功）")
    void placeOrder_emptyBodyIsFailure() {
        when(inventoryClient.deduct(any(DeductCommand.class))).thenReturn(null);

        OrderResult result = orderService.placeOrder("APPLE", 2);

        assertThat(result.status()).isEqualTo(OrderStatus.DEGRADED);
        assertThat(result.reason()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("超过配置的单笔上限 → REJECTED，且根本不去调用下游")
    void placeOrder_rejectsOverLimitWithoutCallingDownstream() {
        OrderResult result = orderService.placeOrder("APPLE", 21);

        assertThat(result.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(result.reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
        verify(inventoryClient, never()).deduct(any(DeductCommand.class));
    }

    @Test
    @DisplayName("数量 <= 0 → REJECTED")
    void placeOrder_rejectsNonPositiveQuantity() {
        assertThat(orderService.placeOrder("APPLE", 0).reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
        assertThat(orderService.placeOrder("APPLE", -5).reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
    }
}
