package com.rushorder.restaurant.dto;

import com.rushorder.restaurant.document.RestaurantDocument;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 검색 결과 응답 DTO.
 *
 * <p>ES 문서를 직접 노출하지 않고, API 스펙에 맞는 형태로 변환한다.
 * 커서 기반 페이징을 위해 nextCursor 필드를 포함한다.
 */
public record RestaurantSearchResponse(
        List<RestaurantSummary> restaurants,
        String nextCursor,
        boolean hasNext
) {

    /**
     * 검색 결과의 개별 가게 요약 정보.
     */
    public record RestaurantSummary(
            Long restaurantId,
            String name,
            String category,
            String status,
            double latitude,
            double longitude,
            List<MenuSummary> menus,
            LocalDateTime updatedAt
    ) {

        public static RestaurantSummary from(RestaurantDocument document) {
            List<MenuSummary> menuSummaries = document.getMenus() != null
                    ? document.getMenus().stream().map(MenuSummary::from).toList()
                    : List.of();

            return new RestaurantSummary(
                    document.getRestaurantId(),
                    document.getRestaurantName(),
                    document.getCategory(),
                    document.getStatus(),
                    document.getLocation().getLat(),
                    document.getLocation().getLon(),
                    menuSummaries,
                    document.getUpdatedAt()
            );
        }
    }

    /**
     * 검색 결과의 메뉴 요약 정보.
     */
    public record MenuSummary(
            Long menuId,
            String name,
            int price,
            boolean available
    ) {

        public static MenuSummary from(RestaurantDocument.MenuDocument menuDocument) {
            return new MenuSummary(
                    menuDocument.getMenuId(),
                    menuDocument.getMenuName(),
                    menuDocument.getPrice(),
                    menuDocument.isAvailable()
            );
        }
    }
}
