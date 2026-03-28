package com.rushorder.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.notification.domain.NotificationType;
import com.rushorder.notification.dto.OrderEventPayload;
import com.rushorder.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OrderEventConsumer 단위 테스트.
 *
 * <p>Kafka Consumer가 이벤트를 올바르게 수신하고,
 * eventId를 {topic}:{key}:{offset} 형태로 조합하여
 * NotificationService에 전달하는지 검증한다.
 *
 * <p>[면접 포인트] eventId 구성이 {topic}:{key}:{offset}인 이유
 * - Kafka는 At-least-once 보장이 기본이므로, 동일 메시지가 재전달될 수 있다.
 * - topic: 서로 다른 토픽의 이벤트를 구분 (같은 key라도 다른 토픽이면 다른 이벤트)
 * - key: Outbox의 aggregateId (주문 단위 파티셔닝 보장)
 * - offset: 같은 key로 여러 번 발행될 수 있으므로 파티션 내 위치로 고유성 확보
 * - 이 세 값의 조합은 Kafka 클러스터 내에서 메시지를 유일하게 식별할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OrderEventConsumer orderEventConsumer;

    private OrderEventPayload createPayload() {
        return new OrderEventPayload(
                "order-123", 1L, 10L, "CREATED",
                25000, List.of(), LocalDateTime.now()
        );
    }

    private ConsumerRecord<String, String> createRecord(String topic, String key, long offset, String value) {
        return new ConsumerRecord<>(topic, 0, offset, key, value);
    }

    @Test
    @DisplayName("order-created 이벤트 수신 시 NotificationService를 호출하고 eventId는 {topic}:{key}:{offset}")
    void handleOrderCreated_validEvent_callsNotificationService() throws Exception {
        // order-created 이벤트를 수신하면 파싱 후 NotificationService.sendNotification()을 호출해야 한다.
        // eventId는 "order-created:order-123:5" 형태로 구성되어야 한다.
        String json = "{\"orderId\":\"order-123\"}";
        ConsumerRecord<String, String> record = createRecord("order-created", "order-123", 5L, json);
        OrderEventPayload payload = createPayload();
        given(objectMapper.readValue(json, OrderEventPayload.class)).willReturn(payload);

        orderEventConsumer.handleOrderCreated(record);

        // eventId가 {topic}:{key}:{offset} 형태로 전달되는지 검증
        verify(notificationService).sendNotification(
                eq(NotificationType.ORDER_CREATED),
                eq("order-created:order-123:5"),
                eq("order-123"),
                eq(1L),
                eq("새 주문이 접수되었습니다"),
                contains("order-123")
        );
    }

    @Test
    @DisplayName("order-confirmed 이벤트 수신 시 ORDER_CONFIRMED 타입으로 알림을 처리한다")
    void handleOrderConfirmed_validEvent_callsNotificationService() throws Exception {
        // 주문 확정 이벤트가 올바른 NotificationType과 eventId로 처리되는지 검증한다.
        String json = "{\"orderId\":\"order-123\"}";
        ConsumerRecord<String, String> record = createRecord("order-confirmed", "order-123", 10L, json);
        OrderEventPayload payload = createPayload();
        given(objectMapper.readValue(json, OrderEventPayload.class)).willReturn(payload);

        orderEventConsumer.handleOrderConfirmed(record);

        verify(notificationService).sendNotification(
                eq(NotificationType.ORDER_CONFIRMED),
                eq("order-confirmed:order-123:10"),
                eq("order-123"),
                eq(1L),
                eq("주문이 확정되었습니다"),
                contains("order-123")
        );
    }

    @Test
    @DisplayName("order-cancelled 이벤트 수신 시 ORDER_CANCELLED 타입으로 알림을 처리한다")
    void handleOrderCancelled_validEvent_callsNotificationService() throws Exception {
        // 주문 취소 이벤트가 올바른 NotificationType과 eventId로 처리되는지 검증한다.
        String json = "{\"orderId\":\"order-123\"}";
        ConsumerRecord<String, String> record = createRecord("order-cancelled", "order-123", 20L, json);
        OrderEventPayload payload = createPayload();
        given(objectMapper.readValue(json, OrderEventPayload.class)).willReturn(payload);

        orderEventConsumer.handleOrderCancelled(record);

        verify(notificationService).sendNotification(
                eq(NotificationType.ORDER_CANCELLED),
                eq("order-cancelled:order-123:20"),
                eq("order-123"),
                eq(1L),
                eq("주문이 취소되었습니다"),
                contains("order-123")
        );
    }

    @Test
    @DisplayName("payment-completed 이벤트 수신 시 PAYMENT_COMPLETED 타입으로 알림을 처리한다")
    void handlePaymentCompleted_validEvent_callsNotificationService() throws Exception {
        // 결제 완료 이벤트가 올바른 NotificationType과 eventId로 처리되는지 검증한다.
        String json = "{\"orderId\":\"order-123\"}";
        ConsumerRecord<String, String> record = createRecord("payment-completed", "order-123", 7L, json);
        OrderEventPayload payload = createPayload();
        given(objectMapper.readValue(json, OrderEventPayload.class)).willReturn(payload);

        orderEventConsumer.handlePaymentCompleted(record);

        verify(notificationService).sendNotification(
                eq(NotificationType.PAYMENT_COMPLETED),
                eq("payment-completed:order-123:7"),
                eq("order-123"),
                eq(1L),
                eq("결제가 완료되었습니다"),
                contains("order-123")
        );
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 NotificationService를 호출하지 않는다")
    void handleOrderCreated_invalidJson_doesNotCallService() throws Exception {
        // 잘못된 JSON이 수신되면 파싱 실패 후 null을 반환하여
        // NotificationService 호출을 건너뛰어야 한다.
        // DLQ(Dead Letter Queue) 도입 전까지는 로그로만 추적한다.
        String invalidJson = "not-a-json";
        ConsumerRecord<String, String> record = createRecord("order-created", "order-123", 0L, invalidJson);
        given(objectMapper.readValue(invalidJson, OrderEventPayload.class))
                .willThrow(new JsonProcessingException("Invalid JSON") {});

        orderEventConsumer.handleOrderCreated(record);

        // 파싱 실패 시 NotificationService가 호출되지 않아야 한다
        verify(notificationService, never()).sendNotification(
                any(), anyString(), anyString(), anyLong(), anyString(), anyString()
        );
    }
}
