package com.rushorder.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.order.client.InventoryClient;
import com.rushorder.order.domain.Order;
import com.rushorder.order.domain.OrderItem;
import com.rushorder.order.domain.OrderStatus;
import com.rushorder.order.outbox.OutboxEvent;
import com.rushorder.order.outbox.OutboxRepository;
import com.rushorder.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * SagaOrchestrator 단위 테스트.
 *
 * <p>분산 트랜잭션의 핵심인 Saga Orchestrator의 행위를 검증한다.
 * 성공/실패/보상실패 경로를 각각 테스트하여 모든 시나리오에서
 * Order 상태, SagaInstance 상태, Outbox 이벤트가 정확히 관리되는지 확인한다.
 *
 * <p>[면접 포인트] Saga Orchestrator 패턴에서 상태를 DB에 영속화하면
 * 서버가 중간에 죽더라도 미완료 Saga를 찾아 복구할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SagaOrchestrator 단위 테스트")
class SagaOrchestratorTest {

    @Mock
    private SagaInstanceRepository sagaInstanceRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private InventoryClient inventoryClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private SagaOrchestrator sagaOrchestrator;

    /**
     * INVENTORY_RESERVED 상태의 Order를 생성한다.
     * startSaga()의 진입 조건이며, Saga 시작 전 재고 예약이 이미 완료된 상태다.
     */
    private Order createInventoryReservedOrder() {
        Order order = new Order("order-1", 1L, 100L, "key-1");
        order.addItem(new OrderItem(10L, "치킨", 18000, 2));
        order.addItem(new OrderItem(20L, "피자", 25000, 1));
        order.transitionTo(OrderStatus.INVENTORY_RESERVED);
        return order;
    }

    /**
     * PAYMENT_PROCESSING 상태의 Order를 생성한다.
     * handlePaymentResult()의 진입 조건이다.
     */
    private Order createPaymentProcessingOrder() {
        Order order = createInventoryReservedOrder();
        order.transitionTo(OrderStatus.PAYMENT_PROCESSING);
        return order;
    }

    @Nested
    @DisplayName("startSaga - Saga 시작")
    class StartSaga {

        @Test
        @DisplayName("Saga 시작 시 SagaInstance(PAYMENT_REQUESTED) + Order(PAYMENT_PROCESSING) + Outbox 저장")
        void startSaga_createsInstanceAndTransitionsOrder() {
            // Saga 시작 시 3가지가 같은 트랜잭션에서 수행되어야 한다:
            // 1) SagaInstance 생성 및 PAYMENT_REQUESTED 상태 설정
            // 2) Order 상태를 PAYMENT_PROCESSING으로 전이
            // 3) "payment-requested" Outbox 이벤트 저장
            Order order = createInventoryReservedOrder();

            sagaOrchestrator.startSaga(order);

            // SagaInstance 저장 검증
            ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
            then(sagaInstanceRepository).should().save(sagaCaptor.capture());
            SagaInstance savedSaga = sagaCaptor.getValue();
            assertThat(savedSaga.getOrderId()).isEqualTo("order-1");
            assertThat(savedSaga.getStatus()).isEqualTo(SagaStatus.PAYMENT_REQUESTED);

            // Order 상태 전이 검증
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);

