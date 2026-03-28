package com.rushorder.order.saga;

/**
 * Payment Service가 발행하는 결제 결과 이벤트.
 *
 * <p>"payment-result" 토픽으로 수신되며,
 * SagaOrchestrator가 이 결과에 따라 Saga를 진행/보상한다.
 */
public record PaymentResultEvent(
        String orderId,
        boolean success,
        String pgTransactionId,
        String failureReason
) {
}
