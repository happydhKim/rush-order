package com.rushorder.notification.repository;

import com.rushorder.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 알림 영속성 레이어.
 *
 * <p>existsByEventId()는 Consumer 멱등성 체크의 1차 방어선이다.
 * eventId의 unique constraint가 동시 요청에 대한 최후의 안전장치 역할을 한다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 이미 처리된 이벤트인지 확인한다. Consumer 멱등성 체크용. */
    boolean existsByEventId(String eventId);

    /** 특정 주문의 알림 목록을 최신순으로 조회한다. */
    List<Notification> findByOrderIdOrderByCreatedAtDesc(String orderId);

    /** 특정 사용자의 알림 목록을 최신순으로 조회한다. */
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
}
