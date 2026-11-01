package com.simtechdata.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void collisionNamesAddOneOrIncrementAnExistingNumber() {
        assertEquals("song-1.gcode", FileNames.nextCollisionName("song.gcode"));
        assertEquals("song-2.gcode", FileNames.nextCollisionName("song-1.gcode"));
        assertEquals("song-10.gcode", FileNames.nextCollisionName("song-9.gcode"));
        assertEquals("uparrow-2.png", FileNames.nextCollisionName("uparrow-1.png"));
        assertEquals("README-1", FileNames.nextCollisionName("README"));
        assertEquals("a-b-1.txt", FileNames.nextCollisionName("a-b.txt"));
        assertEquals("archive.tar-1.gz", FileNames.nextCollisionName("archive.tar.gz"));
        assertEquals(".hidden-1", FileNames.nextCollisionName(".hidden"));
    }

    @Test
    void windowsSanitizingEscapesIllegalCharactersAndReservedNames() {
        assertEquals("a%3Ab%3F.txt", FileNames.sanitize("a:b?.txt", true));
        assertEquals("_CON", FileNames.sanitize("CON", true));
        assertEquals("name%2E", FileNames.sanitize("name.", true));
        assertEquals("a:b", FileNames.sanitize("a:b", false));
    }
}
