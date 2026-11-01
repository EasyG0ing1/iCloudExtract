package com.simtechdata.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simtechdata.admin.RunLog;
import com.simtechdata.admin.Settings;
import com.simtechdata.enums.OverwriteMode;
import com.simtechdata.enums.VerifyMode;
import com.simtechdata.files.FileSystemProbe;
import com.simtechdata.records.SizeReport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The estimate of {@code -getTotal} must equal what a real extraction writes. Each test builds zips, asks
 * {@link ZipSizer} for the total, then really extracts them (in order) and adds up the files on disk.
 */
class ZipSizerTest {

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

    private static Map<String, byte[]> entries(Object... pairs) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((String) pairs[i + 1]).getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private static long[] onDisk(Path dest) throws IOException {
        long bytes = 0;
        long files = 0;
        if (Files.isDirectory(dest)) {
            try (Stream<Path> walk = Files.walk(dest)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p)) {
                        bytes += Files.size(p);
                        files++;
                    }
                }
            }
        }
        return new long[] {bytes, files};
    }

    /** Estimates, then really extracts, and checks that both agree. Returns the estimate. */
    private static SizeReport estimateAndExtract(Path work, boolean consolidate, OverwriteMode overwrite,
                                                 List<Path> zips) throws Exception {
        Path dest = work.resolve("out");
        boolean caseInsensitive = FileSystemProbe.isCaseInsensitive(dest);
        SizeReport estimate = ZipSizer.measure(zips, consolidate, overwrite, caseInsensitive, 1, line -> { });
        SizeReport parallel = ZipSizer.measure(zips, consolidate, overwrite, caseInsensitive, 4, line -> { });
        assertEquals(estimate, parallel, "the number of threads must not change the result");
        assertFalse(Files.exists(dest), "-getTotal must not create the destination");

        try (RunLog log = new RunLog(work)) {
            ZipExtractor extractor = new ZipExtractor(
                    new Settings(dest, VerifyMode.NONE, consolidate, overwrite, false, 1), log);
            for (Path z : zips) {
                ZipReport r = extractor.extract(z);
                assertTrue(r.ok(), r.summary(false));
            }
        }
        long[] actual = onDisk(dest);
        assertEquals(actual[0], estimate.extractedBytes(), "bytes on disk");
        assertEquals(actual[1], estimate.extractedFiles(), "files on disk");
        assertEquals(estimate.entryBytes(), estimate.extractedBytes() + estimate.identicalBytes()
                + estimate.conflictBytes() + estimate.replacedBytes(), "every byte accounted for");
        return estimate;
    }

    @Test
    void zipsWithoutOverlapAddUp(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/a.txt", "aaaa", "Part 1/Drive/b.txt", "bb"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/c.txt", "cccccc"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(12, r.extractedBytes());
        assertEquals(3, r.extractedFiles());
        assertEquals(0, r.identicalSkipped());
    }

    @Test
    void anIdenticalFileInTwoZipsCountsOnceWhenConsolidating(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/same.txt", "0123456789", "Part 1/Drive/a.txt", "aa"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/same.txt", "0123456789", "Part 2/Drive/b.txt", "bbb"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(15, r.extractedBytes());
        assertEquals(1, r.identicalSkipped());
        assertEquals(10, r.identicalBytes());
    }

    @Test
    void withoutConsolidateEveryZipKeepsItsOwnCopy(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/same.txt", "0123456789"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/same.txt", "0123456789"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, false, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(20, r.extractedBytes());
        assertEquals(0, r.identicalSkipped());
    }

    @Test
    void aDifferentFileAtTheSamePathFollowsTheOverwriteMode(@TempDir Path work) throws Exception {
        for (OverwriteMode mode : OverwriteMode.values()) {
            Path dir = Files.createDirectory(work.resolve(mode.name()));
            Path z1 = zip(dir.resolve("Part 1.zip"), entries("Part 1/Drive/f.txt", "short"), 1_600_000_000_000L);
            Path z2 = zip(dir.resolve("Part 2.zip"), entries("Part 2/Drive/f.txt", "a much longer text"), 1_700_000_000_000L);
            SizeReport r = estimateAndExtract(dir, true, mode, List.of(z1, z2));
            // NONE keeps the first (5 bytes); ALWAYS and NEWEST (the second zip is newer) keep the second (18 bytes)
            assertEquals(mode == OverwriteMode.NONE ? 5 : 18, r.extractedBytes(), mode.name());
        }
    }

    @Test
    void newestKeepsTheExistingFileWhenTheLaterCopyIsOlder(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/f.txt", "the newer one"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/f.txt", "old"), 1_600_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NEWEST, List.of(z1, z2));
        assertEquals(13, r.extractedBytes());
        assertEquals(1, r.conflictsSkipped());
    }

    @Test
    void aFileAndAFolderWithTheSameNameAcrossZipsAreBothCounted(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/Doc.pages/preview.jpg", "jpg", "Part 1/Drive/Doc.pages/info.plist", "plist"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/Doc.pages", "the flat document"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(3 + 5 + 17, r.extractedBytes());
    }

    @Test
    void aFlatFileFollowedByItsChildrenInAnotherZipAreBothCounted(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/Doc.pages", "the flat document"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/Doc.pages/preview.jpg", "jpg"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(17 + 3, r.extractedBytes());
    }

    @Test
    void aFileAndFolderConflictInsideOneZipIsCounted(@TempDir Path work) throws Exception {
        Path z = zip(work.resolve("Part 1.zip"), entries(
                "Part 1/Drive/Untitled.pages", "flat document",
                "Part 1/Drive/Untitled.pages/preview.jpg", "jpg",
                "Part 1/Drive/Empty", "",
                "Part 1/Drive/Empty/inside.txt", "inside"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, false, OverwriteMode.NONE, List.of(z));
        assertEquals(13 + 3 + 6, r.extractedBytes());
    }

    @Test
    void nameCollisionsAreCountedBecauseBothFilesAreKept(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/Song.mp3", "first version"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/song.mp3", "second, different"), 1_700_000_000_000L);
        // On a case-insensitive volume the second is renamed and kept; on a case-sensitive one it is a separate file.
        // Either way both are on disk.
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        assertEquals(13 + 17, r.extractedBytes());
    }

    @Test
    void anIdenticalFileWithADifferentCaseCountsOnceOnlyWhereCaseIsIgnored(@TempDir Path work) throws Exception {
        Path z1 = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/Song.mp3", "same bytes"), 1_700_000_000_000L);
        Path z2 = zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/song.mp3", "same bytes"), 1_700_000_000_000L);
        SizeReport r = estimateAndExtract(work, true, OverwriteMode.NONE, List.of(z1, z2));
        boolean ignoresCase = FileSystemProbe.isCaseInsensitive(work.resolve("out"));
        assertEquals(ignoresCase ? 10 : 20, r.extractedBytes());
    }

    @Test
    void theEstimateReadsNothingBeyondTheTableOfContentsAndWritesNothing(@TempDir Path work) throws Exception {
        Path z = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/a.txt", "abc"), 1_700_000_000_000L);
        long before;
        try (Stream<Path> s = Files.list(work)) {
            before = s.count();
        }
        SizeReport r = ZipSizer.measure(List.of(z), true, OverwriteMode.NONE, true, 1, line -> { });
        long after;
        try (Stream<Path> s = Files.list(work)) {
            after = s.count();
        }
        assertEquals(before, after);
        assertEquals(3, r.extractedBytes());
        assertEquals(1, r.zipsRead());
    }

    @Test
    void anUnreadableZipIsReportedAndTheRestStillCounted(@TempDir Path work) throws Exception {
        Path good = zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/a.txt", "abc"), 1_700_000_000_000L);
        Path bad = Files.writeString(work.resolve("Part 2.zip"), "this is not a zip file");
        for (int threads : new int[] {1, 2}) {
            SizeReport r = ZipSizer.measure(List.of(good, bad), true, OverwriteMode.NONE, true, threads, line -> { });
            assertEquals(3, r.extractedBytes());
            assertEquals(1, r.zipsRead());
            assertEquals(1, r.unreadableZips().size());
            assertTrue(r.unreadableZips().getFirst().startsWith("Part 2.zip"));
        }
    }

    @Test
    void zipsReadAtTheSameTimeAreStillCountedInExtractionOrder(@TempDir Path work) throws Exception {
        // Part 1 is the biggest, so the small later zips are likely to finish reading first. With overwrite off the
        // first copy of shared.txt (from Part 1) must win no matter which thread finishes first.
        Map<String, byte[]> big = new LinkedHashMap<>();
        for (int i = 0; i < 3000; i++) {
            big.put("Part 1/Drive/file" + i + ".txt", ("content of file " + i).getBytes(StandardCharsets.UTF_8));
        }
        big.put("Part 1/Drive/shared.txt", "the first and biggest version of the shared file".getBytes(StandardCharsets.UTF_8));
        List<Path> zips = new java.util.ArrayList<>();
        zips.add(zip(work.resolve("Part 1.zip"), big, 1_600_000_000_000L));
        for (int n = 2; n <= 9; n++) {
            zips.add(zip(work.resolve("Part " + n + ".zip"),
                    entries("Part " + n + "/Drive/shared.txt", "v" + n, "Part " + n + "/Drive/own" + n + ".txt", "x".repeat(n)),
                    1_700_000_000_000L + n * 1000L));
        }
        for (OverwriteMode mode : OverwriteMode.values()) {
            SizeReport one = ZipSizer.measure(zips, true, mode, true, 1, line -> { });
            for (int round = 0; round < 5; round++) {
                SizeReport many = ZipSizer.measure(zips, true, mode, true, 8, line -> { });
                assertEquals(one, many, "overwrite " + mode + ", round " + round);
            }
        }
        SizeReport none = ZipSizer.measure(zips, true, OverwriteMode.NONE, true, 8, line -> { });
        long ownFiles = 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9;
        long bigBytes = big.values().stream().mapToLong(b -> b.length).sum();
        assertEquals(bigBytes + ownFiles, none.extractedBytes());
        assertEquals(8, none.conflictsSkipped());
    }

    @Test
    void progressIsReportedWhenAZipStartsAndWhenItFinishes(@TempDir Path work) throws Exception {
        List<Path> zips = List.of(
                zip(work.resolve("Part 1.zip"), entries("Part 1/Drive/a.txt", "a"), 1_700_000_000_000L),
                zip(work.resolve("Part 2.zip"), entries("Part 2/Drive/b.txt", "b"), 1_700_000_000_000L),
                zip(work.resolve("Part 3.zip"), entries("Part 3/Drive/c.txt", "c"), 1_700_000_000_000L));
        List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ZipSizer.measure(zips, true, OverwriteMode.NONE, true, 3, lines::add);
        assertEquals(6, lines.size(), lines.toString());
        for (int n = 1; n <= 3; n++) {
            String name = "Part " + n + ".zip";
            int started = -1;
            int finished = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("Reading " + name)) {
                    started = i;
                } else if (lines.get(i).contains("Finished " + name)) {
                    finished = i;
                }
            }
            assertTrue(started >= 0, "no start line for " + name + ": " + lines);
            assertTrue(finished > started, "the finish line must come after the start line for " + name + ": " + lines);
        }
    }
}
