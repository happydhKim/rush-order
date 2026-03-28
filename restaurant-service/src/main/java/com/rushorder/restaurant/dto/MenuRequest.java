package com.rushorder.restaurant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 메뉴 생성/수정 요청 DTO.
 */
public record MenuRequest(
        @NotBlank(message = "Menu name is required")
        String name,

        @NotBlank(message = "Description is required")
        String description,

        @Min(value = 0, message = "Price must be non-negative")
        int price
) {
}
