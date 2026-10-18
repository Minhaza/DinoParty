package com.dinoparty.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.games.drawguess.DrawGuessModule;
import com.dinoparty.games.telephone.TelephoneModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Game extensibility check (architecture.md §3.7): a game's key, metadata and settings are declared
 * by its module only, so platform code never names a game. Adding a game must not require editing a
 * hard-coded list anywhere outside {@code com.dinoparty.games}.
 */
class GameKeyLiteralTest {

  private static final Path SOURCES = Path.of("src/main/java/com/dinoparty");

  /**
   * Places that may name a game, with the reason. Keep this short: every entry is a place a new
   * game might have to be added by hand.
   */
  private static final Set<String> ALLOWED =
      Set.of(
          // The album endpoints are Telephone-specific by contract (api-contracts §2).
          "web/SessionController.java:\"" + TelephoneModule.KEY + "\"");

  @Test
  void noPlatformCodeNamesAGame() throws IOException {
    List<String> keys = List.of(DrawGuessModule.KEY, TelephoneModule.KEY);
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SOURCES)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String relative = SOURCES.relativize(file).toString().replace('\\', '/');
        if (relative.startsWith("games/")) {
          continue;
        }
        String source = Files.readString(file);
        for (String key : keys) {
          String literal = "\"" + key + "\"";
          if (source.contains(literal) && !ALLOWED.contains(relative + ":" + literal)) {
            offenders.add(relative + " names " + literal);
          }
        }
      }
    }
    assertThat(offenders).as("game keys outside the game modules").isEmpty();
  }
}
