package com.simtechdata.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Finds out how the file system under a path treats names, without writing anything. */
public final class FileSystemProbe {

    private FileSystemProbe() {
    }

    /**
     * True when the file system under {@code location} ignores letter case in names (macOS and Windows volumes
     * usually do, Linux usually does not). It looks at the closest folder that already exists: if the same
     * folder is reachable with its name in a different case, names are case-insensitive. When no folder name has
     * any letters to test with, it falls back to the operating system's usual behavior.
     */
    public static boolean isCaseInsensitive(Path location) {
        Path p = location.toAbsolutePath().normalize();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        for (; p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name == null) {
                break;
            }
            String original = name.toString();
            for (String variant : new String[] {original.toUpperCase(Locale.ROOT), original.toLowerCase(Locale.ROOT)}) {
                if (variant.equals(original)) {
                    continue;
                }
                try {
                    Path other = p.resolveSibling(variant);
                    // Present and the very same folder: case does not matter. Absent, or a different folder: it does.
                    return Files.exists(other) && Files.isSameFile(p, other);
                } catch (IOException | RuntimeException e) {
                    // cannot tell from this folder; try its parent
                }
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("win");
    }
}
