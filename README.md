# Rush Order - 배달 플랫폼 MSA

> 대량 주문 상황에서 **정합성과 확장성을 동시에 확보**하는 배달 플랫폼 백엔드

## 프로젝트 동기

"점심시간에 특정 가게에 주문이 폭증하는 상황"을 가정합니다.
이 시나리오에서 발생하는 분산 시스템의 핵심 문제들을 직접 설계하고 해결합니다.

- 서비스 간 트랜잭션을 어떻게 보장할 것인가? → **Saga**
- DB 커밋과 이벤트 발행의 원자성을 어떻게 확보할 것인가? → **Outbox**
- 네트워크 불안정 시 중복 주문을 어떻게 방지할 것인가? → **Idempotency**
- 읽기가 압도적인 메뉴 조회를 어떻게 최적화할 것인가? → **CQRS**

## 아키텍처

```text
                        Client
                          |
                    [API Gateway]
                   JWT / Rate Limit
                    /    |    \     \
            [Restaurant] [Order] [Payment] [Inventory]
              CQRS      Saga     Circuit    Pessimistic
             PG + ES   Outbox    Breaker      Lock
                          |
                       [Kafka]
                          |
                    [Notification]
```

## 핵심 설계 결정

| 문제 | 해결 패턴 | 설계 문서 |
| --- | --- | --- |
| 서비스 간 분산 트랜잭션 | Saga (Orchestration) | [saga-design.md](docs/saga-design.md) |
| DB 커밋 + 이벤트 발행 원자성 | Transactional Outbox | [outbox-design.md](docs/outbox-design.md) |
| 중복 주문/결제 방지 | Idempotency (Redis SET NX) | [idempotency-design.md](docs/idempotency-design.md) |
| 읽기/쓰기 비대칭 해소 | CQRS (PG + Elasticsearch) | [cqrs-design.md](docs/cqrs-design.md) |
| 외부 PG 장애 전파 차단 | Circuit Breaker (Resilience4j) | [resilience-design.md](docs/resilience-design.md) |
| 재고 동시 차감 경합 | Pessimistic Lock + Retry | [inventory-concurrency.md](docs/inventory-concurrency.md) |

## 서비스 구성

| 서비스               | 포트 | 역할        | 핵심 기술                                 |
| -------------------- | ---- | ----------- | ----------------------------------------- |
| API Gateway          | 8080 | 단일 진입점 | Spring Cloud Gateway, JWT, Rate Limiting  |
| Restaurant Service   | 8082 | 가게/메뉴   | CQRS (PostgreSQL + Elasticsearch)         |
| Order Service        | 8083 | 주문 처리   | Saga Orchestrator, Outbox Pattern         |
| Payment Service      | 8084 | 결제 처리   | Resilience4j, Idempotency                 |
| Inventory Service    | 8085 | 재고 관리   | Pessimistic Lock, Saga Participant        |
| Notification Service | 8086 | 알림 발송   | Kafka Consumer                            |

## 기술 스택

| 영역       | 기술                  | 선택 근거                                |
| ---------- | --------------------- | ---------------------------------------- |
| Language   | Java 21 (LTS)         | Virtual Threads, Record, Pattern Matching |
| Framework  | Spring Boot 3.4.x     | 국내 생태계, Spring Cloud 통합           |
| Database   | PostgreSQL 16         | WAL 기반 CDC, PREPARE TRANSACTION 지원   |
| Messaging  | Kafka 4.0 (KRaft)     | 파티션 순서 보장, Zookeeper 제거         |
| Cache      | Redis 7               | 멱등키, Cache-Aside, 분산 락 통합        |
| Search     | Elasticsearch 8.x     | 전문 검색, CQRS Read Model              |
| Auth       | Keycloak 24           | OAuth2/OIDC 표준, 인증 위임             |
| Resilience | Resilience4j          | Circuit Breaker, Retry, Bulkhead         |
| Tracing    | Micrometer + Zipkin   | Spring Boot 3.4 기본 통합               |
| Container  | Docker Compose        | 로컬 개발 환경                           |

