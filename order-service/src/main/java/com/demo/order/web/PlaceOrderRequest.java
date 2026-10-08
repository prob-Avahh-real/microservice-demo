package com.demo.order.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 下单请求 —— 这是 order-service **对外**的契约，与内部的 DeductCommand 刻意分开：
 * 外部 API 的字段变化不应该被下游服务的契约绑住。
 */
public record PlaceOrderRequest(
        @NotBlank String sku,
        @Min(1) int quantity) {
}
