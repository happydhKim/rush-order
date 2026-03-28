# CQRS 설계

## 왜 CQRS인가

배달 앱에서 메뉴 조회와 메뉴 수정의 비율은 극도로 비대칭이다:

- **읽기**: 사용자 수천 명이 동시에 메뉴를 검색하고 조회한다.
- **쓰기**: 가게 사장님이 하루에 몇 번 메뉴를 수정한다.

읽기와 쓰기를 같은 DB에서 처리하면:
- 전문 검색("강남역 떡볶이")을 PostgreSQL의 LIKE로 처리해야 한다. 성능이 나쁘다.
- 읽기 트래픽이 쓰기 성능에 영향을 준다.

## 이 프로젝트의 CQRS 범위

완전한 Command/Query 모델 분리(별도 도메인 모델)가 아니라, **쓰기 저장소(PostgreSQL)와 조회 최적화 저장소(Elasticsearch)의 Read Model 분리**에 가깝다.

```text
[Write Path]
  Client → Restaurant Service → PostgreSQL
  메뉴 생성/수정/삭제 → PG에 저장 → Outbox 이벤트 발행

[Read Path]
  Client → Restaurant Service → Redis(캐시 확인) → Elasticsearch(검색)
  메뉴 검색/조회 → Redis 캐시 히트 시 즉시 반환, 미스 시 ES 조회

[Sync Path]
  1단계: Kafka Consumer가 이벤트를 받아 ES 인덱싱
  2단계(확장): Flink CDC가 PostgreSQL WAL → ES 직접 동기화
```

## 동기화 지연 (Eventual Consistency)

PG에 쓰기가 반영된 후 ES에 동기화되기까지 지연이 발생한다:

- 1단계(Outbox 폴링): 1~5초
- 2단계(Flink CDC): 0.5~2초

이 지연이 허용 가능한 이유:
- 가게 사장님이 메뉴를 수정한 후 "몇 초 뒤에 반영됩니다"는 배달 앱에서 자연스러운 UX이다.
- 실시간 재고처럼 강한 일관성이 필요한 데이터는 CQRS 대상이 아니다.

## ES 인덱스 설계

```json
{
  "mappings": {
    "properties": {
      "restaurantId": { "type": "keyword" },
      "restaurantName": {
        "type": "text",
        "analyzer": "nori"
      },
      "menuName": {
        "type": "text",
        "analyzer": "nori"
      },
      "category": { "type": "keyword" },
      "price": { "type": "integer" },
      "available": { "type": "boolean" },
      "location": { "type": "geo_point" },
      "updatedAt": { "type": "date" }
    }
  }
}
```

## 장애 대응

```text
ES 정상:
  검색 → ES
  상세 조회 → Redis 캐시 → ES

ES 장애:
  인기 메뉴 목록 → Redis 캐시 (사전에 워밍업)
  상세 조회 → PostgreSQL 직접 쿼리 (성능 저하, 서비스 중단 방지)
  검색 → 제한된 기능 (PG LIKE 기반, 품질 저하)
```

## 구현 현황

### Write Path — Outbox 이벤트 발행

Restaurant Service의 쓰기 작업(생성, 수정, 메뉴 추가/수정) 시 같은 트랜잭션에서 Outbox 이벤트를 저장한다. Outbox 폴링 워커가 `restaurant-updated` 토픽으로 Kafka에 발행한다.

```java
// RestaurantService.java — 비즈니스 데이터 + Outbox 이벤트를 같은 TX에서 저장
@Transactional
public RestaurantResponse createRestaurant(RestaurantRequest request) {
    Restaurant restaurant = new Restaurant(...);
    restaurantRepository.save(restaurant);
    saveOutboxEvent(restaurant);  // 같은 TX
    return RestaurantResponse.from(restaurant);
}
```

### Sync Path — Kafka Consumer → ES 인덱싱

`RestaurantEventConsumer`가 `restaurant-updated` 토픽을 구독하여 `RestaurantDocument`로 변환 후 ES에 인덱싱한다.

### Read Path — 3단계 Fallback

`RestaurantSearchService`가 검색 요청을 처리한다:

```text
1. Redis 캐시 히트 → 즉시 반환 (TTL 5분)
2. ES 검색 → 결과를 Redis에 캐싱 후 반환
3. ES 장애 시 → PG 직접 쿼리 (fallback)
```

