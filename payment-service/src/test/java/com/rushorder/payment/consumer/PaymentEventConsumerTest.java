package com.rushorder.payment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.payment.domain.PaymentStatus;
import com.rushorder.payment.dto.PaymentRequest;
import com.rushorder.payment.dto.PaymentResponse;
import com.rushorder.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * PaymentEventConsumer 단위 테스트.
 *
 * <p>Kafka Consumer의 메시지 수신, 결제 처리, 결과 발행 흐름을 검증한다.
 * PaymentService, KafkaTemplate을 Mock으로 격리하여 Consumer 로직만 테스트한다.
 *
 * <p>ObjectMapper는 실제 JSON 직렬화/역직렬화가 필요하므로 @Spy로 사용한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentEventConsumer consumer;

    @Test
    @DisplayName("정상 메시지 수신 시 결제를 처리하고 성공 결과를 payment-result 토픽으로 발행한다")
    void shouldProcessPaymentAndPublishSuccessResult() throws JsonProcessingException {
        // payment-requested 토픽에서 정상적인 JSON 메시지를 수신하면
        // PaymentService로 결제를 처리하고, 결과를 payment-result 토픽으로 발행해야 한다.
        String message = """
                {"orderId": "order-1", "totalAmount": 15000}
                """;

        PaymentResponse response = new PaymentResponse(
                1L, "order-1", 15000, PaymentStatus.APPROVED,
                "PG-abc", null, LocalDateTime.now()
        );

        given(paymentService.processPayment(any(PaymentRequest.class))).willReturn(response);

        consumer.consume(message);

        // PaymentService가 올바른 파라미터로 호출되었는지 확인
        ArgumentCaptor<PaymentRequest> requestCaptor = ArgumentCaptor.forClass(PaymentRequest.class);
        then(paymentService).should().processPayment(requestCaptor.capture());
        PaymentRequest captured = requestCaptor.getValue();
        assertThat(captured.orderId()).isEqualTo("order-1");
        assertThat(captured.amount()).isEqualTo(15000);
        // orderId를 idempotencyKey로 사용하는지 확인
        assertThat(captured.idempotencyKey()).isEqualTo("order-1");

        // payment-result 토픽으로 결과가 발행되었는지 확인
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        then(kafkaTemplate).should().send(eq("payment-result"), eq("order-1"), valueCaptor.capture());

        // 발행된 메시지의 내용 검증
        String publishedJson = valueCaptor.getValue();
        assertThat(publishedJson).contains("\"orderId\":\"order-1\"");
        assertThat(publishedJson).contains("\"success\":true");
        assertThat(publishedJson).contains("\"pgTransactionId\":\"PG-abc\"");
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 로그만 남기고 예외를 전파하지 않는다")
    void shouldNotPropagateExceptionOnJsonParsingFailure() {
        // 잘못된 JSON 메시지가 들어오면 예외를 전파하지 않아야 한다.
        // 예외를 전파하면 Consumer가 중단되어 후속 메시지도 처리하지 못하기 때문이다.
        // dead-letter-topic으로 보내는 것이 이상적이나, 현재는 로그만 남기는 구현이다.
        String invalidJson = "this is not json";

        assertThatCode(() -> consumer.consume(invalidJson)).doesNotThrowAnyException();

        // PaymentService와 KafkaTemplate은 호출되지 않아야 한다
        then(paymentService).should(never()).processPayment(any());
        then(kafkaTemplate).should(never()).send(any(), any(), any());
    }

    /**
     * [면접 포인트] 결제 실패 시에도 반드시 결과를 발행해야 Saga 보상이 가능한 이유
     *
     * <p>Saga Orchestration 패턴에서 Order Service는 payment-result를 기다린다.
     * 결제가 실패했을 때 결과를 발행하지 않으면 Order Service는 무한 대기 상태에 빠지거나
     * timeout에 의존해야 한다.
     *
     * <p>실패 결과(success=false)를 발행해야 Order Service의 Saga Orchestrator가
     * 보상 트랜잭션(재고 복구 등)을 즉시 시작할 수 있다.
     * 이것이 "실패도 이벤트다"라는 이벤트 기반 아키텍처의 핵심 원칙이다.
     */
    @Test
    @DisplayName("결제 처리 실패 시 실패 결과(success=false)를 payment-result 토픽으로 발행한다")
    void shouldPublishFailureResultWhenPaymentProcessingFails() {
        // PaymentService에서 예외가 발생해도 Consumer는 실패 결과를 발행해야 한다.
        // 이를 통해 Saga Orchestrator가 보상 트랜잭션을 시작할 수 있다.
        String message = """
                {"orderId": "order-1", "totalAmount": 15000}
                """;

        given(paymentService.processPayment(any(PaymentRequest.class)))
                .willThrow(new RuntimeException("Unexpected error"));

        assertThatCode(() -> consumer.consume(message)).doesNotThrowAnyException();

        // 실패 결과가 payment-result 토픽으로 발행되었는지 확인
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        then(kafkaTemplate).should().send(eq("payment-result"), eq("order-1"), valueCaptor.capture());

        String publishedJson = valueCaptor.getValue();
        assertThat(publishedJson).contains("\"success\":false");
        assertThat(publishedJson).contains("\"orderId\":\"order-1\"");
        assertThat(publishedJson).contains("Unexpected error");
    }

    @Test
    @DisplayName("PENDING 상태로 처리된 결제는 success=false로 발행한다")
    void shouldPublishFailureForPendingStatus() throws JsonProcessingException {
        // fallback에 의해 PENDING 상태로 저장된 경우,
        // Saga 관점에서는 결제가 확정되지 않았으므로 success=false로 발행해야 한다.
        // APPROVED만 success=true로 처리하는 것이 올바른 판단이다.
        String message = """
                {"orderId": "order-1", "totalAmount": 15000}
                """;

        PaymentResponse pendingResponse = new PaymentResponse(
                1L, "order-1", 15000, PaymentStatus.PENDING,
                null, null, LocalDateTime.now()
        );

        given(paymentService.processPayment(any(PaymentRequest.class))).willReturn(pendingResponse);

        consumer.consume(message);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        then(kafkaTemplate).should().send(eq("payment-result"), eq("order-1"), valueCaptor.capture());

        String publishedJson = valueCaptor.getValue();
        // PENDING은 APPROVED가 아니므로 success=false
        assertThat(publishedJson).contains("\"success\":false");
    }
}
