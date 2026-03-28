package com.rushorder.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 주문 생성 요청 DTO.
 *
 * <p>멱등키(X-Idempotency-Key 헤더)는 컨트롤러에서 별도로 수신한다.
 */
public record OrderCreateRequest(
        @NotNull(message = "User ID is required")
        Long userId,

        @NotNull(message = "Restaurant ID is required")
        Long restaurantId,

        @NotEmpty(message = "Order items must not be empty")
        @Valid
        List<OrderItemRequest> items
) {

    public record OrderItemRequest(
            @NotNull(message = "Menu ID is required")
            Long menuId,

            String menuName,

            int price,

            int quantity
    ) {
    }
}
