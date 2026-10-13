package com.dinoparty.engine;

import java.util.Set;
import java.util.UUID;

/**
 * Who receives an event. There is deliberately no "everyone except X": a topic cannot exclude a
 * subscriber, so restricted delivery is always a list of private recipients.
 */
public sealed interface Audience {

  record Public() implements Audience {}

  record Private(Set<UUID> recipients) implements Audience {
    public Private {
      recipients = Set.copyOf(recipients);
    }
  }

  Audience PUBLIC = new Public();

  static Audience to(UUID... recipients) {
    return new Private(Set.of(recipients));
  }
}
