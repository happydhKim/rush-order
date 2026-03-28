# 장애 복구 설계

## 설계 원칙

외부 시스템(PG 결제 게이트웨이)의 장애가 내부 서비스로 전파되지 않도록 격리한다. Resilience4j를 사용하여 Circuit Breaker, Retry, Time Limiter, Bulkhead를 조합한다.

## Circuit Breaker

외부 PG 게이트웨이가 응답하지 않을 때, 계속 호출하면:
- 스레드가 대기 상태로 쌓인다.
- 커넥션 풀이 고갈된다.
- Payment Service 전체가 먹통이 된다.
- 나아가 Order Service, API Gateway까지 장애가 전파된다 (Cascading Failure).

Circuit Breaker는 이 연쇄 장애를 차단한다.

### 상태 전이

```text
CLOSED (정상)
  → 실패율이 50% 초과 시
OPEN (차단, 즉시 실패 반환)
  → 10초 대기 후
HALF_OPEN (일부 요청만 통과)
  → 성공 시 CLOSED / 실패 시 OPEN
```

### 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgPayment:
        slidingWindowSize: 10          # 최근 10건 기준
        failureRateThreshold: 50       # 50% 이상 실패 시 OPEN
        waitDurationInOpenState: 10s   # OPEN 후 10초 대기
        permittedNumberOfCallsInHalfOpenState: 3  # HALF_OPEN에서 3건 시험
  retry:
    instances:
      pgPayment:
        maxAttempts: 3                 # 최대 3회 재시도
        waitDuration: 500ms            # 재시도 간격
        exponentialBackoffMultiplier: 2 # 지수 백오프 (500ms → 1s → 2s)
        retryExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
  timelimiter:
    instances:
      pgPayment:
        timeoutDuration: 3s            # 3초 타임아웃
```

## Fallback 전략 — 구현 완료

```java
// PaymentService.java
@Retry(name = "pgPayment")
@CircuitBreaker(name = "pgPayment", fallbackMethod = "fallbackPayment")
@Bulkhead(name = "pgPayment")
@Transactional
public PaymentResponse processPayment(PaymentRequest request) {
    // 멱등성: 이미 처리된 결제가 있으면 기존 결과 반환
    Optional<Payment> existing = paymentRepository
        .findByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
        return PaymentResponse.from(existing.get());
    }

    // PG 승인 — 실패 시 예외가 전파되어 Retry/CircuitBreaker가 처리
    String pgTransactionId = pgClient.approve(request.orderId(), request.amount());

    Payment payment = new Payment(request.orderId(), request.idempotencyKey(), request.amount());
    payment.approve(pgTransactionId);
    paymentRepository.save(payment);
    return PaymentResponse.from(payment);
}

/**
 * PG 장애 시 결제를 즉시 실패시키지 않고 PENDING 상태로 저장하여
 * PG 복구 후 재처리할 수 있는 여지를 남긴다.
 */
public PaymentResponse fallbackPayment(PaymentRequest request, Exception e) {
    Payment payment = new Payment(request.orderId(), request.idempotencyKey(), request.amount());
    paymentRepository.save(payment);  // status = PENDING
    return PaymentResponse.from(payment);
}
```

핵심 설계 결정: 기존 코드에서는 `PgPaymentException`을 `try-catch`로 잡아 `FAILED` 상태로 저장했으나, 이 경우 Retry/CircuitBreaker AOP 프록시가 예외를 감지하지 못해 재시도가 동작하지 않았다. 예외를 메서드 밖으로 전파하도록 수정하여 Resilience4j 데코레이터가 정상 동작하게 했다.

### TimeLimiter 미적용 이유

`@TimeLimiter`는 `CompletableFuture`를 반환하는 비동기 메서드에만 적용 가능하다. 현재 `processPayment`는 동기 메서드이므로 어노테이션 적용이 불가하다. `application.yml`에 설정만 유지하여 향후 비동기 전환 시 활용할 수 있도록 한다.

## Bulkhead

Payment Service가 PG 호출에 모든 스레드를 소진하면, 다른 API(결제 조회, 환불)까지 영향을 받는다. Bulkhead로 PG 호출 전용 스레드 풀을 격리한다.

```yaml
resilience4j:
  bulkhead:
    instances:
      pgPayment:
        maxConcurrentCalls: 20         # 동시 최대 20개
        maxWaitDuration: 500ms         # 대기 최대 500ms
```

## Rate Limiting (API Gateway) — 구현 완료

Redis 기반 Token Bucket 알고리즘으로 트래픽을 제어한다. Spring Cloud Gateway의 `RequestRateLimiter` 필터 + `RedisRateLimiter`를 사용한다.

```text
IP별: replenishRate=100, burstCapacity=120
```

```yaml
# gateway application.yml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 100
      redis-rate-limiter.burstCapacity: 120
      key-resolver: "#{@ipKeyResolver}"
