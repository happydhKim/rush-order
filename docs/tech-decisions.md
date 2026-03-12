# 기술 스택 선택 근거

> 모든 기술 선택에는 "왜 이것을 선택했고, 대안은 무엇이었는가"가 있어야 한다.

## 서비스 프레임워크: Spring Boot 3.4.x + Java 21

**대안:** Quarkus, Micronaut, Ktor

**선택 이유:**

- Java 21은 현재 가장 널리 사용되는 LTS 버전이다. Virtual Threads, Record, Pattern Matching, Sealed Classes 등 모던 Java 기능을 활용할 수 있다.
- Spring Boot 3.4.x는 Java 21을 완전히 지원하며, Virtual Threads를 `spring.threads.virtual.enabled=true` 한 줄로 활성화할 수 있다.
- Spring Cloud(Gateway, OpenFeign, LoadBalancer), Spring Data JPA, Resilience4j 등 MSA에 필요한 모든 컴포넌트가 하나의 생태계 안에 있다.

**트레이드오프:**

- Quarkus가 GraalVM 네이티브 빌드에서 기동 시간과 메모리 면에서 유리하다.
- 하지만 이 프로젝트의 목표는 런타임 성능이 아니라 분산 시스템 패턴 학습이다.
- Spring의 레퍼런스와 트러블슈팅 자료가 압도적으로 많아 학습 효율이 높다.

---

## 메시지 브로커: Kafka 4.0 (KRaft)

**대안:** RabbitMQ, Apache Pulsar, Redis Streams

**선택 이유:**

- Kafka 4.0에서 Zookeeper가 완전 제거되었다. KRaft 모드만으로 운영되므로 인프라가 단순해진다.
- 파티션 기반 순서 보장이 Saga/Outbox 패턴에 필수적이다. 같은 주문 ID를 같은 파티션으로 보내면 이벤트 순서가 보장된다.
- ISR(In-Sync Replicas) 복제 + `acks=all` + `min.insync.replicas=2`로 메시지 유실을 방지한다.

**트레이드오프:**

- RabbitMQ가 설정이 간단하고 메시지 라우팅(Exchange/Queue) 모델이 유연하다.
- 하지만 Flink 파이프라인 확장을 고려하면 Kafka가 사실상 표준이다.
- Kafka의 Consumer Group 기반 파티션 할당은 서비스 스케일아웃 시 자동으로 부하를 분산한다.

**[고민 포인트] RabbitMQ vs Kafka**

RabbitMQ를 고려했던 시점이 있었다. 설정이 간단하고 Exchange/Queue 기반 라우팅이 직관적이기 때문이다. 하지만 다음 이유로 Kafka를 선택했다:

1. **순서 보장**: 같은 주문 ID의 이벤트가 순서대로 처리되어야 한다. Kafka는 파티션 단위로 순서를 보장하지만, RabbitMQ는 Queue 하나에 여러 Consumer가 붙으면 순서가 깨진다.
2. **재처리 가능성**: Kafka는 offset 기반으로 과거 이벤트를 다시 읽을 수 있다. 장애 복구 시 "어디까지 처리했는가"를 offset으로 추적할 수 있다. RabbitMQ는 메시지를 소비하면 큐에서 사라진다.
3. **확장성**: Consumer Group 기반 파티션 자동 할당으로, 서비스 인스턴스를 추가하면 자동으로 부하가 분산된다.
4. **Flink 연동**: 확장 단계에서 Flink를 도입할 때 Kafka가 사실상 표준 소스이다.

---

## 데이터베이스: PostgreSQL 16

**대안:** MySQL, MariaDB

**선택 이유:**

- `PREPARE TRANSACTION` 명령으로 2PC를 네이티브 지원한다. 학습 확장 시나리오에서 별도 라이브러리 없이 직접 2PC를 체험할 수 있다.
- WAL(Write-Ahead Log) 기반 logical replication이 CDC 연동에 적합하다. `wal_level=logical`만 설정하면 Flink CDC가 변경 사항을 실시간으로 캡처한다.

**트레이드오프:**

- MySQL이 국내에서 더 널리 사용된다.
- 하지만 logical decoding 기반 CDC 설정이 PostgreSQL이 더 직관적이다.
- PostgreSQL의 JSONB 타입은 Outbox 이벤트 페이로드 저장에 유리하다.

---

## 캐시: Redis 7

**대안:** Memcached, Hazelcast

**선택 이유:**

하나의 인프라로 세 가지 역할을 해결한다:

1. **멱등키**: `SET NX` + TTL로 원자적 중복 체크
2. **Cache-Aside**: 메뉴 조회 캐싱
3. **분산 락**: Cache Stampede 방지 (SET NX 기반)

**트레이드오프:**

- Memcached가 단순 캐싱에서는 메모리 효율이 좋다.
- 하지만 멱등키(SET NX), Rate Limiting(Token Bucket), 분산 락 등 다양한 자료구조가 필요하므로 Redis가 적합하다.

