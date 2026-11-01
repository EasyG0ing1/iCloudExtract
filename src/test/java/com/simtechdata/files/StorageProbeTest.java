package com.simtechdata.files;

import com.simtechdata.enums.StorageKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StorageProbeTest {

    @Test
    void macPlistSolidState() {
        assertEquals(StorageKind.SSD, StorageProbe.macSolidState("<key>SolidState</key>\n\t<true/>"));
        assertEquals(StorageKind.HDD, StorageProbe.macSolidState("<key>SolidState</key>\n\t<false/>"));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.macSolidState("<key>Internal</key><false/>"));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.macSolidState(null));
    }

    @Test
    void macPlistStrings() {
        String plist = "<key>APFSPhysicalStore</key>\n<string>disk2s2</string>\n<key>ParentWholeDisk</key>\n<string>disk3</string>";
        assertEquals("disk2s2", StorageProbe.macString(plist, "APFSPhysicalStore"));
        assertEquals("disk3", StorageProbe.macString(plist, "ParentWholeDisk"));
        assertEquals(null, StorageProbe.macString(plist, "Missing"));
    }

    @Test
    void linuxRotationalFlag() {
        assertEquals(StorageKind.SSD, StorageProbe.linuxRotational("0\n"));
        assertEquals(StorageKind.HDD, StorageProbe.linuxRotational("1\n"));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.linuxRotational("x"));
    }

    @Test
    void windowsMediaType() {
        assertEquals(StorageKind.SSD, StorageProbe.windowsMediaType("SSD\r\n"));
        assertEquals(StorageKind.HDD, StorageProbe.windowsMediaType("HDD"));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.windowsMediaType("Unspecified"));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.windowsMediaType(null));
    }

    @Test
    void linuxPartitionUsesTheWholeDisksFlag(@TempDir Path tmp) throws IOException {
        // /sys/devices/.../sda/queue/rotational and /sys/devices/.../sda/sda1, with /sys/class/block/sda1 -> sda1
        Path disk = Files.createDirectories(tmp.resolve("devices/sda"));
        Files.createDirectories(disk.resolve("queue"));
        Files.writeString(disk.resolve("queue/rotational"), "1\n");
        Path part = Files.createDirectories(disk.resolve("sda1"));
        Path classBlock = Files.createDirectories(tmp.resolve("class/block"));
        Files.createSymbolicLink(classBlock.resolve("sda1"), part);
        assertEquals(StorageKind.HDD, StorageProbe.linuxDevice(classBlock, "sda1", 0));
        assertEquals(StorageKind.UNKNOWN, StorageProbe.linuxDevice(classBlock, "nothing", 0));
    }

    @Test
    void linuxMappedDeviceFollowsItsSlaves(@TempDir Path tmp) throws IOException {
        Path nvme = Files.createDirectories(tmp.resolve("devices/nvme0n1"));
        Files.createDirectories(nvme.resolve("queue"));
        Files.writeString(nvme.resolve("queue/rotational"), "0\n");
        Path dm = Files.createDirectories(tmp.resolve("devices/dm-0/slaves"));
        Path classBlock = Files.createDirectories(tmp.resolve("class/block"));
        Files.createSymbolicLink(classBlock.resolve("nvme0n1"), nvme);
        Files.createSymbolicLink(classBlock.resolve("dm-0"), dm.getParent());
        Files.createSymbolicLink(dm.resolve("nvme0n1"), nvme);
        assertEquals(StorageKind.SSD, StorageProbe.linuxDevice(classBlock, "mapper/vg-root".replace("mapper/vg-root", "dm-0"), 0));
    }

    @Test
    void detectNeverThrowsAndAnswersForARealFolder(@TempDir Path tmp) {
        assertNotNull(StorageProbe.detect(tmp));
        assertNotNull(StorageProbe.detect(tmp.resolve("does/not/exist/yet")));
    }
}
