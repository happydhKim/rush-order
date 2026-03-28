package com.rushorder.payment.service;

import com.rushorder.common.exception.NotFoundException;
import com.rushorder.payment.client.PgClient;
import com.rushorder.payment.client.PgClient.PgPaymentException;
import com.rushorder.payment.domain.Payment;
import com.rushorder.payment.domain.PaymentStatus;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * PaymentService 단위 테스트.
 *
 * <p>외부 의존성(PaymentRepository, PgClient)을 Mockito로 격리하여
 * 비즈니스 로직만 순수하게 검증한다.
 *
 * <p>Resilience4j 어노테이션(@CircuitBreaker, @Retry, @Bulkhead)은
 * Spring AOP 프록시가 생성해야 동작하므로, @InjectMocks 기반 단위 테스트에서는
 * 순수 자바 메서드 호출이 되어 Resilience4j 데코레이터가 적용되지 않는다.
 * 따라서 이 테스트에서는 비즈니스 로직(멱등성, PG 호출, 상태 저장)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PgClient pgClient;

    @InjectMocks
    private PaymentService paymentService;

    @Nested
    @DisplayName("processPayment - 결제 처리")
    class ProcessPayment {

        @Test
        @DisplayName("정상 결제: PG 승인 성공 시 APPROVED 상태로 저장하고 응답을 반환한다")
        void shouldApprovePaymentWhenPgSucceeds() {
            // 정상적인 결제 흐름을 검증한다.
            // PG 호출이 성공하면 Payment 엔티티가 APPROVED 상태로 저장되어야 하며,
            // PG 트랜잭션 ID가 응답에 포함되어야 한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);

            given(paymentRepository.findByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(pgClient.approve("order-1", 10000)).willReturn("PG-abc12345");
            given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

            PaymentResponse response = paymentService.processPayment(request);

            // PG 트랜잭션 ID와 APPROVED 상태가 응답에 포함되는지 확인
            assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(response.pgTransactionId()).isEqualTo("PG-abc12345");
            assertThat(response.orderId()).isEqualTo("order-1");
            assertThat(response.amount()).isEqualTo(10000);

            // Payment 엔티티가 올바른 상태로 저장되었는지 검증
            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            then(paymentRepository).should().save(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(saved.getPgTransactionId()).isEqualTo("PG-abc12345");
        }

        /**
         * [면접 포인트] PgPaymentException을 catch하지 않고 전파하는 이유
         *
         * <p>PaymentService.processPayment()는 PgClient에서 발생하는 PgPaymentException을
         * try-catch로 잡지 않고 그대로 전파한다. 이는 의도적인 설계이다.
         *
         * <p>Resilience4j의 @Retry, @CircuitBreaker는 Spring AOP 프록시가 메서드 호출을 감싸서
         * 예외를 인터셉트하는 방식으로 동작한다. 만약 메서드 내부에서 예외를 catch해버리면
         * AOP 프록시는 "성공"으로 인식하여 Retry도 하지 않고, CircuitBreaker에도 실패로 기록되지 않는다.
         *
         * <p>따라서 예외를 전파해야 Retry가 재시도하고, 재시도 모두 실패 시
         * CircuitBreaker가 fallbackPayment()를 호출하는 정상적인 흐름이 된다.
         */
        @Test
        @DisplayName("PG 실패: 예외를 전파하여 Retry/CircuitBreaker가 처리할 수 있도록 한다")
        void shouldPropagateExceptionWhenPgFails() {
            // PG 호출 실패 시 예외가 그대로 전파되는지 검증한다.
            // catch 하지 않아야 AOP 기반 Resilience4j 데코레이터가 정상 동작한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);

            given(paymentRepository.findByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(pgClient.approve("order-1", 10000)).willThrow(new PgPaymentException("PG error"));

            // PgPaymentException이 catch 없이 전파되는지 확인
            assertThatThrownBy(() -> paymentService.processPayment(request))
                    .isInstanceOf(PgPaymentException.class)
                    .hasMessage("PG error");

            // PG 실패 시 Payment가 저장되지 않아야 한다 (fallback에서 처리)
            then(paymentRepository).should(never()).save(any(Payment.class));
        }

        /**
         * [면접 포인트] 멱등성(Idempotency) 보장 패턴
         *
         * <p>분산 시스템에서는 네트워크 타임아웃, 재시도 등으로 동일한 요청이 여러 번 도달할 수 있다.
         * idempotencyKey를 기반으로 이미 처리된 결제를 DB에서 조회하여 기존 결과를 반환함으로써
         * 중복 결제를 방지한다. DB의 unique constraint가 최종 안전망 역할을 한다.
         */
        @Test
        @DisplayName("멱등성: 동일 idempotencyKey 재요청 시 기존 결과를 반환한다")
        void shouldReturnExistingPaymentForDuplicateIdempotencyKey() {
            // 이미 처리된 결제와 동일한 idempotencyKey로 재요청이 오면
            // PG를 다시 호출하지 않고 기존 결과를 반환해야 한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);

            Payment existingPayment = new Payment("order-1", "idem-1", 10000);
            existingPayment.approve("PG-existing");

            given(paymentRepository.findByIdempotencyKey("idem-1")).willReturn(Optional.of(existingPayment));

            PaymentResponse response = paymentService.processPayment(request);

            // 기존 결제 결과가 그대로 반환되는지 확인
            assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(response.pgTransactionId()).isEqualTo("PG-existing");

            // PG 호출이 발생하지 않아야 한다
            then(pgClient).should(never()).approve(anyString(), anyInt());
            // 새로운 Payment가 저장되지 않아야 한다
            then(paymentRepository).should(never()).save(any(Payment.class));
        }

        /**
         * [면접 포인트] Resilience4j 어노테이션 적용 순서: Retry -> CircuitBreaker -> Bulkhead
         *
         * <p>Spring AOP에서 어노테이션의 실행 순서는 "바깥 -> 안쪽" 으로 적용된다.
         * 즉, 요청 흐름은 Retry -> CircuitBreaker -> Bulkhead -> 실제 메서드 순이다.
         *
         * <p>이 순서가 중요한 이유:
         * 1) Retry가 가장 바깥: CB가 OPEN이면 Retry도 즉시 실패하여 불필요한 대기를 방지
         * 2) CircuitBreaker가 중간: 개별 호출의 성공/실패를 정확히 기록
         *    - 만약 CB가 Retry 바깥이면 3번 재시도 결과만 기록되어 실패 횟수가 과소 집계됨
         * 3) Bulkhead가 가장 안쪽: 외부 PG에 대한 동시 호출 수를 직접 제한
         *
         * <p>이 테스트는 fallbackPayment() 메서드의 로직을 검증한다.
         * 실제 Resilience4j 어노테이션 동작은 통합 테스트에서 검증해야 한다.
         */
        @Test
        @DisplayName("Fallback: PG 장애 시 PENDING 상태로 저장하여 재처리 가능하게 한다")
        void shouldSaveAsPendingWhenFallbackTriggered() {
            // CircuitBreaker fallback이 호출되면 PENDING 상태로 저장하여
            // PG 복구 후 재처리할 수 있는 여지를 남긴다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);
            Exception pgException = new PgPaymentException("PG timeout");

            given(paymentRepository.findByIdempotencyKey("idem-1")).willReturn(Optional.empty());
            given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

            PaymentResponse response = paymentService.fallbackPayment(request, pgException);

            // PENDING 상태로 저장되는지 확인
            assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(response.pgTransactionId()).isNull();

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            then(paymentRepository).should().save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("Fallback 멱등성: 이미 처리된 결제가 있으면 기존 결과를 반환한다")
        void shouldReturnExistingPaymentInFallback() {
            // fallback에서도 멱등성을 보장해야 한다.
            // Retry 과정에서 첫 번째 시도가 DB에 저장한 후 두 번째 시도에서 fallback이
            // 호출될 수 있으므로, 기존 결제가 있으면 그대로 반환한다.
            PaymentRequest request = new PaymentRequest("order-1", "idem-1", 10000);
            Exception pgException = new PgPaymentException("PG timeout");

            Payment existingPayment = new Payment("order-1", "idem-1", 10000);
            existingPayment.approve("PG-existing");

            given(paymentRepository.findByIdempotencyKey("idem-1")).willReturn(Optional.of(existingPayment));

            PaymentResponse response = paymentService.fallbackPayment(request, pgException);

            assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
            then(paymentRepository).should(never()).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("getPayment - 결제 조회")
    class GetPayment {

        @Test
        @DisplayName("존재하는 주문 ID로 결제를 조회한다")
        void shouldReturnPaymentWhenExists() {
            // orderId로 결제를 조회하는 기본 흐름을 검증한다.
            Payment payment = new Payment("order-1", "idem-1", 10000);
            payment.approve("PG-abc");

            given(paymentRepository.findByOrderId("order-1")).willReturn(Optional.of(payment));

            PaymentResponse response = paymentService.getPayment("order-1");

            assertThat(response.orderId()).isEqualTo("order-1");
            assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("존재하지 않는 주문 ID로 조회 시 NotFoundException을 발생시킨다")
        void shouldThrowNotFoundWhenPaymentDoesNotExist() {
            // 존재하지 않는 결제를 조회하면 404 응답으로 매핑될 NotFoundException이 발생해야 한다.
            given(paymentRepository.findByOrderId("nonexistent")).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getPayment("nonexistent"))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
