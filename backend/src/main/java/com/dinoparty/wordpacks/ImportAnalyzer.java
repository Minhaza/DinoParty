package com.dinoparty.wordpacks;

import com.dinoparty.wordpacks.ImportParser.RawRow;
import com.dinoparty.wordpacks.WordPackTypes.DuplicateRow;
import com.dinoparty.wordpacks.WordPackTypes.ImportFormat;
import com.dinoparty.wordpacks.WordPackTypes.RowError;
import com.dinoparty.wordpacks.WordPackTypes.RowProblem;
import com.dinoparty.wordpacks.WordRules.Checked;
import com.dinoparty.wordpacks.WordRules.Word;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses, validates and de-duplicates an upload against the keys already in the pack. Pure: the
 * same bytes and keys always give the same result, which is what lets {@code confirm} re-derive
 * exactly what {@code preview} showed.
 */
final class ImportAnalyzer {

  /** A row that will be stored, with the line it came from. */
  record Accepted(int line, Word word) {}

  record Analysis(
      ImportFormat format,
      String sha256,
      int totalRows,
      List<Accepted> accepted,
      int duplicateCount,
      List<DuplicateRow> duplicates,
      int invalidCount,
      List<RowError> errors,
      boolean truncated) {}

  private ImportAnalyzer() {}

  static Analysis analyze(ImportFormat format, byte[] bytes, Set<String> existingKeys) {
    List<RawRow> rows = ImportParser.parse(format, bytes);
    Set<String> seen = new HashSet<>();
    List<Accepted> accepted = new ArrayList<>();
    List<DuplicateRow> duplicates = new ArrayList<>();
    List<RowError> errors = new ArrayList<>();
    int duplicateCount = 0;
    int invalidCount = 0;
    boolean truncated = false;

    for (RawRow row : rows) {
      Checked checked =
          row.malformed()
              ? new Checked(null, RowProblem.MALFORMED_ROW)
              : WordRules.check(row.word(), row.category(), row.difficulty());
      if (checked.problem() != null) {
        invalidCount++;
        if (errors.size() < WordPackLimits.MAX_REPORTED_ISSUES) {
          errors.add(new RowError(row.line(), checked.problem(), checked.problem().message()));
        } else {
          truncated = true;
        }
        continue;
      }
      Word word = checked.word();
      boolean inPack = existingKeys.contains(word.key());
      if (inPack || !seen.add(word.key())) {
        duplicateCount++;
        if (duplicates.size() < WordPackLimits.MAX_REPORTED_ISSUES) {
          duplicates.add(new DuplicateRow(row.line(), inPack ? "PACK" : "FILE"));
        } else {
          truncated = true;
        }
        continue;
      }
      accepted.add(new Accepted(row.line(), word));
    }
    return new Analysis(
        format,
        sha256(bytes),
        rows.size(),
        accepted,
        duplicateCount,
        duplicates,
        invalidCount,
        errors,
        truncated);
  }

  static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // every JRE ships SHA-256
    }
  }
}
