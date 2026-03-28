package com.rushorder.inventory.domain;

import com.rushorder.common.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory 도메인 엔티티의 단위 테스트.
 *
 * <p>재고의 핵심 라이프사이클(예약 -> 확정/해제)에 대한 비즈니스 규칙을 검증한다.
 * 특히 가용 재고 = totalStock - reservedStock 공식이 각 상태 전이에서
 * 올바르게 유지되는지 확인한다.
 */
class InventoryTest {

    @Nested
    @DisplayName("reserve (재고 예약)")
    class Reserve {

        @Test
        @DisplayName("가용 재고 이내의 수량을 예약하면 reservedStock이 증가한다")
        void shouldIncreaseReservedStock() {
            // 총 재고 100인 상태에서 30개를 예약한다.
            // 예약 후 reservedStock=30, availableStock=70이어야 한다.
            Inventory inventory = new Inventory(1L, 100);

            inventory.reserve(30);

            assertThat(inventory.getReservedStock()).isEqualTo(30);
            assertThat(inventory.getAvailableStock()).isEqualTo(70);
        }

        @Test
        @DisplayName("여러 번 예약하면 reservedStock이 누적된다")
        void shouldAccumulateReservedStock() {
            // 동일 메뉴에 대해 여러 주문이 순차적으로 예약하는 시나리오.
            // 비관적 락 덕분에 순차적으로 실행되며, 각 예약이 누적되어야 한다.
            Inventory inventory = new Inventory(1L, 100);

            inventory.reserve(30);
            inventory.reserve(20);

            assertThat(inventory.getReservedStock()).isEqualTo(50);
            assertThat(inventory.getAvailableStock()).isEqualTo(50);
        }

        @Test
        @DisplayName("가용 재고보다 많은 수량을 예약하면 InsufficientStockException이 발생한다")
        void shouldThrowWhenInsufficientStock() {
            // 가용 재고가 부족할 때 예외가 발생하여 트랜잭션 롤백을 유도한다.
            // 이 예외는 @Transactional 롤백의 트리거가 된다.
            Inventory inventory = new Inventory(1L, 10);

            assertThatThrownBy(() -> inventory.reserve(11))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("이미 예약이 있는 상태에서 남은 가용 재고보다 많이 예약하면 예외가 발생한다")
        void shouldThrowWhenAvailableStockNotEnough() {
            // totalStock=100이고 이미 90이 예약된 상태에서 추가로 15개 예약 시도.
            // 가용 재고는 10이므로 예외가 발생해야 한다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(90);

            assertThatThrownBy(() -> inventory.reserve(15))
                    .isInstanceOf(InsufficientStockException.class);
        }
    }

    @Nested
    @DisplayName("confirm (예약 확정)")
    class Confirm {

        @Test
        @DisplayName("확정 시 totalStock과 reservedStock이 모두 차감된다")
        void shouldDecreaseBothStocks() {
            // 결제 성공 시 예약 수량을 실제 차감으로 전환한다.
            // totalStock - quantity, reservedStock - quantity로 가용 재고는 변하지 않는다.
            // [면접 포인트] confirm 후 availableStock이 변하지 않는 이유:
            // 이미 reserve 단계에서 가용 재고가 줄었기 때문이다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(30);

            inventory.confirm(30);

            assertThat(inventory.getTotalStock()).isEqualTo(70);
            assertThat(inventory.getReservedStock()).isEqualTo(0);
            assertThat(inventory.getAvailableStock()).isEqualTo(70);
        }
    }

    @Nested
    @DisplayName("release (예약 해제)")
    class Release {

        @Test
        @DisplayName("해제 시 reservedStock만 차감되어 가용 재고가 복구된다")
        void shouldRestoreAvailableStock() {
            // 결제 실패 또는 Saga 보상 트랜잭션 시 예약을 해제한다.
            // totalStock은 유지되고 reservedStock만 감소하여 가용 재고가 복구된다.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(30);

            inventory.release(30);

            assertThat(inventory.getTotalStock()).isEqualTo(100);
            assertThat(inventory.getReservedStock()).isEqualTo(0);
            assertThat(inventory.getAvailableStock()).isEqualTo(100);
        }

        @Test
        @DisplayName("부분 해제 시 해당 수량만큼만 가용 재고가 복구된다")
        void shouldPartiallyRestoreStock() {
            // 여러 메뉴 중 일부만 보상 트랜잭션으로 해제하는 경우.
            Inventory inventory = new Inventory(1L, 100);
            inventory.reserve(30);

            inventory.release(10);

            assertThat(inventory.getReservedStock()).isEqualTo(20);
            assertThat(inventory.getAvailableStock()).isEqualTo(80);
        }
    }
}
