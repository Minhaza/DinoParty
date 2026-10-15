package com.dinoparty.wordpacks;

import static com.dinoparty.wordpacks.PackTestKit.pack;
import static com.dinoparty.wordpacks.PackTestKit.packs;
import static com.dinoparty.wordpacks.PackTestKit.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import com.dinoparty.wordpacks.PackTestKit.Room;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Races against the real service and database: limits, duplicates and imports must hold. */
@IntegrationTest
class WordPackConcurrencyIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  PackTestKit kit;

  @BeforeEach
  void setUp() {
    kit = new PackTestKit(mvc);
  }

  private static List<Integer> runAll(List<Callable<Integer>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return task.call();
                }));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : futures) {
        statuses.add(f.get());
      }
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  private int entries(UUID packId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from word_pack_entry where pack_id = ?", Integer.class, packId);
    return n == null ? 0 : n;
  }

  @Test
  void identicalConfirmsOfOnePreviewImportExactlyOnce() throws Exception {
    Room room = kit.newRoom("RaceConfirm");
    UUID packId = kit.createPackId(room, "Pack");
    byte[] file = utf8("cat\ndog\nbird\nCat\n");
    JsonNode preview =
        kit.api.parse(
            kit.preview(room.host(), room.code(), packId, "w.txt", file)
                .andExpect(status().isOk()));

    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tasks.add(
          () ->
              kit.confirm(room.host(), room.code(), packId, "w.txt", file, preview)
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    List<Integer> statuses = runAll(tasks);

    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
    assertThat(entries(packId)).isEqualTo(3);
    kit.get(room.host(), pack(room.code(), packId))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.wordCount")
                .value(3));
  }

  @Test
  void overlappingImportsAndAddsNeverCreateDuplicates() throws Exception {
    Room room = kit.newRoom("RaceMix");
    UUID packId = kit.createPackId(room, "Pack");
    // Each confirm is made against a fresh preview taken right before the race, so they all start
    // from the same version: the first wins, the others are stale and must change nothing.
    byte[] fileA = utf8("alpha\nbeta\ngamma\n");
    byte[] fileB = utf8("BETA\ngamma\ndelta\n");
    JsonNode previewA =
        kit.api.parse(kit.preview(room.host(), room.code(), packId, "a.txt", fileA));
    JsonNode previewB =
        kit.api.parse(kit.preview(room.host(), room.code(), packId, "b.txt", fileB));

    List<Callable<Integer>> tasks = new ArrayList<>();
    tasks.add(
        () ->
            kit.confirm(room.host(), room.code(), packId, "a.txt", fileA, previewA)
                .andReturn()
                .getResponse()
                .getStatus());
    tasks.add(
        () ->
            kit.confirm(room.host(), room.code(), packId, "b.txt", fileB, previewB)
                .andReturn()
                .getResponse()
                .getStatus());
    for (int i = 0; i < 4; i++) {
      tasks.add(
          () ->
              kit.addWords(
                      room.host(),
                      room.code(),
                      packId,
                      "[{\"text\":\"Gamma\"},{\"text\":\"omega\"}]")
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    List<Integer> statuses = runAll(tasks);

    assertThat(statuses).allMatch(s -> s == 200 || s == 409);
    // Whatever the interleaving, no word is stored twice and the count column matches the rows.
    Integer distinct =
        jdbc.queryForObject(
            "select count(distinct text_key) from word_pack_entry where pack_id = ?",
            Integer.class,
            packId);
    Integer recorded =
        jdbc.queryForObject("select word_count from word_pack where id = ?", Integer.class, packId);
    assertThat(entries(packId)).isEqualTo(distinct).isEqualTo(recorded);
    assertThat(entries(packId)).isBetween(2, 6);
  }

  @Test
  void concurrentAddsOfTheSameWordStoreItOnce() throws Exception {
    Room room = kit.newRoom("RaceAdd");
    UUID packId = kit.createPackId(room, "Pack");

    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String text = i % 2 == 0 ? "Triceratops" : "TRICERATOPS";
      tasks.add(
          () ->
              kit.addWords(room.host(), room.code(), packId, "[{\"text\":\"" + text + "\"}]")
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }

    assertThat(runAll(tasks)).allMatch(s -> s == 200);
    assertThat(entries(packId)).isEqualTo(1);
  }

  @Test
  void concurrentPackCreationStopsAtTheRoomLimit() throws Exception {
    Room room = kit.newRoom("RacePacks");

    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      String name = "Pack " + i;
      tasks.add(
          () ->
              kit.api
                  .request(post(packs(room.code())), room.host(), "{\"name\":\"" + name + "\"}")
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    List<Integer> statuses = runAll(tasks);

    assertThat(statuses.stream().filter(s -> s == 201).count())
        .isEqualTo(WordPackLimits.MAX_PACKS_PER_ROOM);
    assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(10);
    kit.get(room.host(), packs(room.code()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.packs.length()")
                .value(20));
  }

  @Test
  void concurrentAddsStopAtThePackLimit() throws Exception {
    Room room = kit.newRoom("RaceFull");
    UUID packId = kit.createPackId(room, "Pack");
    StringBuilder fill = new StringBuilder();
    for (int i = 0; i < 950; i++) {
      fill.append("fill").append(i).append('\n');
    }
    byte[] file = utf8(fill.toString());
    JsonNode preview = kit.api.parse(kit.preview(room.host(), room.code(), packId, "f.txt", file));
    kit.confirm(room.host(), room.code(), packId, "f.txt", file, preview)
        .andExpect(status().isOk());

    // 8 requests of 10 distinct words each want 80 of the 50 free places: 5 fit, 3 do not.
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int r = 0; r < 8; r++) {
      StringBuilder words = new StringBuilder("[");
      for (int i = 0; i < 10; i++) {
        words
            .append(i == 0 ? "" : ",")
            .append("{\"text\":\"late")
            .append(r)
            .append('x')
            .append(i)
            .append("\"}");
      }
      String json = words.append("]").toString();
      tasks.add(
          () ->
              kit.addWords(room.host(), room.code(), packId, json)
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    List<Integer> statuses = runAll(tasks);

    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(5);
    assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
    assertThat(entries(packId)).isEqualTo(1000);
  }
}
