package com.dinoparty.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Contract timestamps: ISO-8601 UTC with exactly three fractional digits. */
public final class Timestamps {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Timestamps() {}

  public static String format(Instant instant) {
    return FORMAT.format(instant);
  }
}
