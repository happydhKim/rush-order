package com.rushorder.inventory.service;

import com.rushorder.common.exception.InsufficientStockException;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.inventory.domain.Inventory;
import com.rushorder.inventory.domain.StockReservation;
import com.rushorder.inventory.domain.StockReservationStatus;
import com.rushorder.inventory.dto.StockReserveRequest;
import com.rushorder.inventory.dto.StockReserveRequest.StockItem;
import com.rushorder.inventory.repository.InventoryRepository;
import com.rushorder.inventory.repository.StockReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * InventoryService 단위 테스트.
 *
 * <p>Repository를 Mock으로 대체하여 서비스 계층의 비즈니스 로직만 검증한다.
 * 비관적 락이나 @Retryable 같은 인프라 관심사는 통합 테스트에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @InjectMocks
    private InventoryService inventoryService;

    @Nested
    @DisplayName("reserveStock (재고 예약)")
    class ReserveStock {

        @Test
        @DisplayName("정상적으로 재고를 예약하면 Inventory.reserve()와 StockReservation 생성이 수행된다")
        void shouldReserveStockAndCreateReservation() {
            // 재고가 충분한 상태에서 예약 요청이 오면,
            // Inventory.reserve()로 예약 수량을 반영하고
            // StockReservation을 새로 생성하여 건별 추적이 가능해야 한다.
            Inventory inventory = new Inventory(1L, 100);
            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));

            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(new StockItem(1L, 10))
            );

            inventoryService.reserveStock(request);

            // Inventory.reserve()가 호출되어 reservedStock이 증가했는지 확인
            assertThat(inventory.getReservedStock()).isEqualTo(10);
            assertThat(inventory.getAvailableStock()).isEqualTo(90);

            // StockReservation이 저장되었는지 확인
            ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
            verify(stockReservationRepository).save(captor.capture());

            StockReservation saved = captor.getValue();
            assertThat(saved.getMenuId()).isEqualTo(1L);
            assertThat(saved.getOrderId()).isEqualTo("order-1");
            assertThat(saved.getQuantity()).isEqualTo(10);
            assertThat(saved.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        }

        @Test
        @DisplayName("재고가 부족하면 InsufficientStockException이 발생한다")
        void shouldThrowWhenInsufficientStock() {
            // 가용 재고보다 많은 수량을 예약하면 예외가 발생하여
            // 트랜잭션이 롤백되고, StockReservation도 생성되지 않아야 한다.
            Inventory inventory = new Inventory(1L, 5);
            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));

            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(new StockItem(1L, 10))
            );

            assertThatThrownBy(() -> inventoryService.reserveStock(request))
                    .isInstanceOf(InsufficientStockException.class);

            // 예외 발생 시 StockReservation이 저장되지 않았는지 확인
            verify(stockReservationRepository, never()).save(any());
        }

        @Test
        @DisplayName("menuId 오름차순으로 정렬하여 락을 획득한다 (데드락 방지)")
        void shouldSortByMenuIdToPreventDeadlock() {
            // [면접 포인트] 데드락 방지를 위한 락 획득 순서 정렬:
            // 두 트랜잭션이 (A, B)와 (B, A) 순서로 락을 요청하면 데드락이 발생한다.
            // menuId 오름차순으로 정렬하여 모든 트랜잭션이 동일한 순서로 락을 획득하게 하면
            // 데드락을 원천 차단할 수 있다.
            // 이 기법은 "Lock Ordering" 또는 "Resource Ordering"으로 불린다.
            Inventory inv1 = new Inventory(1L, 100);
            Inventory inv2 = new Inventory(2L, 100);
            Inventory inv3 = new Inventory(3L, 100);

            given(inventoryRepository.findByMenuIdForUpdate(1L)).willReturn(Optional.of(inv1));
            given(inventoryRepository.findByMenuIdForUpdate(2L)).willReturn(Optional.of(inv2));
            given(inventoryRepository.findByMenuIdForUpdate(3L)).willReturn(Optional.of(inv3));

            // 의도적으로 menuId를 역순(3, 1, 2)으로 전달
            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(
                            new StockItem(3L, 5),
                            new StockItem(1L, 5),
                            new StockItem(2L, 5)
                    )
            );

            inventoryService.reserveStock(request);

            // findByMenuIdForUpdate 호출 순서가 1 -> 2 -> 3 (오름차순)인지 검증
            var inOrder = inOrder(inventoryRepository);
            inOrder.verify(inventoryRepository).findByMenuIdForUpdate(1L);
            inOrder.verify(inventoryRepository).findByMenuIdForUpdate(2L);
            inOrder.verify(inventoryRepository).findByMenuIdForUpdate(3L);
        }

        @Test
        @DisplayName("존재하지 않는 menuId로 예약하면 NotFoundException이 발생한다")
        void shouldThrowWhenMenuNotFound() {
            // Inventory 테이블에 없는 menuId로 예약 요청이 오면
            // NotFoundException을 발생시켜 잘못된 메뉴 ID를 알려야 한다.
            given(inventoryRepository.findByMenuIdForUpdate(999L))
                    .willReturn(Optional.empty());

            StockReserveRequest request = new StockReserveRequest(
                    "order-1",
                    List.of(new StockItem(999L, 5))
            );

            assertThatThrownBy(() -> inventoryService.reserveStock(request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("confirmStock (재고 확정)")
    class ConfirmStock {

        @Test
        @DisplayName("정상적으로 확정하면 Inventory.confirm()과 StockReservation.confirm()이 호출된다")
        void shouldConfirmStockAndReservation() {
            // 결제 성공 후 Saga가 confirmStock을 호출하면,
            // Inventory에서 재고를 확정하고 StockReservation 상태를 CONFIRMED로 변경한다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(10);

            StockReservation reservation = new StockReservation(1L, "order-1", 10);

            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));
            given(stockReservationRepository.findByOrderIdAndMenuId("order-1", 1L))
                    .willReturn(Optional.of(reservation));

            inventoryService.confirmStock("order-1", 1L, 10);

            // Inventory.confirm() 결과: totalStock=90, reservedStock=0
            assertThat(inventory.getTotalStock()).isEqualTo(90);
            assertThat(inventory.getReservedStock()).isEqualTo(0);
            // StockReservation 상태가 CONFIRMED로 변경되었는지 확인
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("이미 CONFIRMED된 예약에 대해 다시 확정해도 정상 처리된다 (멱등성)")
        void shouldBeIdempotentForAlreadyConfirmed() {
            // [면접 포인트] 멱등성(Idempotency):
            // 네트워크 장애로 Saga가 confirmStock을 재시도할 수 있다.
            // 이미 CONFIRMED 상태인 예약에 confirm()을 다시 호출해도
            // 상태가 유지되며 예외가 발생하지 않아야 한다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(10);
            inventory.confirm(10);  // 이미 한 번 확정

            StockReservation reservation = new StockReservation(1L, "order-1", 10);
            reservation.confirm();  // 이미 CONFIRMED 상태

            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));
            given(stockReservationRepository.findByOrderIdAndMenuId("order-1", 1L))
                    .willReturn(Optional.of(reservation));

            // 두 번째 확정 — 예외 없이 정상 처리되어야 함
            inventoryService.confirmStock("order-1", 1L, 10);

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("StockReservation이 없어도 Inventory 확정은 수행된다 (하위 호환성)")
        void shouldConfirmInventoryEvenWithoutReservation() {
            // StockReservation 도입 이전 버전과의 하위 호환성을 위해
            // reservation이 없더라도 Inventory.confirm()은 정상 수행해야 한다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(10);

            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));
            given(stockReservationRepository.findByOrderIdAndMenuId("order-1", 1L))
                    .willReturn(Optional.empty());

            inventoryService.confirmStock("order-1", 1L, 10);

            assertThat(inventory.getTotalStock()).isEqualTo(90);
            assertThat(inventory.getReservedStock()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("releaseStock (재고 해제)")
    class ReleaseStock {

        @Test
        @DisplayName("정상적으로 해제하면 Inventory.release()와 StockReservation.release()가 호출된다")
        void shouldReleaseStockAndReservation() {
            // 결제 실패 시 Saga 보상 트랜잭션으로 재고를 해제한다.
            // reservedStock이 감소하여 가용 재고가 복구되고,
            // StockReservation 상태가 RELEASED로 변경된다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(10);

            StockReservation reservation = new StockReservation(1L, "order-1", 10);

            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));
            given(stockReservationRepository.findByOrderIdAndMenuId("order-1", 1L))
                    .willReturn(Optional.of(reservation));

            inventoryService.releaseStock("order-1", 1L, 10);

            assertThat(inventory.getReservedStock()).isEqualTo(0);
            assertThat(inventory.getAvailableStock()).isEqualTo(100);
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        }

        @Test
        @DisplayName("이미 RELEASED된 예약에 대해 다시 해제해도 정상 처리된다 (멱등성)")
        void shouldBeIdempotentForAlreadyReleased() {
            // [면접 포인트] 멱등성:
            // Saga 보상 트랜잭션이 네트워크 장애로 재시도될 수 있다.
            // 이미 RELEASED 상태인 예약에 release()를 다시 호출해도
            // 상태가 유지되며 예외가 발생하지 않아야 한다.
            Inventory inventory = new Inventory(1L, 100);
            // 이미 해제된 상태이므로 reservedStock=0

            StockReservation reservation = new StockReservation(1L, "order-1", 10);
            reservation.release();  // 이미 RELEASED 상태

            given(inventoryRepository.findByMenuIdForUpdate(1L))
                    .willReturn(Optional.of(inventory));
            given(stockReservationRepository.findByOrderIdAndMenuId("order-1", 1L))
                    .willReturn(Optional.of(reservation));

            // 두 번째 해제 — 예외 없이 정상 처리되어야 함
            inventoryService.releaseStock("order-1", 1L, 10);

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        }
    }
}
