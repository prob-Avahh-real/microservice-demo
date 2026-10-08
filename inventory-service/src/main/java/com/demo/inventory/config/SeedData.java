package com.demo.inventory.config;

import java.util.List;

import com.demo.inventory.domain.InventoryItem;
import com.demo.inventory.domain.InventoryRepository;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 种子数据。PEAR=3 是刻意留的：配置中心下发 low-stock-threshold=5，
 * 于是只有「配置真的从配置中心拿到」时，PEAR 才会被判为 lowStock。
 */
@Configuration
public class SeedData {

    @Bean
    ApplicationRunner seedInventory(InventoryRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.saveAll(List.of(
                    new InventoryItem("APPLE", 100),
                    new InventoryItem("PEAR", 3),
                    new InventoryItem("BANANA", 0),
                    new InventoryItem("LAPTOP", 12),
                    // 库存充足，供 E2E 反复下单/验单笔上限用（脚本可重复跑很多次）
                    new InventoryItem("MONITOR", 1000)));
        };
    }
}
