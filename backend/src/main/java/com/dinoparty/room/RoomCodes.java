package com.dinoparty.room;

import com.dinoparty.common.RandomSource;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Room codes: 6 characters from an alphabet without look-alikes (0/O, 1/I/L). */
@Component
class RoomCodes {

  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  static final int LENGTH = 6;

  private final RandomSource random;

  RoomCodes(RandomSource random) {
    this.random = random;
  }

  String generate() {
    StringBuilder code = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /** Upper-cases and validates user input; empty when it cannot possibly be a room code. */
  static java.util.Optional<String> normalize(String input) {
    if (input == null || input.length() != LENGTH) {
      return java.util.Optional.empty();
    }
    String upper = input.toUpperCase(Locale.ROOT);
    for (int i = 0; i < upper.length(); i++) {
      if (ALPHABET.indexOf(upper.charAt(i)) < 0) {
        return java.util.Optional.empty();
      }
    }
    return java.util.Optional.of(upper);
  }
}
