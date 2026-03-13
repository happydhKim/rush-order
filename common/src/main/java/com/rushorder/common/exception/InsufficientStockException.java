package com.rushorder.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 재고 부족 시 발생하는 예외.
 */
public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String menuId, int available, int requested) {
        super(
                "INSUFFICIENT_STOCK",
                String.format("Menu %s: available=%d, requested=%d", menuId, available, requested),
                HttpStatus.CONFLICT
        );
    }
}
