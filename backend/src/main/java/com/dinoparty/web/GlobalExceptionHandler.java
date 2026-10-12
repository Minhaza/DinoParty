package com.dinoparty.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to problem+json. Framework exceptions (404, 405, 400 …) keep their status and
 * gain the contract's {@code code}/{@code traceId} fields; nothing leaks stack traces or messages.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      HttpStatus status = HttpStatus.resolve(statusCode.value());
      problem.setProperty("code", status == null ? "ERROR" : codeFor(status));
      problem.setProperty("traceId", UUID.randomUUID().toString());
    }
    return response;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception e) {
    ProblemDetail problem =
        ProblemFactory.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "SERVER_ERROR",
            "Internal error",
            "An unexpected error occurred.");
    log.error("Unhandled exception, traceId={}", problem.getProperties().get("traceId"), e);
    return problem;
  }

  private static String codeFor(HttpStatus status) {
    return switch (status) {
      case NOT_FOUND -> "NOT_FOUND";
      case BAD_REQUEST -> "VALIDATION_FAILED";
      case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
      case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
      default -> status.name();
    };
  }
}
