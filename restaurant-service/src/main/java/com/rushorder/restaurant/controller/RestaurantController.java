package com.rushorder.restaurant.controller;

import com.rushorder.common.dto.ApiResponse;
import com.rushorder.restaurant.dto.*;
import com.rushorder.restaurant.service.RestaurantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 가게/메뉴 REST API.
 *
 * <p>CQRS Write 경로의 진입점. 모든 쓰기는 PostgreSQL로 직접 수행되며,
 * 읽기는 Phase 2에서는 PostgreSQL, Phase 3 이후에는 Elasticsearch를 우선 조회한다.
 */
@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantResponse>> create(
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse response = restaurantService.createRestaurant(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.getRestaurant(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.getAllRestaurants()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantRequest request) {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.updateRestaurant(id, request)));
    }

    @PostMapping("/{restaurantId}/menus")
    public ResponseEntity<ApiResponse<MenuResponse>> addMenu(
            @PathVariable Long restaurantId,
            @Valid @RequestBody MenuRequest request) {
        MenuResponse response = restaurantService.addMenu(restaurantId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @PutMapping("/menus/{menuId}")
    public ResponseEntity<ApiResponse<MenuResponse>> updateMenu(
            @PathVariable Long menuId,
            @Valid @RequestBody MenuRequest request) {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.updateMenu(menuId, request)));
    }

    @GetMapping("/{restaurantId}/menus")
    public ResponseEntity<ApiResponse<List<MenuResponse>>> getMenus(
            @PathVariable Long restaurantId) {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.getMenusByRestaurant(restaurantId)));
    }
}
