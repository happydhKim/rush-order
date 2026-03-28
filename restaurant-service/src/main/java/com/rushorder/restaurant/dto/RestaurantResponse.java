package com.rushorder.restaurant.dto;

import com.rushorder.restaurant.domain.Restaurant;
import com.rushorder.restaurant.domain.RestaurantStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가게 응답 DTO.
 *
 * <p>엔티티를 직접 노출하지 않고, 필요한 필드만 선택하여 반환한다.
 * 내부 도메인 모델이 API 스펙에 결합되는 것을 방지한다.
 */
public record RestaurantResponse(
        Long id,
        String name,
        String address,
        String phone,
        String category,
        RestaurantStatus status,
        Double latitude,
        Double longitude,
        List<MenuResponse> menus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static RestaurantResponse from(Restaurant restaurant) {
        List<MenuResponse> menuResponses = restaurant.getMenus().stream()
                .map(MenuResponse::from)
                .toList();

        return new RestaurantResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getPhone(),
                restaurant.getCategory(),
                restaurant.getStatus(),
                restaurant.getLatitude(),
                restaurant.getLongitude(),
                menuResponses,
                restaurant.getCreatedAt(),
                restaurant.getUpdatedAt()
        );
    }
}
