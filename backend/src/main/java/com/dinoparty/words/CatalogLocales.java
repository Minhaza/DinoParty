package com.dinoparty.words;

import com.dinoparty.common.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog locale tags: a lowercase language with an optional uppercase region ({@code en}, {@code
 * pt-BR}), the format stored in the database. Input is case-insensitive and accepts {@code _}.
 */
public final class CatalogLocales {

  /** The built-in catalog's locale and the last fallback for every lookup. */
  public static final String DEFAULT = "en";

  private static final Pattern TAG = Pattern.compile("([A-Za-z]{2,3})(?:[-_]([A-Za-z]{2}))?");

  private CatalogLocales() {}

  /** Normalizes a client-supplied tag; {@code null} or blank means {@link #DEFAULT}. */
  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    Matcher m = TAG.matcher(value.strip());
    if (!m.matches()) {
      throw DomainException.invalid("locale", "must be a language tag such as en or en-US");
    }
    String language = m.group(1).toLowerCase(Locale.ROOT);
    return m.group(2) == null ? language : language + "-" + m.group(2).toUpperCase(Locale.ROOT);
  }

  /** Lookup order for a normalized tag: the tag, its language, then {@link #DEFAULT}. */
  public static List<String> fallbackChain(String locale) {
    List<String> chain = new ArrayList<>(3);
    chain.add(locale);
    int dash = locale.indexOf('-');
    if (dash > 0) {
      chain.add(locale.substring(0, dash));
    }
    if (!chain.contains(DEFAULT)) {
      chain.add(DEFAULT);
    }
    return List.copyOf(chain);
  }
}
