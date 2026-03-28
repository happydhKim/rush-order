package com.rushorder.notification.dto;

import com.rushorder.notification.domain.Notification;
import com.rushorder.notification.domain.NotificationStatus;
import com.rushorder.notification.domain.NotificationType;

import java.time.LocalDateTime;

/**
 * 알림 조회 응답 DTO.
 */
public record NotificationResponse(
        Long id,
        String orderId,
        NotificationType type,
        NotificationStatus status,
        String title,
        String message,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getOrderId(),
                notification.getType(),
                notification.getStatus(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getCreatedAt()
        );
    }
}
