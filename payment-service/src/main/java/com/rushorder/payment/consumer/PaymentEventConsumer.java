package com.rushorder.payment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제 요청 Kafka Consumer.
 *
 * <p>"payment-requested" 토픽을 구독하여 Order Service의 결제 요청을 수신한다.
 * 기존 PaymentService.processPayment()를 호출하여 결제를 처리한 뒤,
 * 결과를 "payment-result" 토픽으로 발행한다.
 *
 * <p>흐름:
 * <pre>
 *   Order Outbox → Kafka("payment-requested") → 이 Consumer
 *     → PaymentService.processPayment()
 *     → Kafka("payment-result") → Order Service의 PaymentResultConsumer
 * </pre>
 *
 * <p>[면접 포인트] 결제 요청을 Kafka로 비동기 처리하는 이유:
 * PG 호출은 외부 시스템 의존이므로 지연이 불확실하다.
 * 동기 Feign으로 호출하면 Order Service의 스레드가 PG 응답을 기다리며 블로킹된다.
 * Kafka를 사이에 두면 Order Service는 즉시 응답하고,
 * Payment Service가 독립적인 속도로 처리할 수 있다.
 *
 * <p>멱등성: PaymentService가 idempotencyKey 기반으로 중복 결제를 방지한다.
 * orderId를 idempotencyKey로 사용하여 동일 주문에 대한 중복 결제를 차단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentService paymentService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-requested", groupId = "payment-service")
    public void consume(String message) {
        log.info("Received payment request: {}", message);

        String orderId = null;
        try {
            JsonNode node = objectMapper.readTree(message);
            orderId = node.get("orderId").asText();
            int totalAmount = node.get("totalAmount").asInt();

            // orderId를 idempotencyKey로 사용 — 하나의 주문에 하나의 결제만 허용
            PaymentRequest request = new PaymentRequest(orderId, orderId, totalAmount);
            PaymentResponse response = paymentService.processPayment(request);

            // 결제 결과를 "payment-result" 토픽으로 발행
            boolean success = "APPROVED".equals(response.status().name());
            PaymentResultMessage result = new PaymentResultMessage(
                    orderId,
                    success,
                    response.pgTransactionId(),
                    response.failureReason()
            );

            String resultJson = objectMapper.writeValueAsString(result);
            kafkaTemplate.send("payment-result", orderId, resultJson);

            log.info("Payment result published: orderId={}, success={}", orderId, success);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse payment request: {}", message, e);
        } catch (Exception e) {
            log.error("Payment processing failed: orderId={}", orderId, e);

            // 결제 처리 자체가 실패한 경우에도 실패 결과를 발행하여 Saga가 보상할 수 있도록 함
            publishFailureResult(orderId, e.getMessage());
        }
    }

    /**
     * 결제 처리 중 예외 발생 시 실패 결과를 발행한다.
     * Saga가 보상 트랜잭션을 시작할 수 있도록 반드시 결과를 보내야 한다.
     */
    private void publishFailureResult(String orderId, String reason) {
        if (orderId == null) {
            return;
        }

        try {
            PaymentResultMessage result = new PaymentResultMessage(orderId, false, null, reason);
            String resultJson = objectMapper.writeValueAsString(result);
            kafkaTemplate.send("payment-result", orderId, resultJson);
        } catch (Exception e) {
            log.error("Failed to publish failure result: orderId={}", orderId, e);
        }
    }

    /**
     * "payment-result" 토픽으로 발행되는 메시지 구조.
     * Order Service의 PaymentResultEvent와 동일한 필드를 가진다.
     */
    private record PaymentResultMessage(
            String orderId,
            boolean success,
            String pgTransactionId,
            String failureReason
    ) {
    }
}
