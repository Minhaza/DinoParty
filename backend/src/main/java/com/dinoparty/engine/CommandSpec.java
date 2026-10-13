package com.dinoparty.engine;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A client command a game accepts. The transport enforces the byte cap and the rate before the
 * command reaches the room mailbox, so a module cannot forget a limit: both are mandatory.
 *
 * @param phases phase names in which the command is legal; empty means any phase
 */
public record CommandSpec(
    String type, int maxPayloadBytes, double ratePerSecond, int burst, Set<String> phases) {

  private static final Pattern TYPE = Pattern.compile("[A-Z_]{1,40}");

  public CommandSpec {
    if (type == null || !TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid command type");
    }
    if (maxPayloadBytes <= 0 || ratePerSecond <= 0 || burst <= 0) {
      throw new IllegalArgumentException("Command " + type + " needs a payload cap and a rate");
    }
    phases = Set.copyOf(phases);
  }
}
