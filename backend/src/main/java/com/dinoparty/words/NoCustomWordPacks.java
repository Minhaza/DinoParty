package com.dinoparty.words;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The custom-pack provider until WP-03 stores packs: every pack id is unknown, so a game that
 * selects packs is rejected at start with {@code settings.wordPackIds}. WP-03 replaces this bean.
 */
@Component
class NoCustomWordPacks implements CustomWordProvider {

  @Override
  public Map<UUID, List<CustomWord>> packs(UUID requester, List<UUID> packIds) {
    return Map.of();
  }
}
