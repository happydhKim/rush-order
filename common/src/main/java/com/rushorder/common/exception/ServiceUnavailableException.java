package com.rushorder.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 다운스트림 서비스가 응답 불가 상태일 때 발생하는 예외.
 *
 * <p>Feign 호출 시 5xx 응답을 수신하면 이 예외로 변환하여
 * 클라이언트에게 503 Service Unavailable을 반환한다.
 */
public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(String serviceName, String detail) {
        super(
                "SERVICE_UNAVAILABLE",
                String.format("%s is currently unavailable: %s", serviceName, detail),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }
}
