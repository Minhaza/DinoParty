package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.words.CustomWordProvider;
import com.dinoparty.words.TestWords.Packs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * WP-02 word selection end to end: settings over REST, the start check over REST and STOMP (problem
 * body and {@code GAME_ERROR} with {@code details}), and games drawing from the real PostgreSQL
 * catalog and from custom packs. Packs come from an in-memory {@link CustomWordProvider}, standing
 * in for WP-03's storage.
 */
@RealtimeIntegrationTest
@Import(DrawGuessWordSelectionIT.TestPacks.class)
class DrawGuessWordSelectionIT extends GameEngineSupport {

  @TestConfiguration
  static class TestPacks {
    @Bean
    @Primary
    Packs testWordPacks() {
      return new Packs();
    }
  }

  @Autowired Packs packs;

  private record Lobby(String code, List<Guest> guests, List<StompTestClient> sockets) {}

  private Lobby lobby(int players) {
    Guest host = newGuest();
    String code =
        roomService
            .create(
                host.id(),
                new CreateRoomCommand(
                    "draw-guess", Map.of("cycles", 1, "wordChoiceSeconds", 5), null))
            .code();
    List<Guest> guests = new ArrayList<>(List.of(host));
    for (int i = 1; i < players; i++) {
      Guest g = newGuest();
      roomService.join(g.id(), code);
      guests.add(g);
    }
    List<StompTestClient> sockets = new ArrayList<>();
    guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
    return new Lobby(code, guests, sockets);
  }

