package com.dinoparty.wordpacks;

import static com.dinoparty.wordpacks.PackTestKit.pack;
import static com.dinoparty.wordpacks.PackTestKit.packs;
import static com.dinoparty.wordpacks.PackTestKit.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import com.dinoparty.wordpacks.PackTestKit.Room;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Word pack CRUD, authorization, room isolation and secrecy (api-contracts §2.5). */
@IntegrationTest
class WordPackApiIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  PackTestKit kit;

  @BeforeEach
  void setUp() {
    kit = new PackTestKit(mvc);
  }

  // ---- authentication ----------------------------------------------------------------------

  @Test
  void everyEndpointRequiresAGuestToken() throws Exception {
    Room room = kit.newRoom("Auth");
    UUID id = kit.createPackId(room, "Pack");
    String base = pack(room.code(), id);
    String word = UUID.randomUUID().toString();

    for (var request :
        java.util.List.of(
            post(packs(room.code())).contentType("application/json").content("{\"name\":\"x\"}"),
            get(packs(room.code())),
            get(base),
            patch(base).contentType("application/json").content("{\"name\":\"x\"}"),
            delete(base),
            get(base + "/words"),
            post(base + "/words").contentType("application/json").content("{\"words\":[]}"),
            delete(base + "/words/" + word))) {
      mvc.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
    kit.preview(null, room.code(), id, "w.txt", utf8("cat")).andExpect(status().isUnauthorized());
    kit.confirm(null, room.code(), id, "w.txt", utf8("cat"), "00", 0)
        .andExpect(status().isUnauthorized());
  }

  // ---- host CRUD ---------------------------------------------------------------------------

  @Test
  void hostCreatesRenamesDescribesListsAndDeletesAPack() throws Exception {
    Room room = kit.newRoom("Crud");
    JsonNode created = kit.createPack(room, "  My   Pack  ");

    assertThat(created.get("name").asString()).isEqualTo("My Pack");
    assertThat(created.get("description").asString()).isEmpty();
    assertThat(created.get("wordCount").asInt()).isZero();
    assertThat(created.get("roomId").asString()).isNotBlank();
    assertThat(created.get("createdAt").asString()).isNotBlank();
    String id = created.get("id").asString();
    String url = pack(room.code(), id);

    JsonNode renamed =
        kit.api.parse(
            kit.patch(room.host(), url, "{\"name\":\"Renamed\"}").andExpect(status().isOk()));
    assertThat(renamed.get("name").asString()).isEqualTo("Renamed");
    assertThat(renamed.get("description").asString()).isEmpty();

    JsonNode described =
        kit.api.parse(
            kit.patch(room.host(), url, "{\"description\":\"For dinosaurs\"}")
                .andExpect(status().isOk()));
    assertThat(described.get("name").asString()).isEqualTo("Renamed");
    assertThat(described.get("description").asString()).isEqualTo("For dinosaurs");
    assertThat(described.get("updatedAt").asString())
        .isGreaterThanOrEqualTo(created.get("updatedAt").asString());

    kit.patch(room.host(), url, "{\"description\":\"\"}")
        .andExpect(jsonPath("$.description").value(""));

    kit.get(room.host(), packs(room.code()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packs.length()").value(1))
        .andExpect(jsonPath("$.packs[0].id").value(id));
    kit.get(room.host(), url).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

    kit.delete(room.host(), url).andExpect(status().isNoContent());
    kit.get(room.host(), url)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORD_PACK_NOT_FOUND"));
    kit.get(room.host(), packs(room.code())).andExpect(jsonPath("$.packs.length()").value(0));
  }

  @Test
  void deletingAPackDeletesItsWords() throws Exception {
    Room room = kit.newRoom("DelWords");
    UUID id = kit.createPackId(room, "Pack");
    kit.addPlain(room, id, "alpha", "beta");
    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", id)).isEqualTo(2);

    kit.delete(room.host(), pack(room.code(), id)).andExpect(status().isNoContent());

    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", id)).isZero();
  }

  @Test
  void packsDieWithTheirRoom() throws Exception {
    Room room = kit.newRoom("Cascade");
    UUID id = kit.createPackId(room, "Pack");
    kit.addPlain(room, id, "alpha");

    jdbc.update("delete from room where code = ?", room.code());

    assertThat(count("select count(*) from word_pack where id = ?", id)).isZero();
    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", id)).isZero();
  }

  @Test
  void invalidPackFieldsAreRejectedWithFieldErrors() throws Exception {
    Room room = kit.newRoom("Valid");
    for (String body :
        new String[] {
          "{}",
          "{\"name\":\"\"}",
          "{\"name\":\"   \"}",
          "{\"name\":\"" + "n".repeat(81) + "\"}",
          "{\"name\":\"<script>\"}",
          "{\"name\":\"ok\",\"description\":\"" + "d".repeat(281) + "\"}",
          "{\"name\":\"ok\",\"description\":\"bad\\u0000\"}",
          "not json"
        }) {
      kit.api
          .request(post(packs(room.code())), room.host(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    UUID id = kit.createPackId(room, "Pack");
    kit.patch(room.host(), pack(room.code(), id), "{}").andExpect(status().isBadRequest());
    kit.patch(room.host(), pack(room.code(), id), "{\"name\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    // 80 characters is the limit and is fine.
    kit.createPack(room, "n".repeat(80));
  }

  @Test
  void aRoomHoldsAtMost20Packs() throws Exception {
    Room room = kit.newRoom("Twenty");
    for (int i = 0; i < 20; i++) {
      kit.createPack(room, "Pack " + i);
    }
    kit.api
        .request(post(packs(room.code())), room.host(), "{\"name\":\"one too many\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORD_PACK_LIMIT_REACHED"))
        .andExpect(jsonPath("$.details.limit").value(20))
        .andExpect(jsonPath("$.details.kind").value("PACKS"));
  }

  // ---- authorization -----------------------------------------------------------------------

  @Test
  void membersSeeMetadataButNeverWords() throws Exception {
    Room room = kit.newRoom("Member");
    UUID id = kit.createPackId(room, "Secrets");
    kit.addPlain(room, id, "zebraquartz", "pterodactylus");
    TestGuest member = kit.join(room, "Mem");
    String url = pack(room.code(), id);

    String list =
        kit.get(member, packs(room.code()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packs[0].name").value("Secrets"))
            .andExpect(jsonPath("$.packs[0].wordCount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String one =
        kit.get(member, url)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wordCount").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list + one).doesNotContain("zebraquartz", "pterodactylus");

    kit.get(member, url + "/words")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_HOST"));
  }

  @Test
  void membersCannotChangeAnything() throws Exception {
    Room room = kit.newRoom("NoWrite");
    UUID id = kit.createPackId(room, "Pack");
    kit.addPlain(room, id, "alpha");
    TestGuest member = kit.join(room, "Mem");
    String url = pack(room.code(), id);
    String wordId =
        kit.api
            .parse(kit.get(room.host(), url + "/words"))
            .get("words")
            .get(0)
            .get("id")
            .asString();

    kit.api
        .request(post(packs(room.code())), member, "{\"name\":\"mine\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_HOST"));
    kit.patch(member, url, "{\"name\":\"mine\"}").andExpect(status().isForbidden());
    kit.delete(member, url).andExpect(status().isForbidden());
    kit.addWords(member, room.code(), id, "[{\"text\":\"beta\"}]")
        .andExpect(status().isForbidden());
    kit.delete(member, url + "/words/" + wordId).andExpect(status().isForbidden());
    kit.preview(member, room.code(), id, "w.txt", utf8("beta")).andExpect(status().isForbidden());
    kit.confirm(member, room.code(), id, "w.txt", utf8("beta"), "00", 0)
        .andExpect(status().isForbidden());

    JsonNode after = kit.api.parse(kit.get(room.host(), url).andExpect(status().isOk()));
    assertThat(after.get("name").asString()).isEqualTo("Pack");
    assertThat(after.get("wordCount").asInt()).isEqualTo(1);
  }

  @Test
  void aNonMemberIsRejectedEverywhere() throws Exception {
    Room room = kit.newRoom("Outside");
    UUID id = kit.createPackId(room, "Pack");
    TestGuest outsider =
        kit.api.createGuest("Outsider" + UUID.randomUUID().toString().substring(0, 4));
    String url = pack(room.code(), id);

    for (var result :
        java.util.List.of(
            kit.get(outsider, packs(room.code())),
            kit.get(outsider, url),
            kit.get(outsider, url + "/words"),
            kit.delete(outsider, url),
            kit.patch(outsider, url, "{\"name\":\"x\"}"),
            kit.api.request(post(packs(room.code())), outsider, "{\"name\":\"x\"}"),
            kit.addWords(outsider, room.code(), id, "[{\"text\":\"beta\"}]"),
            kit.preview(outsider, room.code(), id, "w.txt", utf8("beta")))) {
      result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    }
  }

  @Test
  void unknownOrMalformedRoomCodesAre404() throws Exception {
    Room room = kit.newRoom("NoRoom");
    kit.get(room.host(), packs("ZZZZZZ")).andExpect(status().isNotFound());
    kit.get(room.host(), packs("bad")).andExpect(status().isNotFound());
  }

  @Test
  void aFormerHostLosesAccessWhenHostshipMoves() throws Exception {
    Room room = kit.newRoom("Transfer");
    UUID id = kit.createPackId(room, "Pack");
    TestGuest next = kit.join(room, "Next");

    kit.api.leave(room.host(), room.code()).andExpect(status().isNoContent());

    kit.patch(next, pack(room.code(), id), "{\"name\":\"Taken over\"}").andExpect(status().isOk());
    kit.patch(room.host(), pack(room.code(), id), "{\"name\":\"Back\"}")
        .andExpect(status().isForbidden());
  }

  @Test
  void roomsAreIsolatedFromEachOther() throws Exception {
    Room a = kit.newRoom("RoomA");
    Room b = kit.newRoom("RoomB");
    UUID packA = kit.createPackId(a, "Pack A");
    kit.addPlain(a, packA, "alpha");
    String wordA =
        kit.api
            .parse(kit.get(a.host(), pack(a.code(), packA) + "/words"))
            .get("words")
            .get(0)
            .get("id")
            .asString();

    // B's host addresses A's pack through B's own room: not found.
    String viaB = pack(b.code(), packA);
    kit.get(b.host(), viaB)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORD_PACK_NOT_FOUND"));
    kit.get(b.host(), viaB + "/words").andExpect(status().isNotFound());
    kit.patch(b.host(), viaB, "{\"name\":\"hijack\"}").andExpect(status().isNotFound());
    kit.delete(b.host(), viaB).andExpect(status().isNotFound());
    kit.addWords(b.host(), b.code(), packA, "[{\"text\":\"beta\"}]")
        .andExpect(status().isNotFound());
    kit.delete(b.host(), viaB + "/words/" + wordA).andExpect(status().isNotFound());
    kit.preview(b.host(), b.code(), packA, "w.txt", utf8("beta")).andExpect(status().isNotFound());
    kit.confirm(b.host(), b.code(), packA, "w.txt", utf8("beta"), "00", 0)
        .andExpect(status().isNotFound());

    // B's host addresses A's room: not a member.
    kit.get(b.host(), pack(a.code(), packA)).andExpect(status().isForbidden());
    kit.delete(b.host(), pack(a.code(), packA)).andExpect(status().isForbidden());

    // A's pack is untouched and B has no packs.
    JsonNode after = kit.api.parse(kit.get(a.host(), pack(a.code(), packA)));
    assertThat(after.get("name").asString()).isEqualTo("Pack A");
    assertThat(after.get("wordCount").asInt()).isEqualTo(1);
    kit.get(b.host(), packs(b.code())).andExpect(jsonPath("$.packs.length()").value(0));
  }

  @Test
  void packsNeverAppearInRoomOrSnapshotPayloads() throws Exception {
    Room room = kit.newRoom("NoLeak");
    UUID id = kit.createPackId(room, "Quietpack");
    kit.addPlain(room, id, "mysterioussecretword");
    TestGuest member = kit.join(room, "Peek");

    for (TestGuest guest : new TestGuest[] {room.host(), member}) {
      for (String path :
          new String[] {
            "/api/v1/rooms/" + room.code(), "/api/v1/rooms/" + room.code() + "/snapshot"
          }) {
        String body =
            kit.get(guest, path)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("mysterioussecretword");
      }
    }
  }

  // ---- words -------------------------------------------------------------------------------

  @Test
  void addWordsAppliesDefaultsNormalizesAndSkipsDuplicates() throws Exception {
    Room room = kit.newRoom("Add");
    UUID id = kit.createPackId(room, "Pack");

    JsonNode result =
        kit.api.parse(
            kit.addWords(
                    room.host(),
                    room.code(),
                    id,
                    """
                    [{"text":"  Brontosaurus "},
                     {"text":"brontosaurus","difficulty":"HARD"},
                     {"text":"Caf\\u00e9","category":"Drinks","difficulty":"easy"},
                     {"text":"Cafe"},
                     {"text":"CAF\\u00c9"}]
                    """)
                .andExpect(status().isOk()));

    assertThat(result.get("added").asInt()).isEqualTo(3);
    assertThat(result.get("skipped").asInt()).isEqualTo(2);
    assertThat(result.get("pack").get("wordCount").asInt()).isEqualTo(3);

    JsonNode words =
        kit.api.parse(
            kit.get(room.host(), pack(room.code(), id) + "/words").andExpect(status().isOk()));
    assertThat(words.get("total").asInt()).isEqualTo(3);
    assertThat(words.get("words").get(0).get("text").asString()).isEqualTo("Brontosaurus");
    assertThat(words.get("words").get(0).get("category").asString()).isEqualTo("Custom");
    assertThat(words.get("words").get(0).get("difficulty").asString()).isEqualTo("MEDIUM");
    assertThat(words.get("words").get(1).get("text").asString()).isEqualTo("Café");
    assertThat(words.get("words").get(1).get("difficulty").asString()).isEqualTo("EASY");
    assertThat(words.get("words").get(2).get("text").asString()).isEqualTo("Cafe");

    // The same words again add nothing (a duplicate never creates an entry).
    kit.addWords(room.host(), room.code(), id, "[{\"text\":\"BRONTOSAURUS\"}]")
        .andExpect(jsonPath("$.added").value(0))
        .andExpect(jsonPath("$.skipped").value(1));
  }

  @Test
  void invalidWordsRejectTheWholeRequestWithIndexedFieldErrors() throws Exception {
    Room room = kit.newRoom("BadAdd");
    UUID id = kit.createPackId(room, "Pack");

    kit.addWords(
            room.host(),
            room.code(),
            id,
            "[{\"text\":\"fine\"},{\"text\":\"<b>x</b>\"},{\"text\":\"ok\",\"difficulty\":\"MIXED\"},{\"text\":\"ok\",\"category\":\""
                + "c".repeat(41)
                + "\"},{\"text\":\""
                + "w".repeat(81)
                + "\"}]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("words[1].text"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("words[2].difficulty"))
        .andExpect(jsonPath("$.fieldErrors[2].field").value("words[3].category"))
        .andExpect(jsonPath("$.fieldErrors[3].field").value("words[4].text"));

    kit.get(room.host(), pack(room.code(), id)).andExpect(jsonPath("$.wordCount").value(0));
    kit.addWords(room.host(), room.code(), id, "[]").andExpect(status().isBadRequest());
    kit.addWords(room.host(), room.code(), id, "null").andExpect(status().isBadRequest());
    kit.addWords(room.host(), room.code(), id, "[{}]").andExpect(status().isBadRequest());
  }

  @Test
  void anAddRequestHoldsAtMost100Words() throws Exception {
    Room room = kit.newRoom("Batch");
    UUID id = kit.createPackId(room, "Pack");
    assertThat(addMany(room, id, 0, 101).andReturn().getResponse().getStatus()).isEqualTo(400);
    addMany(room, id, 0, 100).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(100));
  }

  @Test
  void aPackHoldsAtMost1000Words() throws Exception {
    Room room = kit.newRoom("Thousand");
    UUID id = kit.createPackId(room, "Pack");
    for (int batch = 0; batch < 10; batch++) {
      addMany(room, id, batch * 100, 100).andExpect(status().isOk());
    }
    kit.get(room.host(), pack(room.code(), id)).andExpect(jsonPath("$.wordCount").value(1000));

    kit.addWords(room.host(), room.code(), id, "[{\"text\":\"onemore\"}]")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORD_PACK_LIMIT_REACHED"))
        .andExpect(jsonPath("$.details.limit").value(1000))
        .andExpect(jsonPath("$.details.kind").value("WORDS"))
        .andExpect(jsonPath("$.details.remaining").value(0));
    // A duplicate of an existing word is not an addition, so it fits even in a full pack.
    kit.addWords(room.host(), room.code(), id, "[{\"text\":\"WORD0\"}]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skipped").value(1));
    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", id)).isEqualTo(1000);
  }

  @Test
  void removingAWordUpdatesCountsAndIsScopedToThePack() throws Exception {
    Room room = kit.newRoom("Remove");
    UUID id = kit.createPackId(room, "Pack");
    UUID other = kit.createPackId(room, "Other");
    kit.addPlain(room, id, "alpha", "beta");
    kit.addPlain(room, other, "gamma");
    JsonNode words = kit.api.parse(kit.get(room.host(), pack(room.code(), id) + "/words"));
    String alpha = words.get("words").get(0).get("id").asString();
    String gamma =
        kit.api
            .parse(kit.get(room.host(), pack(room.code(), other) + "/words"))
            .get("words")
            .get(0)
            .get("id")
            .asString();

    // A word id of another pack of the same room is not found through this pack.
    kit.delete(room.host(), pack(room.code(), id) + "/words/" + gamma)
        .andExpect(status().isNotFound());

    kit.delete(room.host(), pack(room.code(), id) + "/words/" + alpha)
        .andExpect(status().isNoContent());
    kit.get(room.host(), pack(room.code(), id)).andExpect(jsonPath("$.wordCount").value(1));
    kit.delete(room.host(), pack(room.code(), id) + "/words/" + alpha)
        .andExpect(status().isNotFound());
    // The removed word can be added again.
    kit.addPlain(room, id, "ALPHA");
    kit.get(room.host(), pack(room.code(), id)).andExpect(jsonPath("$.wordCount").value(2));
  }

  @Test
  void wordsArePagedInInsertionOrder() throws Exception {
    Room room = kit.newRoom("Paging");
    UUID id = kit.createPackId(room, "Pack");
    addMany(room, id, 0, 30).andExpect(status().isOk());

    JsonNode page =
        kit.api.parse(
            kit.get(room.host(), pack(room.code(), id) + "/words?offset=10&limit=5")
                .andExpect(status().isOk()));
    assertThat(page.get("total").asInt()).isEqualTo(30);
    assertThat(page.get("offset").asInt()).isEqualTo(10);
    assertThat(page.get("limit").asInt()).isEqualTo(5);
    assertThat(page.get("words")).hasSize(5);
    assertThat(page.get("words").get(0).get("text").asString()).isEqualTo("word10");
    assertThat(page.get("words").get(4).get("text").asString()).isEqualTo("word14");

    kit.get(room.host(), pack(room.code(), id) + "/words?offset=-1")
        .andExpect(status().isBadRequest());
    kit.get(room.host(), pack(room.code(), id) + "/words?limit=0")
        .andExpect(status().isBadRequest());
    kit.get(room.host(), pack(room.code(), id) + "/words?offset=999")
        .andExpect(jsonPath("$.words.length()").value(0));
  }

  @Test
  void malformedIdsAre400() throws Exception {
    Room room = kit.newRoom("BadId");
    kit.get(room.host(), packs(room.code()) + "/not-a-uuid").andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.ResultActions addMany(
      Room room, UUID id, int from, int count) throws Exception {
    StringBuilder json = new StringBuilder("[");
    for (int i = from; i < from + count; i++) {
      json.append(i == from ? "" : ",").append("{\"text\":\"word").append(i).append("\"}");
    }
    return kit.addWords(room.host(), room.code(), id, json.append("]").toString());
  }

  private int count(String sql, UUID id) {
    Integer n = jdbc.queryForObject(sql, Integer.class, id);
    return n == null ? 0 : n;
  }
}