```

KeyResolver 구현:

- `ipKeyResolver`: `X-Forwarded-For` 헤더 우선, 없으면 `remoteAddress` 사용
- `userKeyResolver`: JWT principal에서 사용자 ID 추출 (선택적 사용)

Token Bucket을 선택한 이유: `replenishRate`로 평균 속도를 제한하면서 `burstCapacity`로 순간 트래픽을 허용한다. Fixed Window 방식 대비 경계 시점(boundary) 문제가 없다.

Rate Limit 초과 시 `429 Too Many Requests`를 반환한다.

## Feign ErrorDecoder — 구현 완료

Order Service → Inventory Service 간 Feign 통신에서 에러 응답을 적절한 비즈니스 예외로 변환한다.

```text
4xx 응답 → ApiResponse body 파싱 → BusinessException 변환 (INSUFFICIENT_STOCK 등)
5xx 응답 → ServiceUnavailableException (503)
```

4xx와 5xx를 구분하는 이유: 4xx는 클라이언트 요청 문제(재고 부족 등)이므로 재시도해도 결과가 같다. 5xx는 일시적 장애일 수 있으므로 Retry/CircuitBreaker 대상이 된다.

## 장애 시나리오별 대응

| 시나리오 | 대응 | 사용자 경험 |
| --- | --- | --- |
| PG 일시 장애 (3초 이내) | Retry + 지수 백오프 | 약간의 지연 후 성공 |
| PG 장기 장애 | Circuit Breaker OPEN → Fallback | "결제 처리 중" 안내, PG 복구 후 자동 재시도 |
| ES 장애 | Redis 캐시 + PG 직접 쿼리 | 검색 품질 저하, 서비스 유지 |
| Redis 장애 | DB unique constraint fallback | 멱등성 체크 약간 느려짐 |
| Kafka 장애 | Outbox 이벤트 누적, Kafka 복구 시 자동 소진 | 비동기 처리 지연 |

**[고민 포인트] slidingWindowSize 설정 기준**

slidingWindowSize를 몇으로 설정할 것인가는 정답이 없다. 트레이드오프를 이해하고 도메인에 맞게 조정해야 한다.

- **너무 작으면 (3)**: 일시적인 네트워크 지터에도 Circuit이 열린다. PG가 멀쩡한데 3번 연속 타임아웃이 나면 바로 OPEN이 되어 불필요한 서비스 중단이 발생한다.
- **너무 크면 (100)**: 실제 PG 장애 시 100건이 쌓여야 OPEN이 되므로, 그 사이에 100명의 사용자가 긴 대기를 경험한다.
- **10건**: 결제 호출이 초당 수십 건인 환경에서, 최근 10건은 수 초~수십 초의 데이터를 반영한다. 장애 감지는 빠르면서도 일시적 에러에 과민반응하지 않는 균형점이다.

**[고민 포인트] Retry와 Circuit Breaker의 데코레이터 순서**

Resilience4j에서 어노테이션을 여러 개 붙이면 실행 순서가 중요하다. 기본 순서는 다음과 같다:

```text
Retry(CircuitBreaker(TimeLimiter(실제 호출)))
```

이 순서의 의미: Retry가 가장 바깥에서 감싸므로, CircuitBreaker가 OPEN이면 Retry도 즉시 실패한다. 만약 반대로 CircuitBreaker(Retry(...))이면 Retry가 3번 재시도한 최종 결과만 Circuit Breaker에 기록된다. 이 경우 실제로는 9번 실패(3회 x 3회)했는데 Circuit Breaker는 3건만 인식하여 장애 감지가 느려진다.

`spring.cloud.circuitbreaker.resilience4j.enableGroupMeterFilter`로 실행 순서를 커스텀할 수 있지만, 기본 순서가 대부분의 상황에 적합하다.

## 테스트 커버리지

### PaymentServiceTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 정상 결제 | PG 호출 → Payment(APPROVED) 저장 → PaymentResponse 반환 |
| PG 실패 | PgPaymentException 전파 — AOP 프록시가 Retry/CB를 트리거하도록 catch하지 않음 |
| 멱등성 (중복 idempotencyKey) | 기존 결과 반환, PG 재호출 없음 |
| Fallback | PENDING 상태 저장 — PG 복구 후 재처리 가능 |

### PaymentEventConsumerTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 정상 메시지 | processPayment + payment-result 발행 |
| 결제 실패 | 실패 결과(success=false) 발행 — Saga 보상을 위해 반드시 발행 필요 |
| JSON 파싱 실패 | 로그만 남기고 에러 전파하지 않음 |

### FeignErrorDecoderTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 4xx (INSUFFICIENT_STOCK) | InsufficientStockException 변환 |
| 4xx (NOT_FOUND) | NotFoundException 변환 |
| 5xx | ServiceUnavailableException — Retry/CB 대상 |
