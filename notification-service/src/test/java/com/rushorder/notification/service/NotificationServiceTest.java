package com.rushorder.notification.service;

import com.rushorder.notification.domain.Notification;
import com.rushorder.notification.domain.NotificationChannel;
import com.rushorder.notification.domain.NotificationStatus;
import com.rushorder.notification.domain.NotificationType;
import com.rushorder.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * NotificationService 단위 테스트.
 *
 * <p>Kafka Consumer 멱등성 보장의 핵심 로직을 검증한다.
 * 2계층 멱등성 방어(SELECT 체크 + DB unique constraint)가
 * 중복 이벤트를 올바르게 처리하는지 테스트한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSender notificationSender;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("정상 알림 생성 및 발송 시 SENT 상태로 전이된다")
    void sendNotification_success_marksSent() {
        // 새로운 이벤트가 들어오면 저장 후 발송하고, 상태를 SENT로 변경해야 한다.
        // 이는 알림 파이프라인의 기본 정상 흐름을 검증한다.
        String eventId = "order-created:order-123:0";
        given(notificationRepository.existsByEventId(eventId)).willReturn(false);
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        notificationService.sendNotification(
                NotificationType.ORDER_CREATED, eventId,
                "order-123", 1L, "새 주문", "주문번호: order-123"
        );

        // save가 호출되었는지, sender가 호출되었는지 검증
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        verify(notificationSender).send(captor.getValue());

        // 발송 성공 후 상태가 SENT로 전이되었는지 확인
        Notification saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("1차 방어: 중복 eventId는 SELECT 체크에서 걸러진다")
    void sendNotification_duplicateEventId_ignoredBySelectCheck() {
        // 이미 처리된 이벤트(existsByEventId = true)가 재수신되면
        // save/send를 호출하지 않고 즉시 리턴해야 한다.
        // 이것이 멱등성 1차 방어선(SELECT 체크)의 동작이다.
        String eventId = "order-created:order-123:0";
        given(notificationRepository.existsByEventId(eventId)).willReturn(true);

        notificationService.sendNotification(
                NotificationType.ORDER_CREATED, eventId,
                "order-123", 1L, "새 주문", "주문번호: order-123"
        );

        // DB 저장과 발송이 모두 호출되지 않아야 한다
        verify(notificationRepository, never()).save(any());
        verify(notificationSender, never()).send(any());
    }

    @Test
    @DisplayName("2차 방어: TOCTOU 경쟁 조건에서 unique constraint가 중복을 차단한다")
    void sendNotification_uniqueConstraintViolation_caughtGracefully() {
        // [면접 포인트] 2계층 멱등성 방어 (SELECT + unique constraint)의 필요성
        //
        // SELECT 체크(1차 방어)만으로는 동시 요청을 완전히 방어할 수 없다.
        // 두 Consumer 스레드가 동시에 existsByEventId()를 호출하면 둘 다 false를 받을 수 있다.
        // 이것이 TOCTOU(Time-of-Check-Time-of-Use) 문제다.
        //
        // DB unique constraint(2차 방어)가 최후의 안전장치 역할을 하여,
        // 두 번째 INSERT 시 DataIntegrityViolationException이 발생하고
        // 이를 catch하여 정상 리턴한다. 알림은 최대 한 번만 발송된다.
        String eventId = "order-created:order-123:0";
        given(notificationRepository.existsByEventId(eventId)).willReturn(false);
        given(notificationRepository.save(any(Notification.class)))
                .willThrow(new DataIntegrityViolationException("Unique constraint violated"));

        // 예외가 외부로 전파되지 않고 정상 리턴되어야 한다
        notificationService.sendNotification(
                NotificationType.ORDER_CREATED, eventId,
                "order-123", 1L, "새 주문", "주문번호: order-123"
        );

        // unique constraint 위반 시 발송이 호출되지 않아야 한다
        verify(notificationSender, never()).send(any());
    }

    @Test
    @DisplayName("발송 실패 시 FAILED 상태로 전이된다")
    void sendNotification_sendFails_marksNotificationFailed() {
        // 외부 발송 시스템(FCM, SMS 등) 장애 시 예외가 발생하면
        // 알림 상태를 FAILED로 전이하여 재시도 대상으로 관리할 수 있어야 한다.
        String eventId = "order-created:order-456:1";
        given(notificationRepository.existsByEventId(eventId)).willReturn(false);
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("FCM 서버 장애"))
                .when(notificationSender).send(any(Notification.class));

        notificationService.sendNotification(
                NotificationType.ORDER_CREATED, eventId,
                "order-456", 2L, "새 주문", "주문번호: order-456"
        );

        // 발송 실패 후 상태가 FAILED로 전이되었는지 확인
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    @DisplayName("주문 ID로 알림 목록을 최신순 조회한다")
    void getNotificationsByOrderId_returnsList() {
        // 특정 주문의 알림 이력을 조회하는 기본 흐름을 검증한다.
        String orderId = "order-123";
        Notification notification = new Notification(
                "order-created:order-123:0", orderId, 1L,
                NotificationType.ORDER_CREATED, "새 주문", "내용",
                NotificationChannel.PUSH
        );
        given(notificationRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .willReturn(List.of(notification));

        List<Notification> result = notificationService.getNotificationsByOrderId(orderId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("사용자 ID로 알림 목록을 최신순 조회한다")
    void getNotificationsByUserId_returnsList() {
        // 특정 사용자의 알림 이력을 조회하는 기본 흐름을 검증한다.
        Long userId = 1L;
        Notification notification = new Notification(
                "order-created:order-123:0", "order-123", userId,
                NotificationType.ORDER_CREATED, "새 주문", "내용",
                NotificationChannel.PUSH
        );
        given(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .willReturn(List.of(notification));

        List<Notification> result = notificationService.getNotificationsByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(userId);
    }
}
