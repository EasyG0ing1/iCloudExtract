package com.icloudextract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class EntryNamesTest {

    @Test
    void validUtf8IsKept() {
        byte[] raw = "Michael’s MacBook Pro/é.txt".getBytes(StandardCharsets.UTF_8);
        assertEquals("Michael’s MacBook Pro/é.txt", EntryNames.decode(raw));
    }

    @Test
    void invalidBytesAreReadAsWindows1252() {
        byte[] raw = {'c', 'a', 'f', (byte) 0xE9, '.', 't', 'x', 't'};
        assertEquals("café.txt", EntryNames.decode(raw));
    }

    @Test
    void validAndInvalidBytesCanBeMixed() {
        byte[] utf8 = "ü".getBytes(StandardCharsets.UTF_8);
        // 0x96 is an en dash in Windows-1252, 0xFF is y with a diaeresis
        byte[] raw = {'a', (byte) 0x96, utf8[0], utf8[1], (byte) 0xFF, 'b'};
        assertEquals("a–üÿb", EntryNames.decode(raw));
    }

    @Test
    void bytesWindows1252LeavesUndefinedBecomeUnderscores() {
        assertEquals("a_b", EntryNames.decode(new byte[] {'a', (byte) 0x81, 'b'}));
    }

    @Test
    void collisionNamesAddOneOrIncrementAnExistingNumber() {
        assertEquals("song-1.gcode", EntryNames.nextCollisionName("song.gcode"));
        assertEquals("song-2.gcode", EntryNames.nextCollisionName("song-1.gcode"));
        assertEquals("song-10.gcode", EntryNames.nextCollisionName("song-9.gcode"));
        assertEquals("uparrow-2.png", EntryNames.nextCollisionName("uparrow-1.png"));
        assertEquals("README-1", EntryNames.nextCollisionName("README"));
        assertEquals("a-b-1.txt", EntryNames.nextCollisionName("a-b.txt"));
        assertEquals("archive.tar-1.gz", EntryNames.nextCollisionName("archive.tar.gz"));
        assertEquals(".hidden-1", EntryNames.nextCollisionName(".hidden"));
    }

    @Test
    void segmentsDropEmptyAndDotParts() throws Exception {
        assertEquals(List.of("a", "b", "c.txt"), EntryNames.segments("/a//./b/c.txt"));
    }

    @Test
    void parentReferencesAreRejected() {
        assertThrows(EntryNames.UnsafeNameException.class, () -> EntryNames.segments("a/../../etc/passwd"));
    }

    @Test
    void windowsSanitizingEscapesIllegalCharactersAndReservedNames() {
        assertEquals("a%3Ab%3F.txt", EntryNames.sanitize("a:b?.txt", true));
        assertEquals("_CON", EntryNames.sanitize("CON", true));
        assertEquals("name%2E", EntryNames.sanitize("name.", true));
        assertEquals("a:b", EntryNames.sanitize("a:b", false));
    }
}
