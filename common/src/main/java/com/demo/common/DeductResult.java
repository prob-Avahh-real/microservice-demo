package com.demo.common;

/**
 * 扣减结果。success=false 时 reason 必填（业务拒绝：库存不足 / SKU 不存在）。
 * 「上游不可用」不走这个对象，而是由调用方的熔断降级处理——两者语义必须分开。
 */
public record DeductResult(String sku, int requested, int remaining, boolean success, String reason) {

    public static DeductResult success(String sku, int requested, int remaining) {
        return new DeductResult(sku, requested, remaining, true, ErrorCodes.OK);
    }

    public static DeductResult rejected(String sku, int requested, int remaining, String reason) {
        return new DeductResult(sku, requested, remaining, false, reason);
    }
}
