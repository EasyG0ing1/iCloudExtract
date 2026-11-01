package com.icloudextract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipSourceResolverTest {

    private static void touch(Path dir, String... names) throws Exception {
        for (String n : names) {
            Files.write(dir.resolve(n), new byte[0]);
        }
    }

    @Test
    void orderedByTheNumberThatDiffersNotByName(@TempDir Path dir) throws Exception {
        touch(dir, "iCloud Drive Part 10 of 12.zip", "iCloud Drive Part 2 of 12.zip", "iCloud Drive Part 1 of 12.zip",
                "iCloud Drive Part 3 of 12.zip", "iCloud Drive Part 4 of 12.zip", "iCloud Drive Part 5 of 12.zip",
                "iCloud Drive Part 6 of 12.zip", "iCloud Drive Part 7 of 12.zip", "iCloud Drive Part 8 of 12.zip",
                "iCloud Drive Part 9 of 12.zip", "iCloud Drive Part 11 of 12.zip", "iCloud Drive Part 12 of 12.zip",
                "notes.txt", "iCloud Drive Part 3 of 12.zip.part");

        var resolved = ZipSourceResolver.resolve(dir);

        List<String> names = resolved.zips().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(12, names.size());
        assertEquals("iCloud Drive Part 1 of 12.zip", names.get(0));
        assertEquals("iCloud Drive Part 10 of 12.zip", names.get(9));
        assertEquals("iCloud Drive Part 12 of 12.zip", names.get(11));
        assertTrue(resolved.warnings().isEmpty());
    }

    @Test
    void aGapInTheSequenceIsOnlyAWarning(@TempDir Path dir) throws Exception {
        touch(dir, "Part 1 of 4.zip", "Part 2 of 4.zip", "Part 4 of 4.zip");
        var resolved = ZipSourceResolver.resolve(dir);

        assertEquals(3, resolved.zips().size());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("[3]")), resolved.warnings().toString());
    }

    @Test
    void aNameWithoutANumberIsKeptAndListedLast(@TempDir Path dir) throws Exception {
        touch(dir, "Part 1.zip", "Part 2.zip", "extra.zip");
        var resolved = ZipSourceResolver.resolve(dir);

        List<String> names = resolved.zips().stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("Part 1.zip", "Part 2.zip", "extra.zip"), names);
        assertEquals(1, resolved.warnings().size());
    }

    @Test
    void namesWhereNoNumberDiffersFallBackToNameOrder(@TempDir Path dir) throws Exception {
        touch(dir, "b 2024.zip", "a 2024.zip");
        var resolved = ZipSourceResolver.resolve(dir);

        assertEquals("a 2024.zip", resolved.zips().get(0).getFileName().toString());
        assertEquals(1, resolved.warnings().size());
    }

    @Test
    void fewerFilesThanTheNamesPromiseIsAWarning(@TempDir Path dir) throws Exception {
        touch(dir, "Part 1 of 5.zip", "Part 2 of 5.zip", "Part 3 of 5.zip");
        var resolved = ZipSourceResolver.resolve(dir);
        assertEquals(1, resolved.warnings().size());
    }

    @Test
    void aSingleZipFileIsUsedAsIs(@TempDir Path dir) throws Exception {
        touch(dir, "whatever.zip");
        var resolved = ZipSourceResolver.resolve(dir.resolve("whatever.zip"));
        assertEquals(1, resolved.zips().size());
    }
}
