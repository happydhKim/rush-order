# Transactional Outbox 패턴 설계

## 해결하려는 문제: Dual Write

주문을 DB에 저장하면서 동시에 Kafka에 이벤트를 발행해야 한다. 이 두 작업은 서로 다른 시스템에 대한 쓰기이므로, 하나는 성공하고 하나는 실패하는 상황이 발생할 수 있다.

```text
문제 시나리오:
1. DB INSERT 성공 → Kafka 발행 실패 → 이벤트 유실
2. Kafka 발행 성공 → DB INSERT 실패 → 유령 이벤트
```

## 해결: Outbox 테이블

핵심 아이디어는 단순하다. **Kafka 발행을 DB 트랜잭션 안에 포함시킨다.**

```text
@Transactional 범위:
  1. Order 테이블 INSERT
  2. Outbox 테이블 INSERT  ← 이벤트를 "예약"
  → 둘 다 성공 또는 둘 다 실패 (DB 트랜잭션이 보장)

별도 워커:
  3. Outbox 테이블에서 미발행 이벤트를 폴링
  4. Kafka로 발행
  5. processed = true로 업데이트
```

## @Transactional의 정확한 보장 범위

```text
보장하는 것:
  ✅ Order INSERT + Outbox INSERT의 원자성 (같은 DB, 같은 TX)

보장하지 않는 것:
  ❌ Kafka 메시지 발행 (DB 바깥의 시스템)
  → 이것은 Outbox 폴링 워커 또는 CDC가 비동기로 처리한다
```

이 분리가 Outbox 패턴의 핵심이다. DB 커밋은 확실히 되고, 메시지 발행은 별도 메커니즘이 책임진다.

## Outbox 엔티티

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateType;   // "Order"
    private String aggregateId;     // orderId
    private String eventType;       // "order-created"

    @Column(columnDefinition = "jsonb")
    private String payload;

    private boolean processed = false;
    private LocalDateTime createdAt;
}
```

## 1단계: 폴링 워커

```java
@Scheduled(fixedDelay = 1000)  // 1초 간격
public void publishPendingEvents() {
    List<OutboxEvent> events = outboxRepository
        .findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(0, 100));

    for (OutboxEvent event : events) {
        kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload());
        event.markAsProcessed();
        outboxRepository.save(event);
    }
}
```

### 폴링의 한계

- 폴링 간격(1초)만큼 이벤트 발행이 지연된다.
- 미발행 이벤트를 찾기 위한 주기적 SELECT가 DB에 부하를 준다.
- 폴링 간격을 줄이면 DB 부하가 증가하고, 늘리면 지연이 커지는 트레이드오프가 있다.

## 2단계: Flink CDC 전환 (확장)

```text
PostgreSQL WAL → Flink CDC Connector → Kafka

변경 사항:
- WAL의 logical replication으로 실시간 캡처 (~0.5~2초)
- DB에 추가 SELECT 쿼리 부하 없음
- processed 플래그의 역할이 축소됨 (WAL 변경 자체를 읽으므로)
```

## Outbox 이벤트 정리 전략

시간이 지나면 processed=true인 레코드가 누적된다:

- **단기**: 일별 배치로 7일 이상 된 processed 이벤트를 삭제한다.
- **장기**: 파티셔닝(created_at 기준)으로 오래된 파티션을 DROP한다.

**[고민 포인트] 폴링 워커가 Kafka 발행 후 죽으면?**

시나리오: 폴링 워커가 Kafka에 메시지를 발행했지만, `processed = true` 업데이트 전에 프로세스가 종료된다. 재시작하면 같은 이벤트를 다시 폴링하여 Kafka에 중복 발행한다.

이것은 Outbox 패턴의 설계상 의도된 동작이다. At-least-once 보장이며, 중복은 Consumer 쪽에서 멱등성으로 해결한다. 이벤트 유실보다 중복 발행이 훨씬 안전하다. 유실은 데이터 불일치로 이어지지만, 중복은 멱등 처리로 무해하게 만들 수 있기 때문이다.

**[고민 포인트] 왜 @Transactional 안에서 Kafka를 직접 호출하지 않는가?**

가장 직관적인 접근은 `@Transactional` 안에서 DB 저장과 Kafka 발행을 함께 하는 것이다. 하지만 이 방식은 두 가지 문제가 있다:

1. **Kafka 장애가 주문 실패로 이어진다**: Kafka 브로커가 일시적으로 응답하지 않으면 DB 트랜잭션도 롤백된다. 사용자는 "주문 실패"를 보게 된다. Outbox 방식이면 주문은 성공하고 이벤트 발행만 잠시 지연된다.
2. **트랜잭션 시간이 늘어난다**: Kafka 네트워크 왕복 시간만큼 DB 커넥션을 점유한다. 커넥션 풀 고갈 위험이 있다.

Outbox는 "DB 커밋의 신뢰성"과 "메시지 발행의 비동기성"을 분리하는 것이 핵심이다.