  private void configure(Lobby l, String settingsJson) throws Exception {
    mvc.perform(
            patch("/api/v1/rooms/" + l.code() + "/settings")
                .header("Authorization", bearer(l.guests().getFirst()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":" + settingsJson + "}"))
        .andExpect(status().isOk());
  }

  /** The words offered to whoever drew first; only the drawer's socket receives them. */
  private List<String> firstChoices(Lobby l) {
    StompTestClient.Message choices =
        await()
            .atMost(Duration.ofSeconds(20))
            .until(
                () ->
                    l.sockets().stream()
                        .flatMap(ws -> ws.ofType("WORD_CHOICES").stream())
                        .findFirst()
                        .orElse(null),
                m -> m != null);
    long receivers =
        l.sockets().stream().filter(ws -> !ws.ofType("WORD_CHOICES").isEmpty()).count();
    assertThat(receivers).isEqualTo(1);
    List<String> words = new ArrayList<>();
    choices.payload().get("choices").forEach(w -> words.add(w.asString()));
    return words;
  }

  @Test
  void wordSettingsAreValidatedWhenSavedAndOmittedKeysTakeTheDefaults() throws Exception {
    Lobby l = lobby(2);
    mvc.perform(
            patch("/api/v1/rooms/" + l.code() + "/settings")
                .header("Authorization", bearer(l.guests().getFirst()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"settings\":{\"wordSource\":\"packs\",\"difficulty\":\"medium\","
                        + "\"wordCategories\":[\"random-mix\",\"animals\"],"
                        + "\"wordPackIds\":[\"nope\"]}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(3));

    configure(l, "{\"difficulty\":\"medium\"}");
    mvc.perform(
            get("/api/v1/rooms/" + l.code()).header("Authorization", bearer(l.guests().getFirst())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.difficulty").value("medium"))
        .andExpect(jsonPath("$.settings.wordSource").value("built-in"))
        .andExpect(jsonPath("$.settings.wordCategories[0]").value("random-mix"))
        .andExpect(jsonPath("$.settings.wordPackIds.length()").value(0));
  }

  @Test
  void aTooSmallPoolRejectsARestStartWithCountsAndLeavesTheRoomWaiting() throws Exception {
    Lobby l = lobby(2);
    UUID pack = packs.add("comet", "Comet", "planet");
    configure(l, "{\"wordSource\":\"custom\",\"wordPackIds\":[\"" + pack + "\"]}");

    mvc.perform(
            post("/api/v1/rooms/" + l.code() + "/start")
                .header("Authorization", bearer(l.guests().getFirst())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORD_POOL_TOO_SMALL"))
        .andExpect(jsonPath("$.details.required").value(3))
        .andExpect(jsonPath("$.details.available").value(2));

    assertThat(roomService.detailsOf(l.code()).status().name()).isEqualTo("WAITING");
    assertThat(sessions.isActive(l.code())).isFalse();
    assertThat(l.sockets().getFirst().ofType("ROOM_STATUS_CHANGED")).isEmpty();
  }

  @Test
  void aRejectedStompStartIsAPrivateGameErrorWithDetails() throws Exception {
    Lobby l = lobby(2);
    StompTestClient host = l.sockets().getFirst();
    configure(l, "{\"wordSource\":\"custom\",\"wordPackIds\":[\"" + UUID.randomUUID() + "\"]}");
    String unknown = actionId();
    host.command(l.code(), "START_GAME", unknown, "{}");
    JsonNode packError =
        host.awaitMessage(
                m ->
                    m.type().equals("GAME_ERROR")
                        && unknown.equals(m.payload().get("actionId").asString()))
            .payload();
    assertThat(packError.get("code").asString()).isEqualTo("VALIDATION_FAILED");

    UUID small = packs.add("sun", "moon");
    configure(l, "{\"wordSource\":\"custom\",\"wordPackIds\":[\"" + small + "\"]}");
    String tooSmall = actionId();
    host.command(l.code(), "START_GAME", tooSmall, "{}");
    JsonNode error =
        host.awaitMessage(
                m ->
                    m.type().equals("GAME_ERROR")
                        && tooSmall.equals(m.payload().get("actionId").asString()))
            .payload();
    assertThat(error.get("code").asString()).isEqualTo("WORD_POOL_TOO_SMALL");
    assertThat(error.get("retryable").asBoolean()).isFalse();
    assertThat(error.get("details").get("required").asInt()).isEqualTo(3);
    assertThat(error.get("details").get("available").asInt()).isEqualTo(2);
    assertThat(error.toString()).doesNotContain("sun").doesNotContain("moon");
    // Only the host learns about the failed start.
    assertThat(l.sockets().get(1).ofType("GAME_ERROR")).isEmpty();
    assertThat(sessions.isActive(l.code())).isFalse();
  }

  @Test
  void aBuiltInGameOffersOnlyCatalogWordsOfTheSelectedCategoryAndDifficulty() throws Exception {
    Lobby l = lobby(2);
    configure(l, "{\"wordCategories\":[\"sports\",\"technology\"],\"difficulty\":\"hard\"}");
    l.sockets().getFirst().command(l.code(), "START_GAME", actionId(), "{}");

    List<String> choices = firstChoices(l);

    Set<String> allowed =
        new HashSet<>(
            jdbc.queryForList(
                "select display_text from word_catalog_entry where locale = 'en' and active"
                    + " and difficulty = 'HARD' and category_key in ('sports', 'technology')",
                String.class));
    assertThat(choices).hasSize(3).doesNotHaveDuplicates();
    assertThat(allowed).containsAll(choices);
  }

  @Test
  void aCustomGameOffersOnlyThePackWordsFrozenAtStart() throws Exception {
    Lobby l = lobby(2);
    UUID pack = packs.add("volcano", "rainbow", "lighthouse");
    configure(l, "{\"wordSource\":\"custom\",\"wordPackIds\":[\"" + pack + "\"]}");
    l.sockets().getFirst().command(l.code(), "START_GAME", actionId(), "{}");
    l.sockets().getFirst().awaitType("ACTION_ACK");
    packs.packs.get(pack).clear(); // edited during the countdown: the game already has its pool

    assertThat(firstChoices(l)).containsExactlyInAnyOrder("volcano", "rainbow", "lighthouse");
  }
}
