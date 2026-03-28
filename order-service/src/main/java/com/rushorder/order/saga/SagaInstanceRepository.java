package com.rushorder.order.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, String> {

    Optional<SagaInstance> findByOrderId(String orderId);

    /**
     * 장기 미완료(stuck) Saga를 조회한다.
     *
     * <p>특정 상태에서 일정 시간 이상 머물러 있는 Saga를 찾아
     * 타임아웃 처리하거나 보상을 재시도하는 데 사용한다.
     *
     * @param status 조회할 Saga 상태
     * @param before 이 시간 이전에 업데이트된 Saga만 조회
     */
    @Query("SELECT s FROM SagaInstance s WHERE s.status = :status AND s.updatedAt < :before")
    List<SagaInstance> findStuckSagas(@Param("status") SagaStatus status,
                                     @Param("before") LocalDateTime before);
}
