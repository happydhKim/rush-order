package com.rushorder.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.domain.RestaurantStatus;
import com.rushorder.restaurant.dto.*;
import com.rushorder.restaurant.service.RestaurantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RestaurantController MockMvc 테스트.
 *
 * <p>CQRS Write Path의 HTTP 계층을 검증한다.
 * 요청 유효성 검증, 응답 형식(ApiResponse 래퍼), 에러 처리를 테스트한다.
 */
@WebMvcTest(RestaurantController.class)
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RestaurantService restaurantService;

    private RestaurantResponse createResponse() {
        return new RestaurantResponse(
                1L, "맛있는 치킨집", "서울시 강남구", "02-1234-5678",
                "치킨", RestaurantStatus.OPEN, 37.5665, 126.9780,
                List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("POST /api/restaurants - 가게 등록 성공 시 201 Created")
    void create_validRequest_returns201() throws Exception {
        // 유효한 요청으로 가게를 등록하면 201 Created와 ApiResponse로 감싼 응답을 반환해야 한다.
        RestaurantRequest request = new RestaurantRequest(
                "맛있는 치킨집", "서울시 강남구", "02-1234-5678",
                "치킨", 37.5665, 126.9780
        );
        given(restaurantService.createRestaurant(any(RestaurantRequest.class)))
                .willReturn(createResponse());

        mockMvc.perform(post("/api/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("맛있는 치킨집"))
                .andExpect(jsonPath("$.data.category").value("치킨"));
    }

    @Test
    @DisplayName("POST /api/restaurants - 필수 필드 누락 시 400 Bad Request")
    void create_missingRequiredField_returns400() throws Exception {
        // name이 빈 문자열이면 @NotBlank 검증에 실패하여 400을 반환해야 한다.
        RestaurantRequest request = new RestaurantRequest(
                "", "서울시 강남구", "02-1234-5678",
                "치킨", 37.5665, 126.9780
        );

        mockMvc.perform(post("/api/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/restaurants/{id} - 존재하는 가게 조회 시 200 OK")
    void get_existingId_returns200() throws Exception {
        // 존재하는 가게 ID로 조회하면 200 OK와 가게 정보를 반환해야 한다.
        given(restaurantService.getRestaurant(1L)).willReturn(createResponse());

        mockMvc.perform(get("/api/restaurants/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("GET /api/restaurants/{id} - 존재하지 않는 가게 조회 시 404 Not Found")
    void get_nonExistingId_returns404() throws Exception {
        // 존재하지 않는 ID 조회 시 NotFoundException -> GlobalExceptionHandler -> 404
        given(restaurantService.getRestaurant(999L))
                .willThrow(new NotFoundException("Restaurant", "999"));

        mockMvc.perform(get("/api/restaurants/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/restaurants - 전체 가게 목록 조회")
    void getAll_returnsList() throws Exception {
        // 전체 가게 목록을 조회하면 200 OK와 리스트를 반환해야 한다.
        given(restaurantService.getAllRestaurants())
                .willReturn(List.of(createResponse()));

        mockMvc.perform(get("/api/restaurants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("PUT /api/restaurants/{id} - 가게 수정 성공 시 200 OK")
    void update_validRequest_returns200() throws Exception {
        // 유효한 요청으로 가게를 수정하면 200 OK와 수정된 데이터를 반환해야 한다.
        RestaurantRequest request = new RestaurantRequest(
                "더 맛있는 치킨집", "서울시 서초구", "02-9876-5432",
                "치킨", 37.5665, 126.9780
        );
        RestaurantResponse updatedResponse = new RestaurantResponse(
                1L, "더 맛있는 치킨집", "서울시 서초구", "02-9876-5432",
                "치킨", RestaurantStatus.OPEN, 37.5665, 126.9780,
                List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        given(restaurantService.updateRestaurant(eq(1L), any(RestaurantRequest.class)))
                .willReturn(updatedResponse);

        mockMvc.perform(put("/api/restaurants/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("더 맛있는 치킨집"));
    }

    @Test
    @DisplayName("POST /api/restaurants/{restaurantId}/menus - 메뉴 추가 성공 시 201 Created")
    void addMenu_validRequest_returns201() throws Exception {
        // 유효한 메뉴 추가 요청 시 201 Created를 반환해야 한다.
        MenuRequest menuRequest = new MenuRequest("양념치킨", "매콤한 양념치킨", 18000);
        MenuResponse menuResponse = new MenuResponse(
                1L, 1L, "양념치킨", "매콤한 양념치킨", 18000, true,
                LocalDateTime.now(), LocalDateTime.now()
        );
        given(restaurantService.addMenu(eq(1L), any(MenuRequest.class)))
                .willReturn(menuResponse);

        mockMvc.perform(post("/api/restaurants/{restaurantId}/menus", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(menuRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("양념치킨"))
                .andExpect(jsonPath("$.data.price").value(18000));
    }

    @Test
    @DisplayName("GET /api/restaurants/{restaurantId}/menus - 메뉴 목록 조회")
    void getMenus_returnsMenuList() throws Exception {
        // 특정 가게의 메뉴 목록을 조회하면 200 OK와 메뉴 리스트를 반환해야 한다.
        MenuResponse menuResponse = new MenuResponse(
                1L, 1L, "양념치킨", "매콤한 양념치킨", 18000, true,
                LocalDateTime.now(), LocalDateTime.now()
        );
        given(restaurantService.getMenusByRestaurant(1L))
                .willReturn(List.of(menuResponse));

        mockMvc.perform(get("/api/restaurants/{restaurantId}/menus", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("양념치킨"));
    }
}
