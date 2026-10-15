package com.dinoparty.wordpacks;

import static com.dinoparty.wordpacks.PackTestKit.pack;
import static com.dinoparty.wordpacks.PackTestKit.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import com.dinoparty.wordpacks.PackTestKit.Room;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Import preview/confirm workflow over REST (api-contracts §2.5). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class WordPackImportIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  PackTestKit kit;
  Room room;
  UUID packId;

  @BeforeEach
  void setUp() throws Exception {
    kit = new PackTestKit(mvc);
    room = kit.newRoom("Import");
    packId = kit.createPackId(room, "Pack");
  }

  private JsonNode preview(String filename, byte[] bytes) throws Exception {
    return kit.api.parse(
        kit.preview(room.host(), room.code(), packId, filename, bytes).andExpect(status().isOk()));
  }

  private JsonNode currentPack() throws Exception {
    return kit.api.parse(
        kit.get(room.host(), pack(room.code(), packId)).andExpect(status().isOk()));
  }

  private int entries() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from word_pack_entry where pack_id = ?", Integer.class, packId);
    return n == null ? 0 : n;
  }

  @Test
  void previewReportsCountsRowsAndErrorsAndPersistsNothing() throws Exception {
    kit.addPlain(room, packId, "existing");
    long version = currentPack().get("wordCount").asLong();
    byte[] file = utf8("cat\nDog\n\ndog\nExisting\n<b>x</b>\n" + "w".repeat(81) + "\nbird\n");

    JsonNode preview = preview("words.txt", file);

    assertThat(preview.get("format").asString()).isEqualTo("TXT");
    assertThat(preview.get("totalRows").asInt()).isEqualTo(7);
    assertThat(preview.get("validCount").asInt()).isEqualTo(3);
    assertThat(preview.get("duplicateCount").asInt()).isEqualTo(2);
    assertThat(preview.get("invalidCount").asInt()).isEqualTo(2);
    assertThat(preview.get("remainingCapacity").asInt()).isEqualTo(999);
    assertThat(preview.get("exceedsPackLimit").asBoolean()).isFalse();
    assertThat(preview.get("sha256").asString()).hasSize(64);
    assertThat(preview.get("packVersion").asLong()).isGreaterThanOrEqualTo(version);
    assertThat(preview.get("preview")).hasSize(3);
    assertThat(preview.get("preview").get(1).get("text").asString()).isEqualTo("Dog");
    assertThat(preview.get("preview").get(1).get("line").asInt()).isEqualTo(2);
    assertThat(preview.get("duplicates").get(0).get("line").asInt()).isEqualTo(4);
    assertThat(preview.get("duplicates").get(0).get("of").asString()).isEqualTo("FILE");
    assertThat(preview.get("duplicates").get(1).get("of").asString()).isEqualTo("PACK");
    assertThat(preview.get("errors").get(0).get("line").asInt()).isEqualTo(6);
    assertThat(preview.get("errors").get(0).get("code").asString()).isEqualTo("INVALID_CHARACTER");
    assertThat(preview.get("errors").get(1).get("code").asString()).isEqualTo("TOO_LONG");
    assertThat(preview.get("errors").toString()).doesNotContain("<b>");

    assertThat(entries()).isEqualTo(1);
    assertThat(currentPack().get("wordCount").asInt()).isEqualTo(1);
  }

  @Test
  void previewListsAreBounded() throws Exception {
    StringBuilder file = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      file.append("word").append(i).append('\n');
    }
    for (int i = 0; i < 150; i++) {
      file.append("<x>\n");
    }

    JsonNode preview = preview("many.txt", utf8(file.toString()));

    assertThat(preview.get("validCount").asInt()).isEqualTo(200);
    assertThat(preview.get("invalidCount").asInt()).isEqualTo(150);
    assertThat(preview.get("preview")).hasSize(WordPackLimits.PREVIEW_ROWS);
    assertThat(preview.get("errors")).hasSize(WordPackLimits.MAX_REPORTED_ISSUES);
    assertThat(preview.get("truncated").asBoolean()).isTrue();
  }

  @Test
  void confirmStoresTheValidatedRowsAndReportsCounts() throws Exception {
    kit.addPlain(room, packId, "existing");
    byte[] file = utf8("cat\nDog\ndog\nExisting\n<b>x</b>\nbird\n");
    JsonNode preview = preview("words.txt", file);

    JsonNode result =
        kit.api.parse(
            kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
                .andExpect(status().isOk()));

    assertThat(result.get("imported").asInt()).isEqualTo(3);
    assertThat(result.get("duplicates").asInt()).isEqualTo(2);
    assertThat(result.get("invalid").asInt()).isEqualTo(1);
    assertThat(result.get("skipped").asInt()).isEqualTo(3);
    assertThat(result.get("pack").get("wordCount").asInt()).isEqualTo(4);
    assertThat(entries()).isEqualTo(4);
    assertThat(currentPack().get("wordCount").asInt()).isEqualTo(4);
    // File order is kept, after the words that were already there.
    JsonNode words = kit.api.parse(kit.get(room.host(), pack(room.code(), packId) + "/words"));
    assertThat(words.get("words").get(1).get("text").asString()).isEqualTo("cat");
    assertThat(words.get("words").get(3).get("text").asString()).isEqualTo("bird");
  }

  @Test
  void csvWithBomUnicodeQuotesAndMetadataRoundTrips() throws Exception {
    kit.addPlain(room, packId, "Café");
    String csv =
        "﻿word,Category,DIFFICULTY\r\n"
            + "\"T. rex, king\",\"Big, scary\",hard\r\n"
            + "CAFÉ,Drinks,easy\r\n"
            + "Cafe,Drinks,easy\r\n"
            + "恐龍,,\r\n"
            + "naïve,Words\r\n"
            + "bad,Words,MIXED\r\n";
    byte[] file = csv.getBytes(StandardCharsets.UTF_8);
    JsonNode preview = preview("words.CSV", file);
    assertThat(preview.get("format").asString()).isEqualTo("CSV");
    assertThat(preview.get("validCount").asInt()).isEqualTo(4);
    assertThat(preview.get("duplicateCount").asInt()).isEqualTo(1);
    assertThat(preview.get("errors").get(0).get("code").asString()).isEqualTo("INVALID_DIFFICULTY");
    assertThat(preview.get("errors").get(0).get("line").asInt()).isEqualTo(7);

    kit.confirm(room.host(), room.code(), packId, "words.CSV", file, preview)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imported").value(4));

    JsonNode words = kit.api.parse(kit.get(room.host(), pack(room.code(), packId) + "/words"));
    assertThat(words.get("words")).hasSize(5);
    JsonNode rex = words.get("words").get(1);
    assertThat(rex.get("text").asString()).isEqualTo("T. rex, king");
    assertThat(rex.get("category").asString()).isEqualTo("Big, scary");
    assertThat(rex.get("difficulty").asString()).isEqualTo("HARD");
    assertThat(words.get("words").get(2).get("text").asString()).isEqualTo("Cafe");
    assertThat(words.get("words").get(3).get("text").asString()).isEqualTo("恐龍");
    assertThat(words.get("words").get(3).get("category").asString()).isEqualTo("Custom");
    assertThat(words.get("words").get(3).get("difficulty").asString()).isEqualTo("MEDIUM");
  }

  @Test
  void confirmRejectsAStalePreviewAfterThePackChanged() throws Exception {
    byte[] file = utf8("cat\ndog\n");
    JsonNode preview = preview("words.txt", file);

    kit.addPlain(room, packId, "intruder");

    kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    assertThat(entries()).isEqualTo(1);
  }

  @Test
  void confirmRejectsADifferentFileThanThePreviewedOne() throws Exception {
    JsonNode preview = preview("words.txt", utf8("cat\ndog\n"));

    kit.confirm(room.host(), room.code(), packId, "words.txt", utf8("cat\ndog\nextra\n"), preview)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    kit.confirm(
            room.host(),
            room.code(),
            packId,
            "words.txt",
            utf8("cat\ndog\n"),
            "0".repeat(64),
            preview.get("packVersion").asLong())
        .andExpect(status().isConflict());
    kit.confirm(
            room.host(),
            room.code(),
            packId,
            "words.txt",
            utf8("cat\ndog\n"),
            "",
            preview.get("packVersion").asLong())
        .andExpect(status().isConflict());
    assertThat(entries()).isZero();
  }

  @Test
  void aPreviewCannotBeConfirmedTwice() throws Exception {
    byte[] file = utf8("cat\ndog\n");
    JsonNode preview = preview("words.txt", file);

    kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
        .andExpect(status().isOk());
    kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    assertThat(entries()).isEqualTo(2);

    // A fresh preview of the same file now finds only duplicates.
    JsonNode again = preview("words.txt", file);
    assertThat(again.get("validCount").asInt()).isZero();
    assertThat(again.get("duplicateCount").asInt()).isEqualTo(2);
    kit.confirm(room.host(), room.code(), packId, "words.txt", file, again)
        .andExpect(jsonPath("$.imported").value(0));
    assertThat(entries()).isEqualTo(2);
  }

  @Test
  void aPreviewMadeForAnotherPackCannotBeUsedHere() throws Exception {
    UUID other = kit.createPackId(room, "Other");
    byte[] file = utf8("cat\n");
    JsonNode forOther =
        kit.api.parse(
            kit.preview(room.host(), room.code(), other, "w.txt", file).andExpect(status().isOk()));
    kit.addPlain(room, packId, "something"); // moves this pack's version, not the other's

    kit.confirm(room.host(), room.code(), packId, "w.txt", file, forOther)
        .andExpect(status().isConflict());
    assertThat(entries()).isEqualTo(1);
  }

  @Test
  void importThatDoesNotFitIsRejectedAsAWholeAndStoresNothing() throws Exception {
    StringBuilder fill = new StringBuilder();
    for (int i = 0; i < 995; i++) {
      fill.append("fill").append(i).append('\n');
    }
    byte[] first = utf8(fill.toString());
    kit.confirm(room.host(), room.code(), packId, "fill.txt", first, preview("fill.txt", first))
        .andExpect(status().isOk());
    assertThat(entries()).isEqualTo(995);

    byte[] second = utf8("one\ntwo\nthree\nfour\nfive\nsix\nseven\n");
    JsonNode preview = preview("more.txt", second);
    assertThat(preview.get("exceedsPackLimit").asBoolean()).isTrue();
    assertThat(preview.get("remainingCapacity").asInt()).isEqualTo(5);

    kit.confirm(room.host(), room.code(), packId, "more.txt", second, preview)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORD_PACK_LIMIT_REACHED"))
        .andExpect(jsonPath("$.details.limit").value(1000))
        .andExpect(jsonPath("$.details.kind").value("WORDS"))
        .andExpect(jsonPath("$.details.remaining").value(5));
    assertThat(entries()).isEqualTo(995);
    assertThat(currentPack().get("wordCount").asInt()).isEqualTo(995);
  }

  @Test
  void badFilesAreRejectedWithStructuredErrors() throws Exception {
    kit.preview(room.host(), room.code(), packId, "words.xlsx", utf8("cat"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    kit.preview(room.host(), room.code(), packId, "words", utf8("cat"))
        .andExpect(status().isUnsupportedMediaType());

    for (String[] bad :
        new String[][] {
          {"empty.txt", ""},
          {"blank.txt", "\n  \n\n"},
          {"header.csv", "name,colour\ncat,red\n"},
          {"noword.csv", "category\nAnimals\n"},
          {"quotes.csv", "word\n\"open\ncat\n"},
          {"headeronly.csv", "word,category,difficulty\n"}
        }) {
      kit.preview(room.host(), room.code(), packId, bad[0], utf8(bad[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
    }
    kit.preview(
            room.host(),
            room.code(),
            packId,
            "latin1.txt",
            "café".getBytes(StandardCharsets.ISO_8859_1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));

    // A request without the file part.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                    pack(room.code(), packId) + "/imports/preview")
                .header("Authorization", "Bearer " + room.host().token()))
        .andExpect(status().isBadRequest());
    assertThat(entries()).isZero();
  }

  @Test
  void filesOverOneMebibyteAreRejectedAndExactlyOneMebibyteIsRead() throws Exception {
    byte[] max = new byte[WordPackLimits.MAX_FILE_BYTES];
    java.util.Arrays.fill(max, (byte) 'a');
    kit.preview(room.host(), room.code(), packId, "max.txt", max)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invalidCount").value(1)); // one 1 MiB line is too long as a word

    byte[] over = new byte[WordPackLimits.MAX_FILE_BYTES + 1];
    java.util.Arrays.fill(over, (byte) 'a');
    kit.preview(room.host(), room.code(), packId, "over.txt", over)
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    kit.confirm(room.host(), room.code(), packId, "over.csv", over, "00", 0)
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void tooManyRowsAreRejected() throws Exception {
    byte[] rows = "a\n".repeat(WordPackLimits.MAX_FILE_ROWS + 1).getBytes(StandardCharsets.UTF_8);
    kit.preview(room.host(), room.code(), packId, "rows.txt", rows)
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void importedContentIsNeverLogged(CapturedOutput output) throws Exception {
    String marker = "zzmarkerword" + UUID.randomUUID().toString().substring(0, 8);
    byte[] file = utf8(marker + "\n<bad " + marker + ">\n");
    JsonNode preview = preview("words.txt", file);
    kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
        .andExpect(status().isOk());

    assertThat(output.getAll()).doesNotContain(marker);
    assertThat(output.getAll()).contains("Word pack import room=" + room.code());
  }

  @Test
  void aTxtImportOfScriptsAndAccentsKeepsThemIntact() throws Exception {
    String text = "École\nécole\nnaïve\nCafé\nдинозавр\n🦖 rex\n";
    byte[] file = text.getBytes(StandardCharsets.UTF_8);
    JsonNode preview = preview("words.txt", file);
    assertThat(preview.get("duplicateCount").asInt()).isEqualTo(1);
    kit.confirm(room.host(), room.code(), packId, "words.txt", file, preview)
        .andExpect(status().isOk());

    JsonNode words = kit.api.parse(kit.get(room.host(), pack(room.code(), packId) + "/words"));
    assertThat(words.get("words"))
        .extracting(w -> w.get("text").asString())
        .containsExactly("École", "naïve", "Café", "динозавр", "🦖 rex");
  }
}
