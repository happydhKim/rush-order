package com.rushorder.inventory.repository;

import com.rushorder.inventory.domain.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /**
     * 특정 주문의 특정 메뉴에 대한 예약을 조회한다.
     *
     * <p>confirm, release 시 해당 예약을 찾아 상태를 변경하기 위해 사용한다.
     */
    Optional<StockReservation> findByOrderIdAndMenuId(String orderId, Long menuId);

    /**
     * TTL이 만료된 예약을 조회한다.
     *
     * <p>status=RESERVED이면서 reservedUntil이 현재 시각보다 이전인 건을 대상으로 한다.
     * idx_reservation_expired 인덱스를 활용하여 효율적으로 조회한다.
     *
     * @param now 현재 시각
     * @return 만료된 예약 목록
     */
    @Query("SELECT r FROM StockReservation r WHERE r.status = 'RESERVED' AND r.reservedUntil < :now")
    List<StockReservation> findExpiredReservations(@Param("now") LocalDateTime now);
}
