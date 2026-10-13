package com.dinoparty.guest;

/** Shared profile constraints so REST DTO annotations and the service cannot drift apart. */
public final class GuestProfileRules {

  public static final int NICKNAME_MIN = 2;
  public static final int NICKNAME_MAX = 20;

  /**
   * Letters/digits first, then single-space separated words of letters, digits and {@code _.'-}.
   */
  public static final String NICKNAME_REGEX =
      "^[\\p{L}\\p{N}][\\p{L}\\p{N}_.'-]*(?: [\\p{L}\\p{N}_.'-]+)*$";

  public static final String COLOR_REGEX = "^#[0-9A-Fa-f]{6}$";

  private GuestProfileRules() {}
}
