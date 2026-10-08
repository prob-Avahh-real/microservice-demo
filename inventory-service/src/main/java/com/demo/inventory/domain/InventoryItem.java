package com.demo.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 库存行。{@code Version} 做乐观锁，避免并发扣减把库存扣穿。 */
@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String sku;

    @Column(nullable = false)
    private int available;

    /** 已锁定（预占）数量，预留给后续「下单锁定 → 支付确认」两阶段，本阶段恒为 0。 */
    @Column(nullable = false)
    private int reserved;

    @Version
    private Long version;

    protected InventoryItem() {
        // for JPA
    }

    public InventoryItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
        this.reserved = 0;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public Long getVersion() {
        return version;
    }

    /** 扣减成功才改变状态；调用方负责先判定库存是否充足。 */
    public void deduct(int quantity) {
        this.available -= quantity;
    }
}
