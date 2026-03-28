package com.rushorder.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 재고 초기 등록 요청.
 */
public record StockInitRequest(
        @NotNull(message = "Menu ID is required")
        Long menuId,

        @Min(value = 0, message = "Stock must be non-negative")
        int totalStock
) {
}
