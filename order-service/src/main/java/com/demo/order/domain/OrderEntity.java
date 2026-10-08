package com.demo.order.domain;

import java.time.Instant;

import com.demo.common.OrderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 订单。降级订单也落库（status=DEGRADED + reason），不静默丢弃。 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String orderNo;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(length = 64)
    private String reason;

    /** 上游扣减后剩余量；失败时为 -1（未知），不用 0 冒充已知。 */
    @Column(nullable = false)
    private int remaining;

    @Column(nullable = false)
    private Instant createdAt;

    protected OrderEntity() {
        // for JPA
    }

    public OrderEntity(String orderNo, String sku, int quantity, OrderStatus status,
                       String reason, int remaining, Instant createdAt) {
        this.orderNo = orderNo;
        this.sku = sku;
        this.quantity = quantity;
        this.status = status;
        this.reason = reason;
        this.remaining = remaining;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public int getRemaining() {
        return remaining;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
