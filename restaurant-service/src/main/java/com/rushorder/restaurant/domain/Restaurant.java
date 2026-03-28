package com.rushorder.restaurant.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 가게 엔티티.
 *
 * <p>가게는 여러 메뉴를 소유하며, 영업 상태를 관리한다.
 * CQRS 패턴에서 Write Model(PostgreSQL)의 근원 데이터이다.
 *
 * @see Menu
 */
@Entity
@Table(name = "restaurants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RestaurantStatus status;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Restaurant(String name, String address, String phone, String category,
                      Double latitude, Double longitude) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = RestaurantStatus.OPEN;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateInfo(String name, String address, String phone, String category) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.category = category;
        this.updatedAt = LocalDateTime.now();
    }

    public void changeStatus(RestaurantStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 메뉴를 추가하고 양방향 관계를 설정한다.
     */
    public void addMenu(Menu menu) {
        menus.add(menu);
        menu.assignRestaurant(this);
    }
}
