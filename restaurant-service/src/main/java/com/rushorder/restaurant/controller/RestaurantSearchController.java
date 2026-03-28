package com.rushorder.restaurant.controller;

import com.rushorder.common.dto.ApiResponse;
import com.rushorder.restaurant.dto.RestaurantSearchResponse;
import com.rushorder.restaurant.dto.RestaurantSearchResponse.RestaurantSummary;
import com.rushorder.restaurant.service.RestaurantSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * CQRS Read Path 검색 API.
 *
 * <p>Write Path의 RestaurantController와 분리하여, 읽기 전용 엔드포인트를 제공한다.
 * 모든 검색은 Elasticsearch를 우선 사용하며, 장애 시 PostgreSQL로 fallback한다.
 *
 * @see RestaurantSearchService
 */
@RestController
@RequestMapping("/api/restaurants/search")
@RequiredArgsConstructor
public class RestaurantSearchController {

    private final RestaurantSearchService restaurantSearchService;

    /**
     * Nori 분석기 기반 키워드 검색.
     * 가게명과 메뉴명을 동시에 검색한다.
     *
     * @param q      검색 키워드
     * @param cursor 이전 페이지의 마지막 updatedAt (첫 페이지는 생략)
     * @param size   페이지 크기 (기본 20)
     */
    @GetMapping("/keyword")
    public ResponseEntity<ApiResponse<RestaurantSearchResponse>> searchByKeyword(
            @RequestParam String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        RestaurantSearchResponse response = restaurantSearchService.searchByKeyword(q, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 카테고리 기반 필터 검색.
     *
     * @param category 카테고리 (정확 일치)
     * @param cursor   커서
     * @param size     페이지 크기
     */
    @GetMapping("/category")
    public ResponseEntity<ApiResponse<RestaurantSearchResponse>> searchByCategory(
            @RequestParam String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        RestaurantSearchResponse response = restaurantSearchService.searchByCategory(category, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 위치 기반 근처 가게 검색.
     *
     * @param lat      위도
     * @param lon      경도
     * @param distance 검색 반경 (km, 기본 3km)
     * @param cursor   커서
     * @param size     페이지 크기
     */
    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<RestaurantSearchResponse>> searchNearby(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "3") double distance,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        RestaurantSearchResponse response = restaurantSearchService.searchNearby(
                lat, lon, distance, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 가게 상세 조회.
     * Redis 캐시 -> ES -> PG 순으로 fallback한다.
     *
     * @param restaurantId 가게 ID
     */
    @GetMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<RestaurantSummary>> getRestaurantDetail(
            @PathVariable Long restaurantId) {
        RestaurantSummary response = restaurantSearchService.getRestaurantDetail(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
