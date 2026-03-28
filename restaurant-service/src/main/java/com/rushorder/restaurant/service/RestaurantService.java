package com.rushorder.restaurant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushorder.common.exception.NotFoundException;
import com.rushorder.restaurant.document.RestaurantDocument;
import com.rushorder.restaurant.domain.Menu;
import com.rushorder.restaurant.domain.OutboxEvent;
import com.rushorder.restaurant.domain.Restaurant;
import com.rushorder.restaurant.dto.*;
import com.rushorder.restaurant.repository.MenuRepository;
import com.rushorder.restaurant.repository.OutboxEventRepository;
import com.rushorder.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가게/메뉴 CRUD를 담당하는 서비스.
 *
 * <p>CQRS Write 경로: 모든 쓰기 연산은 PostgreSQL에 직접 수행한다.
 * 변경 사항은 같은 트랜잭션 내에서 Outbox 테이블에 이벤트를 INSERT하고,
 * 별도 워커가 이를 Kafka로 발행하여 Elasticsearch에 비동기 동기화한다.
 *
 * <p>Outbox 패턴을 사용하는 이유: DB 저장과 메시지 발행의 원자성을 보장하기 위함이다.
 * 직접 Kafka로 발행하면 DB commit 후 Kafka 발행 실패 시 데이터 불일치가 발생한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private static final String OUTBOX_TOPIC = "restaurant-updated";

    private final RestaurantRepository restaurantRepository;
    private final MenuRepository menuRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public RestaurantResponse createRestaurant(RestaurantRequest request) {
        Restaurant restaurant = new Restaurant(
                request.name(),
                request.address(),
                request.phone(),
                request.category(),
                request.latitude(),
                request.longitude()
        );
        Restaurant saved = restaurantRepository.save(restaurant);
        saveOutboxEvent(saved, "RESTAURANT_CREATED");
        return RestaurantResponse.from(saved);
    }

    public RestaurantResponse getRestaurant(Long id) {
        Restaurant restaurant = findRestaurantOrThrow(id);
        return RestaurantResponse.from(restaurant);
    }

    public List<RestaurantResponse> getAllRestaurants() {
        return restaurantRepository.findAll().stream()
                .map(RestaurantResponse::from)
                .toList();
    }

    @Transactional
    public RestaurantResponse updateRestaurant(Long id, RestaurantRequest request) {
        Restaurant restaurant = findRestaurantOrThrow(id);
        restaurant.updateInfo(
                request.name(),
                request.address(),
                request.phone(),
                request.category()
        );
        saveOutboxEvent(restaurant, "RESTAURANT_UPDATED");
        return RestaurantResponse.from(restaurant);
    }

    @Transactional
    public MenuResponse addMenu(Long restaurantId, MenuRequest request) {
        Restaurant restaurant = findRestaurantOrThrow(restaurantId);

        Menu menu = new Menu(request.name(), request.description(), request.price());
        restaurant.addMenu(menu);

        // cascade로 menu도 함께 저장된다
        restaurantRepository.save(restaurant);
        saveOutboxEvent(restaurant, "MENU_ADDED");
        return MenuResponse.from(menu);
    }

    @Transactional
    public MenuResponse updateMenu(Long menuId, MenuRequest request) {
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new NotFoundException("Menu", menuId.toString()));

        menu.updateInfo(request.name(), request.description(), request.price());

        // 메뉴가 속한 가게 전체를 다시 인덱싱해야 한다 (비정규화 문서 구조)
        saveOutboxEvent(menu.getRestaurant(), "MENU_UPDATED");
        return MenuResponse.from(menu);
    }

    public List<MenuResponse> getMenusByRestaurant(Long restaurantId) {
        findRestaurantOrThrow(restaurantId);
        return menuRepository.findByRestaurantId(restaurantId).stream()
                .map(MenuResponse::from)
                .toList();
    }

    private Restaurant findRestaurantOrThrow(Long id) {
        return restaurantRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Restaurant", id.toString()));
    }

    /**
     * Outbox 이벤트를 저장한다. 비즈니스 트랜잭션과 같은 TX에서 수행되어 원자성을 보장한다.
     *
     * <p>페이로드는 RestaurantDocument의 JSON 형태로, Kafka Consumer가 그대로 역직렬화하여
     * ES에 인덱싱할 수 있도록 한다.
     */
    private void saveOutboxEvent(Restaurant restaurant, String eventType) {
        try {
            RestaurantDocument document = RestaurantDocument.from(restaurant);
            String payload = objectMapper.writeValueAsString(document);
            OutboxEvent outboxEvent = new OutboxEvent(
                    OUTBOX_TOPIC,
                    restaurant.getId().toString(),
                    eventType,
                    payload
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 프로그래밍 오류이므로 런타임 예외로 전파
            throw new RuntimeException("Outbox 이벤트 직렬화 실패. restaurantId=" + restaurant.getId(), e);
        }
    }
}
