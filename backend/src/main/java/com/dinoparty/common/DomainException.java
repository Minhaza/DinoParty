package com.dinoparty.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-rule failure that the web layer maps to problem+json using its {@link ErrorCode}. */
public class DomainException extends RuntimeException {

  /** A single invalid input field, surfaced as {@code fieldErrors} in the problem body. */
  public record FieldViolation(String field, String message) {}

  private final ErrorCode code;
  private final List<FieldViolation> fieldErrors;
  private final Map<String, Object> details;

  public DomainException(ErrorCode code, String detail) {
    this(code, detail, List.of());
  }

  public DomainException(ErrorCode code, String detail, List<FieldViolation> fieldErrors) {
    this(code, detail, fieldErrors, Map.of());
  }

  /**
   * {@code details} are machine-readable facts about the failure (surfaced as {@code details} in
   * the problem body and the STOMP error payload); they must never contain secrets.
   */
  public DomainException(
      ErrorCode code,
      String detail,
      List<FieldViolation> fieldErrors,
      Map<String, Object> details) {
    super(detail);
    this.code = code;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  public static DomainException invalid(String field, String message) {
    return new DomainException(
        ErrorCode.VALIDATION_FAILED,
        "The request is invalid.",
        List.of(new FieldViolation(field, message)));
  }

  public ErrorCode code() {
    return code;
  }

  public List<FieldViolation> fieldErrors() {
    return fieldErrors;
  }

  public Map<String, Object> details() {
    return details;
  }
}
