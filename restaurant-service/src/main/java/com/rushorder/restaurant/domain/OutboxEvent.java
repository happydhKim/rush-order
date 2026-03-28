package com.rushorder.restaurant.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 패턴을 위한 이벤트 엔티티.
 *
 * <p>비즈니스 트랜잭션과 동일한 TX 내에서 INSERT되어,
 * 별도 워커(또는 Kafka Connect)가 이 테이블을 폴링하여 Kafka로 발행한다.
 * 이를 통해 "DB 저장 + 이벤트 발행"의 원자성을 보장한다.
 *
 * <p>Restaurant Service는 자체 outbox_events 테이블을 사용하며,
 * Order Service의 Outbox와 동일한 구조를 따른다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kafka 토픽명 */
    @Column(nullable = false)
    private String topic;

    /** 이벤트 대상의 식별자 (파티션 키로 사용) */
    @Column(nullable = false)
    private String aggregateId;

    /** 이벤트 타입 (예: RESTAURANT_UPDATED, MENU_ADDED) */
    @Column(nullable = false)
    private String eventType;

    /** JSON 직렬화된 이벤트 페이로드 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** 발행 완료 여부. 워커가 발행 후 true로 변경한다. */
    @Column(nullable = false)
    private boolean published;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public OutboxEvent(String topic, String aggregateId, String eventType, String payload) {
        this.topic = topic;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.published = false;
        this.createdAt = LocalDateTime.now();
    }

    public void markPublished() {
        this.published = true;
    }
}
