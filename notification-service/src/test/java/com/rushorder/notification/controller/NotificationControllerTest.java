package com.rushorder.notification.controller;

import com.rushorder.notification.domain.Notification;
import com.rushorder.notification.domain.NotificationChannel;
import com.rushorder.notification.domain.NotificationType;
import com.rushorder.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NotificationController MockMvc 테스트.
 *
 * <p>알림 조회 API의 HTTP 계층을 검증한다.
 * 알림 생성은 Kafka Consumer를 통해서만 이루어지므로,
 * 이 컨트롤러는 조회(GET) 엔드포인트만 테스트한다.
 */
@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    private Notification createNotification(String eventId, String orderId, Long userId) {
        return new Notification(
                eventId, orderId, userId,
                NotificationType.ORDER_CREATED,
                "새 주문이 접수되었습니다",
                "주문번호: " + orderId,
                NotificationChannel.PUSH
        );
    }

    @Test
    @DisplayName("GET /api/notifications/orders/{orderId} - 주문별 알림 목록 조회")
    void getNotificationsByOrderId_returnsOkWithList() throws Exception {
        // 주문 ID로 알림 이력을 조회하면 200 OK와 함께 알림 목록을 반환해야 한다.
        String orderId = "order-123";
        Notification notification = createNotification("order-created:order-123:0", orderId, 1L);
        given(notificationService.getNotificationsByOrderId(orderId))
                .willReturn(List.of(notification));

        mockMvc.perform(get("/api/notifications/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].type").value("ORDER_CREATED"))
                .andExpect(jsonPath("$[0].title").value("새 주문이 접수되었습니다"));
    }

    @Test
    @DisplayName("GET /api/notifications/orders/{orderId} - 알림이 없으면 빈 리스트 반환")
    void getNotificationsByOrderId_noNotifications_returnsEmptyList() throws Exception {
        // 해당 주문에 알림이 없으면 빈 배열을 반환해야 한다 (404가 아님).
        given(notificationService.getNotificationsByOrderId("order-999"))
                .willReturn(Collections.emptyList());

        mockMvc.perform(get("/api/notifications/orders/{orderId}", "order-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/notifications/users/{userId} - 사용자별 알림 목록 조회")
    void getNotificationsByUserId_returnsOkWithList() throws Exception {
        // 사용자 ID로 알림 이력을 조회하면 200 OK와 함께 알림 목록을 반환해야 한다.
        Long userId = 1L;
        Notification notification = createNotification("order-created:order-123:0", "order-123", userId);
        given(notificationService.getNotificationsByUserId(userId))
                .willReturn(List.of(notification));

        mockMvc.perform(get("/api/notifications/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value("order-123"));
    }

    @Test
    @DisplayName("GET /api/notifications/users/{userId} - 알림이 없으면 빈 리스트 반환")
    void getNotificationsByUserId_noNotifications_returnsEmptyList() throws Exception {
        // 해당 사용자에 알림이 없으면 빈 배열을 반환해야 한다.
        given(notificationService.getNotificationsByUserId(999L))
                .willReturn(Collections.emptyList());

        mockMvc.perform(get("/api/notifications/users/{userId}", 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
