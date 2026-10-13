package com.dinoparty.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The deadlines of one session as plain data, not timer objects. Entries are ordered by time and
 * then by registration. Replacing an id bumps its generation; changing the phase drops every module
 * entry, so a stale wake-up finds nothing due.
 */
public final class DeadlineBook {

  public enum Kind {
    /** Owned by the engine (countdown, results timeout); not tied to a phase. */
    ENGINE,
    /** Ends the current phase. */
    PHASE_END,
    /** Module internal (hint reveal, summary pause). */
    INTERNAL
  }

  public record Entry(
      String id, Kind kind, UUID phaseId, Instant at, long generation, long order) {}

  private final List<Entry> entries = new ArrayList<>();
  private long registrations;

  public void set(String id, Kind kind, UUID phaseId, Instant at) {
    long generation = 1;
    for (Entry e : entries) {
      if (e.id().equals(id)) {
        generation = e.generation() + 1;
      }
    }
    entries.removeIf(e -> e.id().equals(id));
    entries.add(new Entry(id, kind, phaseId, at, generation, registrations++));
    entries.sort(Comparator.comparing(Entry::at).thenComparingLong(Entry::order));
  }

  public boolean remove(String id) {
    return entries.removeIf(e -> e.id().equals(id));
  }

  /** Removes every non-engine entry; called when the phase changes. */
  public void removeModuleEntries() {
    entries.removeIf(e -> e.kind() != Kind.ENGINE);
  }

  public void clear() {
    entries.clear();
  }

  /** Removes and returns the earliest entry that is due at the given time. */
  public Optional<Entry> pollDue(Instant now) {
    if (entries.isEmpty() || entries.getFirst().at().isAfter(now)) {
      return Optional.empty();
    }
    return Optional.of(entries.removeFirst());
  }

  public Optional<Entry> earliest() {
    return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }
}
