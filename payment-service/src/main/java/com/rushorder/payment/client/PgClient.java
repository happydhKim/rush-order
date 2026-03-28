package com.rushorder.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 외부 PG(Payment Gateway) Mock 클라이언트.
 *
 * <p>실제 PG 연동 대신 승인/실패를 시뮬레이션한다.
 * Circuit Breaker, Retry, Bulkhead가 이 클라이언트를 감싸며,
 * 장애 시나리오를 테스트할 수 있도록 실패율을 조절할 수 있다.
 *
 * <p>프로덕션에서는 이 클래스를 실제 PG SDK 호출로 교체한다.
 */
@Slf4j
@Component
public class PgClient {

    /**
     * PG 승인을 요청한다.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     * @return PG 트랜잭션 ID
     * @throws PgPaymentException PG 승인 실패 시
     */
    public String approve(String orderId, int amount) {
        log.info("PG approve request: orderId={}, amount={}", orderId, amount);

        // 시뮬레이션: 10% 확률로 실패
        if (Math.random() < 0.1) {
            throw new PgPaymentException("PG approval failed: simulated error");
        }

        String transactionId = "PG-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("PG approved: orderId={}, txId={}", orderId, transactionId);
        return transactionId;
    }

    public static class PgPaymentException extends RuntimeException {
        public PgPaymentException(String message) {
            super(message);
        }
    }
}
