package com.dinoparty.web;

import com.dinoparty.common.DomainException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem bodies with the extension fields from docs/api-contracts.md §1. */
final class ProblemFactory {

  private static final String TYPE_BASE = "https://dinoparty.app/errors/";

  private ProblemFactory() {}

  static ProblemDetail from(DomainException e) {
    ProblemDetail problem =
        create(e.code().status(), e.code().name(), e.code().title(), e.getMessage());
    if (!e.fieldErrors().isEmpty()) {
      problem.setProperty("fieldErrors", e.fieldErrors());
    }
    return problem;
  }

  static ProblemDetail create(HttpStatus status, String code, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
    problem.setTitle(title);
    problem.setProperty("code", code);
    problem.setProperty("traceId", UUID.randomUUID().toString());
    return problem;
  }
}
