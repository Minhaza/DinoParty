package com.dinoparty.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import com.dinoparty.room.RoomService;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code PATCH /guests/me} is refused with {@code 409 INVALID_PHASE} while the guest's room is
 * {@code STARTING} or {@code PLAYING} (api-contracts §2): the roster is frozen from the profiles at
 * the end of the countdown, so a rename then would split the room view from the game's.
 */
@IntegrationTest
class GuestProfileLockIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired RoomService rooms;

  ApiTestSupport api;
  TestGuest host;
  TestGuest other;
  String code;

  @BeforeEach
  void setUp() throws Exception {
    api = new ApiTestSupport(mvc);
    host = api.createGuest(ApiTestSupport.unique("Host"));
    other = api.createGuest(ApiTestSupport.unique("Other"));
    code = api.createRoom(host, "test-game").get("code").asString();
    api.join(other, code).andExpect(status().isOk());
  }

  private ResultActions rename(TestGuest guest, String nickname) throws Exception {
    return api.request(
        patch("/api/v1/guests/me"), guest, "{\"nickname\":\"%s\"}".formatted(nickname));
  }

  private String nickname(TestGuest guest) throws Exception {
    return api.parse(api.request(get("/api/v1/guests/me"), guest, null)).get("nickname").asString();
  }

  private void setStatus(String status) {
    jdbc.update("UPDATE room SET status = ? WHERE code = ?", status, code);
  }

  @Test
  void aGuestOutsideAnyRoomOrInAWaitingRoomMayChangeTheirProfile() throws Exception {
    TestGuest loner = api.createGuest(ApiTestSupport.unique("Solo"));
    rename(loner, "Lone Rex").andExpect(status().isOk());

    rename(other, "Waiting Rex")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nickname").value("Waiting Rex"));
  }

  @Test
  void startingAndPlayingRoomsLockTheProfileOfEveryMember() throws Exception {
    for (String locked : new String[] {"STARTING", "PLAYING"}) {
      setStatus(locked);

      for (TestGuest member : new TestGuest[] {host, other}) {
        api.request(patch("/api/v1/guests/me"), member, "{\"avatarKey\":\"dino-ptera\"}")
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        rename(member, "Locked Rex").andExpect(status().isConflict());
      }
      assertThat(nickname(other)).isEqualTo(other.nickname());
    }
  }

  @Test
  void anInvalidProfileIsStillAValidationErrorFirst() throws Exception {
    setStatus("PLAYING");

    rename(other, "x")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void theProfileUnlocksInResultsAfterARealGame() throws Exception {
    api.request(post("/api/v1/rooms/" + code + "/start"), host, "{}")
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                "RESULTS"
                    .equals(
                        jdbc.queryForObject(
                            "SELECT status FROM room WHERE code = ?", String.class, code)));

    rename(other, "After Rex").andExpect(status().isOk());
  }

  /**
   * The check and the change run inside the room mailbox, where every status change happens, so a
   * start cannot slip in between: a change waiting behind a mailbox command that starts the game is
   * refused.
   */
  @Test
  void aChangeQueuedBehindAStartIsRefused() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> start =
        CompletableFuture.runAsync(
            () ->
                rooms.inRoom(
                    code,
                    () -> {
                      entered.countDown();
                      try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      setStatus("STARTING");
                      return null;
                    }));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Integer> change =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return rename(other, "Sneaky Rex").andReturn().getResponse().getStatus();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Thread.sleep(300);
    assertThat(change).as("the change waits for the mailbox").isNotDone();

    release.countDown();
    start.get(5, TimeUnit.SECONDS);
    assertThat(change.get(5, TimeUnit.SECONDS)).isEqualTo(409);
    assertThat(nickname(other)).isEqualTo(other.nickname());
  }
}
