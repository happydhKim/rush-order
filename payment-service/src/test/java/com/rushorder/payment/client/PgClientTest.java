package com.rushorder.payment.client;

import com.rushorder.payment.client.PgClient.PgPaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgClient 단위 테스트.
 *
 * <p>Mock PG 클라이언트의 승인/실패 로직을 검증한다.
 * 실제 PG는 10% 확률로 실패하도록 구현되어 있으나,
 * 단위 테스트에서는 확률적 동작이 아닌 반환값/예외의 형태를 검증한다.
 */
class PgClientTest {

    private final PgClient pgClient = new PgClient();

    @Test
    @DisplayName("PG 승인 성공 시 'PG-' 접두사가 붙은 트랜잭션 ID를 반환한다")
    void shouldReturnTransactionIdWithPgPrefix() {
        // PG 승인이 성공하면 "PG-" 접두사 + UUID 일부로 구성된 트랜잭션 ID가 반환되어야 한다.
        // 10% 실패 확률이 있으므로 성공할 때까지 반복 호출하여 성공 케이스의 반환값 형태를 검증한다.
        String transactionId = null;
        for (int i = 0; i < 100; i++) {
            try {
                transactionId = pgClient.approve("order-1", 10000);
                break;
            } catch (PgPaymentException ignored) {
                // 실패 시 재시도
            }
        }

        assertThat(transactionId).isNotNull();
        assertThat(transactionId).startsWith("PG-");
        // "PG-" 접두사 + UUID 8자리 = 총 11자
        assertThat(transactionId).hasSize(11);
    }

    @Test
    @DisplayName("PG 승인 실패 시 PgPaymentException을 발생시킨다")
    void shouldThrowPgPaymentExceptionOnFailure() {
        // 10% 확률로 실패하므로 충분히 반복 호출하면 반드시 예외가 발생한다.
        // 이 테스트는 예외의 타입과 메시지 형태를 검증한다.
        PgPaymentException caught = null;
        for (int i = 0; i < 200; i++) {
            try {
                pgClient.approve("order-1", 10000);
            } catch (PgPaymentException e) {
                caught = e;
                break;
            }
        }

        assertThat(caught).isNotNull();
        assertThat(caught.getMessage()).contains("PG approval failed");
    }

    @Test
    @DisplayName("동일 파라미터로 여러 번 호출해도 매번 다른 트랜잭션 ID를 반환한다")
    void shouldReturnUniqueTransactionIdPerCall() {
        // PG 트랜잭션 ID는 UUID 기반이므로 매 호출마다 고유해야 한다.
        // 동일한 orderId/amount로 호출해도 서로 다른 ID가 반환되어야 한다.
        String txId1 = null;
        String txId2 = null;

        for (int i = 0; i < 100; i++) {
            try {
                if (txId1 == null) {
                    txId1 = pgClient.approve("order-1", 10000);
                } else if (txId2 == null) {
                    txId2 = pgClient.approve("order-1", 10000);
                }
                if (txId1 != null && txId2 != null) break;
            } catch (PgPaymentException ignored) {
                // 실패 시 다음 반복에서 재시도
            }
        }

        assertThat(txId1).isNotNull();
        assertThat(txId2).isNotNull();
        assertThat(txId1).isNotEqualTo(txId2);
    }
}