---

## 검색 엔진 (CQRS Read Model): Elasticsearch 8.x

**대안:** Redis(캐시만), OpenSearch, Apache Solr

**선택 이유:**

- "강남역 떡볶이" 같은 전문 검색이 배달 앱 핵심 기능이다.
- CQRS Read Model로서 Inverted Index 기반 조회 최적화 저장소 역할을 자연스럽게 수행한다.
- Nori 한국어 형태소 분석기를 지원한다.

**장애 대응:**

- ES 장애 시 인기 메뉴는 Redis 캐시에서 서비스한다.
- 상세 조회는 PostgreSQL 직접 쿼리로 fallback한다 (성능 저하되지만 서비스 중단 방지).

---

## 인증: Keycloak 24

**대안:** JWT 직접 구현, Auth0, Firebase Auth

**선택 이유:**

- OAuth2/OIDC 표준을 준수하는 오픈소스 인증 서버이다.
- 인증 개발 시간을 절약하고 핵심 패턴(Saga, Outbox, CQRS, 멱등성)에 집중할 수 있다.
- Docker 이미지로 간편하게 구동되며 Realm/Client/Role 설정을 Admin Console에서 관리한다.

**트레이드오프:**

- JWT를 직접 구현하면 토큰 내부 동작(서명, 검증, 리프레시)을 더 깊이 이해할 수 있다.
- 하지만 이 프로젝트의 학습 목표는 분산 시스템 패턴이지 인증 구현이 아니다.

---

## API Gateway: Spring Cloud Gateway

**대안:** Kong, Apache APISIX, Envoy, Nginx

**선택 이유:**

- Spring 생태계와 완전한 통합. WebFlux 기반 non-blocking 처리.
- JWT 검증 필터, Rate Limiting, Trace ID 주입 등 횡단 관심사를 Java 코드로 직접 구현할 수 있다.

**트레이드오프:**

- Kong이나 APISIX가 순수 성능에서 우세하다.
- 하지만 로컬 학습 환경에서는 성능보다 개발 편의성과 디버깅 용이성이 중요하다.

---

## 서비스 간 통신: OpenFeign + Spring Cloud LoadBalancer

**대안:** RestTemplate, WebClient, gRPC

**선택 이유:**

- 선언적 인터페이스로 코드가 깔끔하다. `@FeignClient` 어노테이션만으로 HTTP 클라이언트를 정의할 수 있다.
- Resilience4j와 자연스러운 통합이 가능하다 (`@CircuitBreaker`, `@Retry` 조합).

**트레이드오프:**

- Feign은 동기 블로킹 호출이다. 고부하 구간이 병목이 되면 WebClient(non-blocking)나 메시지 기반 비동기 통신으로 전환을 검토할 수 있다.
- 재고 예약처럼 응답이 반드시 필요한 동기 호출에서는 Feign의 선언적 인터페이스가 코드 가독성을 높인다.

---

## 장애 회복: Resilience4j

**대안:** Netflix Hystrix (deprecated), Sentinel

**선택 이유:**

- Hystrix의 공식 후계자이다.
- Circuit Breaker, Retry, Time Limiter, Bulkhead를 모듈별로 조합 가능하다.
- Spring Boot Actuator와 통합되어 Circuit Breaker 상태를 모니터링할 수 있다.

---

## 서비스 디스커버리: 사용하지 않음

**대안:** Eureka, Consul, Kubernetes Service Discovery

**선택 이유:**

- Docker Compose 환경에서는 서비스 이름이 DNS로 해석되므로 별도 디스커버리가 불필요하다.
- 프로덕션에서는 Kubernetes Service Discovery가 대체한다.
- 불필요한 인프라 컴포넌트를 줄여 학습의 핵심에 집중한다.

---

## 이벤트 발행: Outbox 폴링 → Flink CDC (확장)

**대안:** Debezium + Kafka Connect, 직접 Kafka Producer 호출

**선택 이유:**

- 1단계는 가장 단순한 폴링(`@Scheduled`)으로 시작한다.
- 폴링의 지연(1~5초)과 DB 부하를 직접 경험한 뒤 Flink CDC로 전환한다.
- "왜 CDC가 필요한가"를 자기 경험으로 설명할 수 있게 된다.

**트레이드오프:**

- CDC만 필요하면 Debezium + Kafka Connect가 더 일반적이고 운영 분리도 깔끔하다.
- 이 프로젝트는 이후 실시간 집계와 CEP까지 Flink로 연결하기 때문에 통합한다.

---

## 분산 추적: Micrometer Tracing + Zipkin

**대안:** Jaeger, OpenTelemetry Collector

**선택 이유:**

- Spring Boot 3.x에서 Micrometer Tracing이 기본 통합되어 있다.
- Zipkin은 경량이고 Docker로 쉽게 구동된다.
- Trace ID가 서비스 간 전파되어 주문 플로우 전체를 하나의 트레이스로 추적할 수 있다.
