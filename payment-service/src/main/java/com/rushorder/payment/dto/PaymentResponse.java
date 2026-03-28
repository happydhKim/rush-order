package com.rushorder.payment.dto;

import com.rushorder.payment.domain.Payment;
import com.rushorder.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

/**
 * 결제 응답 DTO.
 */
public record PaymentResponse(
        Long id,
        String orderId,
        int amount,
        PaymentStatus status,
        String pgTransactionId,
        String failureReason,
        LocalDateTime createdAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPgTransactionId(),
                payment.getFailureReason(),
                payment.getCreatedAt()
        );
    }
}
