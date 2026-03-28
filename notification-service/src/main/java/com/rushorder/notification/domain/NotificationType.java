package com.rushorder.notification.domain;

/**
 * 알림을 트리거하는 이벤트 유형.
 *
 * <p>각 유형은 Kafka 토픽과 1:1로 매핑되며,
 * 알림 제목과 메시지 템플릿을 결정하는 기준이 된다.
 */
public enum NotificationType {
    ORDER_CREATED,
    PAYMENT_COMPLETED,
    ORDER_CONFIRMED,
    ORDER_CANCELLED
}
