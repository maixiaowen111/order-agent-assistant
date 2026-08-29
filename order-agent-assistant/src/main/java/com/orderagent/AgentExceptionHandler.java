package com.orderagent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一把身份/归属异常转成带状态码的 JSON，别让 Spring 默认按 500 处理。 */
@RestControllerAdvice
public class AgentExceptionHandler {

    @ExceptionHandler(AgentAuthException.class)
    public ResponseEntity<Map<String, String>> handle(AgentAuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
