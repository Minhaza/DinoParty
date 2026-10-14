package com.dinoparty.wordpacks;

import com.dinoparty.words.WordDifficulty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models and results of the word pack service; serialized as the §2.5 DTOs. */
public final class WordPackTypes {

  private WordPackTypes() {}

  public record PackInfo(
      UUID id,
      UUID roomId,
      String name,
      String description,
      int wordCount,
      Instant createdAt,
      Instant updatedAt) {}

  public record PackWord(UUID id, String text, String category, WordDifficulty difficulty) {}

  public record WordsPage(List<PackWord> words, int offset, int limit, int total) {}

  /** An unvalidated word from a client; every field is checked by the service. */
  public record WordInput(String text, String category, String difficulty) {}

  public record AddResult(int added, int skipped, PackInfo pack) {}

  public enum ImportFormat {
    TXT,
    CSV
  }

  /** Why a row cannot be imported. Messages never repeat the row's content. */
  public enum RowProblem {
    BLANK_WORD("The word is empty."),
    TOO_LONG("The value is too long."),
    INVALID_CHARACTER("The value contains a character that is not allowed."),
    INVALID_DIFFICULTY("The difficulty must be EASY, MEDIUM or HARD."),
    INVALID_CATEGORY("The category must be 1-40 characters without control characters."),
    MALFORMED_ROW("The row has more columns than the header.");

    private final String message;

    RowProblem(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  public record RowError(int line, RowProblem code, String message) {}

  /** {@code of} is {@code FILE} (earlier row of the upload) or {@code PACK} (already stored). */
  public record DuplicateRow(int line, String of) {}

  public record PreviewRow(int line, String text, String category, WordDifficulty difficulty) {}

  public record ImportPreview(
      ImportFormat format,
      String sha256,
      long packVersion,
      int totalRows,
      int validCount,
      int duplicateCount,
      int invalidCount,
      int remainingCapacity,
      boolean exceedsPackLimit,
      List<PreviewRow> preview,
      List<DuplicateRow> duplicates,
      List<RowError> errors,
      boolean truncated) {}

  public record ImportResult(
      int imported, int skipped, int duplicates, int invalid, PackInfo pack) {}
}
