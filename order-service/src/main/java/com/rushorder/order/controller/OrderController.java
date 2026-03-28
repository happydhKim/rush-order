package com.rushorder.order.controller;

import com.rushorder.common.dto.ApiResponse;
import com.rushorder.order.dto.OrderCreateRequest;
import com.rushorder.order.dto.OrderResponse;
import com.rushorder.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 주문 REST API.
 *
 * <p>멱등키(X-Idempotency-Key)를 헤더로 수신하여 중복 주문을 방지한다.
 * 클라이언트는 주문 화면 진입 시 UUID를 생성하고, 같은 화면에서의
 * 재시도에는 동일 UUID를 사용해야 한다.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 생성.
     *
     * @param idempotencyKey 클라이언트가 생성한 UUID (필수)
     * @param request        주문 생성 요청
     * @return PENDING 상태의 주문 응답
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrder(orderId)));
    }
}
