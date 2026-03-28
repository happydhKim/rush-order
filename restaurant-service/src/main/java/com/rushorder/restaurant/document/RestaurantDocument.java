package com.rushorder.restaurant.document;

import com.rushorder.restaurant.domain.Menu;
import com.rushorder.restaurant.domain.Restaurant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch에 저장되는 가게 문서.
 *
 * <p>CQRS Read Model로, PostgreSQL의 Restaurant + Menu 데이터를
 * 비정규화하여 단일 문서로 저장한다. 검색 성능을 위해 Nori 분석기를 사용하며,
 * 위치 기반 검색을 위해 GeoPoint 타입을 사용한다.
 *
 * <p>NOTE: Nori 분석기는 ES에 analysis-nori 플러그인이 설치되어 있어야 동작한다.
 * 플러그인 미설치 시 인덱스 생성이 실패하므로, ES 컨테이너에서
 * `elasticsearch-plugin install analysis-nori`를 실행해야 한다.
 *
 * @see Restaurant
 */
@Document(indexName = "restaurants")
@Setting(settingPath = "/elasticsearch/restaurant-settings.json")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private Long restaurantId;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String restaurantName;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String status;

    @GeoPointField
    private GeoPoint location;

    @Field(type = FieldType.Nested)
    private List<MenuDocument> menus;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime updatedAt;

    /**
     * JPA Restaurant 엔티티로부터 ES 문서를 생성한다.
     *
     * <p>메뉴 리스트를 비정규화하여 nested document로 포함시킨다.
     * 이렇게 하면 "이 가게에 이 메뉴가 있는가"를 단일 쿼리로 검색할 수 있다.
     *
     * @param restaurant JPA 엔티티
     * @return ES 문서
     */
    public static RestaurantDocument from(Restaurant restaurant) {
        List<MenuDocument> menuDocuments = restaurant.getMenus().stream()
                .map(MenuDocument::from)
                .toList();

        return RestaurantDocument.builder()
                .id(restaurant.getId().toString())
                .restaurantId(restaurant.getId())
                .restaurantName(restaurant.getName())
                .category(restaurant.getCategory())
                .status(restaurant.getStatus().name())
                .location(new GeoPoint(restaurant.getLatitude(), restaurant.getLongitude()))
                .menus(menuDocuments)
                .updatedAt(restaurant.getUpdatedAt())
                .build();
    }

    /**
     * 메뉴 하위 문서.
     *
     * <p>Nested 타입으로 저장하여, 각 메뉴를 독립적인 문서처럼 검색할 수 있다.
     * Inner object와 달리 nested는 cross-matching 문제를 방지한다.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenuDocument {

        @Field(type = FieldType.Keyword)
        private Long menuId;

        @Field(type = FieldType.Text, analyzer = "nori")
        private String menuName;

        @Field(type = FieldType.Text)
        private String description;

        @Field(type = FieldType.Integer)
        private int price;

        @Field(type = FieldType.Boolean)
        private boolean available;

        public static MenuDocument from(Menu menu) {
            return MenuDocument.builder()
                    .menuId(menu.getId())
                    .menuName(menu.getName())
                    .description(menu.getDescription())
                    .price(menu.getPrice())
                    .available(menu.isAvailable())
                    .build();
        }
    }
}