기술 선택의 상세 근거와 대안 비교는 [docs/tech-decisions.md](docs/tech-decisions.md)에서 확인할 수 있습니다.

## 주문 플로우

```text
1. [Client] → 주문 요청 (JWT + 멱등키)
2. [Gateway] → JWT 검증 + Rate Limit
3. [Order Service] → 멱등키 중복 확인 (Redis SET NX)
4. [Order Service] → 재고 예약 요청 (Feign, 동기)
5. [Inventory Service] → SELECT FOR UPDATE + 재고 차감
6. [Order Service] → 주문 저장 + Outbox 이벤트 (단일 TX)
7. [Order Service] → PENDING 응답 반환
8. [Outbox Worker] → 미발행 이벤트 폴링 → Kafka 발행
9. [Saga Orchestrator] → 결제 요청 (비동기)
10. [Payment Service] → PG 승인 (Circuit Breaker)
11. 성공 → 주문 확정 / 실패 → 보상 트랜잭션 (재고 복구)
```

**왜 재고를 동기 처리하는가:**
초과판매를 방지하기 위해 재고 예약까지는 동기로 처리합니다.
결제 이후 단계는 비동기로 분리하여 사용자 체감 지연을 억제합니다.

## 프로젝트 구조

```text
rush-order/
├── common/                     # 공통 모듈 (DTO, Exception, Utils)
├── gateway/                    # API Gateway (JWT, Rate Limiting)
├── restaurant-service/         # 가게/메뉴 (CQRS)
├── order-service/              # 주문 (Saga + Outbox)
├── payment-service/            # 결제 (Resilience4j)
├── inventory-service/          # 재고 (Pessimistic Lock)
├── notification-service/       # 알림 (Kafka Consumer)
├── infra/                      # 인프라 설정 (DB 초기화 스크립트 등)
├── docs/                       # 설계 문서
├── docker-compose.yml          # 인프라 컨테이너
├── settings.gradle             # 멀티모듈 설정
└── build.gradle                # 루트 빌드 설정
```

## 실행 방법

### 사전 요구사항

- Java 21+
- Docker & Docker Compose
- 16GB+ RAM 권장

### 인프라 실행

```bash
docker compose up -d postgres kafka redis elasticsearch keycloak zipkin
```

### 서비스 빌드 및 실행

```bash
./gradlew build
docker compose up -d
```

## 구현 로드맵

### Phase 1: 기반 구축

- [ ] Docker Compose 인프라 (PG, Kafka, Redis, Keycloak, ES, Zipkin)
- [ ] Gradle 멀티모듈 프로젝트 구조
- [ ] Keycloak Realm/Client 설정
- [ ] API Gateway (JWT 검증, Rate Limiting, Trace ID)

### Phase 2: 핵심 서비스

- [ ] Restaurant Service: CRUD + ES 인덱싱 (CQRS)
- [ ] Inventory Service: 재고 관리 (비관적 락 + 재시도)
- [ ] Order Service: 주문 생성 (멱등키 + Outbox)
- [ ] 서비스 간 통신 (OpenFeign + Resilience4j)

### Phase 3: Outbox + Kafka

- [ ] Outbox 테이블 + JPA 단일 트랜잭션
- [ ] Outbox 폴링 워커
- [ ] Kafka 토픽 구성
- [ ] Notification Service

### Phase 4: Saga

- [ ] Saga Orchestrator (상태 머신)
- [ ] 보상 트랜잭션 (재고 롤백, 주문 취소)
- [ ] Payment Service (PG Mock + Circuit Breaker)
- [ ] Dead Letter Queue

## 문서

- [기술 스택 선택 근거](docs/tech-decisions.md)
- [Saga 설계](docs/saga-design.md)
- [Outbox 패턴 설계](docs/outbox-design.md)
- [멱등성 설계](docs/idempotency-design.md)
- [CQRS 설계](docs/cqrs-design.md)
- [장애 복구 설계](docs/resilience-design.md)
- [재고 동시성 제어](docs/inventory-concurrency.md)

## License

MIT License
