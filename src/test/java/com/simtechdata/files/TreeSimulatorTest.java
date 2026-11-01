package com.simtechdata.files;

import static com.simtechdata.files.TreeSimulator.Result.NEW;
import static com.simtechdata.files.TreeSimulator.Result.OVERWRITTEN;
import static com.simtechdata.files.TreeSimulator.Result.RENAMED;
import static com.simtechdata.files.TreeSimulator.Result.SKIPPED_CONFLICT;
import static com.simtechdata.files.TreeSimulator.Result.SKIPPED_IDENTICAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.simtechdata.enums.OverwriteMode;

import java.util.List;

import org.junit.jupiter.api.Test;

class TreeSimulatorTest {

    private static List<String> path(String p) {
        return List.of(p.split("/"));
    }

    @Test
    void newFilesAreAddedUp() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        assertEquals(NEW, sim.add(path("a/one.txt"), 100, 1, 1000));
        assertEquals(NEW, sim.add(path("a/two.txt"), 250, 2, 1000));
        assertEquals(2, sim.fileCount());
        assertEquals(350, sim.totalBytes());
    }

    @Test
    void anIdenticalCopyInALaterZipCountsOnce() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("a/one.txt"), 100, 7, 1000);
        sim.startZip();
        assertEquals(SKIPPED_IDENTICAL, sim.add(path("a/one.txt"), 100, 7, 1000));
        assertEquals(1, sim.fileCount());
        assertEquals(100, sim.totalBytes());
        assertEquals(1, sim.identicalSkipped());
        assertEquals(100, sim.identicalBytes());
    }

    @Test
    void sameSizeButDifferentCrcIsADifferentFile() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("a/one.txt"), 100, 7, 1000);
        sim.startZip();
        assertEquals(SKIPPED_CONFLICT, sim.add(path("a/one.txt"), 100, 8, 1000));
        assertEquals(100, sim.totalBytes());
        assertEquals(100, sim.conflictBytes());
    }

    @Test
    void overwriteNoneKeepsTheFirstFile() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("f.txt"), 100, 1, 1000);
        sim.startZip();
        assertEquals(SKIPPED_CONFLICT, sim.add(path("f.txt"), 400, 2, 2000));
        assertEquals(100, sim.totalBytes());
    }

    @Test
    void overwriteAlwaysKeepsTheLastFileAndNotBoth() {
        var sim = new TreeSimulator(OverwriteMode.ALWAYS, true);
        sim.startZip();
        sim.add(path("f.txt"), 100, 1, 1000);
        sim.startZip();
        assertEquals(OVERWRITTEN, sim.add(path("f.txt"), 400, 2, 500));
        assertEquals(1, sim.fileCount());
        assertEquals(400, sim.totalBytes());
        assertEquals(100, sim.replacedBytes());
    }

    @Test
    void overwriteNewestReplacesOnlyWithANewerFile() {
        var sim = new TreeSimulator(OverwriteMode.NEWEST, true);
        sim.startZip();
        sim.add(path("f.txt"), 100, 1, 5000);
        sim.startZip();
        assertEquals(SKIPPED_CONFLICT, sim.add(path("f.txt"), 400, 2, 4000));
        assertEquals(100, sim.totalBytes());
        sim.startZip();
        assertEquals(OVERWRITTEN, sim.add(path("f.txt"), 900, 3, 6000));
        assertEquals(900, sim.totalBytes());
    }

    @Test
    void aNameUsedTwiceInOneZipKeepsBothUnderANewName() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("z/dup.txt"), 10, 1, 1000);
        assertEquals(RENAMED, sim.add(path("z/dup.txt"), 20, 2, 1000));
        assertEquals(2, sim.fileCount());
        assertEquals(30, sim.totalBytes());
        assertEquals(1, sim.renamed());
    }

    @Test
    void namesThatDifferOnlyByCaseAreKeptOnACaseInsensitiveVolume() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("Song.mp3"), 10, 1, 1000);
        sim.startZip();
        assertEquals(RENAMED, sim.add(path("song.mp3"), 20, 2, 1000));
        assertEquals(30, sim.totalBytes());
    }

    @Test
    void anIdenticalFileWithADifferentCaseIsTheSameFileOnACaseInsensitiveVolume() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("Song.mp3"), 10, 1, 1000);
        sim.startZip();
        assertEquals(SKIPPED_IDENTICAL, sim.add(path("song.mp3"), 10, 1, 1000));
        assertEquals(10, sim.totalBytes());
    }

    @Test
    void differentCaseMeansDifferentFilesOnACaseSensitiveVolume() {
        var sim = new TreeSimulator(OverwriteMode.NONE, false);
        sim.startZip();
        sim.add(path("Song.mp3"), 10, 1, 1000);
        sim.startZip();
        assertEquals(NEW, sim.add(path("song.mp3"), 10, 1, 1000));
        assertEquals(20, sim.totalBytes());
    }

    @Test
    void aFolderWantedWhereAFileSitsGoesToTheFolderSuffixName() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("Doc.pages"), 50, 1, 1000);
        sim.startZip();
        assertEquals(NEW, sim.add(path("Doc.pages/preview.jpg"), 5, 2, 1000));
        assertEquals(2, sim.fileCount());
        assertEquals(55, sim.totalBytes());
        // the same child again is now an identical copy in Doc.pages-folder
        sim.startZip();
        assertEquals(SKIPPED_IDENTICAL, sim.add(path("Doc.pages/preview.jpg"), 5, 2, 1000));
        assertEquals(55, sim.totalBytes());
    }

    @Test
    void aFileWantedWhereAFolderIsMovesTheFolderAside() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("Doc.pages/preview.jpg"), 5, 2, 1000);
        sim.startZip();
        assertEquals(NEW, sim.add(path("Doc.pages"), 50, 1, 1000));
        assertEquals(2, sim.fileCount());
        assertEquals(55, sim.totalBytes());
        // the moved child is found under Doc.pages-folder, so an identical one is a duplicate
        sim.startZip();
        assertEquals(SKIPPED_IDENTICAL, sim.add(path("Doc.pages/preview.jpg"), 5, 2, 1000));
        assertEquals(55, sim.totalBytes());
    }

    @Test
    void unknownCrcsAreNeverTreatedAsIdentical() {
        var sim = new TreeSimulator(OverwriteMode.NONE, true);
        sim.startZip();
        sim.add(path("f.txt"), 10, -1, 1000);
        sim.startZip();
        assertEquals(SKIPPED_CONFLICT, sim.add(path("f.txt"), 10, -1, 1000));
    }

    @Test
    void everyByteIsAccountedForExactlyOnce() {
        var sim = new TreeSimulator(OverwriteMode.ALWAYS, true);
        long entryBytes = 0;
        sim.startZip();
        for (long[] f : new long[][] {{100, 1}, {200, 2}, {300, 3}}) {
            sim.add(path("f" + f[1] + ".txt"), f[0], f[1], 1000);
            entryBytes += f[0];
        }
        sim.startZip();
        sim.add(path("f1.txt"), 100, 1, 1000);   // identical
        sim.add(path("f2.txt"), 250, 9, 2000);   // replaces
        sim.add(path("new.txt"), 40, 4, 1000);   // new
        entryBytes += 100 + 250 + 40;
        assertEquals(entryBytes, sim.totalBytes() + sim.identicalBytes() + sim.conflictBytes() + sim.replacedBytes());
    }
}
