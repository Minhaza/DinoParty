package com.dinoparty.wordpacks;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.DomainException.FieldViolation;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RateLimiter;
import com.dinoparty.room.RoomAccess;
import com.dinoparty.room.RoomService;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.wordpacks.ImportAnalyzer.Accepted;
import com.dinoparty.wordpacks.ImportAnalyzer.Analysis;
import com.dinoparty.wordpacks.WordPackTypes.AddResult;
import com.dinoparty.wordpacks.WordPackTypes.ImportFormat;
import com.dinoparty.wordpacks.WordPackTypes.ImportPreview;
import com.dinoparty.wordpacks.WordPackTypes.ImportResult;
import com.dinoparty.wordpacks.WordPackTypes.PackInfo;
import com.dinoparty.wordpacks.WordPackTypes.PackWord;
import com.dinoparty.wordpacks.WordPackTypes.PreviewRow;
import com.dinoparty.wordpacks.WordPackTypes.RowProblem;
import com.dinoparty.wordpacks.WordPackTypes.WordInput;
import com.dinoparty.wordpacks.WordPackTypes.WordsPage;
import com.dinoparty.wordpacks.WordRules.Checked;
import com.dinoparty.wordpacks.WordRules.Cleaned;
import com.dinoparty.wordpacks.WordRules.Word;
import com.dinoparty.wordpacks.persistence.WordPackEntity;
import com.dinoparty.wordpacks.persistence.WordPackEntryEntity;
import com.dinoparty.wordpacks.persistence.WordPackEntryRepository;
import com.dinoparty.wordpacks.persistence.WordPackRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room-scoped custom word packs (api-contracts §2.5). The caller's identity always comes from the
 * authenticated principal and every lookup is scoped to the room of the request, so a pack id of
 * another room is simply not found. Mutations run in one transaction under an advisory lock of the
 * room: limits, duplicate checks and the preview version check cannot be raced, and a failure rolls
 * everything back. Words are only returned to the host; nothing here logs word content.
 */
@Service
@Transactional
public class WordPackService {

  private static final Logger log = LoggerFactory.getLogger(WordPackService.class);

  private static final int WRITES_PER_MINUTE = 60;
  private static final int IMPORTS_PER_MINUTE = 10;
  private static final int MAX_WORDS_PAGE = 500;

