# 멱등성 설계

## 왜 멱등성이 필요한가

분산 시스템에서 네트워크 불안정은 일상이다. 클라이언트가 주문 요청을 보냈는데 응답을 받지 못하면, 같은 요청을 재전송한다. 서버 입장에서 이 두 요청이 "같은 주문의 재시도"인지 "새로운 주문"인지 구분할 수 없다.

결제가 관련된 도메인에서 중복 처리는 치명적이다. 같은 주문이 두 번 결제되면 사용자 신뢰를 잃는다.

## 해결: 멱등키 (Idempotency Key)

클라이언트가 요청마다 고유한 키(UUID)를 생성하여 헤더에 포함한다.

```text
POST /api/orders
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

서버는 이 키로 "이미 처리한 요청인가"를 판단한다.

## 처리 흐름

```text
1. 요청 수신 → Redis에서 멱등키 확인
2. 키가 없으면:
   → Redis SET NX (원자적 선점) + TTL 24h
   → 주문 처리
   → 결과를 Redis에 캐싱
3. 키가 있으면:
   → 캐싱된 결과를 반환 (재처리 하지 않음)
```

## 구현

```java
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * 멱등키의 중복 여부를 확인하고, 중복이 아니면 선점한다.
     *
     * SET NX는 원자적 연산이므로 동시에 같은 키로 요청이 와도
     * 하나만 성공한다.
     */
    public boolean isDuplicate(String idempotencyKey) {
        Boolean result = redisTemplate.opsForValue()
            .setIfAbsent("idempotency:" + idempotencyKey, "PROCESSING", TTL);
        return result == null || !result;
    }

    public void cacheResponse(String idempotencyKey, String response) {
        redisTemplate.opsForValue()
            .set("idempotency:" + idempotencyKey, response, TTL);
    }

    public String getCachedResponse(String idempotencyKey) {
        return redisTemplate.opsForValue()
            .get("idempotency:" + idempotencyKey);
    }
}
```

## 적용 위치 — 구현 완료

| 서비스 | 적용 대상 | 멱등키 | 방식 |
| --- | --- | --- | --- |
| Order Service | 주문 생성 API | X-Idempotency-Key 헤더 (UUID) | Redis SET NX + DB unique |
| Payment Service | 결제 처리 | orderId를 idempotencyKey로 사용 | DB unique constraint |
| Notification Service | Kafka Consumer | `{topic}:{key}:{offset}` | DB unique (event_id) |
| Saga Orchestrator | 결제 결과 처리 | SagaInstance 상태 확인 | 상태 기반 멱등성 |

### Consumer 멱등성 (Notification Service)

Kafka의 At-least-once 특성상 같은 메시지가 재전달될 수 있다. `eventId`를 `{topic}:{key}:{offset}` 조합으로 생성하고, DB unique constraint로 중복 처리를 방지한다.

```java
@KafkaListener(topics = "order-created")
public void handleOrderCreated(ConsumerRecord<String, String> record) {
    String eventId = record.topic() + ":" + record.key() + ":" + record.offset();
    // 1차: existsByEventId() SELECT 체크
    // 2차: DB unique constraint (TOCTOU 방어)
}
```

### Saga 상태 기반 멱등성

`handlePaymentResult()`에서 SagaInstance의 현재 상태를 확인한다. COMPLETED/FAILED/COMPENSATION_FAILED 같은 종료 상태면 재처리하지 않는다. Kafka 메시지 재전달 시 안전하게 무시된다.

## SET NX의 원자성

Redis의 `SET NX`(Set if Not eXists)는 단일 명령어로 "존재 여부 확인 + 생성"을 원자적으로 수행한다. 이것이 중요한 이유는:

```text
만약 GET → 없으면 SET 두 단계로 처리하면:
  Thread A: GET → 없음
  Thread B: GET → 없음  (A가 SET 하기 전)
  Thread A: SET
  Thread B: SET           ← 중복 처리 발생

SET NX는 이것을 하나의 원자적 명령으로 해결한다.
```

## TTL 설정 근거

- **24시간**: 사용자가 하루 안에 재시도할 가능성을 커버한다.
- 너무 짧으면(1시간) 늦은 재시도를 중복 주문으로 처리할 수 없다.
- 너무 길면(7일) Redis 메모리가 불필요하게 소비된다.
- 24시간은 배달 앱의 사용 패턴에 적합한 합리적 기간이다.

## 멱등키 생성 책임

멱등키는 **클라이언트가 생성**한다. 서버가 생성하면 "같은 요청의 재시도"를 구분할 수 없다.

클라이언트 구현 시:

- 주문 화면 진입 시 UUID를 생성한다.
- 같은 화면에서 재시도하면 동일한 UUID를 사용한다.
- 새로운 주문 화면을 열면 새 UUID를 생성한다.

**[고민 포인트] Redis 장애 시 멱등성 보장**

Redis가 장애 나면 멱등키 확인이 불가능해진다. 이때 두 가지 선택지가 있다:

1. **요청을 거부한다 (Fail-closed)**: 안전하지만 가용성이 떨어진다. 모든 주문이 실패한다.
2. **DB fallback으로 전환한다 (Fail-open with fallback)**: DB의 unique constraint(orderId + idempotencyKey)로 중복을 방지한다. 성능은 저하되지만 서비스는 유지된다.

이 프로젝트에서는 2번을 선택한다. Redis 장애는 일시적이고, 그 동안 DB unique constraint가 최소한의 중복 방지를 제공한다.

**[고민 포인트] DB unique만으로 충분하지 않은 이유**

DB unique constraint만으로도 중복 방지는 가능하다. 하지만 대량 트래픽에서 문제가 된다:

- DB 조회는 네트워크 왕복 + 디스크 I/O가 필요하다. Redis SET NX는 인메모리 O(1)이다.
- 점심시간 피크에 수천 건의 주문이 동시에 들어올 때, 모든 중복 체크를 DB에 위임하면 DB 커넥션 풀이 멱등키 확인만으로 소진될 수 있다.
- Redis를 앞단에 두면 대부분의 중복 요청을 DB에 도달하기 전에 차단한다. DB는 Redis를 통과한 요청만 처리하므로 부하가 줄어든다.

## 테스트 커버리지

### IdempotencyServiceTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| SET NX 성공 (새 키) | isDuplicate → false, Redis에 "PROCESSING" + TTL 24h 저장 |
| SET NX 실패 (중복 키) | isDuplicate → true, 재처리 방지 |
| SET NX null 결과 | isDuplicate → true — 안전한 쪽으로 처리 (defensive) |
| Redis 장애 시 | isDuplicate → false — DB unique constraint에 위임 (Fail-open with fallback) |
| cacheResponse/getCachedResponse | 정상 캐싱 및 조회 + Redis 장애 시 graceful 처리 |

### NotificationServiceTest — Consumer 멱등성

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 정상 알림 생성 | eventId로 저장 + 발송 → SENT 상태 |
| 중복 eventId (1차 방어) | existsByEventId() SELECT → 저장하지 않고 리턴 |
| DB unique constraint (2차 방어) | DataIntegrityViolationException catch — TOCTOU 방어 |
| eventId 구성 | `{topic}:{key}:{offset}` — Kafka At-least-once 특성에 맞춘 고유 식별자 |

### PaymentServiceTest — 결제 멱등성

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 동일 idempotencyKey | 기존 결제 결과 반환, PG 재호출 없음 |
