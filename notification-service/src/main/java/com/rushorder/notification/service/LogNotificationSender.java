package com.rushorder.notification.service;

import com.rushorder.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로그 기반 알림 발송 구현체.
 *
 * <p>개발/테스트 환경에서 실제 외부 시스템(FCM, SMS 등) 없이
 * 알림 흐름을 검증하기 위한 구현체다.
 * 프로덕션에서는 {@code notification.sender.type=fcm} 등으로 교체한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.sender.type", havingValue = "log", matchIfMissing = true)
public class LogNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        log.info("[NOTIFICATION] channel={}, type={}, orderId={}, userId={}, title='{}', message='{}'",
                notification.getChannel(),
                notification.getType(),
                notification.getOrderId(),
                notification.getUserId(),
                notification.getTitle(),
                notification.getMessage());
    }
}
