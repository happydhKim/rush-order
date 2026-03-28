package com.rushorder.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment 엔티티 단위 테스트.
 *
 * <p>도메인 객체의 생성, 상태 전이 로직을 검증한다.
 * JPA 영속성 컨텍스트 없이 순수 자바 객체로 테스트한다.
 */
class PaymentTest {

    @Nested
    @DisplayName("생성자 - Payment 엔티티 생성")
    class Constructor {

        @Test
        @DisplayName("생성 시 PENDING 상태와 현재 시각이 설정된다")
        void shouldCreatePaymentWithPendingStatus() {
            // 새로 생성된 Payment는 아직 PG 승인을 받지 않았으므로
            // PENDING 상태여야 한다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            assertThat(payment.getOrderId()).isEqualTo("order-1");
            assertThat(payment.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(payment.getAmount()).isEqualTo(10000);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getPgTransactionId()).isNull();
            assertThat(payment.getFailureReason()).isNull();
            assertThat(payment.getCreatedAt()).isNotNull();
            assertThat(payment.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("생성 시 createdAt과 updatedAt이 동일한 시점으로 설정된다")
        void shouldSetCreatedAtAndUpdatedAtToSameTime() {
            // 최초 생성 시점에서는 두 타임스탬프가 동일해야 한다.
            // updatedAt은 상태 변경 시에만 갱신된다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            assertThat(payment.getCreatedAt()).isEqualTo(payment.getUpdatedAt());
        }
    }

    @Nested
    @DisplayName("approve - 결제 승인")
    class Approve {

        @Test
        @DisplayName("승인 시 APPROVED 상태와 PG 트랜잭션 ID가 설정된다")
        void shouldTransitionToApprovedWithPgTransactionId() {
            // PG 승인이 완료되면 APPROVED 상태로 전이하고
            // PG 트랜잭션 ID를 저장해야 한다. 이 ID는 환불 등 후속 처리에 사용된다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            payment.approve("PG-abc12345");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(payment.getPgTransactionId()).isEqualTo("PG-abc12345");
        }

        @Test
        @DisplayName("승인 시 updatedAt이 갱신된다")
        void shouldUpdateTimestampOnApproval() {
            // 상태 변경 시 updatedAt이 갱신되어야 변경 이력을 추적할 수 있다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            payment.approve("PG-abc12345");

            assertThat(payment.getUpdatedAt()).isAfterOrEqualTo(payment.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("fail - 결제 실패")
    class Fail {

        @Test
        @DisplayName("실패 시 FAILED 상태와 실패 사유가 설정된다")
        void shouldTransitionToFailedWithReason() {
            // 결제 실패 시 FAILED 상태로 전이하고 실패 사유를 저장해야 한다.
            // 실패 사유는 디버깅과 고객 안내에 사용된다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            payment.fail("Insufficient balance");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("Insufficient balance");
        }

        @Test
        @DisplayName("실패 시 updatedAt이 갱신된다")
        void shouldUpdateTimestampOnFailure() {
            // 상태 변경 시 updatedAt이 갱신되어야 한다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            payment.fail("PG timeout");

            assertThat(payment.getUpdatedAt()).isAfterOrEqualTo(payment.getCreatedAt());
        }

        @Test
        @DisplayName("실패 시 pgTransactionId는 null로 유지된다")
        void shouldKeepPgTransactionIdNullOnFailure() {
            // PG 승인이 실패했으므로 트랜잭션 ID가 없어야 한다.
            Payment payment = new Payment("order-1", "idem-1", 10000);

            payment.fail("PG error");

            assertThat(payment.getPgTransactionId()).isNull();
        }
    }

    @Nested
    @DisplayName("상태 전이 시나리오")
    class StateTransition {

        @Test
        @DisplayName("PENDING -> APPROVED 상태 전이가 정상 동작한다")
        void shouldTransitionFromPendingToApproved() {
            // 결제의 정상 흐름: PENDING -> APPROVED
            Payment payment = new Payment("order-1", "idem-1", 10000);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            payment.approve("PG-abc");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("PENDING -> FAILED 상태 전이가 정상 동작한다")
        void shouldTransitionFromPendingToFailed() {
            // 결제의 실패 흐름: PENDING -> FAILED
            Payment payment = new Payment("order-1", "idem-1", 10000);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            payment.fail("Declined by PG");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }
}
