package com.simtechdata.admin;

import com.simtechdata.enums.StorageKind;
import org.junit.jupiter.api.Test;

import static com.simtechdata.enums.StorageKind.HDD;
import static com.simtechdata.enums.StorageKind.SSD;
import static com.simtechdata.enums.StorageKind.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadChooserTest {

    @Test
    void bothOnSsdUsesOneThreadPerCore() {
        ThreadChooser.Choice c = ThreadChooser.choose(SSD, SSD, 8);
        assertEquals(8, c.threads());
        assertTrue(c.message().contains("8"), c.message());
    }

    @Test
    void aHardDriveOnEitherSideMeansOneThread() {
        assertEquals(1, ThreadChooser.choose(HDD, SSD, 8).threads());
        assertEquals(1, ThreadChooser.choose(SSD, HDD, 8).threads());
        assertEquals(1, ThreadChooser.choose(HDD, HDD, 8).threads());
        assertTrue(ThreadChooser.choose(SSD, HDD, 8).message().contains("destination"));
        assertTrue(ThreadChooser.choose(HDD, SSD, 8).message().contains("zip files"));
    }

    @Test
    void anUnknownDriveIsTreatedLikeAHardDrive() {
        assertEquals(1, ThreadChooser.choose(UNKNOWN, SSD, 8).threads());
        assertEquals(1, ThreadChooser.choose(SSD, UNKNOWN, 8).threads());
        assertTrue(ThreadChooser.choose(SSD, UNKNOWN, 8).message().contains("could not tell"));
    }

    @Test
    void withoutADestinationOnlyTheSourceCounts() {
        assertEquals(4, ThreadChooser.choose(SSD, null, 4).threads());
        assertEquals(1, ThreadChooser.choose(HDD, null, 4).threads());
        assertEquals(1, ThreadChooser.choose(UNKNOWN, null, 4).threads());
    }

    @Test
    void neverBelowOne() {
        assertEquals(1, ThreadChooser.choose(SSD, SSD, 0).threads());
    }

    @Test
    void settingsKeepEverythingElseWhenTheThreadCountChanges() {
        Settings s = new Settings(java.nio.file.Path.of("x"), com.simtechdata.enums.VerifyMode.CRC, true,
                com.simtechdata.enums.OverwriteMode.NEWEST, true, 1).withThreads(6);
        assertEquals(6, s.threads());
        assertTrue(s.consolidate() && s.checkZip());
        assertEquals(StorageKind.SSD, StorageKind.valueOf("SSD"));
    }
}
