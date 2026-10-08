package com.demo.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.demo.common.DeductResult;
import com.demo.common.ErrorCodes;
import com.demo.common.InventoryView;
import com.demo.inventory.domain.InventoryItem;
import com.demo.inventory.domain.InventoryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 库存领域逻辑单测：不需要 Spring 上下文，纯逻辑。 */
@ExtendWith(MockitoExtension.class)
class DefaultInventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    private DefaultInventoryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultInventoryService(repository);
        // @Value 字段在单测里不走 Spring，直接注入
        ReflectionTestUtils.setField(service, "lowStockThreshold", 5);
        ReflectionTestUtils.setField(service, "maxDeductPerRequest", 50);
    }

    @Test
    @DisplayName("扣减成功时返回剩余量，且真的改了实体状态")
    void deduct_success() {
        InventoryItem apple = new InventoryItem("APPLE", 10);
        when(repository.findBySku("APPLE")).thenReturn(Optional.of(apple));

        DeductResult result = service.deduct("APPLE", 3);

        assertThat(result.success()).isTrue();
        assertThat(result.reason()).isEqualTo(ErrorCodes.OK);
        assertThat(result.remaining()).isEqualTo(7);
        assertThat(apple.getAvailable()).isEqualTo(7);
    }

    @Test
    @DisplayName("库存不足是业务拒绝，不是异常，也不是假成功")
    void deduct_insufficientStock() {
        when(repository.findBySku("APPLE")).thenReturn(Optional.of(new InventoryItem("APPLE", 2)));

        DeductResult result = service.deduct("APPLE", 5);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCodes.INSUFFICIENT_STOCK);
        assertThat(result.remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("SKU 不存在给出明确错误码")
    void deduct_skuNotFound() {
        when(repository.findBySku("NOPE")).thenReturn(Optional.empty());

        DeductResult result = service.deduct("NOPE", 1);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCodes.SKU_NOT_FOUND);
    }

    @Test
    @DisplayName("数量非法（<=0 或超过单次上限）被拒")
    void deduct_invalidQuantity() {
        assertThat(service.deduct("APPLE", 0).reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
        assertThat(service.deduct("APPLE", -3).reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
        assertThat(service.deduct("APPLE", 999).reason()).isEqualTo(ErrorCodes.INVALID_REQUEST);
    }

    @Test
    @DisplayName("lowStock 由配置阈值驱动")
    void query_lowStockDrivenByThreshold() {
        when(repository.findBySku("PEAR")).thenReturn(Optional.of(new InventoryItem("PEAR", 3)));
        when(repository.findBySku("APPLE")).thenReturn(Optional.of(new InventoryItem("APPLE", 100)));

        InventoryView pear = service.query("PEAR");
        InventoryView apple = service.query("APPLE");

        assertThat(pear.lowStock()).isTrue();
        assertThat(apple.lowStock()).isFalse();
    }

    @Test
    @DisplayName("查询不存在的 SKU 返回 0 而不是抛异常")
    void query_unknownSku() {
        when(repository.findBySku("NOPE")).thenReturn(Optional.empty());

        InventoryView view = service.query("NOPE");

        assertThat(view.available()).isZero();
        assertThat(view.lowStock()).isFalse();
    }
}
