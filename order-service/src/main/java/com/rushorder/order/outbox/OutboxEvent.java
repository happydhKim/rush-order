package com.rushorder.order.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 엔티티.
 *
 * <p>Transactional Outbox 패턴의 핵심. 비즈니스 엔티티와 같은 트랜잭션에서
 * 저장되어 DB 커밋의 원자성을 보장한다. 별도 폴링 워커가 미발행 이벤트를
 * Kafka로 전송한 뒤 {@code processed = true}로 표시한다.
 *
 * <p>At-least-once 보장: 폴링 워커가 Kafka 발행 후 DB 업데이트 전에
 * 죽으면 중복 발행이 발생한다. 이는 설계상 의도된 동작이며,
 * Consumer 측 멱등성으로 해결한다.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_unprocessed", columnList = "processed, createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Aggregate 타입 (e.g., "Order") */
    @Column(nullable = false)
    private String aggregateType;

    /** Aggregate ID (e.g., orderId). Kafka 파티션 키로 사용된다. */
    @Column(nullable = false)
    private String aggregateId;

    /** Kafka 토픽명 (e.g., "order-created", "payment-requested") */
    @Column(nullable = false)
    private String eventType;

    /** JSON 직렬화된 이벤트 페이로드 */
    @Column(columnDefinition = "text", nullable = false)
    private String payload;

    @Column(nullable = false)
    private boolean processed;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.processed = false;
        this.createdAt = LocalDateTime.now();
    }

    public void markAsProcessed() {
        this.processed = true;
        this.processedAt = LocalDateTime.now();
    }
}
