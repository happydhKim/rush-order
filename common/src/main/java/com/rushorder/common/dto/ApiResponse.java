package com.rushorder.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API 응답의 공통 래퍼.
 *
 * <p>성공/실패에 관계없이 일관된 응답 구조를 제공하여
 * 클라이언트의 파싱 로직을 단순화한다.
 *
 * @param <T> 응답 데이터 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorDetail(code, message));
    }

    public record ErrorDetail(String code, String message) {
    }
}
