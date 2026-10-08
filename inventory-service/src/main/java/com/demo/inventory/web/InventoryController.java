package com.demo.inventory.web;

import java.util.List;

import com.demo.common.ApiResponse;
import com.demo.common.DeductCommand;
import com.demo.common.DeductResult;
import com.demo.common.InventoryView;
import com.demo.inventory.chaos.ChaosSwitch;
import com.demo.inventory.service.InventoryService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存对外接口。网关会把 /api/inventory/** 去掉 /api 前缀后路由到这里。 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final ChaosSwitch chaosSwitch;

    public InventoryController(InventoryService inventoryService, ChaosSwitch chaosSwitch) {
        this.inventoryService = inventoryService;
        this.chaosSwitch = chaosSwitch;
    }

    @GetMapping("/_list")
    public ApiResponse<List<InventoryView>> list() {
        return ApiResponse.ok(inventoryService.queryAll());
    }

    @GetMapping("/{sku}")
    public ApiResponse<InventoryView> query(@PathVariable String sku) {
        return ApiResponse.ok(inventoryService.query(sku));
    }

    @PostMapping("/deduct")
    public ApiResponse<DeductResult> deduct(@RequestBody @Valid DeductCommand command) {
        chaosSwitch.guard();
        return ApiResponse.ok(inventoryService.deduct(command.sku(), command.quantity()));
    }

    /**
     * 故障注入：打开后所有库存写接口返回 503，让调用方熔断器跳开。
     * 这个开关本身**不受注入影响**，否则打开了就关不掉。
     */
    @PostMapping("/chaos")
    public ApiResponse<ChaosState> chaos(@RequestParam boolean enabled) {
        chaosSwitch.toggle(enabled);
        return ApiResponse.ok(new ChaosState(chaosSwitch.enabled(), chaosSwitch.tripCount()));
    }

    @GetMapping("/chaos")
    public ApiResponse<ChaosState> chaosState() {
        return ApiResponse.ok(new ChaosState(chaosSwitch.enabled(), chaosSwitch.tripCount()));
    }

    public record ChaosState(boolean enabled, int tripCount) {
    }
}
