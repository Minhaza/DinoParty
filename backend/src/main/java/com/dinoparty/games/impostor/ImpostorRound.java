package com.dinoparty.games.impostor;

import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is the impostor and what the word is in one round, and the only builder of what each player
 * is told about it ([DI-4], R35). Every role has the same keys in the same order, whoever receives
 * it, so the impostor cannot tell their role from the shape of a payload. Holds secrets: {@link
 * #toString()} prints neither the word nor the impostor.
 */
record ImpostorRound(int round, UUID impostor, CategorizedWord word) {

  static final String ROLE_EVENT = "IMPOSTOR_ROLE";
  static final String ARTIST = "ARTIST";
  static final String IMPOSTOR = "IMPOSTOR";

  boolean isImpostor(UUID guest) {
    return impostor.equals(guest);
  }

  /** The viewer's role as in the snapshot: {@code {role, category:{key, label}, word|null}}. */
  Map<String, Object> role(UUID viewer) {
    boolean isImpostor = isImpostor(viewer);
    Map<String, Object> role = new LinkedHashMap<>();
    role.put("role", isImpostor ? IMPOSTOR : ARTIST);
    role.put("category", category());
    role.put("word", isImpostor ? null : word.text());
    return role;
  }

  /**
   * One private {@code IMPOSTOR_ROLE} per player, all for the same transition: {@code {phaseId,
   * round, role, category, word}}.
   */
  List<OutboundEvent> roleEvents(Collection<UUID> players, UUID phaseId) {
    List<OutboundEvent> events = new ArrayList<>(players.size());
    for (UUID player : players) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("phaseId", phaseId);
      payload.put("round", round);
      payload.putAll(role(player));
      events.add(OutboundEvent.privateEvent(player, ROLE_EVENT, payload));
    }
    return events;
  }

  private Map<String, Object> category() {
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("key", word.categoryKey());
    category.put("label", word.categoryLabel());
    return category;
  }

  @Override
  public String toString() {
    return "ImpostorRound[round=" + round + "]";
  }
}
