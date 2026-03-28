package com.rushorder.inventory.service;

import com.rushorder.inventory.domain.StockReservation;
import com.rushorder.inventory.repository.InventoryRepository;
import com.rushorder.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 만료된 재고 예약을 자동으로 해제하는 스케줄러.
 *
 * <p>설계 의도:
 * 주문 후 5분 이내에 결제가 완료되지 않으면 예약된 재고를 자동으로 해제한다.
 * 이를 통해 "유령 예약"으로 인한 재고 고갈을 방지한다.
 *
 * <p>[면접 포인트] 분산 환경에서 여러 인스턴스가 동시에 스케줄러를 실행하면
 * 중복 해제가 발생할 수 있다. 이를 방지하려면 ShedLock 등의 분산 락이 필요하다.
 * 현재는 단일 인스턴스를 전제로 하되, 비관적 락으로 최소한의 안전장치를 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCleanupScheduler {

    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 1분 간격으로 만료된 예약을 조회하고 재고를 해제한다.
     *
     * <p>각 만료 예약에 대해 비관적 락으로 Inventory를 잠근 후
     * reservedStock을 원복하고, StockReservation 상태를 EXPIRED로 변경한다.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void releaseExpiredReservations() {
        List<StockReservation> expired = stockReservationRepository
                .findExpiredReservations(LocalDateTime.now());

        if (expired.isEmpty()) {
            return;
        }

        int released = 0;
        for (StockReservation reservation : expired) {
            inventoryRepository.findByMenuIdForUpdate(reservation.getMenuId())
                    .ifPresent(inventory -> {
                        inventory.release(reservation.getQuantity());
                        reservation.expire();
                        log.debug("Expired reservation: orderId={}, menuId={}, quantity={}",
                                reservation.getOrderId(), reservation.getMenuId(),
                                reservation.getQuantity());
                    });
            released++;
        }

        log.info("Released {} expired reservations", released);
    }
}
