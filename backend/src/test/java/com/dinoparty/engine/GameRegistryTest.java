package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.games.testgame.TestGameModule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GameRegistryTest {

  /** A module with the same key as the test game. */
  private static final class Duplicate extends TestGameModule {
    Duplicate() {
      super(1000, 1);
    }
  }

  private static GameModule moduleWith(
      String key, List<CommandSpec> commands, Set<String> publicEvents) {
    TestGameModule base = new TestGameModule(1000, 1);
    return new GameModuleDelegate(
        base,
        new GameDefinition(
            key,
            2,
            6,
            2,
            true,
            commands,
            publicEvents,
            Set.of(),
            base.definition().metadata(),
            SettingsSchema.EMPTY));
  }

  private record GameModuleDelegate(GameModule delegate, GameDefinition definition)
      implements GameModule {
    @Override
    public Transition init(GameContext ctx) {
      return delegate.init(ctx);
    }

    @Override
    public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
      return delegate.handle(state, action, ctx);
    }

    @Override
    public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
      return delegate.onDeadline(state, deadline, ctx);
    }

    @Override
    public Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx) {
      return delegate.onPlayerStatus(state, change, ctx);
    }

    @Override
    public Map<String, Object> view(GameState state, java.util.UUID viewer, GameContext ctx) {
      return delegate.view(state, viewer, ctx);
    }

    @Override
    public GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx) {
      return delegate.outcome(state, reason, ctx);
    }
  }

  @Test
  void indexesModulesByKeyAndCommand() {
    GameRegistry registry = new GameRegistry(List.of(new TestGameModule(1000, 1)));

    assertThat(registry.find("test-game")).isPresent();
    assertThat(registry.find("nope")).isEmpty();
    assertThat(registry.commandSpec("TEST_SCORE")).isPresent();
    assertThat(registry.commandSpec("MAKE_ME_ADMIN")).isEmpty();
  }

  @Test
  void definitionsComeInKeyOrderWithTheirMetadataAndSchema() {
    GameModule zebra = moduleWith("zebra-game", List.of(), Set.of("ZEBRA_EVENT"));
    GameModule alpha = moduleWith("alpha-game", List.of(), Set.of("ALPHA_EVENT"));
    GameRegistry registry = new GameRegistry(List.of(zebra, new TestGameModule(1000, 1), alpha));

    assertThat(registry.definitions())
        .extracting(GameDefinition::key)
        .containsExactly("alpha-game", "test-game", "zebra-game");
    assertThat(registry.definition("test-game"))
        .hasValueSatisfying(
            d -> {
              assertThat(d.metadata().name()).isEqualTo("Test Game");
              assertThat(d.settings()).isEqualTo(SettingsSchema.EMPTY);
            });
    assertThat(registry.definition("nope")).isEmpty();
  }

  @Test
  void duplicateKeysFailStartup() {
    assertThatThrownBy(
            () -> new GameRegistry(List.of(new TestGameModule(1000, 1), new Duplicate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate game key");
  }

  @Test
  void aCommandDeclaredByTwoGamesFailsStartup() {
    GameModule other =
        moduleWith(
            "other-game",
            List.of(new CommandSpec("TEST_SCORE", 10, 1, 1, Set.of())),
            Set.of("OTHER_EVENT"));

    assertThatThrownBy(() -> new GameRegistry(List.of(new TestGameModule(1000, 1), other)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TEST_SCORE");
  }

  @Test
  void anEventDeclaredByTwoGamesFailsStartup() {
    GameModule other = moduleWith("other-game", List.of(), Set.of("TEST_SCORED"));

    assertThatThrownBy(() -> new GameRegistry(List.of(new TestGameModule(1000, 1), other)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TEST_SCORED");
  }

  @Test
  void commandsMustDeclareASizeCapAndARate() {
    assertThatThrownBy(() -> new CommandSpec("DRAW_STROKE", 0, 1, 1, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CommandSpec("DRAW_STROKE", 10, 0, 1, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CommandSpec("draw", 10, 1, 1, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCatalogRowWithoutAModuleOnlyFailsInStrictMode() {
    GameRegistry registry = new GameRegistry(List.of(new TestGameModule(1000, 1)));
    Map<String, int[]> rows =
        Map.of("test-game", new int[] {2, 6}, "draw-guess", new int[] {2, 10});

    assertThat(registry.verifyAgainstCatalog(rows, false)).isEmpty();
    assertThat(registry.verifyAgainstCatalog(rows, true)).hasSize(1);
  }

  @Test
  void aModuleWithoutARowIsReportedAndBoundsMustMatch() {
    GameRegistry registry = new GameRegistry(List.of(new TestGameModule(1000, 1)));

    assertThat(registry.verifyAgainstCatalog(Map.of(), false)).hasSize(1);
    assertThatThrownBy(
            () -> registry.verifyAgainstCatalog(Map.of("test-game", new int[] {2, 8}), false))
        .isInstanceOf(IllegalStateException.class);
  }
}
