package com.icloudextract.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icloudextract.files.OverwriteMode;
import com.icloudextract.files.RunLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionTest {

    private static Path zip(Path file, Map<String, byte[]> entries) throws IOException {
        return zip(file, entries, 1_700_000_000_000L);
    }

    /** Names are written as Latin-1 so a char above 0x7F becomes one raw byte (invalid UTF-8 on its own). */
    private static Path zip(Path file, Map<String, byte[]> entries, long time) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file);
             ZipOutputStream zos = new ZipOutputStream(raw, StandardCharsets.ISO_8859_1)) {
            for (var e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(time);
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return file;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Settings settings(Path dest, boolean consolidate, OverwriteMode overwrite,
                                     Settings.VerifyMode verify) {
        return new Settings(dest, verify, consolidate, overwrite, false, 1);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    @Test
    void fileAndFolderWithTheSameNameAreBothKept(@TempDir Path work) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Part 1/Drive/Untitled.pages", bytes("flat document"));
        entries.put("Part 1/Drive/Untitled.pages/Metadata/Properties.plist", bytes("props"));
        entries.put("Part 1/Drive/Untitled.pages/preview.jpg", bytes("jpg"));
        entries.put("Part 1/Drive/Empty", new byte[0]);
        entries.put("Part 1/Drive/Empty/inside.txt", bytes("inside"));
        entries.put("Part 1/Drive/plain.txt", bytes("plain"));
        Path z = zip(work.resolve("Part 1.zip"), entries);
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            ZipReport r = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.CRC), log).extract(z);

            assertTrue(r.ok(), r.summary(true));
            assertEquals(0, r.failed);
            assertEquals(1, r.placeholders);
            assertEquals(1, r.folderRedirects);
            assertEquals(5, r.extracted);
            assertEquals(5, r.verified);
        }

        Path drive = dest.resolve("Part 1/Drive");
        assertEquals("flat document", read(drive.resolve("Untitled.pages")));
        assertEquals("props", read(drive.resolve("Untitled.pages-folder/Metadata/Properties.plist")));
        assertEquals("jpg", read(drive.resolve("Untitled.pages-folder/preview.jpg")));
        assertTrue(Files.isDirectory(drive.resolve("Empty")));
        assertEquals("inside", read(drive.resolve("Empty/inside.txt")));
        assertEquals("plain", read(drive.resolve("plain.txt")));
        assertTrue(Files.exists(work.resolve(RunLog.CONFLICT_LOG_NAME)));
    }

    @Test
    void namesThatAreNotUtf8AreReadAsWindows1252(@TempDir Path work) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Part 1/café.txt", bytes("latin1 name"));
        entries.put("Part 1/ok.txt", bytes("ok"));
        Path z = zip(work.resolve("Part 1.zip"), entries);
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            ZipReport r = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.SIZE), log).extract(z);
            assertTrue(r.ok(), r.summary(true));
        }
        assertEquals("latin1 name", read(dest.resolve("Part 1/caf\u00e9.txt")));
        assertEquals("ok", read(dest.resolve("Part 1/ok.txt")));
    }

    @Test
    void consolidateMergesZipsAndHandlesExistingFiles(@TempDir Path work) throws Exception {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("Part 1/Drive/shared/same.txt", bytes("same"));
        first.put("Part 1/Drive/shared/differs.txt", bytes("old"));
        first.put("Part 1/Drive/only1.txt", bytes("one"));
        Map<String, byte[]> second = new LinkedHashMap<>();
        second.put("Part 2/Drive/shared/same.txt", bytes("same"));
        second.put("Part 2/Drive/shared/differs.txt", bytes("new!"));
        second.put("Part 2/Drive/only2.txt", bytes("two"));
        Path z1 = zip(work.resolve("Part 1.zip"), first, 1_600_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), second, 1_700_000_000_000L);
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            var noOverwrite = new ZipExtractor(settings(dest, true, OverwriteMode.NONE,
                    Settings.VerifyMode.NONE), log);
            noOverwrite.extract(z1);
            ZipReport r2 = noOverwrite.extract(z2);

            assertEquals(1, r2.skippedIdentical);
            assertEquals(1, r2.skippedConflict);
            assertEquals(1, r2.extracted);
        }
        // The mirror folders are gone and both zips share one tree.
        assertEquals("one", read(dest.resolve("Drive/only1.txt")));
        assertEquals("two", read(dest.resolve("Drive/only2.txt")));
        assertEquals("old", read(dest.resolve("Drive/shared/differs.txt")));
        assertFalse(Files.exists(dest.resolve("Part 1")));

        String conflicts = read(work.resolve(RunLog.CONFLICT_LOG_NAME));
        assertTrue(conflicts.contains("skipped, identical to the copy already extracted from Part 1.zip"), conflicts);
        assertTrue(conflicts.contains("skipped, a different file already exists"), conflicts);

        try (RunLog log = new RunLog(work)) {
            ZipReport r = new ZipExtractor(settings(dest, true, OverwriteMode.ALWAYS,
                    Settings.VerifyMode.CRC), log).extract(z2);
            assertEquals(1, r.overwritten);
            // same.txt and only2.txt were both written by the earlier pass and are byte-identical
            assertEquals(2, r.skippedIdentical);
            assertTrue(r.ok(), r.summary(true));
        }
        assertEquals("new!", read(dest.resolve("Drive/shared/differs.txt")));
        assertTrue(read(work.resolve(RunLog.CONFLICT_LOG_NAME)).contains("overwritten"));
    }

    @Test
    void overwriteNewestOnlyReplacesOlderFiles(@TempDir Path work) throws Exception {
        Path dest = work.resolve("out");
        Files.createDirectories(dest.resolve("Drive"));
        Path existing = dest.resolve("Drive/f.txt");
        Files.writeString(existing, "existing");
        Files.setLastModifiedTime(existing, FileTime.fromMillis(1_650_000_000_000L));

        Path older = zip(work.resolve("a.zip"), Map.of("a/Drive/f.txt", bytes("OLDER")), 1_600_000_000_000L);
        Path newer = zip(work.resolve("b.zip"), Map.of("b/Drive/f.txt", bytes("NEWER")), 1_700_000_000_000L);

        try (RunLog log = new RunLog(work)) {
            var extractor = new ZipExtractor(settings(dest, true, OverwriteMode.NEWEST,
                    Settings.VerifyMode.NONE), log);
            assertEquals(1, extractor.extract(older).skippedConflict);
            assertEquals("existing", read(existing));
            assertEquals(1, extractor.extract(newer).overwritten);
            assertEquals("NEWER", read(existing));
        }
    }

    @Test
    void anExistingFolderIsMovedAsideWhenALaterZipHasAFileOfThatName(@TempDir Path work) throws Exception {
        Path childrenOnly = zip(work.resolve("a.zip"), Map.of("a/Drive/Doc.pages/preview.jpg", bytes("jpg")));
        Path flatFile = zip(work.resolve("b.zip"), Map.of("b/Drive/Doc.pages", bytes("flat")));
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            var extractor = new ZipExtractor(settings(dest, true, OverwriteMode.NONE,
                    Settings.VerifyMode.NONE), log);
            assertTrue(extractor.extract(childrenOnly).ok());
            assertTrue(extractor.extract(flatFile).ok());
        }
        assertEquals("flat", read(dest.resolve("Drive/Doc.pages")));
        assertEquals("jpg", read(dest.resolve("Drive/Doc.pages-folder/preview.jpg")));
    }

    @Test
    void aFileAlreadyInTheWayRedirectsLaterChildrenToTheFolderSuffix(@TempDir Path work) throws Exception {
        Path flatFile = zip(work.resolve("a.zip"), Map.of("a/Drive/Doc.pages", bytes("flat")));
        Path childrenOnly = zip(work.resolve("b.zip"), Map.of("b/Drive/Doc.pages/preview.jpg", bytes("jpg")));
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            var extractor = new ZipExtractor(settings(dest, true, OverwriteMode.NONE,
                    Settings.VerifyMode.NONE), log);
            assertTrue(extractor.extract(flatFile).ok());
            assertTrue(extractor.extract(childrenOnly).ok());
        }
        assertEquals("flat", read(dest.resolve("Drive/Doc.pages")));
        assertEquals("jpg", read(dest.resolve("Drive/Doc.pages-folder/preview.jpg")));
    }

    @Test
    void checkZipFlagsACorruptedArchiveAndExtractsNothing(@TempDir Path work) throws Exception {
        Path z = zip(work.resolve("Part 1.zip"), Map.of("Part 1/a.txt", bytes("hello world, hello world")));
        // Flip a byte inside the (stored/deflated) data so the CRC no longer matches.
        byte[] data = Files.readAllBytes(z);
        int offset = 30 + "Part 1/a.txt".length() + 3;
        data[offset] ^= 0x55;
        Files.write(z, data);
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            var withCheck = new Settings(dest, Settings.VerifyMode.NONE, false, OverwriteMode.NONE, true, 1);
            ZipReport r = new ZipExtractor(withCheck, log).extract(z);
            assertTrue(r.integrityFailed);
            assertFalse(r.ok());
            assertFalse(Files.exists(dest.resolve("Part 1/a.txt")));
        }
    }

    /** Writes a zip in which two entries really have the same name (ZipOutputStream refuses to do that). */
    private static Path zipWithDuplicateName(Path file, byte[] first, byte[] second) throws IOException {
        zip(file, new LinkedHashMap<>(Map.of("z/dup-a.txt", first)));
        Path tmp = file.resolveSibling("tmp-" + file.getFileName());
        Map<String, byte[]> both = new LinkedHashMap<>();
        both.put("z/dup-a.txt", first);
        both.put("z/dup-b.txt", second);
        zip(tmp, both);
        byte[] data = Files.readAllBytes(tmp);
        byte[] from = "dup-b".getBytes(StandardCharsets.US_ASCII);
        byte[] to = "dup-a".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + from.length <= data.length; i++) {
            boolean match = true;
            for (int k = 0; k < from.length && match; k++) {
                match = data[i + k] == from[k];
            }
            if (match) {
                System.arraycopy(to, 0, data, i, to.length);
            }
        }
        Files.delete(tmp);
        Files.write(file, data);
        return file;
    }

    @Test
    void twoEntriesWithTheSameNameAreBothKept(@TempDir Path work) throws Exception {
        Path z = zipWithDuplicateName(work.resolve("Part 1.zip"), bytes("first"), bytes("second!"));
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            ZipReport r = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.CRC), log).extract(z);
            assertTrue(r.ok(), r.summary(true));
            assertEquals(2, r.extracted);
            assertEquals(1, r.renamed);
            assertEquals(0, r.skippedConflict);
        }
        assertEquals("first", read(dest.resolve("z/dup-a.txt")));
        assertEquals("second!", read(dest.resolve("z/dup-a-1.txt")));
        assertTrue(read(work.resolve(RunLog.CONFLICT_LOG_NAME)).contains("name collision"));

        // Running again must not create dup-a-2.txt: both copies are already there and identical.
        try (RunLog log = new RunLog(work)) {
            ZipReport again = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.CRC), log).extract(z);
            assertTrue(again.ok(), again.summary(true));
            assertEquals(2, again.skippedIdentical);
            assertEquals(0, again.renamed);
        }
        assertFalse(Files.exists(dest.resolve("z/dup-a-2.txt")));
    }

    @Test
    void namesThatDifferOnlyByCaseAreBothKeptOnACaseInsensitiveVolume(@TempDir Path work) throws Exception {
        Path probe = Files.writeString(work.resolve("probe"), "x");
        Assumptions.assumeTrue(Files.exists(work.resolve("PROBE")), "needs a case-insensitive file system");
        Files.delete(probe);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("z/WWF - Kurt Angle.gcode", bytes("one"));
        entries.put("z/Wwf - Kurt Angle.gcode", bytes("two"));
        Path z = zip(work.resolve("Part 1.zip"), entries);
        Path dest = work.resolve("out");

        try (RunLog log = new RunLog(work)) {
            var extractor = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.CRC), log);
            ZipReport r = extractor.extract(z);
            assertTrue(r.ok(), r.summary(true));
            assertEquals(1, r.renamed);
            ZipReport again = extractor.extract(z);
            assertEquals(2, again.skippedIdentical);
            assertEquals(0, again.renamed);
        }
        assertEquals("one", read(dest.resolve("z/WWF - Kurt Angle.gcode")));
        assertEquals("two", read(dest.resolve("z/Wwf - Kurt Angle-1.gcode")));
    }

    @Test
    void filesAlreadyOnDiskAreSkippedSilentlyWithoutReadingThem(@TempDir Path work) throws Exception {
        Path z = zip(work.resolve("Part 1.zip"), Map.of("Part 1/Drive/a.txt", bytes("abc")));
        Path dest = work.resolve("out");
        Path logs1 = Files.createDirectories(work.resolve("logs1"));
        Path logs2 = Files.createDirectories(work.resolve("logs2"));
        try (RunLog log = new RunLog(logs1)) {
            new ZipExtractor(settings(dest, false, OverwriteMode.NONE, Settings.VerifyMode.NONE), log)
                    .extract(z);
        }
        // Same name and size, different bytes: trusted on name and size alone, so never read, never logged.
        Files.writeString(dest.resolve("Part 1/Drive/a.txt"), "xyz");
        try (RunLog log = new RunLog(logs2)) {
            ZipReport r = new ZipExtractor(settings(dest, false, OverwriteMode.NONE,
                    Settings.VerifyMode.NONE), log).extract(z);
            assertEquals(1, r.skippedIdentical);
        }
        assertEquals("xyz", read(dest.resolve("Part 1/Drive/a.txt")));
        Path conflictLog = logs2.resolve(RunLog.CONFLICT_LOG_NAME);
        assertTrue(!Files.exists(conflictLog) || !read(conflictLog).contains("skipped"));
    }
}
