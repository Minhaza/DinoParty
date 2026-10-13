package com.dinoparty.common;

import java.util.List;

/** Business-rule failure that the web layer maps to problem+json using its {@link ErrorCode}. */
public class DomainException extends RuntimeException {

  /** A single invalid input field, surfaced as {@code fieldErrors} in the problem body. */
  public record FieldViolation(String field, String message) {}

  private final ErrorCode code;
  private final List<FieldViolation> fieldErrors;

  public DomainException(ErrorCode code, String detail) {
    this(code, detail, List.of());
  }

  public DomainException(ErrorCode code, String detail, List<FieldViolation> fieldErrors) {
    super(detail);
    this.code = code;
    this.fieldErrors = List.copyOf(fieldErrors);
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
}
