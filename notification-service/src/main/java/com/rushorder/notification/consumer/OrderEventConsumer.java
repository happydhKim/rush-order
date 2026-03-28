package com.rushorder.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.notification.domain.NotificationType;
import com.rushorder.notification.dto.OrderEventPayload;
import com.rushorder.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 이벤트 Kafka Consumer.
 *
 * <p>Outbox 폴링 워커가 발행한 이벤트를 수신하여 알림을 전송한다.
 *
 * <p>Consumer 멱등성 전략:
 * Kafka는 At-least-once 보장이므로 동일 메시지가 재전달될 수 있다.
 * eventId를 {@code {topic}:{key}:{offset}} 조합으로 생성하여
 * DB unique constraint 기반으로 중복 처리를 방지한다.
 *
 * <p>왜 topic + key + offset인가?
 * - topic: 이벤트 유형 구분 (같은 key라도 다른 토픽이면 다른 이벤트)
 * - key: Outbox의 aggregateId (주문 단위 파티셔닝)
 * - offset: 같은 key가 여러 번 발행될 수 있으므로 파티션 내 위치로 구분
 *
 * @see com.rushorder.notification.service.NotificationService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /**
     * 주문 생성 이벤트를 수신한다.
     *
     * @param record Kafka ConsumerRecord (key, offset 등 메타데이터 포함)
     */
    @KafkaListener(topics = "order-created", groupId = "notification-service")
    public void handleOrderCreated(ConsumerRecord<String, String> record) {
        String eventId = buildEventId(record);
        log.info("[NOTIFICATION] Order created event received: eventId={}", eventId);

        OrderEventPayload payload = parsePayload(record.value());
        if (payload == null) return;

        notificationService.sendNotification(
                NotificationType.ORDER_CREATED,
                eventId,
                payload.orderId(),
                payload.userId(),
                "새 주문이 접수되었습니다",
                String.format("주문번호: %s, 금액: %d원", payload.orderId(), payload.totalAmount())
        );
    }

    /**
     * 결제 완료 이벤트를 수신한다.
     */
    @KafkaListener(topics = "payment-completed", groupId = "notification-service")
    public void handlePaymentCompleted(ConsumerRecord<String, String> record) {
        String eventId = buildEventId(record);
        log.info("[NOTIFICATION] Payment completed event received: eventId={}", eventId);

        OrderEventPayload payload = parsePayload(record.value());
        if (payload == null) return;

        notificationService.sendNotification(
                NotificationType.PAYMENT_COMPLETED,
                eventId,
                payload.orderId(),
                payload.userId(),
                "결제가 완료되었습니다",
                String.format("주문번호: %s, 결제 금액: %d원", payload.orderId(), payload.totalAmount())
        );
    }

    /**
     * 주문 확정 이벤트를 수신한다.
     */
    @KafkaListener(topics = "order-confirmed", groupId = "notification-service")
    public void handleOrderConfirmed(ConsumerRecord<String, String> record) {
        String eventId = buildEventId(record);
        log.info("[NOTIFICATION] Order confirmed event received: eventId={}", eventId);

        OrderEventPayload payload = parsePayload(record.value());
        if (payload == null) return;

        notificationService.sendNotification(
                NotificationType.ORDER_CONFIRMED,
                eventId,
                payload.orderId(),
                payload.userId(),
                "주문이 확정되었습니다",
                String.format("주문번호: %s, 조리가 시작됩니다", payload.orderId())
        );
    }

    /**
     * 주문 취소 이벤트를 수신한다.
     */
    @KafkaListener(topics = "order-cancelled", groupId = "notification-service")
    public void handleOrderCancelled(ConsumerRecord<String, String> record) {
        String eventId = buildEventId(record);
        log.info("[NOTIFICATION] Order cancelled event received: eventId={}", eventId);

        OrderEventPayload payload = parsePayload(record.value());
        if (payload == null) return;

        notificationService.sendNotification(
                NotificationType.ORDER_CANCELLED,
                eventId,
                payload.orderId(),
                payload.userId(),
                "주문이 취소되었습니다",
                String.format("주문번호: %s, 환불이 진행됩니다", payload.orderId())
        );
    }

    /**
     * Kafka 메시지의 메타데이터로 멱등성 키를 생성한다.
     *
     * <p>형식: {@code {topic}:{key}:{offset}}
     */
    private String buildEventId(ConsumerRecord<String, String> record) {
        return record.topic() + ":" + record.key() + ":" + record.offset();
    }

    private OrderEventPayload parsePayload(String json) {
        try {
            return objectMapper.readValue(json, OrderEventPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse order event payload: {}", e.getMessage(), e);
            return null;
        }
    }
}
