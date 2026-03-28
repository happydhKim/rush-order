package com.rushorder.order.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 결제 결과 Kafka Consumer.
 *
 * <p>"payment-result" 토픽을 구독하여 Payment Service의 결제 처리 결과를 수신한다.
 * 수신된 결과를 {@link SagaOrchestrator#handlePaymentResult}에 위임하여
 * 주문 확정 또는 보상 트랜잭션을 진행한다.
 *
 * <p>멱등성 보장:
 * Kafka의 at-least-once 특성상 동일 메시지가 재전달될 수 있다.
 * SagaOrchestrator가 SagaInstance의 현재 상태를 확인하여
 * 이미 처리된 결과는 무시하므로 Consumer 레벨에서 별도 처리가 불필요하다.
 *
 * <p>[면접 포인트] Consumer 멱등성 전략:
 * 방법 1) Redis에 메시지 ID를 SET NX로 저장 — 외부 저장소 의존
 * 방법 2) DB 상태 기반으로 중복 판단 — 별도 인프라 불필요, 여기서 사용하는 방식
 * 방법 2가 더 단순하고 SagaInstance가 이미 상태를 추적하므로 추가 비용이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultConsumer {

    private final SagaOrchestrator sagaOrchestrator;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-result", groupId = "order-service")
    public void consume(String message) {
        log.info("Received payment result: {}", message);

        try {
            PaymentResultEvent event = objectMapper.readValue(message, PaymentResultEvent.class);

            sagaOrchestrator.handlePaymentResult(
                    event.orderId(),
                    event.success(),
                    event.pgTransactionId()
            );
        } catch (JsonProcessingException e) {
            // JSON 파싱 실패는 재시도해도 동일하게 실패하므로 로그만 남김
            log.error("Failed to parse payment result message: {}", message, e);
        } catch (Exception e) {
            // 비즈니스 로직 예외는 전파하여 Kafka가 재시도하도록 함
            log.error("Failed to process payment result: {}", message, e);
            throw e;
        }
    }
}
