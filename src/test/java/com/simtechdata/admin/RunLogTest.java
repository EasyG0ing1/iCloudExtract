package com.simtechdata.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simtechdata.enums.LogAction;
import com.simtechdata.enums.LogReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLogTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 11, 1, 5, 45, 12);

    /** A small RFC 4180 reader: returns the rows of fields. */
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        return rows;
    }

    @Test
    void theFileIsNamedAfterTheStartTimeAndCreatedOnFirstUse(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            assertEquals("iCloudExtract-2026-11-01_05-45-12.csv", log.file().getFileName().toString());
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(0, files.count(), "nothing is created before the first line");
            }
            log.info("", "hello");
        }
        assertTrue(Files.exists(dir.resolve("iCloudExtract-2026-11-01_05-45-12.csv")));
    }

    @Test
    void thereIsOneFileNotTwo(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.info("", "run note");
            log.record("Part 1.zip", LogReason.DIFFERENT_FILE, LogAction.SKIPPED, "Drive/a.txt", "a conflict");
            log.record("Part 1.zip", LogReason.ERROR, LogAction.FAILED, "Drive/b.txt", "an error");
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void theFirstLineIsTheHeaderAndEveryLineHasSixColumns(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.info("", "Run started");
            log.record("Part 1.zip", LogReason.IDENTICAL_COPY, LogAction.SKIPPED, "Drive/a.txt", "Identical to Part 0.zip");
            log.warn("Part 1.zip", "careful");
            log.record("Part 2.zip", LogReason.ERROR, LogAction.FAILED, "Drive/b.txt", "boom");
        }
        String text = Files.readString(dir.resolve("iCloudExtract-2026-11-01_05-45-12.csv"));
        assertTrue(text.startsWith("date/time,ZipFile,Reason,Action,File(Path),Discussion\n"), text);
        List<List<String>> rows = parse(text);
        assertEquals(5, rows.size());
        for (List<String> row : rows) {
            assertEquals(6, row.size(), row.toString());
        }
        List<String> skipped = rows.get(2);
        assertTrue(skipped.get(0).matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"), skipped.toString());
        assertEquals("Part 1.zip", skipped.get(1));
        assertEquals("Identical copy", skipped.get(2));
        assertEquals("Skipped", skipped.get(3));
        assertEquals("Drive/a.txt", skipped.get(4));
        assertEquals("Identical to Part 0.zip", skipped.get(5));
        assertEquals("", rows.get(1).get(1), "a note about the whole run has no zip file");
    }

    @Test
    void theFirstLineHoldsTheColumnTitlesSoASpreadsheetShowsThemAsHeadings(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.info("", "anything");
        }
        String firstLine = Files.readAllLines(log(dir)).getFirst();
        assertEquals("date/time,ZipFile,Reason,Action,File(Path),Discussion", firstLine);
        assertEquals(List.of("date/time", "ZipFile", "Reason", "Action", "File(Path)", "Discussion"),
                parse(firstLine + "\n").getFirst());
        assertTrue(log(dir).getFileName().toString().endsWith(".csv"));
    }

    @Test
    void commasQuotesAndLineBreaksSurviveARoundTrip(@TempDir Path dir) throws Exception {
        String path = "Drive/Smith, John \"Johnny\"/notes\nline two.txt";
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.record("Part 1.zip", LogReason.NAME_COLLISION, LogAction.RENAMED, path, "Written as 'a, b'");
        }
        List<List<String>> rows = parse(Files.readString(log(dir)));
        assertEquals(2, rows.size());
        assertEquals(6, rows.get(1).size());
        assertEquals(path, rows.get(1).get(4));
        assertEquals("Written as 'a, b'", rows.get(1).get(5));
    }

    @Test
    void aFieldThatLooksLikeAFormulaIsDefusedForSpreadsheets() {
        assertEquals("'=HYPERLINK(\"x\")".replace("\"", "\"\""), RunLog.csv("=HYPERLINK(\"x\")").replaceAll("^\"|\"$", ""));
        assertEquals("'+1", RunLog.csv("+1"));
        assertEquals("'-2", RunLog.csv("-2"));
        assertEquals("'@SUM(A1)", RunLog.csv("@SUM(A1)"));
        assertEquals("Drive/=ok", RunLog.csv("Drive/=ok"));
        assertEquals("", RunLog.csv(null));
        assertEquals("", RunLog.csv(""));
    }

    @Test
    void anotherRunInTheSameSecondAppendsWithoutASecondHeader(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.info("", "first");
        }
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.info("", "second");
        }
        String text = Files.readString(log(dir));
        assertEquals(1, text.split("date/time,ZipFile", -1).length - 1, text);
        assertEquals(3, parse(text).size());
    }

    @Test
    void nonAsciiNamesAreKept(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            log.record("Michael’s Drive.zip", LogReason.ERROR, LogAction.FAILED, "Drive/café – 日本語.txt", "failed");
        }
        List<List<String>> rows = parse(Files.readString(log(dir)));
        assertEquals("Michael’s Drive.zip", rows.get(1).get(1));
        assertEquals("Drive/café – 日本語.txt", rows.get(1).get(4));
    }

    @Test
    void severalThreadsMayWriteAtTheSameTime(@TempDir Path dir) throws Exception {
        try (RunLog log = new RunLog(dir, STARTED)) {
            Thread[] threads = new Thread[8];
            for (int t = 0; t < threads.length; t++) {
                final int number = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 200; i++) {
                        log.record("Part " + number + ".zip", LogReason.IDENTICAL_COPY, LogAction.SKIPPED, "f" + i, "x");
                    }
                });
                threads[t].start();
            }
            for (Thread t : threads) {
                t.join();
            }
        }
        List<List<String>> rows = parse(Files.readString(log(dir)));
        assertEquals(1 + 8 * 200, rows.size());
        assertFalse(rows.stream().anyMatch(r -> r.size() != 6));
    }

    private static Path log(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".csv")).findFirst().orElseThrow();
        }
    }
}
