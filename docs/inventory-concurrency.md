# 재고 동시성 제어

## 문제 상황

점심시간에 인기 메뉴(예: 치킨)에 수십 명이 동시에 주문한다. 재고가 10개 남았을 때 20명이 동시에 주문하면, 적절한 동시성 제어 없이는 재고가 음수가 되는 초과판매가 발생한다.

```text
Thread A: SELECT stock FROM inventory WHERE menu_id = 1;  → 10
Thread B: SELECT stock FROM inventory WHERE menu_id = 1;  → 10
Thread A: UPDATE inventory SET stock = 10 - 1 = 9;
Thread B: UPDATE inventory SET stock = 10 - 1 = 9;  ← Lost Update
```

## 선택: 비관적 락 (Pessimistic Lock)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT i FROM Inventory i WHERE i.menuId = :menuId")
Optional<Inventory> findByMenuIdForUpdate(@Param("menuId") String menuId);
```

`SELECT ... FOR UPDATE`는 해당 행에 배타적 잠금을 건다. 다른 트랜잭션은 이 잠금이 해제될 때까지 대기한다.

### 왜 비관적 락인가

| 방식 | 장점 | 단점 | 적합한 상황 |
| --- | --- | --- | --- |
| 낙관적 락 (Version) | 락 없이 동시성 높음 | 충돌 시 재시도 비용 | 충돌이 드문 경우 |
| 비관적 락 (FOR UPDATE) | 충돌 없이 순차 처리 | 대기 시간 발생 | 충돌이 빈번한 경우 |

인기 메뉴는 동시 주문이 빈번하다. 낙관적 락을 쓰면 OptimisticLockException이 반복적으로 발생하여 재시도 비용이 크다. 비관적 락으로 순차 처리하는 것이 전체 처리량에서 유리하다.

## 데드락 방지: 락 순서 통일

하나의 주문에 여러 메뉴가 포함될 때, 락 획득 순서가 다르면 데드락이 발생한다.

```text
주문 A: menu_1 락 → menu_2 락 시도 (대기)
주문 B: menu_2 락 → menu_1 락 시도 (대기)
→ 데드락
```

해결: 항상 menuId 오름차순으로 잠근다.

```java
public void reserveStock(List<OrderItem> items) {
    // 데드락 방지: menuId 오름차순 정렬 후 순차적으로 락 획득
    List<OrderItem> sorted = items.stream()
        .sorted(Comparator.comparing(OrderItem::getMenuId))
        .toList();

    for (OrderItem item : sorted) {
        Inventory inventory = inventoryRepository
            .findByMenuIdForUpdate(item.getMenuId())
            .orElseThrow(() -> new MenuNotFoundException(item.getMenuId()));

        inventory.decrease(item.getQuantity());
    }
}
```

## 재시도 정책 (지수 백오프)

락 경합으로 타임아웃이 발생하면 즉시 재시도하지 않고 점진적으로 대기 시간을 늘린다.

```text
1차 재시도: 100ms 대기
2차 재시도: 200ms 대기
3차 재시도: 400ms 대기
최대 3회, 이후 실패 반환
```

## 재고 예약 TTL

주문 접수 시 재고를 "예약"하고, 결제가 완료되면 확정한다. 결제가 일정 시간 내에 완료되지 않으면 예약을 자동 해제한다.

```text
주문 접수 → 재고 예약 (reserved_until = now + 5분)
결제 성공 → 재고 확정 (reserved_until = null, confirmed = true)
5분 경과 → 스케줄러가 미확정 예약을 자동 해제
```

```java
@Scheduled(fixedDelay = 60000)  // 1분 간격
public void releaseExpiredReservations() {
    int released = inventoryRepository
        .releaseExpiredReservations(LocalDateTime.now());
    if (released > 0) {
        log.info("Released {} expired reservations", released);
    }
}
```

## 재고 엔티티

```java
@Entity
@Table(name = "inventories")
public class Inventory {
    @Id
    private String menuId;
    private int totalStock;
    private int reservedStock;

    public void decrease(int quantity) {
        int available = totalStock - reservedStock;
        if (available < quantity) {
            throw new InsufficientStockException(menuId, available, quantity);
        }
        this.reservedStock += quantity;
    }

    public void confirm(int quantity) {
        this.totalStock -= quantity;
        this.reservedStock -= quantity;
    }

    public void release(int quantity) {
        this.reservedStock -= quantity;
    }
}
```

**[고민 포인트] 비관적 락의 확장 한계와 대안**

비관적 락의 근본적 한계는 직렬화 병목이다. 극단적으로 인기 메뉴 하나에 1000명이 동시에 주문하면, 모든 요청이 줄을 서서 한 건씩 처리된다. 락 대기 시간이 타임아웃을 초과하면 다수의 주문이 실패한다.

현재 프로젝트에서는 비관적 락을 선택했지만, 트래픽이 더 증가하면 다음 대안을 검토할 수 있다:

1. **Redis DECR 기반 재고 관리**: `DECR` 명령은 원자적이고 인메모리이므로 DB 락보다 훨씬 빠르다. 다만 Redis와 DB 간 재고 정합성을 별도로 관리해야 한다 (주기적 동기화 또는 이벤트 기반 동기화).
2. **재고 분산 (Sharding)**: 재고 100개를 10개씩 10개 슬롯으로 나눈다. 각 요청은 랜덤 슬롯에 접근하므로 경합이 1/10로 줄어든다. 슬롯 하나가 소진되면 다른 슬롯에서 가져온다 (rebalancing).
3. **낙관적 락 + 제한적 재시도**: 충돌이 드문 일반 메뉴에는 낙관적 락이 효율적이다. 메뉴별 주문 빈도를 모니터링하여 동적으로 락 전략을 전환하는 것도 가능하다.

**[고민 포인트] 낙관적 락을 선택하지 않은 이유**

낙관적 락(@Version)은 충돌이 드문 경우에 적합하다. 충돌 시 `OptimisticLockException`이 발생하고, 트랜잭션 전체를 롤백한 뒤 처음부터 재시도해야 한다.

인기 메뉴의 점심시간 특성상 충돌률이 50%를 넘을 수 있다. 이 경우 낙관적 락의 비용을 계산하면:

- 10건 중 5건이 충돌 → 5건 롤백 + 재시도 → 재시도 중 또 충돌 → 연쇄적 롤백
- 실질적으로 처리하는 DB 작업량이 비관적 락의 2~3배에 달한다

비관적 락은 대기 시간이 발생하지만, 한 번 락을 잡으면 확실히 성공한다. "대기 vs 롤백+재시도"의 트레이드오프에서, 충돌 빈도가 높은 이 도메인에서는 비관적 락이 전체 처리량에서 유리하다.
