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

## 적용 위치

| 서비스 | 적용 대상 | 이유 |
| --- | --- | --- |
| Order Service | 주문 생성 API | 중복 주문 방지 |
| Payment Service | 결제 처리 | 중복 결제 방지 |
| Saga 보상 트랜잭션 | 재고 롤백 | 보상이 중복 실행되어도 안전하게 |

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
