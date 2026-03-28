package com.rushorder.notification.service;

import com.rushorder.notification.domain.Notification;

/**
 * 알림 발송 추상화.
 *
 * <p>구현체를 교체하여 PUSH(FCM), SMS, Email 등 다양한 채널로 발송할 수 있다.
 * {@code notification.sender.type} 프로퍼티로 구현체를 선택한다.
 *
 * @see LogNotificationSender
 */
public interface NotificationSender {

    /**
     * 알림을 발송한다.
     *
     * @param notification 발송할 알림 엔티티
     * @throws RuntimeException 발송 실패 시
     */
    void send(Notification notification);
}