  private final RoomService rooms;
  private final WordPackRepository packs;
  private final WordPackEntryRepository entries;
  private final JdbcTemplate jdbc;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  WordPackService(
      RoomService rooms,
      WordPackRepository packs,
      WordPackEntryRepository entries,
      JdbcTemplate jdbc,
      RateLimiter rateLimiter,
      Clock clock) {
    this.rooms = rooms;
    this.packs = packs;
    this.entries = entries;
    this.jdbc = jdbc;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public PackInfo create(UUID guestId, String roomCode, String name, String description) {
    throttleWrite(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    String cleanName = label("name", name, WordPackLimits.MAX_NAME_CODE_POINTS, 1);
    String cleanDescription =
        description == null
            ? ""
            : label("description", description, WordPackLimits.MAX_DESCRIPTION_CODE_POINTS, 0);
    lock(room.roomId());
    if (packs.countByRoomId(room.roomId()) >= WordPackLimits.MAX_PACKS_PER_ROOM) {
      throw limitReached(WordPackLimits.MAX_PACKS_PER_ROOM, "PACKS", null);
    }
    WordPackEntity pack =
        packs.save(
            new WordPackEntity(
                UUID.randomUUID(), room.roomId(), cleanName, cleanDescription, clock.instant()));
    log.info("Word pack created room={} pack={}", room.code(), pack.getId());
    return info(pack);
  }

  @Transactional(readOnly = true)
  public List<PackInfo> list(UUID guestId, String roomCode) {
    RoomAccess room = requireMember(guestId, roomCode);
    return packs.findByRoomIdOrderByCreatedAtAscIdAsc(room.roomId()).stream()
        .map(WordPackService::info)
        .toList();
  }

  @Transactional(readOnly = true)
  public PackInfo get(UUID guestId, String roomCode, UUID packId) {
    RoomAccess room = requireMember(guestId, roomCode);
    return info(requirePack(room, packId));
  }

  /** {@code null} arguments leave the field unchanged; an empty description clears it. */
  public PackInfo update(
      UUID guestId, String roomCode, UUID packId, String name, String description) {
    throttleWrite(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    if (name == null && description == null) {
      throw DomainException.invalid("name", "give a name or a description to change");
    }
    String cleanName =
        name == null ? null : label("name", name, WordPackLimits.MAX_NAME_CODE_POINTS, 1);
    String cleanDescription =
        description == null
            ? null
            : label("description", description, WordPackLimits.MAX_DESCRIPTION_CODE_POINTS, 0);
    lock(room.roomId());
    WordPackEntity pack = requirePack(room, packId);
    Instant now = clock.instant();
    if (cleanName != null) {
      pack.rename(cleanName, now);
    }
    if (cleanDescription != null) {
      pack.describe(cleanDescription, now);
    }
    return info(pack);
  }

  public void delete(UUID guestId, String roomCode, UUID packId) {
    throttleWrite(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    lock(room.roomId());
    WordPackEntity pack = requirePack(room, packId);
    entries.deleteByPackId(pack.getId());
    packs.delete(pack);
    log.info("Word pack deleted room={} pack={}", room.code(), packId);
  }

  /** The words of a pack, host only; {@code limit} is capped at 500. */
  @Transactional(readOnly = true)
  public WordsPage words(UUID guestId, String roomCode, UUID packId, int offset, int limit) {
    if (offset < 0) {
      throw DomainException.invalid("offset", "must not be negative");
    }
    if (limit < 1) {
      throw DomainException.invalid("limit", "must be at least 1");
    }
    RoomAccess room = requireHost(guestId, roomCode);
    WordPackEntity pack = requirePack(room, packId);
    int size = Math.min(limit, MAX_WORDS_PAGE);
    List<WordPackEntryEntity> all = entries.findByPackIdOrderByPositionAscIdAsc(pack.getId());
    List<PackWord> page =
        all.stream()
            .skip(offset)
            .limit(size)
            .map(e -> new PackWord(e.getId(), e.getText(), e.getCategory(), e.getDifficulty()))
            .toList();
    return new WordsPage(page, offset, size, all.size());
  }

  public AddResult addWords(UUID guestId, String roomCode, UUID packId, List<WordInput> inputs) {
    throttleWrite(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    if (inputs == null || inputs.isEmpty() || inputs.size() > WordPackLimits.MAX_ADD_BATCH) {
      throw DomainException.invalid("words", "must contain 1-100 words");
    }
    List<Word> valid = new ArrayList<>(inputs.size());
    List<FieldViolation> violations = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      WordInput input = inputs.get(i);
      Checked checked =
          input == null
              ? new Checked(null, RowProblem.BLANK_WORD)
              : WordRules.check(input.text(), input.category(), input.difficulty());
      if (checked.problem() != null) {
        violations.add(
            new FieldViolation(fieldOf(i, checked.problem()), checked.problem().message()));
      } else {
        valid.add(checked.word());
      }
    }
    if (!violations.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The request is invalid.", violations);
    }
    lock(room.roomId());
    WordPackEntity pack = requirePack(room, packId);
    Set<String> keys = new HashSet<>(entries.keysOf(pack.getId()));
    List<Word> fresh = new ArrayList<>();
    for (Word word : valid) {
      if (keys.add(word.key())) {
        fresh.add(word);
      }
    }
    requireCapacity(pack, fresh.size());
    store(pack, fresh);
    return new AddResult(fresh.size(), valid.size() - fresh.size(), info(pack));
  }

  public void removeWord(UUID guestId, String roomCode, UUID packId, UUID wordId) {
    throttleWrite(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    lock(room.roomId());
    WordPackEntity pack = requirePack(room, packId);
    WordPackEntryEntity entry =
        entries
            .findByIdAndPackId(wordId, pack.getId())
            .orElseThrow(() -> new DomainException(ErrorCode.WORD_PACK_NOT_FOUND, "No such word."));
    entries.delete(entry);
    pack.entriesChanged(pack.getWordCount() - 1, clock.instant());
  }

  /** Parses and validates an upload without storing anything. */
  @Transactional(readOnly = true)
  public ImportPreview preview(
      UUID guestId, String roomCode, UUID packId, String filename, byte[] file) {
    throttleImport(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    ImportFormat format = ImportParser.formatOf(filename);
    WordPackEntity pack = requirePack(room, packId);
    Analysis analysis =
        ImportAnalyzer.analyze(format, file, new HashSet<>(entries.keysOf(pack.getId())));
    int remaining = WordPackLimits.MAX_WORDS_PER_PACK - pack.getWordCount();
    List<PreviewRow> rows =
        analysis.accepted().stream()
            .limit(WordPackLimits.PREVIEW_ROWS)
            .map(
                a ->
                    new PreviewRow(
                        a.line(), a.word().text(), a.word().category(), a.word().difficulty()))
            .toList();
    return new ImportPreview(
        format,
        analysis.sha256(),
        pack.getContentVersion(),
        analysis.totalRows(),
        analysis.accepted().size(),
        analysis.duplicateCount(),
        analysis.invalidCount(),
        remaining,
        analysis.accepted().size() > remaining,
        rows,
        analysis.duplicates(),
        analysis.errors(),
        analysis.truncated() || analysis.accepted().size() > rows.size());
  }

  /**
   * Stores the accepted rows of an upload in one transaction. The upload is parsed again here; the
   * preview's digest and pack version only prove that it is the file the host reviewed and that the
   * pack has not changed since.
   *
   * @throws DomainException {@code IMPORT_STALE} when either differs; {@code
   *     WORD_PACK_LIMIT_REACHED} when the accepted rows do not all fit
   */
  public ImportResult confirm(
      UUID guestId,
      String roomCode,
      UUID packId,
      String filename,
      byte[] file,
      String sha256,
      long packVersion) {
    throttleImport(guestId);
    RoomAccess room = requireHost(guestId, roomCode);
    ImportFormat format = ImportParser.formatOf(filename);
    lock(room.roomId());
    WordPackEntity pack = requirePack(room, packId);
    if (pack.getContentVersion() != packVersion
        || sha256 == null
        || !ImportAnalyzer.sha256(file).equalsIgnoreCase(sha256.strip())) {
      throw new DomainException(
          ErrorCode.IMPORT_STALE, "The pack or the file changed since the preview. Preview again.");
    }
    Analysis analysis =
        ImportAnalyzer.analyze(format, file, new HashSet<>(entries.keysOf(pack.getId())));
    List<Word> words = analysis.accepted().stream().map(Accepted::word).toList();
    requireCapacity(pack, words.size());
    store(pack, words);
    int skipped = analysis.duplicateCount() + analysis.invalidCount();
    log.info(
        "Word pack import room={} pack={} imported={} duplicates={} invalid={}",
        room.code(),
        pack.getId(),
        words.size(),
        analysis.duplicateCount(),
        analysis.invalidCount());
    return new ImportResult(
        words.size(), skipped, analysis.duplicateCount(), analysis.invalidCount(), info(pack));
  }

  private void store(WordPackEntity pack, List<Word> words) {
    if (words.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    int position = entries.maxPosition(pack.getId());
    List<WordPackEntryEntity> rows = new ArrayList<>(words.size());
    for (Word word : words) {
      rows.add(
          new WordPackEntryEntity(
              UUID.randomUUID(),
              pack.getId(),
              ++position,
              word.text(),
              word.key(),
              word.category(),
              word.difficulty(),
              now));
    }
    entries.saveAll(rows);
    pack.entriesChanged(pack.getWordCount() + rows.size(), now);
    // Force the inserts now so a constraint violation surfaces inside this transaction's method.
    entries.flush();
  }

  private void requireCapacity(WordPackEntity pack, int adding) {
    int remaining = WordPackLimits.MAX_WORDS_PER_PACK - pack.getWordCount();
    if (adding > remaining) {
      throw limitReached(WordPackLimits.MAX_WORDS_PER_PACK, "WORDS", remaining);
    }
  }

  private static DomainException limitReached(int limit, String kind, Integer remaining) {
    Map<String, Object> details =
        remaining == null
            ? Map.of("limit", limit, "kind", kind)
            : Map.of("limit", limit, "kind", kind, "remaining", remaining);
    return new DomainException(
        ErrorCode.WORD_PACK_LIMIT_REACHED,
        kind.equals("PACKS")
            ? "A room can have at most " + limit + " word packs."
            : "A word pack can hold at most " + limit + " words.",
        List.of(),
        details);
  }

  private RoomAccess requireMember(UUID guestId, String roomCode) {
    RoomAccess room = rooms.accessOf(guestId, roomCode);
    if (!room.member()) {
      throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
    }
    return room;
  }

  private RoomAccess requireHost(UUID guestId, String roomCode) {
    RoomAccess room = requireMember(guestId, roomCode);
    if (!room.host()) {
      throw new DomainException(ErrorCode.NOT_HOST, "Only the host can manage word packs.");
    }
    if (room.status() == RoomStatus.CLOSED) {
      throw new DomainException(ErrorCode.ROOM_CLOSED, "This room is closed.");
    }
    return room;
  }

  private WordPackEntity requirePack(RoomAccess room, UUID packId) {
    return packs
        .findByIdAndRoomId(packId, room.roomId())
        .orElseThrow(
            () -> new DomainException(ErrorCode.WORD_PACK_NOT_FOUND, "No such word pack."));
  }

  /** Serializes pack mutations of one room until the transaction ends. */
  private void lock(UUID roomId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        ps -> ps.setString(1, "word-packs:" + roomId),
        rs -> null);
  }

  private void throttleWrite(UUID guestId) {
    rateLimiter.check("word-pack-write:" + guestId, WRITES_PER_MINUTE, 60);
  }

  private void throttleImport(UUID guestId) {
    rateLimiter.check("word-pack-import:" + guestId, IMPORTS_PER_MINUTE, 60);
  }

  private static String label(String field, String raw, int max, int min) {
    Cleaned cleaned = WordRules.clean(raw, max, min, RowProblem.BLANK_WORD);
    if (cleaned.problem() != null) {
      throw DomainException.invalid(
          field,
          switch (cleaned.problem()) {
            case BLANK_WORD -> "must not be empty";
            case TOO_LONG -> "must be at most " + max + " characters";
            default -> "contains a character that is not allowed";
          });
    }
    return cleaned.value();
  }

  private static String fieldOf(int index, RowProblem problem) {
    String suffix =
        switch (problem) {
          case INVALID_DIFFICULTY -> "difficulty";
          case INVALID_CATEGORY -> "category";
          default -> "text";
        };
    return "words[" + index + "]." + suffix;
  }

  private static PackInfo info(WordPackEntity p) {
    return new PackInfo(
        p.getId(),
        p.getRoomId(),
        p.getName(),
        p.getDescription(),
        p.getWordCount(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }
}
