package com.rushorder.notification.domain;

/**
 * 알림 발송 채널.
 *
 * <p>현재는 PUSH만 사용하며, 향후 SMS/EMAIL 채널로 확장 가능.
 */
public enum NotificationChannel {
    PUSH,
    SMS,
    EMAIL
}
