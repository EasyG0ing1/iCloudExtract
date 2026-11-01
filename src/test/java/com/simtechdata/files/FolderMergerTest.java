package com.simtechdata.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.simtechdata.admin.RunLog;
import com.simtechdata.enums.OverwriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderMergerTest {

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void zipNamedFoldersAreMovedUpAndMerged(@TempDir Path dest) throws Exception {
        write(dest.resolve("Part 1/Drive/Documents/a.txt"), "a");
        write(dest.resolve("Part 2/Drive/Documents/b.txt"), "b");
        write(dest.resolve("Part 2/Drive/Desktop/c.txt"), "c");

        try (RunLog log = new RunLog(dest.resolveSibling("logs-" + dest.getFileName()))) {
            Files.createDirectories(dest.resolveSibling("logs-" + dest.getFileName()));
            var stats = new FolderMerger.Stats();
            for (String name : new String[] {"Part 1", "Part 2"}) {
                new FolderMerger(dest, log, name, OverwriteMode.NONE).mergeContents(dest.resolve(name), dest, stats);
            }
            assertEquals(0, stats.conflictsLeft);
        }

        assertEquals("a", Files.readString(dest.resolve("Drive/Documents/a.txt")));
        assertEquals("b", Files.readString(dest.resolve("Drive/Documents/b.txt")));
        assertEquals("c", Files.readString(dest.resolve("Drive/Desktop/c.txt")));
        assertFalse(Files.exists(dest.resolve("Part 1")));
        assertFalse(Files.exists(dest.resolve("Part 2")));
    }

    @Test
    void identicalSourceFilesAreRemovedAndDifferentOnesLeftInPlace(@TempDir Path dest) throws Exception {
        write(dest.resolve("Part 1/Drive/same.txt"), "same");
        write(dest.resolve("Part 2/Drive/same.txt"), "same");
        write(dest.resolve("Part 1/Drive/differs.txt"), "old");
        write(dest.resolve("Part 2/Drive/differs.txt"), "new!");

        Path logs = Files.createDirectories(dest.resolveSibling("logs2-" + dest.getFileName()));
        try (RunLog log = new RunLog(logs)) {
            var stats = new FolderMerger.Stats();
            for (String name : new String[] {"Part 1", "Part 2"}) {
                new FolderMerger(dest, log, name, OverwriteMode.NONE).mergeContents(dest.resolve(name), dest, stats);
            }
            assertEquals(1, stats.duplicatesRemoved);
            assertEquals(1, stats.conflictsLeft);
        }

        assertEquals("old", Files.readString(dest.resolve("Drive/differs.txt")));
        assertEquals("new!", Files.readString(dest.resolve("Part 2/Drive/differs.txt")));
        assertFalse(Files.exists(dest.resolve("Part 2/Drive/same.txt")));
        assertEquals("same", Files.readString(dest.resolve("Drive/same.txt")));
        assertFalse(Files.readString(logs.resolve(RunLog.CONFLICT_LOG_NAME)).contains("identical"));
    }

    private static FolderMerger.Stats mergePart2(Path dest, OverwriteMode mode) throws Exception {
        Path logs = Files.createDirectories(dest.resolveSibling("logs4-" + dest.getFileName()));
        var stats = new FolderMerger.Stats();
        try (RunLog log = new RunLog(logs)) {
            new FolderMerger(dest, log, "Part 2", mode).mergeContents(dest.resolve("Part 2"), dest, stats);
        }
        return stats;
    }

    @Test
    void overwriteReplacesAndNewestOnlyReplacesOlder(@TempDir Path dest) throws Exception {
        write(dest.resolve("Drive/f.txt"), "existing");
        write(dest.resolve("Part 2/Drive/f.txt"), "older source");
        Files.setLastModifiedTime(dest.resolve("Drive/f.txt"),
                java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(dest.resolve("Part 2/Drive/f.txt"),
                java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L));

        var newest = mergePart2(dest, OverwriteMode.NEWEST);
        assertEquals(0, newest.overwritten);
        assertEquals(1, newest.conflictsLeft);
        assertEquals("existing", Files.readString(dest.resolve("Drive/f.txt")));

        var always = mergePart2(dest, OverwriteMode.ALWAYS);
        assertEquals(1, always.overwritten);
        assertEquals("older source", Files.readString(dest.resolve("Drive/f.txt")));
        assertFalse(Files.exists(dest.resolve("Part 2")));
    }

    @Test
    void aFileAndAFolderWithTheSameNameBothSurvive(@TempDir Path dest) throws Exception {
        write(dest.resolve("Part 1/Drive/Doc.pages"), "flat document");
        write(dest.resolve("Part 2/Drive/Doc.pages/preview.jpg"), "jpg");

        Path logs = Files.createDirectories(dest.resolveSibling("logs3-" + dest.getFileName()));
        try (RunLog log = new RunLog(logs)) {
            var stats = new FolderMerger.Stats();
            for (String name : new String[] {"Part 1", "Part 2"}) {
                new FolderMerger(dest, log, name, OverwriteMode.NONE).mergeContents(dest.resolve(name), dest, stats);
            }
            assertEquals(1, stats.redirectedToFolderSuffix);
        }

        assertTrue(Files.isRegularFile(dest.resolve("Drive/Doc.pages")));
        assertEquals("jpg", Files.readString(dest.resolve("Drive/Doc.pages-folder/preview.jpg")));
    }
}
