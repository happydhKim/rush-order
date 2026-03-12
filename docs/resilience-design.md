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

## Fallback 전략

```java
@CircuitBreaker(name = "pgPayment", fallbackMethod = "fallbackPayment")
@Retry(name = "pgPayment")
@TimeLimiter(name = "pgPayment")
public PaymentResult processExternalPayment(PaymentRequest request) {
    return pgClient.approve(request);
}

/**
 * PG 장애 시 결제를 즉시 실패시키지 않고 지연 큐에 넣어
 * PG 복구 후 재시도한다.
 */
public PaymentResult fallbackPayment(PaymentRequest request, Exception e) {
    delayedPaymentQueue.enqueue(request);
    return PaymentResult.PENDING;
}
```

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

## Rate Limiting (API Gateway)

Redis 기반 Token Bucket 알고리즘으로 트래픽을 제어한다.

```text
사용자별: 100 req/min
IP별:    1000 req/min
가게별:  5000 req/min (이벤트 시 동적 조절 가능)
```

Rate Limit 초과 시 `429 Too Many Requests`를 반환한다.

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
