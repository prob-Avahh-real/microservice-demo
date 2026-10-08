package com.demo.common;

/** 订单状态。DEGRADED = 下游不可用被降级吞下，与 REJECTED（业务性拒绝）语义不同。 */
public enum OrderStatus {
    CREATED,
    REJECTED,
    DEGRADED
}
