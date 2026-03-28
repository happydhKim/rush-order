package com.rushorder.order.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 미발행 이벤트를 생성 시간 순으로 조회한다.
     * 폴링 워커가 배치 단위로 처리하기 위해 Pageable을 사용한다.
     */
    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);
}
