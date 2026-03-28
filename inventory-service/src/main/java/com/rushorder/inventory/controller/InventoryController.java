package com.rushorder.inventory.controller;

import com.rushorder.common.dto.ApiResponse;
import com.rushorder.inventory.dto.StockInitRequest;
import com.rushorder.inventory.dto.StockReserveRequest;
import com.rushorder.inventory.dto.StockResponse;
import com.rushorder.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 재고 관리 REST API.
 *
 * <p>내부 서비스 간 통신(OpenFeign)과 외부 API 모두 이 컨트롤러를 통한다.
 * 재고 예약/확정/해제는 Saga 참여자로서 Order Service에서 호출된다.
 */
@RestController
@RequestMapping("/api/inventories")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<ApiResponse<StockResponse>> initStock(
            @Valid @RequestBody StockInitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(inventoryService.initStock(request)));
    }

    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<StockResponse>> getStock(@PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.getStock(menuId)));
    }

    /**
     * 재고 예약 (Order Service에서 Feign으로 동기 호출).
     */
    @PostMapping("/reserve")
    public ResponseEntity<ApiResponse<Void>> reserve(
            @Valid @RequestBody StockReserveRequest request) {
        inventoryService.reserveStock(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 예약 확정 (Saga - 결제 성공 시).
     */
    @PostMapping("/{menuId}/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(
            @PathVariable Long menuId,
            @RequestParam String orderId,
            @RequestParam int quantity) {
        inventoryService.confirmStock(orderId, menuId, quantity);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 예약 해제 (Saga - 보상 트랜잭션).
     */
    @PostMapping("/{menuId}/release")
    public ResponseEntity<ApiResponse<Void>> release(
            @PathVariable Long menuId,
            @RequestParam String orderId,
            @RequestParam int quantity) {
        inventoryService.releaseStock(orderId, menuId, quantity);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
