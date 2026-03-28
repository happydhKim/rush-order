package com.rushorder.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockReservation 도메인 엔티티의 단위 테스트.
 *
 * <p>예약 건별 상태 전이(RESERVED -> CONFIRMED/RELEASED/EXPIRED)와
 * TTL 메커니즘을 검증한다.
 *
 * <p>[면접 포인트] 예약 TTL은 Saga 패턴에서 장애 발생 시 재고가 영구적으로
 * 잠기는 것을 방지하는 안전장치다. 결제 서비스가 다운되어 confirm/release
 * 호출이 오지 않더라도, 5분 후 스케줄러가 자동으로 재고를 해제한다.
 * 이는 "장애 격리(Fault Isolation)"의 핵심 요소이다.
 */
class StockReservationTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("생성 시 status=RESERVED이고, 만료 시각은 현재 시각 + 5분이다")
        void shouldCreateWithReservedStatusAndTtl() {
            // StockReservation 생성 시 기본 상태가 RESERVED여야 하고,
            // reservedUntil이 생성 시각 + 5분으로 설정되어야 한다.
            // 이 5분 TTL은 결제 완료를 위한 유예 시간이다.
            LocalDateTime before = LocalDateTime.now();

            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            LocalDateTime after = LocalDateTime.now();

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(reservation.getMenuId()).isEqualTo(1L);
            assertThat(reservation.getOrderId()).isEqualTo("order-1");
            assertThat(reservation.getQuantity()).isEqualTo(5);
            assertThat(reservation.getReservedAt()).isBetween(before, after);
            // reservedUntil은 reservedAt + 5분
            assertThat(reservation.getReservedUntil())
                    .isBetween(before.plusMinutes(5), after.plusMinutes(5));
        }
    }

    @Nested
    @DisplayName("confirm (확정)")
    class Confirm {

        @Test
        @DisplayName("confirm 호출 시 상태가 CONFIRMED로 변경된다")
        void shouldTransitionToConfirmed() {
            // 결제 성공 시 예약 상태를 CONFIRMED로 변경한다.
            // CONFIRMED 상태의 예약은 스케줄러의 자동 해제 대상에서 제외된다.
            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            reservation.confirm();

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.CONFIRMED);
        }
    }

    @Nested
    @DisplayName("release (해제)")
    class Release {

        @Test
        @DisplayName("release 호출 시 상태가 RELEASED로 변경된다")
        void shouldTransitionToReleased() {
            // 결제 실패 또는 Saga 보상 트랜잭션 시 예약을 해제한다.
            // Inventory.release()와 함께 호출되어 실제 재고를 복구한다.
            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            reservation.release();

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        }
    }

    @Nested
    @DisplayName("expire (만료)")
    class Expire {

        @Test
        @DisplayName("expire 호출 시 상태가 EXPIRED로 변경된다")
        void shouldTransitionToExpired() {
            // 스케줄러가 TTL 만료된 예약을 발견했을 때 호출한다.
            // RELEASED와 구분하여 "명시적 취소"와 "자동 만료"를 이력으로 추적한다.
            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            reservation.expire();

            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("TTL 메커니즘")
    class TtlMechanism {

        @Test
        @DisplayName("생성 직후에는 만료되지 않은 상태이다 (reservedUntil > now)")
        void shouldNotBeExpiredImmediatelyAfterCreation() {
            // [면접 포인트] 예약 TTL이 Saga 장애 시 재고 잠김을 방지하는 메커니즘:
            // 결제 서비스가 장애로 confirm/release를 호출하지 못하더라도,
            // reservedUntil이 현재 시각보다 과거가 되면 스케줄러가 자동으로 해제한다.
            // 이는 분산 시스템에서 "최종적 일관성(Eventual Consistency)"을 보장하는 패턴이다.
            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            // 방금 생성된 예약의 reservedUntil은 5분 후이므로 아직 만료되지 않았다.
            assertThat(reservation.getReservedUntil()).isAfter(LocalDateTime.now());
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        }

        @Test
        @DisplayName("reservedUntil이 현재 시각 이전이면 스케줄러 해제 대상이다")
        void expiredReservationShouldBeDetectable() {
            // 실제 스케줄러는 JPQL로 status=RESERVED AND reservedUntil < now 조건으로 조회한다.
            // 여기서는 도메인 관점에서 reservedUntil 필드가 올바르게 설정되는지 검증한다.
            StockReservation reservation = new StockReservation(1L, "order-1", 5);

            // reservedUntil은 reservedAt + 5분으로 설정됨
            assertThat(reservation.getReservedUntil())
                    .isEqualTo(reservation.getReservedAt().plusMinutes(5));
        }
    }
}
