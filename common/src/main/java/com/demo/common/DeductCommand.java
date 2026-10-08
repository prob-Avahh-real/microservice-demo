package com.demo.common;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 扣减库存指令。这是 order-service 与 inventory-service 之间的契约。 */
public record DeductCommand(
        @NotBlank String sku,
        @Min(1) int quantity) {
}
