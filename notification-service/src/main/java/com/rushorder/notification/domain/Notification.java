package com.rushorder.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 엔티티.
 *
 * <p>Kafka Consumer 멱등성의 핵심 구조체.
 * eventId에 unique constraint를 걸어 동일 이벤트의 중복 처리를 DB 레벨에서 차단한다.
 *
 * <p>eventId 구성: {@code {topic}:{key}:{offset}}
 * - topic: Kafka 토픽명 (이벤트 유형 식별)
 * - key: Outbox aggregateId (주문 단위 파티션 보장)
 * - offset: 파티션 내 고유 위치 (같은 key의 재발행 구분)
 *
 * @see com.rushorder.notification.consumer.OrderEventConsumer
 */
@Entity
@Table(
        name = "notifications",
        indexes = @Index(name = "idx_notification_event_id", columnList = "eventId", unique = true)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Consumer 멱등성 키. {topic}:{key}:{offset} 조합으로 생성. */
    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    private LocalDateTime sentAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Notification(String eventId, String orderId, Long userId,
                        NotificationType type, String title, String message,
                        NotificationChannel channel) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.status = NotificationStatus.PENDING;
        this.title = title;
        this.message = message;
        this.channel = channel;
        this.createdAt = LocalDateTime.now();
    }

    /** 발송 성공 시 상태를 SENT로 전이한다. */
    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    /** 발송 실패 시 상태를 FAILED로 전이한다. */
    public void markFailed() {
        this.status = NotificationStatus.FAILED;
    }
}