            // Outbox 이벤트 검증 — "payment-requested" 토픽으로 발행될 이벤트
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            then(outboxRepository).should().save(outboxCaptor.capture());
            OutboxEvent savedEvent = outboxCaptor.getValue();
            assertThat(savedEvent.getEventType()).isEqualTo("payment-requested");
            assertThat(savedEvent.getAggregateId()).isEqualTo("order-1");
        }
    }

    @Nested
    @DisplayName("handlePaymentResult - 결제 성공")
    class PaymentSuccess {

        @Test
        @DisplayName("결제 성공 시 Inventory confirm + Order CONFIRMED + Outbox(order-confirmed)")
        void handlePaymentResult_success_completesOrder() {
            // 결제 성공 시 전체 흐름:
            // 1) SagaInstance → PAYMENT_COMPLETED → COMPLETED
            // 2) Inventory Service에 재고 확정 요청 (각 item별)
            // 3) Order → CONFIRMED
            // 4) "order-confirmed" Outbox 이벤트 저장 (Notification Service 등이 소비)
            Order order = createPaymentProcessingOrder();
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment(); // PAYMENT_REQUESTED 상태로 설정

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));
            given(orderRepository.findByOrderId("order-1")).willReturn(Optional.of(order));

            sagaOrchestrator.handlePaymentResult("order-1", true, "pg-tx-001");

            // 재고 확정 호출 검증 — 주문 항목 수만큼 호출
            then(inventoryClient).should(times(2)).confirmStock(anyLong(), eq("order-1"), anyInt());

            // Order 최종 상태 검증
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

            // Saga 최종 상태 검증
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);

            // Outbox 이벤트 검증
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            then(outboxRepository).should().save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order-confirmed");
        }
    }

    @Nested
    @DisplayName("handlePaymentResult - 결제 실패 (보상 성공)")
    class PaymentFailure {

        @Test
        @DisplayName("결제 실패 시 Inventory release + Order CANCELLED + Outbox(order-cancelled)")
        void handlePaymentResult_failure_compensatesAndCancels() {
            // 결제 실패 시 보상 트랜잭션 흐름:
            // 1) Order → COMPENSATING
            // 2) Inventory Service에 재고 해제 요청
            // 3) 보상 성공 → Order → CANCELLED, Saga → FAILED
            // 4) "order-cancelled" Outbox 이벤트 저장
            Order order = createPaymentProcessingOrder();
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));
            given(orderRepository.findByOrderId("order-1")).willReturn(Optional.of(order));

            sagaOrchestrator.handlePaymentResult("order-1", false, null);

            // 재고 해제 호출 검증
            then(inventoryClient).should(times(2)).releaseStock(anyLong(), eq("order-1"), anyInt());

            // Order 최종 상태 검증
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

            // Saga 최종 상태 검증
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);

            // Outbox 이벤트 검증
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            then(outboxRepository).should().save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("order-cancelled");
        }
    }

    @Nested
    @DisplayName("handlePaymentResult - 보상 실패 (최대 재시도 초과)")
    class CompensationFailure {

        @Test
        @DisplayName("보상 3회 실패 시 COMPENSATION_FAILED + DLQ 발행")
        void handlePaymentResult_compensationFails_sentToDlq() {
            // [면접 포인트] 보상 트랜잭션도 실패할 수 있다.
            // 최대 재시도(3회) 초과 시 COMPENSATION_FAILED로 전환하고
            // DLQ 토픽에 발행하여 운영팀이 수동으로 처리할 수 있게 한다.
            // 자동 복구 불가능한 상태이므로 모니터링/알림이 필수다.
            Order order = createPaymentProcessingOrder();
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();
            // 이전 2회 재시도 실패를 시뮬레이션
            saga.incrementRetryCount();
            saga.incrementRetryCount();

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));
            given(orderRepository.findByOrderId("order-1")).willReturn(Optional.of(order));
            // 재고 해제 호출 시 예외 발생 (Inventory Service 장애)
            doThrow(new RuntimeException("Inventory service unavailable"))
                    .when(inventoryClient).releaseStock(anyLong(), anyString(), anyInt());

            sagaOrchestrator.handlePaymentResult("order-1", false, null);

            // 3회째 재시도 실패 → COMPENSATION_FAILED
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATION_FAILED);
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
            assertThat(saga.getRetryCount()).isEqualTo(3);

            // DLQ 토픽에 직접 발행 검증
            then(kafkaTemplate).should().send(eq("order-compensation-dlq"), eq("order-1"), anyString());
        }

        @Test
        @DisplayName("보상 1회 실패 시 retryCount만 증가하고 DLQ 발행하지 않음")
        void handlePaymentResult_compensationFailsOnce_retriesWithoutDlq() {
            // 첫 번째 보상 실패: retryCount가 1로 증가하지만 최대 횟수(3) 미만이므로
            // COMPENSATION_FAILED로 전환하지 않고, DLQ에도 발행하지 않는다.
            // 다음 Kafka 재전달에서 다시 시도할 기회가 있다.
            Order order = createPaymentProcessingOrder();
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));
            given(orderRepository.findByOrderId("order-1")).willReturn(Optional.of(order));
            doThrow(new RuntimeException("Inventory service unavailable"))
                    .when(inventoryClient).releaseStock(anyLong(), anyString(), anyInt());

            sagaOrchestrator.handlePaymentResult("order-1", false, null);

            // retryCount는 1로 증가하지만 COMPENSATING 상태 유지
            assertThat(saga.getRetryCount()).isEqualTo(1);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);

            // DLQ 발행은 하지 않음
            then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("handlePaymentResult - 멱등성")
    class Idempotency {

        @Test
        @DisplayName("이미 COMPLETED인 SagaInstance에 대한 재처리 요청은 무시된다")
        void handlePaymentResult_alreadyCompleted_ignored() {
            // [면접 포인트] At-least-once + 멱등성 = 사실상 Exactly-once
            // Kafka는 at-least-once 전달을 보장하므로 같은 메시지가 여러 번 올 수 있다.
            // 이미 완료된 Saga에 대한 중복 메시지를 무시함으로써
            // Consumer 측에서 exactly-once 시맨틱을 사실상(effectively) 달성한다.
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();
            saga.completePayment();
            saga.complete(); // COMPLETED 상태

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));

            sagaOrchestrator.handlePaymentResult("order-1", true, "pg-tx-001");

            // Order 조회도 하지 않아야 한다 — 불필요한 DB 접근 방지
            then(orderRepository).should(never()).findByOrderId(anyString());
            // Outbox 이벤트도 저장하지 않아야 한다
            then(outboxRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("이미 FAILED인 SagaInstance에 대한 재처리 요청은 무시된다")
        void handlePaymentResult_alreadyFailed_ignored() {
            // 보상이 완료되어 FAILED 상태인 Saga도 재처리 대상이 아니다
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();
            saga.startCompensation();
            saga.fail(); // FAILED 상태

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));

            sagaOrchestrator.handlePaymentResult("order-1", false, null);

            then(orderRepository).should(never()).findByOrderId(anyString());
            then(outboxRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("이미 COMPENSATION_FAILED인 SagaInstance에 대한 재처리 요청은 무시된다")
        void handlePaymentResult_alreadyCompensationFailed_ignored() {
            // COMPENSATION_FAILED는 수동 처리 대상이므로 시스템이 자동 재처리하면 안 된다
            SagaInstance saga = new SagaInstance("saga-1", "order-1", "{}");
            saga.startPayment();
            saga.startCompensation();
            saga.failCompensation(); // COMPENSATION_FAILED 상태

            given(sagaInstanceRepository.findByOrderId("order-1")).willReturn(Optional.of(saga));

            sagaOrchestrator.handlePaymentResult("order-1", true, "pg-tx-001");

            then(orderRepository).should(never()).findByOrderId(anyString());
            then(outboxRepository).should(never()).save(any());
        }
    }
}
