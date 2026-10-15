package com.dinoparty.wordpacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.wordpacks.ImportAnalyzer.Analysis;
import com.dinoparty.wordpacks.WordPackTypes.DuplicateRow;
import com.dinoparty.wordpacks.WordPackTypes.ImportFormat;
import com.dinoparty.wordpacks.WordPackTypes.RowError;
import com.dinoparty.wordpacks.WordPackTypes.RowProblem;
import com.dinoparty.words.WordDifficulty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportAnalyzerTest {

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static Analysis txt(String content, String... existingKeys) {
    return ImportAnalyzer.analyze(ImportFormat.TXT, utf8(content), Set.of(existingKeys));
  }

  private static Analysis csv(String content, String... existingKeys) {
    return ImportAnalyzer.analyze(ImportFormat.CSV, utf8(content), Set.of(existingKeys));
  }

  private static List<String> texts(Analysis a) {
    return a.accepted().stream().map(x -> x.word().text()).toList();
  }

  private static void assertRejected(Runnable parse, String field) {
    assertThatThrownBy(parse::run)
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors()).extracting("field").containsExactly(field);
            });
  }

  // ---- TXT -------------------------------------------------------------------------------

  @Test
  void txtIgnoresBlankLinesTrimsAndReportsPhysicalLineNumbers() {
    Analysis a = txt("cat\r\n\r\n   \n  dog  \n \nbird\r\n");

    assertThat(texts(a)).containsExactly("cat", "dog", "bird");
    assertThat(a.accepted()).extracting("line").containsExactly(1, 4, 6);
    assertThat(a.totalRows()).isEqualTo(3);
    assertThat(a.invalidCount()).isZero();
  }

  @Test
  void txtHandlesBomAndOldMacLineEndings() {
    Analysis a = txt("﻿cat\rdog\rbird");
    assertThat(texts(a)).containsExactly("cat", "dog", "bird");
    assertThat(a.accepted()).extracting("line").containsExactly(1, 2, 3);
  }

  @Test
  void txtPreservesAccentsAndScripts() {
    Analysis a = txt("Café\nnaïve\n恐龍\nдинозавр\n");
    assertThat(texts(a)).containsExactly("Café", "naïve", "恐龍", "динозавр");
  }

  @Test
  void txtDeduplicatesCaseInsensitivelyWithinTheFileAndAgainstThePack() {
    String rex = WordRules.key("Rex");
    Analysis a = txt("Dino\ndino\nDINO\nCafe\nCafé\nRex\nTrike\n", rex);

    assertThat(texts(a)).containsExactly("Dino", "Cafe", "Café", "Trike");
    assertThat(a.duplicateCount()).isEqualTo(3);
    assertThat(a.duplicates())
        .containsExactly(
            new DuplicateRow(2, "FILE"), new DuplicateRow(3, "FILE"), new DuplicateRow(6, "PACK"));
  }

  @Test
  void txtReportsInvalidRowsWithLineNumbersAndNoContent() {
    String tooLong = "x".repeat(81);
    Analysis a = txt("ok\n<b>bold</b>\n" + tooLong + "\nfine\nbad\u0000row\n");

    assertThat(texts(a)).containsExactly("ok", "fine");
    assertThat(a.invalidCount()).isEqualTo(3);
    assertThat(a.errors())
        .extracting(RowError::line, RowError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(2, RowProblem.INVALID_CHARACTER),
            org.assertj.core.groups.Tuple.tuple(3, RowProblem.TOO_LONG),
            org.assertj.core.groups.Tuple.tuple(5, RowProblem.INVALID_CHARACTER));
    assertThat(a.errors()).allSatisfy(e -> assertThat(e.message()).doesNotContain("bold", "xxx"));
  }

  @Test
  void emptyAndBlankOnlyFilesAreRejected() {
    assertRejected(() -> txt(""), "file");
    assertRejected(() -> txt("\n\n   \r\n"), "file");
    assertRejected(() -> txt("﻿"), "file");
  }

  @Test
  void invalidUtf8IsRejected() {
    byte[] latin1 = "café\n".getBytes(StandardCharsets.ISO_8859_1);
    assertRejected(() -> ImportAnalyzer.analyze(ImportFormat.TXT, latin1, Set.of()), "file");
    byte[] truncated = {'a', '\n', (byte) 0xE2, (byte) 0x82};
    assertRejected(() -> ImportAnalyzer.analyze(ImportFormat.CSV, truncated, Set.of()), "file");
  }

  @Test
  void errorAndDuplicateListsAreBoundedButCountsAreExact() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 250; i++) {
      sb.append("<bad>\n");
    }
    for (int i = 0; i < 250; i++) {
      sb.append("same\n");
    }
    Analysis a = txt(sb.toString());
    assertThat(a.invalidCount()).isEqualTo(250);
    assertThat(a.errors()).hasSize(WordPackLimits.MAX_REPORTED_ISSUES);
    assertThat(a.duplicateCount()).isEqualTo(249);
    assertThat(a.duplicates()).hasSize(WordPackLimits.MAX_REPORTED_ISSUES);
    assertThat(a.truncated()).isTrue();
  }

  @Test
  void tooManyRowsAndTooManyBytesAreRejected() {
    String rows = "a\n".repeat(WordPackLimits.MAX_FILE_ROWS + 1);
    assertThatThrownBy(() -> txt(rows))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    byte[] big = new byte[WordPackLimits.MAX_FILE_BYTES + 1];
    java.util.Arrays.fill(big, (byte) 'a');
    assertThatThrownBy(() -> ImportAnalyzer.analyze(ImportFormat.TXT, big, Set.of()))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    // Exactly 1 MiB of text is accepted by the size check.
    byte[] max = new byte[WordPackLimits.MAX_FILE_BYTES];
    java.util.Arrays.fill(max, (byte) 'a');
    assertThat(ImportAnalyzer.analyze(ImportFormat.TXT, max, Set.of()).invalidCount()).isEqualTo(1);
  }

  @Test
  void sameBytesGiveTheSameDigestAndAnalysis() {
    Analysis a = txt("cat\ndog\n");
    Analysis b = txt("cat\ndog\n");
    assertThat(a.sha256()).isEqualTo(b.sha256()).hasSize(64);
    assertThat(txt("cat\ndog \n").sha256()).isNotEqualTo(a.sha256());
  }

  // ---- CSV -------------------------------------------------------------------------------

  @Test
  void csvReadsAllColumnsInAnyOrderWithBomAndCaseInsensitiveHeader() {
    Analysis a = csv("﻿Difficulty, WORD ,Category\nhard,Dragon,Fantasy\nEASY,Cat,Animals\n");

    assertThat(texts(a)).containsExactly("Dragon", "Cat");
    assertThat(a.accepted().get(0).word().difficulty()).isEqualTo(WordDifficulty.HARD);
    assertThat(a.accepted().get(0).word().category()).isEqualTo("Fantasy");
    assertThat(a.accepted().get(1).word().difficulty()).isEqualTo(WordDifficulty.EASY);
    assertThat(a.accepted()).extracting("line").containsExactly(2, 3);
  }

  @Test
  void csvHandlesQuotedFieldsCommasAndEscapedQuotes() {
    Analysis a = csv("word,category\n\"Hello, world\",\"Greet, ings\"\n\"say \"\"hi\"\"\",Talk\n");
    assertThat(texts(a)).containsExactly("Hello, world", "say \"hi\"");
    assertThat(a.accepted().get(0).word().category()).isEqualTo("Greet, ings");
  }

  @Test
  void csvWithOnlyAWordColumnUsesTheDefaults() {
    Analysis a = csv("word\ncat\ndog\n");
    assertThat(a.accepted())
        .allSatisfy(
            x -> {
              assertThat(x.word().category()).isEqualTo("Custom");
              assertThat(x.word().difficulty()).isEqualTo(WordDifficulty.MEDIUM);
            });
  }

  @Test
  void csvRowsMayOmitTrailingOptionalCellsAndIgnoreBlankRows() {
    Analysis a = csv("word,category,difficulty\ncat\n\n,,\ndog,Animals\nbird,,HARD\n");
    assertThat(texts(a)).containsExactly("cat", "dog", "bird");
    assertThat(a.accepted().get(2).word().difficulty()).isEqualTo(WordDifficulty.HARD);
    assertThat(a.invalidCount()).isZero();
    assertThat(a.totalRows()).isEqualTo(3);
  }

  @Test
  void csvReportsInvalidDifficultyBlankWordAndMalformedRows() {
    Analysis a =
        csv(
            "word,category,difficulty\ncat,A,easy\ndog,A,MIXED\n,Animals,EASY\nbird,A,EASY,extra\nok,A,\n");

    assertThat(texts(a)).containsExactly("cat", "ok");
    assertThat(a.errors())
        .extracting(RowError::line, RowError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(3, RowProblem.INVALID_DIFFICULTY),
            org.assertj.core.groups.Tuple.tuple(4, RowProblem.BLANK_WORD),
            org.assertj.core.groups.Tuple.tuple(5, RowProblem.MALFORMED_ROW));
  }

  @Test
  void csvDeduplicatesCaseInsensitively() {
    Analysis a = csv("word,difficulty\nDino,EASY\ndino,HARD\n\"DINO\",\n");
    assertThat(texts(a)).containsExactly("Dino");
    assertThat(a.duplicateCount()).isEqualTo(2);
    assertThat(a.accepted().get(0).word().difficulty()).isEqualTo(WordDifficulty.EASY);
  }

  @Test
  void csvRejectsBadHeaders() {
    assertRejected(() -> csv("category,difficulty\nA,EASY\n"), "file");
    assertRejected(() -> csv("word,colour\ncat,red\n"), "file");
    assertRejected(() -> csv("word,word\ncat,dog\n"), "file");
    assertRejected(() -> csv("word,\ncat,\n"), "file");
    assertRejected(() -> csv("cat\ndog\n"), "file"); // no header: first row is read as one
  }

  @Test
  void csvRejectsUnterminatedQuotesAndEmptyFiles() {
    assertRejected(() -> csv("word\n\"unterminated\ncat\n"), "file");
    assertRejected(() -> csv("word\n\"bad\"quote\n"), "file");
    assertRejected(() -> csv(""), "file");
    assertRejected(() -> csv("word\n"), "file"); // header only
    assertRejected(() -> csv("word,category\n\n\n"), "file");
  }

  @Test
  void csvLineNumberOfAMultiLineRecordIsItsLastLine() {
    Analysis a = csv("word\n\"two\nlines\"\ncat\n");
    assertThat(a.accepted()).extracting("line").containsExactly(3, 4);
    assertThat(texts(a)).containsExactly("two lines", "cat");
  }

  @Test
  void csvWithManyCellsOnOneRowIsStillBounded() {
    String row = "a,".repeat(50_000) + "a";
    Analysis a = csv("word\n" + row + "\n");
    assertThat(a.invalidCount()).isEqualTo(1);
    assertThat(a.errors().get(0).code()).isEqualTo(RowProblem.MALFORMED_ROW);
  }

  @Test
  void formatComesFromTheExtension() {
    assertThat(ImportParser.formatOf("Words.TXT")).isEqualTo(ImportFormat.TXT);
    assertThat(ImportParser.formatOf("a.b.csv")).isEqualTo(ImportFormat.CSV);
    for (String bad : new String[] {"words", "words.xlsx", "words.txt.exe", "", null}) {
      assertThatThrownBy(() -> ImportParser.formatOf(bad))
          .isInstanceOfSatisfying(
              DomainException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }
  }
}
