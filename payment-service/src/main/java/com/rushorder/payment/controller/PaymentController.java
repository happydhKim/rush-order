package com.rushorder.payment.controller;

import com.rushorder.common.dto.ApiResponse;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 REST API.
 *
 * <p>Saga의 참여자로서 Order Service(Kafka Consumer)에서 호출되거나,
 * 직접 API 호출로 결제를 처리한다.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> processPayment(
            @Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getPayment(orderId)));
    }
}
