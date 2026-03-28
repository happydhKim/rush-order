package com.rushorder.order.dto;

import com.rushorder.order.domain.Order;
import com.rushorder.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 응답 DTO.
 */
public record OrderResponse(
        Long id,
        String orderId,
        Long userId,
        Long restaurantId,
        OrderStatus status,
        int totalAmount,
        List<OrderItemResponse> items,
        LocalDateTime createdAt
) {

    public static OrderResponse from(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getMenuId(),
                        item.getMenuName(),
                        item.getPrice(),
                        item.getQuantity()
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getOrderId(),
                order.getUserId(),
                order.getRestaurantId(),
                order.getStatus(),
                order.getTotalAmount(),
                itemResponses,
                order.getCreatedAt()
        );
    }

    public record OrderItemResponse(
            Long menuId,
            String menuName,
            int price,
            int quantity
    ) {
    }
}
