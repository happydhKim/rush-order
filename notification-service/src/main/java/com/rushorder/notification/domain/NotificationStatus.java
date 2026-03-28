package com.rushorder.notification.domain;

/**
 * 알림 발송 상태.
 *
 * <p>상태 전이: PENDING -> SENT 또는 PENDING -> FAILED.
 * 한번 SENT/FAILED로 전이되면 되돌리지 않는다.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
