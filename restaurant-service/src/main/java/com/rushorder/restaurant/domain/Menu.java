package com.rushorder.restaurant.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 메뉴 엔티티.
 *
 * <p>가게에 종속되며, 가격과 판매 가능 여부를 관리한다.
 * 메뉴 변경 시 Outbox 이벤트를 통해 Elasticsearch에 비동기 동기화된다.
 *
 * @see Restaurant
 */
@Entity
@Table(name = "menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private boolean available;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Menu(String name, String description, int price) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.available = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 양방향 관계 설정을 위한 패키지 프라이빗 메서드.
     * Restaurant.addMenu()에서 호출된다.
     */
    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }

    public void updateInfo(String name, String description, int price) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.updatedAt = LocalDateTime.now();
    }

    public void changeAvailability(boolean available) {
        this.available = available;
        this.updatedAt = LocalDateTime.now();
    }
}
