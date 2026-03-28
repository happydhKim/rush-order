package com.rushorder.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderStatus 상태 전이 규칙 단위 테스트.
 *
 * <p>Saga 기반 분산 트랜잭션에서 상태 전이 규칙은 비즈니스 정합성의 핵심이다.
 * 잘못된 전이를 허용하면 주문이 불일치 상태에 빠질 수 있으므로,
 * 모든 전이 조합을 빠짐없이 검증한다.
 *
 * <p>[면접 포인트] 상태 전이 규칙을 enum 내부에 캡슐화하면
 * 외부에서 if-else 체인 없이 전이 가능 여부를 판단할 수 있고,
 * 새로운 상태 추가 시 컴파일 타임에 누락을 감지할 수 있다.
 */
@DisplayName("OrderStatus 상태 전이 테스트")
class OrderStatusTest {

    /**
     * 각 상태별 허용되는 전이 목록.
     * 이 Map이 곧 "상태 머신의 진실"이며, 소스 코드의 allowedTransitions()와 일치해야 한다.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED),
            OrderStatus.INVENTORY_RESERVED, Set.of(OrderStatus.PAYMENT_PROCESSING),
            OrderStatus.PAYMENT_PROCESSING, Set.of(OrderStatus.CONFIRMED, OrderStatus.COMPENSATING),
            OrderStatus.CONFIRMED, Set.of(),
            OrderStatus.COMPENSATING, Set.of(OrderStatus.CANCELLED, OrderStatus.COMPENSATION_FAILED),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.COMPENSATION_FAILED, Set.of()
    );

    @Nested
    @DisplayName("허용된 상태 전이")
    class AllowedTransitions {

        @Test
        @DisplayName("PENDING -> INVENTORY_RESERVED: 재고 예약 성공 시 전이")
        void pending_to_inventoryReserved() {
            // 주문 생성 직후 재고 예약이 완료되면 INVENTORY_RESERVED로 전이해야 한다
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.INVENTORY_RESERVED)).isTrue();
        }

        @Test
        @DisplayName("PENDING -> CANCELLED: 재고 부족 등으로 즉시 실패 시 전이")
        void pending_to_cancelled() {
            // 재고 예약 실패 등의 이유로 주문을 즉시 취소할 수 있어야 한다
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("INVENTORY_RESERVED -> PAYMENT_PROCESSING: Saga 시작 시 전이")
        void inventoryReserved_to_paymentProcessing() {
            // 재고 예약 완료 후 결제 요청 단계로 넘어간다
            assertThat(OrderStatus.INVENTORY_RESERVED.canTransitionTo(OrderStatus.PAYMENT_PROCESSING)).isTrue();
        }

        @Test
        @DisplayName("PAYMENT_PROCESSING -> CONFIRMED: 결제 성공 시 전이")
        void paymentProcessing_to_confirmed() {
            // 결제가 성공하면 주문을 최종 확정한다
            assertThat(OrderStatus.PAYMENT_PROCESSING.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
        }

        @Test
        @DisplayName("PAYMENT_PROCESSING -> COMPENSATING: 결제 실패 시 보상 시작")
        void paymentProcessing_to_compensating() {
            // 결제가 실패하면 보상 트랜잭션(재고 해제)을 시작한다
            assertThat(OrderStatus.PAYMENT_PROCESSING.canTransitionTo(OrderStatus.COMPENSATING)).isTrue();
        }

        @Test
        @DisplayName("COMPENSATING -> CANCELLED: 보상 성공 시 최종 취소")
        void compensating_to_cancelled() {
            // 재고 해제가 성공하면 주문을 최종 취소한다
            assertThat(OrderStatus.COMPENSATING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("COMPENSATING -> COMPENSATION_FAILED: 보상 재시도 초과 시 실패")
        void compensating_to_compensationFailed() {
            // 보상 트랜잭션이 최대 재시도 횟수를 초과하면 수동 처리가 필요한 상태로 전환
            assertThat(OrderStatus.COMPENSATING.canTransitionTo(OrderStatus.COMPENSATION_FAILED)).isTrue();
        }
    }

    @Nested
    @DisplayName("거부되는 상태 전이")
    class RejectedTransitions {

        @Test
        @DisplayName("CONFIRMED는 최종 상태이므로 어떤 전이도 불가")
        void confirmed_is_terminal() {
            // CONFIRMED는 성공적으로 완료된 주문이므로 더 이상 상태가 변하면 안 된다
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStatus.CONFIRMED.canTransitionTo(target))
                        .as("CONFIRMED -> %s 전이는 거부되어야 한다", target)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("CANCELLED는 최종 상태이므로 어떤 전이도 불가")
        void cancelled_is_terminal() {
            // CANCELLED 주문이 다시 활성화되면 재고/결제 정합성이 깨진다
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStatus.CANCELLED.canTransitionTo(target))
                        .as("CANCELLED -> %s 전이는 거부되어야 한다", target)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("COMPENSATION_FAILED는 최종 상태이므로 어떤 전이도 불가")
        void compensationFailed_is_terminal() {
            // 보상 실패 상태는 DLQ + 수동 처리 대상이므로 시스템이 자동 전이하면 안 된다
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStatus.COMPENSATION_FAILED.canTransitionTo(target))
                        .as("COMPENSATION_FAILED -> %s 전이는 거부되어야 한다", target)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PENDING에서 CONFIRMED로 직접 전이 불가 - 재고 예약과 결제를 건너뛸 수 없다")
        void pending_cannot_skip_to_confirmed() {
            // 중간 단계(재고 예약, 결제)를 건너뛰는 전이는 비즈니스 규칙 위반
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CONFIRMED)).isFalse();
        }

        @Test
        @DisplayName("INVENTORY_RESERVED에서 CONFIRMED로 직접 전이 불가 - 결제 없이 확정 불가")
        void inventoryReserved_cannot_skip_to_confirmed() {
            // 결제 과정 없이 주문을 확정하면 무료 주문이 되어버린다
            assertThat(OrderStatus.INVENTORY_RESERVED.canTransitionTo(OrderStatus.CONFIRMED)).isFalse();
        }

        @Test
        @DisplayName("PAYMENT_PROCESSING에서 CANCELLED로 직접 전이 불가 - 보상 과정 필요")
        void paymentProcessing_cannot_skip_to_cancelled() {
            // 결제 처리 중 직접 취소하면 재고가 예약된 채로 남는다.
            // 반드시 COMPENSATING을 거쳐 재고를 해제해야 한다.
            assertThat(OrderStatus.PAYMENT_PROCESSING.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        }
    }

    @Nested
    @DisplayName("모든 상태 전이 조합 검증")
    class ExhaustiveTransitions {

        /**
         * [면접 포인트] 상태 머신의 모든 전이를 빠짐없이 테스트하는 이유:
         * 새로운 상태가 추가될 때 기존 전이 규칙에 미치는 영향을 즉시 감지할 수 있다.
         * 이 테스트가 실패하면 ALLOWED_TRANSITIONS 맵 또는 소스 코드를 확인해야 한다.
         */
        @ParameterizedTest(name = "{0}의 허용된 전이가 정의와 일치하는지 검증")
        @EnumSource(OrderStatus.class)
        @DisplayName("모든 상태에 대해 허용/거부 전이가 정확히 일치해야 한다")
        void allTransitions_matchDefinition(OrderStatus source) {
            // 각 상태의 allowedTransitions()가 테스트 상단의 ALLOWED_TRANSITIONS 정의와 일치하는지 검증한다.
            // 소스 코드와 테스트 코드의 "상태 전이 규칙"이 동기화되지 않으면 이 테스트가 실패한다.
            Set<OrderStatus> expected = ALLOWED_TRANSITIONS.get(source);
            assertThat(source.allowedTransitions())
                    .as("%s의 허용된 전이 목록", source)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("Order.transitionTo() 통합 검증")
    class OrderTransitionTest {

        @Test
        @DisplayName("허용된 전이 시 상태가 변경된다")
        void transitionTo_allowed_changesStatus() {
            // Order 엔티티의 transitionTo() 메서드가 상태를 정상적으로 변경하는지 검증
            Order order = new Order("order-1", 1L, 1L, "key-1");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

            order.transitionTo(OrderStatus.INVENTORY_RESERVED);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        }

        @Test
        @DisplayName("거부된 전이 시 IllegalStateException이 발생한다")
        void transitionTo_rejected_throwsException() {
            // 잘못된 전이 시도 시 예외가 발생하여 상태 불일치를 방지한다
            Order order = new Order("order-1", 1L, 1L, "key-1");

            assertThatThrownBy(() -> order.transitionTo(OrderStatus.CONFIRMED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot transition from PENDING to CONFIRMED");
        }

        @Test
        @DisplayName("정상 흐름: PENDING -> INVENTORY_RESERVED -> PAYMENT_PROCESSING -> CONFIRMED")
        void happyPath_fullTransition() {
            // 주문의 정상 흐름(Happy Path) 전체를 검증한다
            Order order = new Order("order-1", 1L, 1L, "key-1");

            order.transitionTo(OrderStatus.INVENTORY_RESERVED);
            order.transitionTo(OrderStatus.PAYMENT_PROCESSING);
            order.transitionTo(OrderStatus.CONFIRMED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("보상 흐름: PENDING -> ... -> PAYMENT_PROCESSING -> COMPENSATING -> CANCELLED")
        void compensationPath_fullTransition() {
            // 결제 실패 시 보상 트랜잭션을 거쳐 최종 취소되는 흐름을 검증한다
            Order order = new Order("order-1", 1L, 1L, "key-1");

            order.transitionTo(OrderStatus.INVENTORY_RESERVED);
            order.transitionTo(OrderStatus.PAYMENT_PROCESSING);
            order.transitionTo(OrderStatus.COMPENSATING);
            order.transitionTo(OrderStatus.CANCELLED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("보상 실패 흐름: COMPENSATING -> COMPENSATION_FAILED")
        void compensationFailedPath() {
            // 보상 재시도가 모두 실패하면 COMPENSATION_FAILED로 전환되어 수동 처리 대상이 된다
            Order order = new Order("order-1", 1L, 1L, "key-1");

            order.transitionTo(OrderStatus.INVENTORY_RESERVED);
            order.transitionTo(OrderStatus.PAYMENT_PROCESSING);
            order.transitionTo(OrderStatus.COMPENSATING);
            order.transitionTo(OrderStatus.COMPENSATION_FAILED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATION_FAILED);
        }
    }
}
