package com.demo.order.web;

import java.util.List;

import com.demo.common.ApiResponse;
import com.demo.order.service.OrderService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订单对外接口。网关把 /api/orders/** 去掉 /api 前缀后路由到这里。 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ApiResponse<OrderResult> place(@RequestBody @Valid PlaceOrderRequest request) {
        return ApiResponse.ok(orderService.placeOrder(request.sku(), request.quantity()));
    }

    @GetMapping
    public ApiResponse<List<OrderResult>> list() {
        return ApiResponse.ok(orderService.list());
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<OrderResult>> find(@PathVariable String orderNo) {
        OrderResult result = orderService.findByOrderNo(orderNo);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.fail("ORDER_NOT_FOUND", "no order with orderNo=" + orderNo));
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 仅用于让 E2E 可重复运行（清空订单表）。 */
    @PostMapping("/_reset")
    public ApiResponse<Integer> reset() {
        return ApiResponse.ok(orderService.clearAll());
    }
}
