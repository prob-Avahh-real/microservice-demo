package com.demo.inventory.service;

import java.util.Comparator;
import java.util.List;

import com.demo.common.DeductResult;
import com.demo.common.ErrorCodes;
import com.demo.common.InventoryView;
import com.demo.inventory.domain.InventoryItem;
import com.demo.inventory.domain.InventoryRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存领域逻辑。
 *
 * <p>注意「库存不足」与「上游不可用」是两件事：前者是业务拒绝（返回 {@code success=false}），
 * 后者由调用方的熔断器处理。这里从不把业务拒绝伪装成异常，也从不把失败涂成成功。
 */
@Service
public class DefaultInventoryService implements InventoryService {

    private final InventoryRepository repository;

    /** 兜底 999 = 永不告警；真实阈值由 config-server 下发（5）。 */
    @Value("${demo.inventory.low-stock-threshold:999}")
    private int lowStockThreshold;

    @Value("${demo.inventory.max-deduct-per-request:1000}")
    private int maxDeductPerRequest;

    public DefaultInventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryView query(String sku) {
        return repository.findBySku(sku)
                .map(this::toView)
                .orElse(new InventoryView(sku, 0, 0, false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryView> queryAll() {
        return repository.findAll().stream()
                .map(this::toView)
                .sorted(Comparator.comparing(InventoryView::sku))
                .toList();
    }

    @Override
    @Transactional
    public DeductResult deduct(String sku, int quantity) {
        if (quantity <= 0) {
            return DeductResult.rejected(sku, quantity, 0, ErrorCodes.INVALID_REQUEST);
        }
        if (quantity > maxDeductPerRequest) {
            return DeductResult.rejected(sku, quantity, 0, ErrorCodes.INVALID_REQUEST);
        }

        InventoryItem item = repository.findBySku(sku).orElse(null);
        if (item == null) {
            return DeductResult.rejected(sku, quantity, 0, ErrorCodes.SKU_NOT_FOUND);
        }
        if (item.getAvailable() < quantity) {
            return DeductResult.rejected(sku, quantity, item.getAvailable(), ErrorCodes.INSUFFICIENT_STOCK);
        }

        item.deduct(quantity);
        repository.save(item);
        return DeductResult.success(sku, quantity, item.getAvailable());
    }

    private InventoryView toView(InventoryItem item) {
        return new InventoryView(
                item.getSku(),
                item.getAvailable(),
                item.getReserved(),
                item.getAvailable() < lowStockThreshold);
    }
}
