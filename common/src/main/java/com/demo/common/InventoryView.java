package com.demo.common;

/** 库存快照。lowStock 由配置中心的阈值驱动，用来证明「配置真的在起作用」。 */
public record InventoryView(String sku, int available, int reserved, boolean lowStock) {
}
