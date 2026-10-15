package com.dinoparty.wordpacks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Requests against the word pack API, shared by the word pack integration tests. */
final class PackTestKit {

  /** A host, their room and the room's code. */
  record Room(TestGuest host, String code) {}

  final MockMvc mvc;
  final ApiTestSupport api;

  PackTestKit(MockMvc mvc) {
    this.mvc = mvc;
    this.api = new ApiTestSupport(mvc);
  }

  Room newRoom(String prefix) throws Exception {
    TestGuest host = api.createGuest(ApiTestSupport.unique(prefix));
    return new Room(host, api.createRoom(host, "draw-guess").get("code").asString());
  }

  TestGuest join(Room room, String prefix) throws Exception {
    TestGuest guest = api.createGuest(ApiTestSupport.unique(prefix));
    api.join(guest, room.code()).andExpect(status().isOk());
    return guest;
  }

  static String packs(String code) {
    return "/api/v1/rooms/" + code + "/word-packs";
  }

  static String pack(String code, Object packId) {
    return packs(code) + "/" + packId;
  }

  JsonNode createPack(Room room, String name) throws Exception {
    return api.parse(
        api.request(post(packs(room.code())), room.host(), "{\"name\":\"%s\"}".formatted(name))
            .andExpect(status().isCreated()));
  }

  UUID createPackId(Room room, String name) throws Exception {
    return UUID.fromString(createPack(room, name).get("id").asString());
  }

  ResultActions addWords(TestGuest as, String code, Object packId, String wordsJson)
      throws Exception {
    return api.request(post(pack(code, packId) + "/words"), as, "{\"words\":" + wordsJson + "}");
  }

  /** Adds plain words (default metadata) to a pack. */
  void addPlain(Room room, UUID packId, String... words) throws Exception {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < words.length; i++) {
      json.append(i == 0 ? "" : ",").append("{\"text\":\"").append(words[i]).append("\"}");
    }
    addWords(room.host(), room.code(), packId, json.append("]").toString())
        .andExpect(status().isOk());
  }

  ResultActions preview(TestGuest as, String code, Object packId, String filename, byte[] bytes)
      throws Exception {
    return upload(multipart(pack(code, packId) + "/imports/preview"), as, filename, bytes);
  }

  ResultActions confirm(
      TestGuest as,
      String code,
      Object packId,
      String filename,
      byte[] bytes,
      String sha256,
      long packVersion)
      throws Exception {
    MockMultipartHttpServletRequestBuilder request =
        multipart(pack(code, packId) + "/imports/confirm");
    request.param("sha256", sha256).param("packVersion", Long.toString(packVersion));
    return upload(request, as, filename, bytes);
  }

  ResultActions confirm(
      TestGuest as, String code, Object packId, String filename, byte[] bytes, JsonNode preview)
      throws Exception {
    return confirm(
        as,
        code,
        packId,
        filename,
        bytes,
        preview.get("sha256").asString(),
        preview.get("packVersion").asLong());
  }

  private ResultActions upload(
      MockMultipartHttpServletRequestBuilder request, TestGuest as, String filename, byte[] bytes)
      throws Exception {
    request.file(new MockMultipartFile("file", filename, "application/octet-stream", bytes));
    if (as != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + as.token());
    }
    return mvc.perform(request);
  }

  ResultActions get(TestGuest as, String url) throws Exception {
    return api.request(MockMvcRequestBuilders.get(url), as, null);
  }

  ResultActions delete(TestGuest as, String url) throws Exception {
    return api.request(MockMvcRequestBuilders.delete(url), as, null);
  }

  ResultActions patch(TestGuest as, String url, String body) throws Exception {
    return api.request(MockMvcRequestBuilders.patch(url), as, body);
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
