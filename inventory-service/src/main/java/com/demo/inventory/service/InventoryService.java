package com.demo.inventory.service;

import java.util.List;

import com.demo.common.InventoryView;
import com.demo.common.DeductResult;

/**
 * 库存领域逻辑契约。纯逻辑与副作用（DB、HTTP）分离，便于单测替换实现。
 */
public interface InventoryService {

    InventoryView query(String sku);

    DeductResult deduct(String sku, int quantity);

    List<InventoryView> queryAll();
}
