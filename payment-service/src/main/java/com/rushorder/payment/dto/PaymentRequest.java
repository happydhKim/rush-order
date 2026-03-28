package com.rushorder.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 결제 요청 DTO.
 */
public record PaymentRequest(
        @NotBlank(message = "Order ID is required")
        String orderId,

        @NotBlank(message = "Idempotency key is required")
        String idempotencyKey,

        @Min(value = 1, message = "Amount must be positive")
        int amount
) {
}
