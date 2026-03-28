package com.rushorder.notification.controller;

import com.rushorder.notification.dto.NotificationResponse;
import com.rushorder.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 알림 조회 API.
 *
 * <p>사용자 또는 관리자가 발송된 알림 이력을 확인하기 위한 엔드포인트.
 * 알림 생성은 Kafka Consumer를 통해서만 이루어진다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 특정 주문의 알림 목록을 조회한다.
     *
     * @param orderId 주문 식별자
     * @return 해당 주문의 알림 목록 (최신순)
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<List<NotificationResponse>> getNotificationsByOrderId(
            @PathVariable String orderId) {
        List<NotificationResponse> responses = notificationService
                .getNotificationsByOrderId(orderId)
                .stream()
                .map(NotificationResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * 특정 사용자의 알림 목록을 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 해당 사용자의 알림 목록 (최신순)
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<List<NotificationResponse>> getNotificationsByUserId(
            @PathVariable Long userId) {
        List<NotificationResponse> responses = notificationService
                .getNotificationsByUserId(userId)
                .stream()
                .map(NotificationResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }
}
