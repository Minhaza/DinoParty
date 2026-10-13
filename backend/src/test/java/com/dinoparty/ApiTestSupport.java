package com.dinoparty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers shared by the REST integration tests. */
public final class ApiTestSupport {

  public record TestGuest(UUID id, String token, String nickname) {}

  private final MockMvc mvc;
  private final JsonMapper json = JsonMapper.builder().build();

  public ApiTestSupport(MockMvc mvc) {
    this.mvc = mvc;
  }

  public JsonNode parse(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  public TestGuest createGuest(String nickname) throws Exception {
    JsonNode body =
        parse(
            mvc.perform(
                    post("/api/v1/guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"nickname\":\"%s\",\"avatarKey\":\"dino-rex\"}".formatted(nickname)))
                .andExpect(status().isCreated()));
    return new TestGuest(
        UUID.fromString(body.get("guest").get("id").asString()),
        body.get("token").asString(),
        nickname);
  }

  public ResultActions request(MockHttpServletRequestBuilder builder, TestGuest as, String body)
      throws Exception {
    if (as != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + as.token());
    }
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(builder);
  }

  public JsonNode createRoom(TestGuest host, String gameKey) throws Exception {
    return parse(
        request(post("/api/v1/rooms"), host, "{\"gameKey\":\"%s\"}".formatted(gameKey))
            .andExpect(status().isCreated()));
  }

  public JsonNode createRoom(TestGuest host, String gameKey, int maxPlayers) throws Exception {
    return parse(
        request(
                post("/api/v1/rooms"),
                host,
                "{\"gameKey\":\"%s\",\"maxPlayers\":%d}".formatted(gameKey, maxPlayers))
            .andExpect(status().isCreated()));
  }

  public ResultActions join(TestGuest guest, String code) throws Exception {
    return request(post("/api/v1/rooms/" + code + "/join"), guest, "{}");
  }

  public ResultActions leave(TestGuest guest, String code) throws Exception {
    return request(post("/api/v1/rooms/" + code + "/leave"), guest, null);
  }

  public ResultActions patchSettings(TestGuest guest, String code, String body) throws Exception {
    return request(patch("/api/v1/rooms/" + code + "/settings"), guest, body);
  }

  public static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 6);
  }
}
