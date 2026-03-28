package com.rushorder.inventory.repository;

import com.rushorder.inventory.domain.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * 비관적 락(SELECT ... FOR UPDATE)으로 재고를 조회한다.
     *
     * <p>동시에 같은 메뉴에 접근하는 트랜잭션은 이 락이 해제될 때까지 대기한다.
     * 이를 통해 Lost Update 문제를 원천 차단한다.
     *
     * @param menuId 메뉴 ID
     * @return 잠금이 걸린 재고 엔티티
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.menuId = :menuId")
    Optional<Inventory> findByMenuIdForUpdate(@Param("menuId") Long menuId);

    Optional<Inventory> findByMenuId(Long menuId);
}
