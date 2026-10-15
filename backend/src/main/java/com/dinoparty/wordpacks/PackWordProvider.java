package com.dinoparty.wordpacks;

import com.dinoparty.room.RoomAccess;
import com.dinoparty.room.RoomService;
import com.dinoparty.wordpacks.persistence.WordPackEntity;
import com.dinoparty.wordpacks.persistence.WordPackEntryEntity;
import com.dinoparty.wordpacks.persistence.WordPackEntryRepository;
import com.dinoparty.wordpacks.persistence.WordPackRepository;
import com.dinoparty.words.CustomWordProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feeds stored packs to the word pool at game start. Only the packs of the room the requester
 * currently hosts resolve; every other id (other room, not host, unknown) is absent, which the pool
 * reports as an unavailable pack.
 */
@Component
@Transactional(readOnly = true)
class PackWordProvider implements CustomWordProvider {

  private final RoomService rooms;
  private final WordPackRepository packs;
  private final WordPackEntryRepository entries;

  PackWordProvider(RoomService rooms, WordPackRepository packs, WordPackEntryRepository entries) {
    this.rooms = rooms;
    this.packs = packs;
    this.entries = entries;
  }

  @Override
  public Map<UUID, List<CustomWord>> packs(UUID requester, List<UUID> packIds) {
    if (packIds.isEmpty()) {
      return Map.of();
    }
    RoomAccess room = rooms.activeAccess(requester).filter(RoomAccess::host).orElse(null);
    if (room == null) {
      return Map.of();
    }
    List<UUID> found =
        packs.findByRoomIdAndIdIn(room.roomId(), packIds).stream()
            .map(WordPackEntity::getId)
            .toList();
    Map<UUID, List<CustomWord>> byPack = new HashMap<>();
    for (WordPackEntryEntity e : entries.findByPackIdInOrderByPackIdAscPositionAscIdAsc(found)) {
      byPack
          .computeIfAbsent(e.getPackId(), k -> new ArrayList<>())
          .add(new CustomWord(e.getText(), e.getCategory(), e.getDifficulty()));
    }
    Map<UUID, List<CustomWord>> result = new LinkedHashMap<>();
    for (UUID id : found) {
      result.put(id, List.copyOf(byPack.getOrDefault(id, List.of())));
    }
    return result;
  }
}
