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
 * #toString()} prints neither the word, the decoy nor the impostor.
 *
 * @param decoy Sketchy Saurus without {@code impostorKnows} ([SS-3]): the word the unaware impostor
 *     is given in place of the real one, as an ordinary {@code ARTIST}; {@code null} otherwise
 */
record ImpostorRound(int round, UUID impostor, CategorizedWord word, CategorizedWord decoy) {

  static final String ROLE_EVENT = ImpostorFamily.ROLE_EVENT;
  static final String ARTIST = "ARTIST";
  static final String IMPOSTOR = "IMPOSTOR";

  ImpostorRound(int round, UUID impostor, CategorizedWord word) {
    this(round, impostor, word, null);
  }

  boolean isImpostor(UUID guest) {
    return impostor.equals(guest);
  }

  /**
   * The viewer's role as in the snapshot: {@code {role, category:{key, label}, word|null}}. An
   * impostor who has a decoy is told {@code ARTIST} and the decoy ([SS-3]), the same fields as any
   * artist's.
   */
  Map<String, Object> role(UUID viewer) {
    boolean knownImpostor = isImpostor(viewer) && decoy == null;
    String text = isImpostor(viewer) ? (decoy == null ? null : decoy.text()) : word.text();
    Map<String, Object> role = new LinkedHashMap<>();
    role.put("role", knownImpostor ? IMPOSTOR : ARTIST);
    role.put("category", category());
    role.put("word", text);
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

  Map<String, Object> category() {
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
