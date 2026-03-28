package com.rushorder.order.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * OutboxPublisher 단위 테스트.
 *
 * <p>Transactional Outbox 패턴의 폴링 워커가 미발행 이벤트를
 * 올바르게 Kafka로 발행하고 처리 완료 표시하는지 검증한다.
 *
 * <p>[면접 포인트] Outbox 패턴이 At-least-once를 보장하는 원리:
 * 비즈니스 엔티티와 Outbox 이벤트를 같은 DB 트랜잭션에서 저장하므로
 * "DB에는 저장되었지만 Kafka에는 발행되지 않는" 상황이 발생하지 않는다.
 * 단, Kafka 발행 후 DB 업데이트 전에 서버가 죽으면 중복 발행이 발생하므로
 * Consumer 측 멱등성이 필요하다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisher 단위 테스트")
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Nested
    @DisplayName("미발행 이벤트 처리")
    class PublishPendingEvents {

        @Test
        @DisplayName("미발행 이벤트가 있으면 Kafka로 발행하고 processed=true로 표시한다")
        void publishPendingEvents_withEvents_sendsToKafkaAndMarksProcessed() {
            // Outbox 폴링의 핵심 흐름:
            // 1) processed=false인 이벤트를 배치 조회
            // 2) 각 이벤트를 Kafka로 발행
            // 3) 발행 성공 시 processed=true로 업데이트
            OutboxEvent event1 = new OutboxEvent("Order", "order-1", "order-created", "{\"orderId\":\"order-1\"}");
            OutboxEvent event2 = new OutboxEvent("Order", "order-2", "order-confirmed", "{\"orderId\":\"order-2\"}");

            given(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                    .willReturn(List.of(event1, event2));

            outboxPublisher.publishPendingEvents();

            // Kafka 발행 검증 — 이벤트 수만큼 호출
            then(kafkaTemplate).should(times(2)).send(
                    any(String.class), any(String.class), any(String.class));

            // processed 상태 변경 검증
            assertThat(event1.isProcessed()).isTrue();
            assertThat(event2.isProcessed()).isTrue();
        }

        @Test
        @DisplayName("aggregateId가 Kafka 파티션 키로 사용되어 같은 주문 이벤트의 순서를 보장한다")
        void publishPendingEvents_usesAggregateIdAsPartitionKey() {
            // [면접 포인트] Kafka 파티션 키의 역할:
            // 같은 파티션 키를 가진 메시지는 같은 파티션에 들어가므로
            // 하나의 Consumer가 순서대로 처리한다. aggregateId(orderId)를
            // 파티션 키로 사용하면 같은 주문의 이벤트 순서가 보장된다.
            // 예: order-created → payment-requested → order-confirmed
            OutboxEvent event = new OutboxEvent("Order", "order-123", "order-created", "{\"data\":\"test\"}");

            given(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                    .willReturn(List.of(event));

            outboxPublisher.publishPendingEvents();

            // send(topic, key, payload)에서 key가 aggregateId인지 검증
            ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            then(kafkaTemplate).should().send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

            assertThat(topicCaptor.getValue()).isEqualTo("order-created");
            assertThat(keyCaptor.getValue()).isEqualTo("order-123");
            assertThat(payloadCaptor.getValue()).isEqualTo("{\"data\":\"test\"}");
        }

        @Test
        @DisplayName("미발행 이벤트가 없으면 Kafka send를 호출하지 않는다")
        void publishPendingEvents_noEvents_doesNotSend() {
            // 이벤트가 없는 정상 상황에서 불필요한 Kafka 호출이 발생하지 않는지 검증
            given(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                    .willReturn(Collections.emptyList());

            outboxPublisher.publishPendingEvents();

            then(kafkaTemplate).should(never()).send(any(String.class), any(String.class), any(String.class));
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 해당 이벤트의 processed는 false를 유지하고 후속 이벤트는 처리하지 않는다")
        void publishPendingEvents_kafkaFailure_stopsAndKeepsUnprocessed() {
            // [면접 포인트] 실패 시 break하는 이유:
            // Outbox 이벤트는 생성 시간 순으로 발행되어야 한다.
            // 중간 이벤트가 실패했는데 후속 이벤트를 발행하면 순서가 꼬인다.
            // 실패한 이벤트부터 다음 폴링 주기에서 재시도한다.
            OutboxEvent event1 = new OutboxEvent("Order", "order-1", "order-created", "{}");
            OutboxEvent event2 = new OutboxEvent("Order", "order-2", "order-confirmed", "{}");

            given(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                    .willReturn(List.of(event1, event2));
            given(kafkaTemplate.send(eq("order-created"), eq("order-1"), any()))
                    .willThrow(new RuntimeException("Kafka broker unavailable"));

            outboxPublisher.publishPendingEvents();

            // 실패한 이벤트는 processed=false 유지
            assertThat(event1.isProcessed()).isFalse();
            // 후속 이벤트도 처리하지 않음
            assertThat(event2.isProcessed()).isFalse();
            // Kafka send는 1번만 호출 (첫 이벤트 실패 후 break)
            then(kafkaTemplate).should(times(1)).send(any(String.class), any(String.class), any(String.class));
        }
    }
}
