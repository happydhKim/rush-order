# Saga 패턴 설계

## 왜 Saga인가

주문 플로우는 4개 서비스에 걸친 장기 트랜잭션이다:
`주문 생성 → 재고 예약 → 결제 → 가게 확인`

전통적인 2PC(Two-Phase Commit)는 서비스 간 블로킹이 발생하고, 참여자 중 하나라도 장애가 나면 전체가 멈춘다. MSA 환경에서는 서비스별 독립 배포와 장애 격리가 핵심이므로, 비동기 보상 기반의 Saga가 적합하다.

## Orchestration vs Choreography

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| Orchestration | 플로우가 한 곳에서 관리됨, 디버깅 용이 | Orchestrator가 단일 장애점(SPOF) |
| Choreography | 서비스 간 결합도 낮음 | 플로우 추적 어려움, 순환 의존 위험 |

**선택: Orchestration**

이유: 주문 플로우가 순차적이고 보상 트랜잭션 순서가 중요하다. Orchestrator가 상태 머신을 관리하므로 "현재 어디까지 진행했는가"를 명확히 알 수 있다. SPOF 우려는 Orchestrator 자체의 상태를 DB에 영구 저장하여 장애 시 마지막 상태부터 재개함으로써 완화한다.

## 상태 머신

```text
PENDING
  → INVENTORY_RESERVED (재고 예약 성공)
    → PAYMENT_PROCESSING (결제 요청 중)
      → CONFIRMED (결제 성공, 주문 확정)
      → COMPENSATING (결제 실패, 보상 시작)
        → CANCELLED (재고 롤백 완료)
        → COMPENSATION_FAILED (보상 실패 → DLQ)
  → INVENTORY_FAILED (재고 부족)
    → CANCELLED
```

### 상태 전이 규칙

- 모든 전이는 단방향이다. CONFIRMED → CANCELLED 직접 전이는 불가하다 (환불은 별도 플로우).
- 각 전이는 DB에 영구 저장된다. 서비스 재시작 시 마지막 상태부터 재개한다.
- COMPENSATING 중 실패하면 COMPENSATION_FAILED 상태로 전이되고, 해당 이벤트는 Dead Letter Queue로 이동하여 수동 처리한다.

## 보상 트랜잭션

| 실패 지점 | 보상 액션 | 멱등성 보장 |
| --- | --- | --- |
| 결제 실패 | 재고 예약 해제 | 예약 ID 기반 중복 해제 방지 |
| 결제 타임아웃 | 결제 상태 조회 후 판단 | 조회는 부수효과 없음 |
| 보상 자체 실패 | 재시도(지수 백오프, 최대 3회) → DLQ | 각 보상 액션에 멱등키 적용 |

## Saga 상태 저장 엔티티

```java
@Entity
@Table(name = "saga_instances")
public class SagaInstance {
    @Id
    private String sagaId;
    private String orderId;

    @Enumerated(EnumType.STRING)
    private SagaStatus status;

    @Column(columnDefinition = "jsonb")
    private String payload;

    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

## 구현 현황

### SagaOrchestrator (Order Service)

`SagaOrchestrator`가 전체 흐름을 중앙에서 관리한다:

```java
// 1. 주문 생성 직후 — 같은 TX에서 호출
sagaOrchestrator.startSaga(order);
  → SagaInstance 생성 (PAYMENT_REQUESTED)
  → Order → PAYMENT_PROCESSING
  → Outbox("payment-requested") 저장

// 2. Outbox 폴링 → Kafka("payment-requested")

// 3. Payment Service의 PaymentEventConsumer
  → PaymentService.processPayment() (Resilience4j 적용)
  → Kafka("payment-result") 발행

// 4. Order Service의 PaymentResultConsumer
  → sagaOrchestrator.handlePaymentResult(orderId, success, pgTxId)
  → 성공: Inventory confirm(Feign) → Order CONFIRMED → Outbox("order-confirmed")
  → 실패: Inventory release(Feign) → Order CANCELLED → Outbox("order-cancelled")
  → 보상 실패(3회): Order COMPENSATION_FAILED → DLQ("order-compensation-dlq")
