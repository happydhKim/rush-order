package com.rushorder.inventory.dto;

import com.rushorder.inventory.domain.Inventory;

/**
 * 재고 조회 응답.
 */
public record StockResponse(
        Long menuId,
        int totalStock,
        int reservedStock,
        int availableStock
) {

    public static StockResponse from(Inventory inventory) {
        return new StockResponse(
                inventory.getMenuId(),
                inventory.getTotalStock(),
                inventory.getReservedStock(),
                inventory.getAvailableStock()
        );
    }
}
