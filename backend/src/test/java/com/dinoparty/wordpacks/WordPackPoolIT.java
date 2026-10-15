package com.dinoparty.wordpacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.IntegrationTest;
import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomService;
import com.dinoparty.wordpacks.WordPackTypes.WordInput;
import com.dinoparty.words.DifficultyFilter;
import com.dinoparty.words.WordPool;
import com.dinoparty.words.WordPoolService;
import com.dinoparty.words.WordSelection;
import com.dinoparty.words.WordSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Stored packs feed the game's word pool, for the hosting guest of their own room only. */
@IntegrationTest
class WordPackPoolIT {

  @Autowired GuestService guests;
  @Autowired RoomService rooms;
  @Autowired WordPackService service;
  @Autowired WordPoolService pools;

  private UUID newGuest() {
    return guests.register(ApiTestSupport.unique("Pool"), "dino-rex", null, "it").guest().id();
  }

  private String newRoom(UUID host) {
    return rooms.create(host, new CreateRoomCommand("draw-guess", null, null)).code();
  }

  private static WordSelection custom(UUID... packs) {
    return new WordSelection(WordSource.CUSTOM, List.of(), DifficultyFilter.MIXED, List.of(packs));
  }

  @Test
  void theHostsOwnPackFeedsTheCustomPoolAndKeepsOnlyUsableWords() {
    UUID host = newGuest();
    String code = newRoom(host);
    UUID packId = service.create(host, code, "Pack", null).id();
    service.addWords(
        host,
        code,
        packId,
        List.of(
            new WordInput("Raptor", "Dinos", "HARD"),
            new WordInput("Ice cream", null, null),
            new WordInput("Café", null, null),
            new WordInput("cafe", null, null), // same word as Café for guessing
            new WordInput("ab", null, null), // too short for the game
            new WordInput("Room 101", null, null))); // digits are not game words

    WordPool pool = pools.build(custom(packId), host, 3);

    assertThat(pool.words()).containsExactly("Raptor", "Ice cream", "Café");
  }

  @Test
  void aPackOfAnotherRoomIsUnavailable() {
    UUID hostA = newGuest();
    UUID hostB = newGuest();
    String codeA = newRoom(hostA);
    newRoom(hostB);
    UUID packA = service.create(hostA, codeA, "A", null).id();
    service.addWords(
        hostA,
        codeA,
        packA,
        List.of(
            new WordInput("alpha", null, null),
            new WordInput("beta", null, null),
            new WordInput("gamma", null, null)));

    assertThatThrownBy(() -> pools.build(custom(packA), hostB, 3))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .extracting("field")
                  .containsExactly("settings.wordPackIds");
            });
  }

  @Test
  void aMemberWhoIsNotTheHostCannotUseThePack() {
    UUID host = newGuest();
    UUID member = newGuest();
    String code = newRoom(host);
    rooms.join(member, code);
    UUID packId = service.create(host, code, "Pack", null).id();
    service.addWords(
        host,
        code,
        packId,
        List.of(
            new WordInput("alpha", null, null),
            new WordInput("beta", null, null),
            new WordInput("gamma", null, null)));

    assertThatThrownBy(() -> pools.build(custom(packId), member, 3))
        .isInstanceOf(DomainException.class);
    assertThat(pools.build(custom(packId), host, 3).size()).isEqualTo(3);
  }

  @Test
  void aDeletedOrUnknownPackIsUnavailableAndAnEmptyOneIsTooSmall() {
    UUID host = newGuest();
    String code = newRoom(host);
    UUID packId = service.create(host, code, "Pack", null).id();

    assertThatThrownBy(() -> pools.build(custom(packId), host, 3))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL));

    service.delete(host, code, packId);
    assertThatThrownBy(() -> pools.build(custom(packId), host, 3))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> pools.build(custom(UUID.randomUUID()), host, 3))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void combinedSourceMergesBuiltInAndPackWords() {
    UUID host = newGuest();
    String code = newRoom(host);
    UUID packId = service.create(host, code, "Pack", null).id();
    service.addWords(host, code, packId, List.of(new WordInput("Zorblax", null, null)));

    WordPool pool =
        pools.build(
            new WordSelection(
                WordSource.COMBINED, List.of(), DifficultyFilter.MIXED, List.of(packId)),
            host,
            3);

    assertThat(pool.words()).contains("Zorblax").hasSizeGreaterThan(600);
  }
}
