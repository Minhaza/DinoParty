package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.games.drawguess.DrawGuessTestModules;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordPoolService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelephoneModuleTest {

  @Test
  void bothProductionGamesRegisterWithoutCommandOrEventClashes() {
    assertThatCode(
            () ->
                new GameRegistry(
                    List.of(
                        DrawGuessTestModules.module(), new TelephoneModule(TestWords.service()))))
        .doesNotThrowAnyException();
  }

  @Test
  void TP3_prepareFreezesAnEasyAndMediumPoolOnly() {
    TestWords.Catalog catalog =
        new TestWords.Catalog("animals")
            .add("cat", "animals", WordDifficulty.EASY)
            .add("owl", "animals", WordDifficulty.MEDIUM)
            .add("axolotl", "animals", WordDifficulty.HARD);
    WordPoolService words = new WordPoolService(catalog, new TestWords.Packs());

    GameSetup setup = new TelephoneModule(words).prepare(Map.of(), UUID.randomUUID());

    assertThat(((TelephoneSetup) setup).prompts()).containsExactlyInAnyOrder("cat", "owl");
    catalog.add("emu", "animals", WordDifficulty.EASY); // a later catalog change
    assertThat(((TelephoneSetup) setup).prompts()).hasSize(2);
  }

  @Test
  void TP3_aCatalogWithoutEasyOrMediumWordsRejectsTheStart() {
    TestWords.Catalog catalog =
        new TestWords.Catalog("animals").add("axolotl", "animals", WordDifficulty.HARD);

    assertThatThrownBy(
            () ->
                new TelephoneModule(new WordPoolService(catalog, new TestWords.Packs()))
                    .prepare(Map.of(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL));
  }

  @Test
  void settingsDefaultsHaveEveryContractKey() {
    assertThat(TelephoneSettings.defaults())
        .containsOnlyKeys(
            "promptSeconds",
            "drawSeconds",
            "describeSeconds",
            "entriesPerChain",
            "allowSkipTimeout");
  }
}
