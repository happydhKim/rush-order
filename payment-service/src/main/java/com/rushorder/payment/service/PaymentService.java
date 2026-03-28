package com.rushorder.payment.service;

import com.rushorder.common.exception.NotFoundException;
import com.rushorder.payment.client.PgClient;
import com.rushorder.payment.domain.Payment;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.repository.PaymentRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 결제 처리 서비스.
 *
 * <p>Resilience4j 데코레이터 적용 순서 (바깥 → 안쪽):
 * <pre>
 *   Retry → CircuitBreaker → Bulkhead → PG 호출
 * </pre>
 *
 * <p>이 순서의 의미:
 * <ul>
 *   <li>Retry가 가장 바깥에서 감싸므로, CircuitBreaker가 OPEN이면 Retry도 즉시 실패한다.</li>
 *   <li>만약 CircuitBreaker가 Retry를 감싸면, 3번 재시도 결과만 Circuit에 기록되어
 *       실제 실패 횟수가 과소 집계되고 장애 감지가 느려진다.</li>
 *   <li>Bulkhead가 가장 안쪽에서 동시 호출 수를 제한하여 PG에 과부하를 방지한다.</li>
 *   <li>TimeLimiter는 동기 메서드에 어노테이션으로 적용 불가(CompletableFuture 필요)하므로
 *       application.yml 설정만 유지한다.</li>
 * </ul>
 *
 * @see com.rushorder.payment.client.PgClient
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    /**
     * 결제를 처리한다.
     *
     * <p>멱등성: 동일 idempotencyKey로 재요청 시 기존 결과를 반환한다.
     *
     * <p>PG 실패 시 예외를 전파하여 Retry가 재시도하고,
     * 모든 재시도가 실패하면 CircuitBreaker fallback이 PENDING 상태로 저장한다.
     * 이전 방식(catch로 FAILED 저장)과 달리 Retry/CircuitBreaker가 실제로 동작한다.
     *
     * @param request 결제 요청 (orderId, idempotencyKey, amount)
     * @return 결제 결과
     */
    @Transactional
    @Retry(name = "pgPayment")
    @CircuitBreaker(name = "pgPayment", fallbackMethod = "fallbackPayment")
    @Bulkhead(name = "pgPayment")
    public PaymentResponse processPayment(PaymentRequest request) {
        // 멱등성: 이미 처리된 결제가 있으면 기존 결과 반환
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Duplicate payment request: idempotencyKey={}", request.idempotencyKey());
            return PaymentResponse.from(existing.get());
        }

        // PG 승인 — 실패 시 예외가 전파되어 Retry/CircuitBreaker가 처리
        String pgTransactionId = pgClient.approve(request.orderId(), request.amount());

        Payment payment = new Payment(request.orderId(), request.idempotencyKey(), request.amount());
        payment.approve(pgTransactionId);
        paymentRepository.save(payment);
        log.info("Payment approved: orderId={}, pgTxId={}", request.orderId(), pgTransactionId);

        return PaymentResponse.from(payment);
    }

    /**
     * PG 장애 시 Fallback.
     *
     * <p>Circuit Breaker가 OPEN 상태이거나 모든 재시도가 실패한 경우 호출된다.
     * 결제를 즉시 실패시키지 않고 PENDING 상태로 저장하여,
     * PG 복구 후 재처리할 수 있는 여지를 남긴다.
     */
    @Transactional
    public PaymentResponse fallbackPayment(PaymentRequest request, Exception e) {
        log.error("Payment fallback triggered: orderId={}, reason={}", request.orderId(), e.getMessage());

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return PaymentResponse.from(existing.get());
        }

        // PENDING 상태로 저장하여 나중에 재처리
        Payment payment = new Payment(request.orderId(), request.idempotencyKey(), request.amount());
        paymentRepository.save(payment);
        return PaymentResponse.from(payment);
    }

    public PaymentResponse getPayment(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Payment", orderId));
        return PaymentResponse.from(payment);
    }
}
