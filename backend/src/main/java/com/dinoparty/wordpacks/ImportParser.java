package com.dinoparty.wordpacks;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.wordpacks.WordPackTypes.ImportFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Turns an uploaded TXT or CSV file into raw rows (api-contracts §2.5). It only splits the file;
 * {@link WordRules} judges each cell. A file that cannot be read as the format at all (bad UTF-8,
 * bad header, broken quoting, no rows) is rejected as a whole with {@code VALIDATION_FAILED} on
 * {@code file}; the file size and row count are bounded so a hostile upload cannot grow memory.
 */
final class ImportParser {

  /** One non-blank row. {@code malformed} rows (too many CSV cells) carry no cells. */
  record RawRow(int line, String word, String category, String difficulty, boolean malformed) {}

  private static final String FILE_FIELD = "file";
  private static final Set<String> COLUMNS = Set.of("word", "category", "difficulty");
  private static final CSVFormat CSV =
      CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).setTrim(false).get();

  private ImportParser() {}

  static ImportFormat formatOf(String filename) {
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".txt")) {
      return ImportFormat.TXT;
    }
    if (name.endsWith(".csv")) {
      return ImportFormat.CSV;
    }
    throw new DomainException(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only .txt and .csv files can be imported.");
  }

  static List<RawRow> parse(ImportFormat format, byte[] bytes) {
    if (bytes.length > WordPackLimits.MAX_FILE_BYTES) {
      throw tooLarge("The file is larger than 1 MiB.");
    }
    String text = decode(bytes);
    List<RawRow> rows = format == ImportFormat.TXT ? parseTxt(text) : parseCsv(text);
    if (rows.isEmpty()) {
      throw DomainException.invalid(FILE_FIELD, "contains no words");
    }
    return rows;
  }

  private static String decode(byte[] bytes) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw DomainException.invalid(FILE_FIELD, "must be UTF-8 text");
    }
    return text.startsWith("﻿") ? text.substring(1) : text;
  }

  private static List<RawRow> parseTxt(String text) {
    List<RawRow> rows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
      String line;
      int number = 0;
      while ((line = reader.readLine()) != null) {
        number++;
        if (isBlank(line)) {
          continue;
        }
        addRow(rows, new RawRow(number, line, null, null, false));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e); // reading a String cannot fail
    }
    return rows;
  }

  private static List<RawRow> parseCsv(String text) {
    List<RawRow> rows = new ArrayList<>();
    try (CSVParser parser = CSV.parse(new StringReader(text))) {
      Map<String, Integer> columns = null;
      int width = 0;
      for (CSVRecord record : parser) {
        int line = (int) parser.getCurrentLineNumber();
        if (columns == null) {
          columns = header(record);
          width = record.size();
          continue;
        }
        if (allBlank(record)) {
          continue;
        }
        if (record.size() > width) {
          addRow(rows, new RawRow(line, null, null, null, true));
          continue;
        }
        addRow(
            rows,
            new RawRow(
                line,
                cell(record, columns.get("word")),
                cell(record, columns.get("category")),
                cell(record, columns.get("difficulty")),
                false));
      }
    } catch (UncheckedIOException | IOException | IllegalStateException e) {
      // Broken quoting. The message of the parser may quote the content, so it is not passed on.
      throw DomainException.invalid(FILE_FIELD, "is not valid CSV (check the quotes)");
    }
    return rows;
  }

  private static Map<String, Integer> header(CSVRecord record) {
    Map<String, Integer> columns = new HashMap<>();
    for (int i = 0; i < record.size(); i++) {
      String name = record.get(i).strip().toLowerCase(Locale.ROOT);
      if (!COLUMNS.contains(name) || columns.put(name, i) != null) {
        throw DomainException.invalid(
            FILE_FIELD,
            "header must be made of the columns word, category and difficulty once each");
      }
    }
    if (!columns.containsKey("word")) {
      throw DomainException.invalid(FILE_FIELD, "header must contain a word column");
    }
    return columns;
  }

  private static String cell(CSVRecord record, Integer index) {
    return index == null || index >= record.size() ? null : record.get(index);
  }

  private static boolean allBlank(CSVRecord record) {
    for (String value : record) {
      if (!isBlank(value)) {
        return false;
      }
    }
    return true;
  }

  private static void addRow(List<RawRow> rows, RawRow row) {
    if (rows.size() >= WordPackLimits.MAX_FILE_ROWS) {
      throw tooLarge("The file has more than 10000 rows.");
    }
    rows.add(row);
  }

  private static boolean isBlank(String s) {
    return s.codePoints().allMatch(WordRules::isSpace);
  }

  private static DomainException tooLarge(String detail) {
    return new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, detail);
  }
}
