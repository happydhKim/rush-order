package com.rushorder.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 규칙 위반 시 발생하는 기반 예외.
 *
 * <p>각 서비스는 이 클래스를 상속하여 도메인 특화 예외를 정의한다.
 * GlobalExceptionHandler에서 이 예외를 잡아 일관된 에러 응답을 반환한다.
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    protected BusinessException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
