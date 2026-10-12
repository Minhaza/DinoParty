package com.dinoparty.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes problem+json from servlet-level components (security filters) that bypass MVC advice. */
@Component
class ProblemJsonWriter {

  private final JsonMapper jsonMapper;

  ProblemJsonWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), problem);
  }
}
