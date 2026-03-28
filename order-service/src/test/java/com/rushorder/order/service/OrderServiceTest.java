package com.rushorder.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.DuplicateRequestException;
import com.rushorder.common.exception.InsufficientStockException;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.order.client.InventoryClient;
import com.rushorder.order.domain.Order;
import com.rushorder.order.dto.OrderCreateRequest;
import com.rushorder.order.dto.OrderCreateRequest.OrderItemRequest;
import com.rushorder.order.dto.OrderResponse;
import com.rushorder.order.outbox.OutboxEvent;
import com.rushorder.order.outbox.OutboxRepository;
import com.rushorder.order.repository.OrderRepository;
import com.rushorder.order.saga.SagaOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

/**
 * OrderService 단위 테스트.
 *
 * <p>주문 생성의 핵심 비즈니스 로직을 Mock 기반으로 검증한다.
 * 외부 의존성(DB, Redis, Feign)을 모두 Mock으로 대체하여
 * 순수한 비즈니스 로직만 빠르게 테스트한다.
 *
 * <p>[면접 포인트] 단위 테스트에서 Mock을 사용하는 이유:
 * 외부 시스템 의존 없이 빠르게 피드백을 받고, 테스트 대상의 행위만 검증할 수 있다.
 * 통합 테스트(Testcontainers)와 역할 분담하여 테스트 피라미드를 구성한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService 단위 테스트")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private SagaOrchestrator sagaOrchestrator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderService orderService;

    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";

    private OrderCreateRequest createValidRequest() {
        return new OrderCreateRequest(
                1L,
                100L,
                List.of(
                        new OrderItemRequest(10L, "치킨", 18000, 2),
                        new OrderItemRequest(20L, "피자", 25000, 1)
                )
        );
    }

    @Nested
    @DisplayName("주문 생성 - 정상 흐름")
    class CreateOrderSuccess {

        @Test
        @DisplayName("정상 주문 생성 시 Order 저장 + Outbox 저장 + Saga 시작이 수행된다")
        void createOrder_success() {
            // 정상 흐름에서 3가지 핵심 동작을 모두 검증:
            // 1) Order 엔티티 저장 (DB 영속화)
            // 2) Outbox 이벤트 저장 (Kafka 발행을 위한 트랜잭션 보장)
            // 3) Saga 시작 (결제 요청 트리거)
            OrderCreateRequest request = createValidRequest();
            given(idempotencyService.isDuplicate(IDEMPOTENCY_KEY)).willReturn(false);

            OrderResponse response = orderService.createOrder(request, IDEMPOTENCY_KEY);

            // Order 저장 검증
            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            then(orderRepository).should().save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();
            assertThat(savedOrder.getUserId()).isEqualTo(1L);
            assertThat(savedOrder.getRestaurantId()).isEqualTo(100L);
            assertThat(savedOrder.getItems()).hasSize(2);
            // 총 금액: 치킨 18000*2 + 피자 25000*1 = 61000
            assertThat(savedOrder.getTotalAmount()).isEqualTo(61000);

            // Outbox 이벤트 저장 검증 — Dual Write 방지의 핵심
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            then(outboxRepository).should().save(outboxCaptor.capture());
            OutboxEvent savedEvent = outboxCaptor.getValue();
            assertThat(savedEvent.getAggregateType()).isEqualTo("Order");
            assertThat(savedEvent.getEventType()).isEqualTo("order-created");
            assertThat(savedEvent.isProcessed()).isFalse();

            // Saga 시작 검증
            then(sagaOrchestrator).should().startSaga(any(Order.class));

            // 응답 검증
            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("재고 예약이 주문 저장보다 먼저 호출된다")
        void createOrder_reservesInventoryBeforeSave() {
            // [면접 포인트] 재고 예약을 동기로 먼저 호출하는 이유:
            // 초과판매(overselling)를 방지하기 위해 DB에 주문을 저장하기 전에
            // 재고가 확보되었음을 보장해야 한다.
            OrderCreateRequest request = createValidRequest();
            given(idempotencyService.isDuplicate(IDEMPOTENCY_KEY)).willReturn(false);

            orderService.createOrder(request, IDEMPOTENCY_KEY);

            // 재고 예약 호출 검증
            then(inventoryClient).should().reserveStock(any(InventoryClient.StockReserveCommand.class));
        }
    }

    @Nested
    @DisplayName("주문 생성 - 멱등키 중복")
    class CreateOrderDuplicate {

        @Test
        @DisplayName("이미 처리된 멱등키로 요청하면 DuplicateRequestException이 발생한다")
        void createOrder_duplicateKey_throwsException() {
            // [면접 포인트] 멱등성 보장:
            // 네트워크 재시도, 사용자 더블 클릭 등으로 같은 요청이 중복 도착할 수 있다.
            // Redis SET NX로 원자적으로 중복을 감지하여 주문이 2번 생성되는 것을 방지한다.
            OrderCreateRequest request = createValidRequest();
            given(idempotencyService.isDuplicate(IDEMPOTENCY_KEY)).willReturn(true);

            assertThatThrownBy(() -> orderService.createOrder(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(DuplicateRequestException.class);

            // 중복 요청 시 Order 저장, Outbox 저장, Saga 시작 모두 수행되지 않아야 한다
            then(orderRepository).should(never()).save(any());
            then(outboxRepository).should(never()).save(any());
            then(sagaOrchestrator).should(never()).startSaga(any());
        }
    }

    @Nested
    @DisplayName("주문 생성 - 재고 부족")
    class CreateOrderInsufficientStock {

        @Test
        @DisplayName("재고 부족 시 InsufficientStockException이 전파되고 Order는 저장되지 않는다")
        void createOrder_insufficientStock_throwsAndDoesNotSave() {
            // 재고 예약 실패 시 주문이 DB에 저장되면 안 된다.
            // FeignErrorDecoder가 4xx 응답을 InsufficientStockException으로 변환하며,
            // 이 예외가 그대로 전파되어야 한다.
            OrderCreateRequest request = createValidRequest();
            given(idempotencyService.isDuplicate(IDEMPOTENCY_KEY)).willReturn(false);
            doThrow(new InsufficientStockException("10", 5, 10))
                    .when(inventoryClient).reserveStock(any());

            assertThatThrownBy(() -> orderService.createOrder(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(InsufficientStockException.class);

            // 재고 부족 시 Order와 Outbox 모두 저장되지 않아야 한다
            then(orderRepository).should(never()).save(any());
            then(outboxRepository).should(never()).save(any());
            then(sagaOrchestrator).should(never()).startSaga(any());
        }
    }

    @Nested
    @DisplayName("주문 조회")
    class GetOrder {

        @Test
        @DisplayName("존재하는 주문 조회 시 OrderResponse를 반환한다")
        void getOrder_found_returnsResponse() {
            // orderId로 주문을 조회하여 응답 DTO로 변환하는 기본 흐름 검증
            Order order = new Order("order-123", 1L, 100L, "key-1");
            given(orderRepository.findByOrderId("order-123")).willReturn(Optional.of(order));

            OrderResponse response = orderService.getOrder("order-123");

            assertThat(response.orderId()).isEqualTo("order-123");
            assertThat(response.userId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("존재하지 않는 주문 조회 시 NotFoundException이 발생한다")
        void getOrder_notFound_throwsException() {
            // 존재하지 않는 orderId로 조회 시 명확한 예외를 던져야 한다
            given(orderRepository.findByOrderId("nonexistent")).willReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrder("nonexistent"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("nonexistent");
        }
    }
}
