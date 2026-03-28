package com.rushorder.inventory.service;

import com.rushorder.common.exception.NotFoundException;
import com.rushorder.inventory.domain.Inventory;
import com.rushorder.inventory.domain.StockReservation;
import com.rushorder.inventory.dto.StockInitRequest;
import com.rushorder.inventory.dto.StockReserveRequest;
import com.rushorder.inventory.dto.StockReserveRequest.StockItem;
import com.rushorder.inventory.dto.StockResponse;
import com.rushorder.inventory.repository.InventoryRepository;
import com.rushorder.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 재고 관리 서비스.
 *
 * <p>비관적 락(SELECT ... FOR UPDATE)을 사용하여 동시 접근 시
 * Lost Update를 방지한다. 주요 설계 결정:
 *
 * <ul>
 *   <li><b>데드락 방지</b>: 여러 메뉴의 재고를 동시에 변경할 때
 *       menuId 오름차순으로 락을 획득한다.</li>
 *   <li><b>예약/확정/해제 분리</b>: 결제 전 예약(reserve), 결제 성공 시 확정(confirm),
 *       실패 시 해제(release)로 재고 라이프사이클을 관리한다.</li>
 * </ul>
 *
 * @see com.rushorder.inventory.repository.InventoryRepository#findByMenuIdForUpdate
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository stockReservationRepository;

    /**
     * 재고를 초기 등록한다.
     */
    @Transactional
    public StockResponse initStock(StockInitRequest request) {
        Inventory inventory = new Inventory(request.menuId(), request.totalStock());
        return StockResponse.from(inventoryRepository.save(inventory));
    }

    /**
     * 재고를 예약한다 (주문 접수 시, 동기 호출).
     *
     * <p>데드락 방지를 위해 menuId 오름차순으로 정렬 후 순차적으로 락을 획득한다.
     * 하나라도 재고가 부족하면 InsufficientStockException이 발생하며,
     * 트랜잭션 롤백으로 이전에 예약한 건도 모두 원복된다.
     *
     * <p>각 메뉴별로 StockReservation 엔티티를 생성하여 예약을 건별로 추적한다.
     * reservedUntil = now + 5분으로 TTL을 설정하며, 이 시간 내에 결제가
     * 완료되지 않으면 스케줄러가 자동으로 재고를 해제한다.
     *
     * <p>[면접 포인트] @Retryable로 비관적 락 충돌(PessimisticLockingFailureException)
     * 발생 시 지수 백오프(100ms → 200ms → 400ms)로 최대 3회 재시도한다.
     * 재시도는 새 트랜잭션에서 수행되므로 이전 트랜잭션의 락은 자동 해제된다.
     *
     * @param request 예약할 메뉴 목록과 수량 (orderId 포함)
     * @throws com.rushorder.common.exception.InsufficientStockException 가용 재고 부족 시
     */
    @Transactional
    @Retryable(
            retryFor = PessimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void reserveStock(StockReserveRequest request) {
        // 데드락 방지: menuId 오름차순으로 락 획득 순서를 통일
        List<StockItem> sorted = request.items().stream()
                .sorted(Comparator.comparing(StockItem::menuId))
                .toList();

        for (StockItem item : sorted) {
            Inventory inventory = inventoryRepository.findByMenuIdForUpdate(item.menuId())
                    .orElseThrow(() -> new NotFoundException("Inventory", item.menuId().toString()));

            inventory.reserve(item.quantity());

            // 예약을 건별로 추적 — TTL 만료 시 스케줄러가 이 엔티티를 기반으로 재고를 해제
            StockReservation reservation = new StockReservation(
                    item.menuId(), request.orderId(), item.quantity()
            );
            stockReservationRepository.save(reservation);

            log.info("Reserved stock: menuId={}, orderId={}, quantity={}, remaining={}, reservedUntil={}",
                    item.menuId(), request.orderId(), item.quantity(),
                    inventory.getAvailableStock(), reservation.getReservedUntil());
        }
    }

    /**
     * 예약을 확정한다 (결제 성공 시).
     *
     * <p>Inventory의 재고를 확정 처리하고, 해당 StockReservation의 상태를
     * CONFIRMED로 변경한다. 예약이 존재하지 않아도 재고 확정은 수행한다
     * (하위 호환성 보장).
     */
    @Transactional
    public void confirmStock(String orderId, Long menuId, int quantity) {
        Inventory inventory = inventoryRepository.findByMenuIdForUpdate(menuId)
                .orElseThrow(() -> new NotFoundException("Inventory", menuId.toString()));

        inventory.confirm(quantity);

        stockReservationRepository.findByOrderIdAndMenuId(orderId, menuId)
                .ifPresent(StockReservation::confirm);

        log.info("Confirmed stock: menuId={}, orderId={}, quantity={}, totalStock={}",
                menuId, orderId, quantity, inventory.getTotalStock());
    }

    /**
     * 예약을 해제한다 (결제 실패, 보상 트랜잭션 시).
     *
     * <p>Inventory의 예약 수량을 원복하고, 해당 StockReservation의 상태를
     * RELEASED로 변경한다.
     */
    @Transactional
    public void releaseStock(String orderId, Long menuId, int quantity) {
        Inventory inventory = inventoryRepository.findByMenuIdForUpdate(menuId)
                .orElseThrow(() -> new NotFoundException("Inventory", menuId.toString()));

        inventory.release(quantity);

        stockReservationRepository.findByOrderIdAndMenuId(orderId, menuId)
                .ifPresent(StockReservation::release);

        log.info("Released stock: menuId={}, orderId={}, quantity={}, available={}",
                menuId, orderId, quantity, inventory.getAvailableStock());
    }

    public StockResponse getStock(Long menuId) {
        Inventory inventory = inventoryRepository.findByMenuId(menuId)
                .orElseThrow(() -> new NotFoundException("Inventory", menuId.toString()));
        return StockResponse.from(inventory);
    }
}