```

### SagaInstance 엔티티

```java
@Entity @Table(name = "saga_instances")
public class SagaInstance {
    private String sagaId;      // UUID PK
    private String orderId;     // unique
    private SagaStatus status;  // STARTED → PAYMENT_REQUESTED → COMPLETED/FAILED/COMPENSATION_FAILED
    private String payload;     // 주문 정보 JSON
    private int retryCount;     // 보상 재시도 횟수
}
```

### 멱등성 보장

- `handlePaymentResult()`에서 SagaInstance 상태 확인 — 이미 COMPLETED/FAILED면 무시
- PaymentService의 idempotencyKey 기반 중복 결제 방지
- Inventory release의 멱등성 — StockReservation 상태가 이미 RELEASED면 skip

### Kafka 이벤트 플로우

| 토픽 | 발행자 | 소비자 | 파티션 키 |
| --- | --- | --- | --- |
| order-created | Order Outbox | Notification | orderId |
| payment-requested | Order Outbox | Payment | orderId |
| payment-result | Payment | Order(Saga) | orderId |
| order-confirmed | Order Outbox | Notification | orderId |
| order-cancelled | Order Outbox | Notification | orderId |
| order-compensation-dlq | Order(DLQ) | 수동 처리 | orderId |

## 동기/비동기 분리 전략

```text
[동기 구간] 주문 접수 시점
  Client → Order Service → Inventory Service (Feign)
  재고 예약까지 동기 처리하여 초과판매 방지

[비동기 구간] Outbox + Kafka
  Order Service → Kafka → Payment Service → Kafka → Order Service(Saga)
  결제 이후는 비동기로 분리하여 사용자 체감 지연 억제
```

**[고민 포인트] Exactly-once는 가능한가?**

Saga 자체는 At-least-once 보장이다. 네트워크 장애로 메시지가 재전달될 수 있으므로, 각 참여자(Inventory, Payment)가 같은 요청을 두 번 받을 가능성이 있다. 이것을 Saga 레벨에서 Exactly-once로 만들기는 사실상 불가능하다.

해결 방향은 "중복 실행을 안전하게 만드는 것"이다. 각 참여자에 멱등성을 적용하면, 같은 메시지가 두 번 와도 결과는 한 번 실행한 것과 동일하다. Saga가 At-least-once + 참여자 멱등성 = 사실상 Exactly-once 효과를 내는 구조다.

**[고민 포인트] Orchestrator 장애 시 복구**

Orchestrator가 죽으면 진행 중인 Saga가 멈춘다. 이것이 Orchestration 방식의 근본적 한계이다.

완화 전략:

- Saga 상태를 DB(saga_instances 테이블)에 영구 저장한다. 재시작 시 `status != CONFIRMED && status != CANCELLED`인 Saga를 조회하여 마지막 상태부터 재개한다.
- Saga 진행 중 Orchestrator가 죽어도, 재고 예약에는 TTL(5분)이 있으므로 무한정 잠기지 않는다.
- 복수 인스턴스 운영 시 분산 락(Redis)으로 같은 Saga를 두 인스턴스가 동시에 재개하는 것을 방지한다.

## 테스트 커버리지

### SagaOrchestratorTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| startSaga | SagaInstance(PAYMENT_REQUESTED) 생성 + Order(PAYMENT_PROCESSING) 전이 + Outbox 저장 |
| handlePaymentResult 성공 | Inventory confirm(Feign) → Order CONFIRMED → Outbox("order-confirmed") |
| handlePaymentResult 실패 | Inventory release(Feign) → Order CANCELLED → Outbox("order-cancelled") |
| 보상 3회 실패 | COMPENSATION_FAILED 상태 전이 + DLQ("order-compensation-dlq") 발행 |
| 보상 1회 실패 | retryCount 증가만, DLQ 미발행 (3회 미만) |
| 멱등성 (COMPLETED) | 이미 종료 상태면 재처리 무시 — At-least-once + 멱등성 = Exactly-once 효과 |

### OrderStatusTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 허용된 상태 전이 7개 | PENDING→INVENTORY_RESERVED, CONFIRMED 등 모든 정방향 전이 |
| 거부되는 상태 전이 | 최종 상태(CONFIRMED, CANCELLED, COMPENSATION_FAILED)에서의 전이 차단 |
| ParameterizedTest | 모든 상태 조합을 ALLOWED_TRANSITIONS 맵과 대조 |
| Order.transitionTo() 통합 | 정상 흐름, 보상 흐름, 보상 실패 흐름 시나리오 |