### ES Document 설계

`RestaurantDocument`는 가게 정보와 메뉴 목록을 하나의 ES 문서로 관리한다. 메뉴는 Nested 타입으로 저장하여 cross-matching 문제를 방지한다 (Object 타입은 배열 요소가 flat하게 저장되어 메뉴A의 가격과 메뉴B의 이름이 매칭될 수 있다).

### 검색 API

| 엔드포인트 | 설명 |
| --- | --- |
| `GET /api/restaurants/search/keyword?q=&cursor=&size=` | Nori 기반 키워드 검색 (가게명 + 메뉴명) |
| `GET /api/restaurants/search/category?category=&cursor=&size=` | 카테고리 필터 |
| `GET /api/restaurants/search/nearby?lat=&lon=&distance=&cursor=&size=` | 위치 기반 검색 (geo_distance) |
| `GET /api/restaurants/search/{restaurantId}` | 상세 조회 (Redis → ES → PG fallback) |

## 키셋 페이징 (Cursor-based Pagination)

```sql
-- Offset 방식: 뒤로 갈수록 느려짐
SELECT * FROM menus ORDER BY created_at DESC LIMIT 20 OFFSET 10000;

-- 키셋 방식: 일정한 성능
SELECT * FROM menus
WHERE created_at < :cursor
ORDER BY created_at DESC
LIMIT 20;
```

키셋 페이징을 선택한 이유:
- 배달 앱에서 "더 보기" 방식의 무한 스크롤이 일반적이다.
- Offset은 건너뛴 행도 모두 스캔하므로 깊은 페이지에서 성능이 급격히 저하된다.
- 키셋은 인덱스를 타므로 어느 위치에서든 일정한 성능을 보인다.

**[고민 포인트] Eventual Consistency의 허용 범위**

CQRS를 도입하면 쓰기 저장소(PG)와 읽기 저장소(ES) 사이에 동기화 지연이 생긴다. 이 지연이 허용 가능한 데이터와 그렇지 않은 데이터를 명확히 구분해야 한다.

허용 가능:

- 메뉴 이름, 가격, 설명 → 가게 사장님이 수정 후 수 초 뒤 반영되어도 자연스럽다.
- 가게 영업 상태 → 1~2초 지연은 사용자가 체감하지 못한다.

허용 불가 (CQRS 대상에서 제외):

- 재고 수량 → 실시간 정합성이 필요하다. 재고는 Inventory Service에서 동기적으로 관리한다.
- 주문 상태 → 사용자가 즉시 확인해야 한다. Order Service에서 직접 조회한다.

**[고민 포인트] ES 장애 시 서비스 연속성**

ES가 죽으면 검색 기능이 멈추지만, 서비스 전체가 멈추면 안 된다. 계층별 fallback을 설계한다:

1. 인기 메뉴 목록 → Redis에 주기적으로 워밍업해둔 캐시에서 서비스한다.
2. 메뉴 상세 조회 → PostgreSQL 직접 쿼리로 대체한다. 전문 검색은 불가하지만 ID 기반 조회는 가능하다.
3. 검색 → PG의 `ILIKE` 기반으로 제한된 검색을 제공한다. 형태소 분석이 안 되므로 품질은 저하되지만, "검색 불가"보다는 낫다.

Circuit Breaker를 ES 호출에 적용하여, ES 장애 감지 시 자동으로 fallback 경로로 전환한다.

## 테스트 커버리지

### RestaurantSearchServiceTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 키워드 검색 정상 | ES 조회 → RestaurantSearchResponse 반환 |
| Redis 캐시 히트 | ES 미호출 — 캐시에서 직접 반환 |
| Redis 장애 | ES 직접 조회 (fallback 1단계) |
| ES 장애 | PG fallback (fallback 2단계) — ILIKE 기반 제한 검색 |

### RestaurantServiceTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| 가게 등록 | Restaurant 저장 + Outbox("restaurant-updated") 저장 — CQRS 동기화 |
| 메뉴 추가 | Menu 저장 + Outbox 이벤트 |
| 가게 조회 | 정상/NotFound |

### RestaurantEventConsumerTest (단위 테스트)

| 테스트 케이스 | 검증 내용 |
| --- | --- |
| restaurant-updated | Kafka 메시지 수신 → ES 인덱싱 |
