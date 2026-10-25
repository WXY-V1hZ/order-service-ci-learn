package com.example.order_service.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        Map<String, String> details
) {

    public static ErrorResponse of(HttpStatus status, ErrorCode code, String message, Map<String, String> details) {
        return new ErrorResponse(Instant.now(), status.value(), code.name(), message, details);
    }
}
