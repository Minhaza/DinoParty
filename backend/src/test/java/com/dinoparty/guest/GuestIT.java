package com.dinoparty.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class GuestIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  ApiTestSupport api;

  @BeforeEach
  void setUp() {
    api = new ApiTestSupport(mvc);
  }

  @Test
  void createGuestReturnsTokenAndProfileAndRestoresIdentity() throws Exception {
    JsonNode created =
        api.parse(
            mvc.perform(
                    post("/api/v1/guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"nickname\":\" Rexy Dino \",\"avatarKey\":\"dino-stego\","
                                + "\"avatarColor\":\"#22C55E\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guest.nickname").value("Rexy Dino"))
                .andExpect(jsonPath("$.guest.avatarKey").value("dino-stego"))
                .andExpect(jsonPath("$.guest.avatarColor").value("#22C55E"))
                .andExpect(jsonPath("$.guest.createdAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty()));
    String token = created.get("token").asString();
    String guestId = created.get("guest").get("id").asString();

    // "Browser refresh": the only thing the client keeps is the token.
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(guestId))
        .andExpect(jsonPath("$.nickname").value("Rexy Dino"));
  }

  @Test
  void rawTokenIsNeverStoredOnlyItsSha256Hash() throws Exception {
    TestGuest guest = api.createGuest(ApiTestSupport.unique("Hash"));

    List<String> hashes =
        jdbc.queryForList(
            "select token_hash from guest_session where guest_id = ?", String.class, guest.id());

    assertThat(hashes).hasSize(1);
    assertThat(hashes.getFirst()).isNotEqualTo(guest.token()).hasSize(64).matches("[0-9a-f]{64}");
    Integer rawMatches =
        jdbc.queryForObject(
            "select count(*) from guest_session where token_hash = ?",
            Integer.class,
            guest.token());
    assertThat(rawMatches).isZero();
  }

  @Test
  void tokensAreUniqueAndOpaque() throws Exception {
    TestGuest a = api.createGuest(ApiTestSupport.unique("TokA"));
    TestGuest b = api.createGuest(ApiTestSupport.unique("TokB"));

    assertThat(a.token()).isNotEqualTo(b.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void invalidOrMissingTokenIsUnauthenticated() throws Exception {
    mvc.perform(get("/api/v1/guests/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer not-a-real-token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Basic abc"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + "x".repeat(5000)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredAndRevokedSessionsAreRejected() throws Exception {
    TestGuest expired = api.createGuest(ApiTestSupport.unique("Exp"));
    jdbc.update(
        "update guest_session set expires_at = ? where guest_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(5)),
        expired.id());
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + expired.token()))
        .andExpect(status().isUnauthorized());

    TestGuest revoked = api.createGuest(ApiTestSupport.unique("Rev"));
    jdbc.update("update guest_session set revoked_at = now() where guest_id = ?", revoked.id());
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + revoked.token()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getMeRenewsSlidingExpiry() throws Exception {
    TestGuest guest = api.createGuest(ApiTestSupport.unique("Slide"));
    Instant nearExpiry = Instant.now().plusSeconds(60);
    jdbc.update(
        "update guest_session set expires_at = ? where guest_id = ?",
        java.sql.Timestamp.from(nearExpiry),
        guest.id());

    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + guest.token()))
        .andExpect(status().isOk());

    Instant renewed =
        jdbc.queryForObject(
                "select expires_at from guest_session where guest_id = ?",
                java.sql.Timestamp.class,
                guest.id())
            .toInstant();
    assertThat(renewed).isAfter(Instant.now().plusSeconds(24 * 3600));
  }

  @Test
  void invalidProfilesAreRejectedWithFieldErrors() throws Exception {
    String[] bad = {
      "{\"nickname\":\"a\",\"avatarKey\":\"dino-rex\"}",
      "{\"nickname\":\"this nickname is far too long\",\"avatarKey\":\"dino-rex\"}",
      "{\"nickname\":\"<script>\",\"avatarKey\":\"dino-rex\"}",
      "{\"nickname\":\"double  space\",\"avatarKey\":\"dino-rex\"}",
      "{\"nickname\":\"   \",\"avatarKey\":\"dino-rex\"}",
      "{\"nickname\":\"Okay Name\",\"avatarKey\":\"dino-unknown\"}",
      "{\"nickname\":\"Okay Name\",\"avatarKey\":\"dino-rex\",\"avatarColor\":\"red\"}",
      "{\"avatarKey\":\"dino-rex\"}",
      "{not json",
    };
    for (String body : bad) {
      mvc.perform(post("/api/v1/guests").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    mvc.perform(
            post("/api/v1/guests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"a\",\"avatarKey\":\"dino-rex\"}"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"));
  }

  @Test
  void patchUpdatesOnlyProvidedFields() throws Exception {
    TestGuest guest = api.createGuest(ApiTestSupport.unique("Patch"));

    api.request(patch("/api/v1/guests/me"), guest, "{\"avatarKey\":\"dino-ptera\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nickname").value(guest.nickname()))
        .andExpect(jsonPath("$.avatarKey").value("dino-ptera"));
    api.request(patch("/api/v1/guests/me"), guest, "{\"nickname\":\"New Name\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nickname").value("New Name"))
        .andExpect(jsonPath("$.avatarKey").value("dino-ptera"));
    api.request(patch("/api/v1/guests/me"), guest, "{\"nickname\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    api.request(patch("/api/v1/guests/me"), null, "{\"nickname\":\"Nope Nope\"}")
        .andExpect(status().isUnauthorized());
  }

  @Test
  void guestCannotEditAnotherGuestsProfile() throws Exception {
    TestGuest alice = api.createGuest(ApiTestSupport.unique("Alice"));
    TestGuest bob = api.createGuest(ApiTestSupport.unique("Bob"));

    // The id is not accepted from the client at all: PATCH /guests/me only touches the caller.
    api.request(
            patch("/api/v1/guests/me"),
            bob,
            "{\"id\":\"" + alice.id() + "\",\"nickname\":\"Hijacked\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(bob.id().toString()));
    mvc.perform(get("/api/v1/guests/me").header("Authorization", "Bearer " + alice.token()))
        .andExpect(jsonPath("$.nickname").value(alice.nickname()));
  }
}
