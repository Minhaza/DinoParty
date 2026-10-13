package com.dinoparty.guest;

import java.util.Set;

/**
 * The fixed set of original dino avatars; mirrors {@code frontend/src/components/dinoAvatars.ts}.
 */
final class AvatarCatalog {

  static final Set<String> KEYS =
      Set.of("dino-rex", "dino-tricera", "dino-ptera", "dino-stego", "dino-mite", "dino-lizard");

  private AvatarCatalog() {}
}
