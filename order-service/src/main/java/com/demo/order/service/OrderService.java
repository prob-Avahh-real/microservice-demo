package com.demo.order.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.demo.common.ApiResponse;
import com.demo.common.DeductCommand;
import com.demo.common.DeductResult;
import com.demo.common.ErrorCodes;
import com.demo.common.OrderStatus;
import com.demo.order.client.InventoryClient;
import com.demo.order.domain.OrderEntity;
import com.demo.order.domain.OrderRepository;
import com.demo.order.web.OrderResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/**
 * 下单编排：调库存 → 落订单。核心在「三类结果被明确区分」：
 *
 * <ol>
 *   <li><b>CREATED</b>：库存扣减成功</li>
 *   <li><b>REJECTED</b>：业务拒绝（库存不足 / SKU 不存在 / 参数非法）——上游是健康的，
 *       抛异常是错的，这**不**计入熔断失败率</li>
 *   <li><b>DEGRADED</b>：上游技术失败或熔断已打开——由 CircuitBreaker 的 fallback 兜住，
 *       落库并写明 reason/errorType，绝不假装成功</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final String CIRCUIT_NAME = "inventory";

    private final InventoryClient inventoryClient;
    private final OrderRepository repository;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    /** 本地兜底 1（几乎不可能下单成功）；真实上限由 config-server 下发（20）。 */
    @Value("${demo.order.max-quantity-per-order:1}")
    private int maxQuantityPerOrder;

    public OrderService(InventoryClient inventoryClient,
                        OrderRepository repository,
                        CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.inventoryClient = inventoryClient;
        this.repository = repository;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    public OrderResult placeOrder(String sku, int quantity) {
        if (quantity <= 0) {
            return persist(sku, quantity, OrderStatus.REJECTED, ErrorCodes.INVALID_REQUEST, null, -1);
        }
        if (quantity > maxQuantityPerOrder) {
            return persist(sku, quantity, OrderStatus.REJECTED, ErrorCodes.INVALID_REQUEST, null, -1);
        }

        CircuitBreaker breaker = circuitBreakerFactory.create(CIRCUIT_NAME);
        return breaker.run(
                () -> callInventoryAndPersist(sku, quantity),
                throwable -> persist(sku, quantity, OrderStatus.DEGRADED,
                        ErrorCodes.UPSTREAM_UNAVAILABLE,
                        throwable.getClass().getSimpleName(),
                        -1));
    }

    /** 只有在 CircuitBreaker 内部才允许抛异常——异常 = 技术失败 = 计入失败率。 */
    private OrderResult callInventoryAndPersist(String sku, int quantity) {
        ApiResponse<DeductResult> response = inventoryClient.deduct(new DeductCommand(sku, quantity));
        if (response == null || response.data() == null) {
            throw new IllegalStateException("inventory-service returned an empty body");
        }

        DeductResult result = response.data();
        if (result.success()) {
            return persist(sku, quantity, OrderStatus.CREATED, ErrorCodes.OK, null, result.remaining());
        }
        // 业务拒绝：正常返回，不计入熔断失败率
        return persist(sku, quantity, OrderStatus.REJECTED, result.reason(), null, result.remaining());
    }

    public List<OrderResult> list() {
        return repository.findAllByOrderByIdDesc().stream().map(OrderService::toResult).toList();
    }

    public OrderResult findByOrderNo(String orderNo) {
        return repository.findByOrderNo(orderNo).map(OrderService::toResult).orElse(null);
    }

    public int clearAll() {
        int size = (int) repository.count();
        repository.deleteAll();
        return size;
    }

    private OrderResult persist(String sku, int quantity, OrderStatus status,
                                String reason, String errorType, int remaining) {
        String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = Instant.now();
        repository.save(new OrderEntity(orderNo, sku, quantity, status, reason, remaining, now));
        return new OrderResult(orderNo, sku, quantity, status, reason, errorType, remaining, now);
    }

    private static OrderResult toResult(OrderEntity entity) {
        return new OrderResult(entity.getOrderNo(), entity.getSku(), entity.getQuantity(),
                entity.getStatus(), entity.getReason(), null, entity.getRemaining(),
                entity.getCreatedAt());
    }
}
