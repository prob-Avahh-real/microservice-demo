package com.demo.order.web;

import java.time.Instant;

import com.demo.common.OrderStatus;

/**
 * 下单结果。
 *
 * @param status     CREATED / REJECTED / DEGRADED
 * @param reason     机器可判定原因，见 {@link com.demo.common.ErrorCodes}
 * @param errorType  降级时记录上游异常类型（如 CallNotPermittedException 代表熔断已打开），
 *                   用来区分「熔断器真的跳了」与「只是偶发超时」——可观测，不猜
 * @param remaining  扣减后剩余库存；-1 表示未知（不是 0，不用 0 冒充已知）
 */
public record OrderResult(
        String orderNo,
        String sku,
        int quantity,
        OrderStatus status,
        String reason,
        String errorType,
        int remaining,
        Instant createdAt) {
}
