-- =============================================================================
-- Rush Order 서비스별 데이터베이스 및 테이블 초기화 스크립트
-- =============================================================================
-- docker-compose의 postgres 서비스 초기화 시 실행된다.
-- 모든 서비스는 ddl-auto=validate이므로, 스키마가 미리 존재해야 한다.
--
-- 실행 순서:
--   1. 서비스별 데이터베이스 생성
--   2. 각 데이터베이스에 접속하여 테이블 + 인덱스 생성
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 서비스별 독립 데이터베이스 생성 (Database per Service 패턴)
-- ---------------------------------------------------------------------------
CREATE DATABASE rushorder_order OWNER rushorder;
CREATE DATABASE rushorder_payment OWNER rushorder;
CREATE DATABASE rushorder_inventory OWNER rushorder;
CREATE DATABASE rushorder_restaurant OWNER rushorder;
CREATE DATABASE rushorder_notification OWNER rushorder;

-- ===========================================================================
-- 2. rushorder_order
-- ===========================================================================
\c rushorder_order

-- 주문 루트 엔티티. Saga의 대상이 되는 핵심 Aggregate Root.
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(255)  NOT NULL UNIQUE,
    user_id         BIGINT        NOT NULL,
    restaurant_id   BIGINT        NOT NULL,
    status          VARCHAR(50)   NOT NULL,
    total_amount    INT           NOT NULL,
    idempotency_key VARCHAR(255)  NOT NULL UNIQUE,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 주문 항목. 하나의 주문에 포함되는 개별 메뉴와 수량.
CREATE TABLE order_items (
    id        BIGSERIAL PRIMARY KEY,
    order_id  BIGINT       NOT NULL REFERENCES orders(id),
    menu_id   BIGINT       NOT NULL,
    menu_name VARCHAR(255) NOT NULL,
    price     INT          NOT NULL,
    quantity  INT          NOT NULL
);

-- Outbox 이벤트. 비즈니스 TX와 같은 TX에서 INSERT되어 At-least-once 발행을 보장한다.
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    payload        TEXT         NOT NULL,
    processed      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL,
    processed_at   TIMESTAMP
);

-- 미발행 이벤트 폴링 최적화. (processed=false, created_at ASC) 순서로 조회.
CREATE INDEX idx_outbox_unprocessed ON outbox_events (processed, created_at);

-- Saga 실행 인스턴스. 분산 트랜잭션의 진행 상태를 DB에 영속화한다.
-- 서버 재시작 시에도 미완료 Saga를 복구할 수 있다.
CREATE TABLE saga_instances (
    saga_id     VARCHAR(36)  PRIMARY KEY,
    order_id    VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(50)  NOT NULL,
    payload     TEXT,
    retry_count INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

-- Stuck saga 감지용. 특정 상태에서 오래 머문 Saga를 조회한다.
CREATE INDEX idx_saga_status_updated ON saga_instances (status, updated_at);

-- ===========================================================================
-- 3. rushorder_payment
-- ===========================================================================
\c rushorder_payment

-- 결제 엔티티. 멱등키로 DB 레벨 중복 결제를 방지한다.
CREATE TABLE payments (
    id                BIGSERIAL PRIMARY KEY,
    order_id          VARCHAR(255) NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    amount            INT          NOT NULL,
    status            VARCHAR(50)  NOT NULL,
    pg_transaction_id VARCHAR(255),
    failure_reason    VARCHAR(500),
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);

CREATE INDEX idx_payment_order_id ON payments (order_id);

-- ===========================================================================
-- 4. rushorder_inventory
-- ===========================================================================
\c rushorder_inventory

-- 메뉴별 재고. 가용 재고 = total_stock - reserved_stock.
-- 동시성 제어는 SELECT ... FOR UPDATE (비관적 락)으로 수행한다.
CREATE TABLE inventories (
    id             BIGSERIAL PRIMARY KEY,
    menu_id        BIGINT    NOT NULL UNIQUE,
    total_stock    INT       NOT NULL,
    reserved_stock INT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMP
);

-- 주문 단위 재고 예약 추적. TTL 기반 자동 만료를 지원한다.
CREATE TABLE stock_reservations (
    id             BIGSERIAL PRIMARY KEY,
    menu_id        BIGINT       NOT NULL,
    order_id       VARCHAR(255) NOT NULL,
    quantity       INT          NOT NULL,
    reserved_at    TIMESTAMP    NOT NULL,
    reserved_until TIMESTAMP    NOT NULL,
    status         VARCHAR(20)  NOT NULL
);

-- 만료된 예약 조회 최적화. 스케줄러가 status=RESERVED AND reserved_until < now()로 조회.
CREATE INDEX idx_reservation_expired ON stock_reservations (status, reserved_until);

-- ===========================================================================
-- 5. rushorder_restaurant
-- ===========================================================================
\c rushorder_restaurant

-- 가게 엔티티. CQRS Write Model(PostgreSQL)의 근원 데이터.
CREATE TABLE restaurants (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255)     NOT NULL,
    address    VARCHAR(500)     NOT NULL,
    phone      VARCHAR(50)      NOT NULL,
    category   VARCHAR(100)     NOT NULL,
    status     VARCHAR(50)      NOT NULL,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 메뉴 엔티티. 변경 시 Outbox를 통해 Elasticsearch에 비동기 동기화된다.
CREATE TABLE menus (
    id            BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT       NOT NULL REFERENCES restaurants(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NOT NULL,
    price         INT          NOT NULL,
    available     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);

-- Restaurant Service Outbox. Order Service와 구조가 다르다 (topic 컬럼 사용).
CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL
);

-- 미발행 이벤트 폴링 최적화.
CREATE INDEX idx_restaurant_outbox_unpublished ON outbox_events (published, created_at);

-- ===========================================================================
-- 6. rushorder_notification
-- ===========================================================================
\c rushorder_notification

-- 알림 이력. 이벤트 소비 멱등성을 event_id UNIQUE로 보장한다.
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    event_id   VARCHAR(255) NOT NULL UNIQUE,
    order_id   VARCHAR(255) NOT NULL,
    user_id    BIGINT,
    type       VARCHAR(50)  NOT NULL,
    status     VARCHAR(50)  NOT NULL,
    title      VARCHAR(255),
    message    TEXT,
    channel    VARCHAR(50),
    sent_at    TIMESTAMP,
    created_at TIMESTAMP
);

CREATE INDEX idx_notification_order_id ON notifications (order_id);
CREATE INDEX idx_notification_user_id ON notifications (user_id);
