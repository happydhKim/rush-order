package com.rushorder.notification.service;

import com.rushorder.notification.domain.Notification;
import com.rushorder.notification.domain.NotificationChannel;
import com.rushorder.notification.domain.NotificationType;
import com.rushorder.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 생성 및 발송을 담당하는 서비스.
 *
 * <p>Consumer 멱등성 보장 전략 (2단계 방어):
 * <ol>
 *   <li>1차: {@code existsByEventId()} SELECT 체크 - 대부분의 중복을 빠르게 걸러냄</li>
 *   <li>2차: eventId unique constraint - 동시 요청 시 {@link DataIntegrityViolationException}으로 차단</li>
 * </ol>
 *
 * <p>왜 SELECT 체크만으로 부족한가?
 * 두 Consumer 스레드가 동시에 같은 eventId로 existsByEventId()를 호출하면
 * 둘 다 false를 받을 수 있다(TOCTOU 문제). DB unique constraint가 최후의 안전장치다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    /**
     * 알림을 생성하고 발송한다.
     *
     * <p>eventId 기반 멱등성을 보장하므로, 같은 이벤트가 중복 수신되어도
     * 알림은 최대 한 번만 발송된다.
     *
     * @param type    알림 유형
     * @param eventId 멱등성 키 ({topic}:{key}:{offset})
     * @param orderId 주문 식별자
     * @param userId  사용자 식별자
     * @param title   알림 제목
     * @param message 알림 본문
     */
    @Transactional
    public void sendNotification(NotificationType type, String eventId,
                                 String orderId, Long userId,
                                 String title, String message) {
        // 1차 방어: 이미 처리된 이벤트는 무시
        if (notificationRepository.existsByEventId(eventId)) {
            log.debug("Duplicate event ignored: eventId={}", eventId);
            return;
        }

        Notification notification = new Notification(
                eventId, orderId, userId, type,
                title, message, NotificationChannel.PUSH
        );

        try {
            // 2차 방어: unique constraint 위반 시 DataIntegrityViolationException
            notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate event caught by unique constraint: eventId={}", eventId);
            return;
        }

        try {
            notificationSender.send(notification);
            notification.markSent();
        } catch (Exception e) {
            log.error("Failed to send notification: eventId={}, error={}", eventId, e.getMessage(), e);
            notification.markFailed();
        }
    }

    /**
     * 특정 주문의 알림 목록을 조회한다.
     *
     * @param orderId 주문 식별자
     * @return 해당 주문의 알림 목록 (최신순)
     */
    @Transactional(readOnly = true)
    public List<Notification> getNotificationsByOrderId(String orderId) {
        return notificationRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    /**
     * 특정 사용자의 알림 목록을 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 해당 사용자의 알림 목록 (최신순)
     */
    @Transactional(readOnly = true)
    public List<Notification> getNotificationsByUserId(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
