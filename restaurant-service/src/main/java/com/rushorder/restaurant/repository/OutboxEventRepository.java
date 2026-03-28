package com.rushorder.restaurant.repository;

import com.rushorder.restaurant.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Outbox 이벤트 레포지토리.
 *
 * <p>미발행 이벤트를 조회하여 Kafka로 발행하는 워커에서 사용한다.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
