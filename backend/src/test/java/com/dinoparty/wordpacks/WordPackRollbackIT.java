package com.dinoparty.wordpacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.IntegrationTest;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomService;
import com.dinoparty.wordpacks.WordPackTypes.ImportPreview;
import com.dinoparty.wordpacks.WordPackTypes.WordInput;
import com.dinoparty.wordpacks.persistence.WordPackEntryRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Atomicity of the import transaction. The entry repository is made to report "no keys yet" for a
 * pack that already holds one, as a lost race would; the database's unique key then fails the last
 * insert and nothing of the batch may remain.
 */
@IntegrationTest
class WordPackRollbackIT {

  @Autowired GuestService guests;
  @Autowired RoomService rooms;
  @Autowired WordPackService service;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean WordPackEntryRepository entries;

  @AfterEach
  void resetSpy() {
    reset(entries);
  }

  private UUID newGuest() {
    return guests.register(ApiTestSupport.unique("Roll"), "dino-rex", null, "it").guest().id();
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void aFailedImportLeavesNoEntryAndKeepsCountAndVersion() {
    UUID host = newGuest();
    String code = rooms.create(host, new CreateRoomCommand("draw-guess", null, null)).code();
    UUID packId = service.create(host, code, "Pack", null).id();
    service.addWords(host, code, packId, List.of(new WordInput("Rex", null, null)));
    byte[] file = "alpha\nbeta\nREX\n".getBytes(StandardCharsets.UTF_8);
    ImportPreview preview = service.preview(host, code, packId, "w.txt", file);
    assertThat(preview.validCount()).isEqualTo(2); // alpha, beta (REX is a known duplicate)

    // The import now believes the pack is empty, so it tries to insert REX as well.
    doReturn(List.of()).when(entries).keysOf(any());

    assertThatThrownBy(
            () ->
                service.confirm(
                    host, code, packId, "w.txt", file, preview.sha256(), preview.packVersion()))
        .isInstanceOf(RuntimeException.class);

    reset(entries);
    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", packId))
        .isEqualTo(1);
    assertThat(count("select word_count from word_pack where id = ?", packId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select content_version from word_pack where id = ?", Long.class, packId))
        .isEqualTo(preview.packVersion());
    // The pack is still usable and the same preview can be confirmed once the fault is gone.
    assertThat(
            service
                .confirm(host, code, packId, "w.txt", file, preview.sha256(), preview.packVersion())
                .imported())
        .isEqualTo(2);
    assertThat(count("select count(*) from word_pack_entry where pack_id = ?", packId))
        .isEqualTo(3);
  }
}
