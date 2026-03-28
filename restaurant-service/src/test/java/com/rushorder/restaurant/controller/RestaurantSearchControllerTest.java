package com.rushorder.restaurant.controller;

import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.dto.RestaurantSearchResponse;
import com.rushorder.restaurant.dto.RestaurantSearchResponse.RestaurantSummary;
import com.rushorder.restaurant.service.RestaurantSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RestaurantSearchController MockMvc 테스트.
 *
 * <p>CQRS Read Path의 HTTP 계층을 검증한다.
 * 키워드 검색, 카테고리 검색, 위치 기반 검색, 상세 조회 엔드포인트를 테스트한다.
 */
@WebMvcTest(RestaurantSearchController.class)
class RestaurantSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantSearchService restaurantSearchService;

    private RestaurantSearchResponse createSearchResponse() {
        RestaurantSummary summary = new RestaurantSummary(
                1L, "맛있는 치킨집", "치킨", "OPEN",
                37.5665, 126.9780, List.of(), LocalDateTime.now()
        );
        return new RestaurantSearchResponse(List.of(summary), null, false);
    }

    @Test
    @DisplayName("GET /api/restaurants/search/keyword - 키워드 검색 성공")
    void searchByKeyword_validQuery_returnsResults() throws Exception {
        // 키워드 검색 시 200 OK와 검색 결과를 ApiResponse로 감싸서 반환해야 한다.
        given(restaurantSearchService.searchByKeyword(eq("치킨"), isNull(), eq(20)))
                .willReturn(createSearchResponse());

        mockMvc.perform(get("/api/restaurants/search/keyword")
                        .param("q", "치킨"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.restaurants.length()").value(1))
                .andExpect(jsonPath("$.data.restaurants[0].name").value("맛있는 치킨집"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /api/restaurants/search/keyword - q 파라미터 누락 시 400")
    void searchByKeyword_missingQuery_returns400() throws Exception {
        // 필수 파라미터 q가 없으면 400 Bad Request를 반환해야 한다.
        mockMvc.perform(get("/api/restaurants/search/keyword"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/restaurants/search/keyword - 커서 기반 페이징 파라미터 전달")
    void searchByKeyword_withCursor_passesCursorToService() throws Exception {
        // cursor 파라미터가 서비스로 올바르게 전달되는지 검증한다.
        // 키셋 페이징에서 cursor는 이전 페이지의 마지막 updatedAt이다.
        String cursor = "2024-01-01T12:00:00";
        given(restaurantSearchService.searchByKeyword(eq("치킨"), eq(cursor), eq(10)))
                .willReturn(createSearchResponse());

        mockMvc.perform(get("/api/restaurants/search/keyword")
                        .param("q", "치킨")
                        .param("cursor", cursor)
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/restaurants/search/category - 카테고리 검색 성공")
    void searchByCategory_validCategory_returnsResults() throws Exception {
        // 카테고리 기반 필터 검색 시 정확 일치로 결과를 반환해야 한다.
        given(restaurantSearchService.searchByCategory(eq("치킨"), isNull(), eq(20)))
                .willReturn(createSearchResponse());

        mockMvc.perform(get("/api/restaurants/search/category")
                        .param("category", "치킨"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurants[0].category").value("치킨"));
    }

    @Test
    @DisplayName("GET /api/restaurants/search/nearby - 위치 기반 검색 성공")
    void searchNearby_validParams_returnsResults() throws Exception {
        // 위도, 경도, 반경을 입력하면 해당 범위 내 가게를 검색해야 한다.
        given(restaurantSearchService.searchNearby(
                eq(37.5665), eq(126.9780), eq(3.0), isNull(), eq(20)))
                .willReturn(createSearchResponse());

        mockMvc.perform(get("/api/restaurants/search/nearby")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurants.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/restaurants/search/{restaurantId} - 상세 조회 성공")
    void getRestaurantDetail_existingId_returnsDetail() throws Exception {
        // 존재하는 가게 상세 조회 시 200 OK와 RestaurantSummary를 반환해야 한다.
        RestaurantSummary summary = new RestaurantSummary(
                1L, "맛있는 치킨집", "치킨", "OPEN",
                37.5665, 126.9780, List.of(), LocalDateTime.now()
        );
        given(restaurantSearchService.getRestaurantDetail(1L)).willReturn(summary);

        mockMvc.perform(get("/api/restaurants/search/{restaurantId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId").value(1))
                .andExpect(jsonPath("$.data.name").value("맛있는 치킨집"));
    }

    @Test
    @DisplayName("GET /api/restaurants/search/{restaurantId} - 존재하지 않는 가게 조회 시 404")
    void getRestaurantDetail_nonExistingId_returns404() throws Exception {
        // 어떤 계층에서도 가게를 찾을 수 없으면 NotFoundException -> 404
        given(restaurantSearchService.getRestaurantDetail(999L))
                .willThrow(new NotFoundException("Restaurant", "999"));

        mockMvc.perform(get("/api/restaurants/search/{restaurantId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
