package com.readcodeai.api;

import com.readcodeai.agent.LlmUnavailableException;
import com.readcodeai.retrieve.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常出口：查不到 → 404，参数错 → 400，其余 → 500。响应体格式一致。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> notFound(NotFoundException e) {
        return ApiResponse.error(404, e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    ApiResponse<Void> onRateLimited(RateLimitedException e) {
        // 429 的形状与其它错误一致（{code, message}），客户端不用为限流单独写一套解析
        return ApiResponse.error(429, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    /** LLM 没配 → 503，并在消息里点明「静态分析那几层照常可用」，别让调用方以为整个服务挂了。 */
    @ExceptionHandler(LlmUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> llmUnavailable(LlmUnavailableException e) {
        return ApiResponse.error(503, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> internalError(Exception e) {
        log.error("接口异常", e);
        return ApiResponse.error(500, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
