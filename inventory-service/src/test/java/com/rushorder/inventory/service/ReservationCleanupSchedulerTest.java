package com.rushorder.inventory.service;

import com.rushorder.inventory.domain.Inventory;
import com.rushorder.inventory.domain.StockReservation;
import com.rushorder.inventory.domain.StockReservationStatus;
import com.rushorder.inventory.repository.InventoryRepository;
import com.rushorder.inventory.repository.StockReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ReservationCleanupScheduler 단위 테스트.
 *
 * <p>TTL이 만료된 예약을 자동으로 해제하는 스케줄러의 로직을 검증한다.
 * @Scheduled 어노테이션에 의한 실행 주기는 통합 테스트에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ReservationCleanupScheduler scheduler;

    @Test
    @DisplayName("만료된 예약이 있으면 재고를 해제하고 상태를 EXPIRED로 변경한다")
    void shouldReleaseExpiredReservations() {
        // TTL이 만료된 예약(status=RESERVED, reservedUntil < now)을 조회하여
        // 해당 Inventory의 reservedStock을 원복하고, StockReservation의 상태를
        // EXPIRED로 변경한다. 이를 통해 "유령 예약"으로 인한 재고 고갈을 방지한다.
        Inventory inventory = new Inventory(1L, 100);
        inventory.reserve(10);

        StockReservation expiredReservation = new StockReservation(1L, "order-1", 10);

        given(stockReservationRepository.findExpiredReservations(any(LocalDateTime.class)))
                .willReturn(List.of(expiredReservation));
        given(inventoryRepository.findByMenuIdForUpdate(1L))
                .willReturn(Optional.of(inventory));

        scheduler.releaseExpiredReservations();

        // Inventory의 reservedStock이 원복되었는지 확인
        assertThat(inventory.getReservedStock()).isEqualTo(0);
        assertThat(inventory.getAvailableStock()).isEqualTo(100);

        // StockReservation 상태가 EXPIRED로 변경되었는지 확인
        assertThat(expiredReservation.getStatus()).isEqualTo(StockReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료된 예약이 없으면 아무 작업도 수행하지 않는다")
    void shouldDoNothingWhenNoExpiredReservations() {
        // 만료된 예약이 없는 경우 early return하여 불필요한 DB 접근을 방지한다.
        given(stockReservationRepository.findExpiredReservations(any(LocalDateTime.class)))
                .willReturn(Collections.emptyList());

        scheduler.releaseExpiredReservations();

        // InventoryRepository에 대한 접근이 없어야 한다.
        verify(inventoryRepository, never()).findByMenuIdForUpdate(any());
    }

    @Test
    @DisplayName("여러 건의 만료된 예약을 한 번에 처리한다")
    void shouldHandleMultipleExpiredReservations() {
        // 스케줄러 실행 간격(1분) 동안 여러 예약이 만료될 수 있다.
        // 모든 만료 건을 순회하며 각각 재고를 해제해야 한다.
        Inventory inv1 = new Inventory(1L, 100);
        inv1.reserve(5);
        Inventory inv2 = new Inventory(2L, 50);
        inv2.reserve(3);

        StockReservation reservation1 = new StockReservation(1L, "order-1", 5);
        StockReservation reservation2 = new StockReservation(2L, "order-2", 3);

        given(stockReservationRepository.findExpiredReservations(any(LocalDateTime.class)))
                .willReturn(List.of(reservation1, reservation2));
        given(inventoryRepository.findByMenuIdForUpdate(1L))
                .willReturn(Optional.of(inv1));
        given(inventoryRepository.findByMenuIdForUpdate(2L))
                .willReturn(Optional.of(inv2));

        scheduler.releaseExpiredReservations();

        assertThat(inv1.getReservedStock()).isEqualTo(0);
        assertThat(inv2.getReservedStock()).isEqualTo(0);
        assertThat(reservation1.getStatus()).isEqualTo(StockReservationStatus.EXPIRED);
        assertThat(reservation2.getStatus()).isEqualTo(StockReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("Inventory가 삭제된 경우 해당 예약은 건너뛴다")
    void shouldSkipWhenInventoryNotFound() {
        // 만료된 예약의 menuId에 해당하는 Inventory가 없는 경우(삭제됨),
        // 해당 건은 건너뛰고 나머지를 계속 처리해야 한다.
        StockReservation reservation = new StockReservation(999L, "order-1", 5);

        given(stockReservationRepository.findExpiredReservations(any(LocalDateTime.class)))
                .willReturn(List.of(reservation));
        given(inventoryRepository.findByMenuIdForUpdate(999L))
                .willReturn(Optional.empty());

        // 예외 없이 정상 종료되어야 한다
        scheduler.releaseExpiredReservations();

        // Inventory가 없으므로 expire()가 호출되지 않아야 한다
        assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
    }
}
