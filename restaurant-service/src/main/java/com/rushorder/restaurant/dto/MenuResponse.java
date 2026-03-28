package com.rushorder.restaurant.dto;

import com.rushorder.restaurant.domain.Menu;

import java.time.LocalDateTime;

/**
 * 메뉴 응답 DTO.
 */
public record MenuResponse(
        Long id,
        Long restaurantId,
        String name,
        String description,
        int price,
        boolean available,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getRestaurant().getId(),
                menu.getName(),
                menu.getDescription(),
                menu.getPrice(),
                menu.isAvailable(),
                menu.getCreatedAt(),
                menu.getUpdatedAt()
        );
    }
}
