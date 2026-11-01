package com.simtechdata.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Test helper: reads the log file(s) a run left in a folder. */
public final class TestLogs {

    private TestLogs() {
    }

    /** The text of every {@code iCloudExtract-*.log} in the folder, or an empty string when there is none. */
    public static String text(Path directory) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("iCloudExtract-.*\\.log")).sorted().toList()) {
                all.append(Files.readString(p));
            }
        }
        return all.toString();
    }
}
