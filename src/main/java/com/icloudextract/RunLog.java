package com.icloudextract;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Two append-only log files, written to the folder the program was started from:
 * <ul>
 *   <li>{@code iCloudExtract.log}: everything that happened, including every per-entry error.</li>
 *   <li>{@code iCloudExtract-conflicts.log}: only conflict events (skipped, overwritten, redirected).
 *       Created on first use.</li>
 * </ul>
 */
final class RunLog implements Closeable {

    static final String RUN_LOG_NAME = "iCloudExtract.log";
    static final String CONFLICT_LOG_NAME = "iCloudExtract-conflicts.log";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path runLogFile;
    private final Path conflictLogFile;
    private BufferedWriter run;
    private BufferedWriter conflicts;

    RunLog(Path directory) {
        this.runLogFile = directory.resolve(RUN_LOG_NAME);
        this.conflictLogFile = directory.resolve(CONFLICT_LOG_NAME);
    }

    Path runLogFile() {
        return runLogFile;
    }

    Path conflictLogFile() {
        return conflictLogFile;
    }

    synchronized void info(String message) {
        write(false, "INFO ", message);
    }

    synchronized void warn(String message) {
        write(false, "WARN ", message);
    }

    synchronized void error(String message) {
        write(false, "ERROR", message);
    }

    /** Recorded in both logs so the run log stays a complete account. */
    synchronized void conflict(String message) {
        write(true, "CONFLICT", message);
        write(false, "CONFLICT", message);
    }

    private void write(boolean toConflictLog, String level, String message) {
        try {
            BufferedWriter w;
            if (toConflictLog) {
                if (conflicts == null) {
                    conflicts = open(conflictLogFile);
                }
                w = conflicts;
            } else {
                if (run == null) {
                    run = open(runLogFile);
                }
                w = run;
            }
            w.write(STAMP.format(LocalDateTime.now()) + " " + level + " " + message);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            System.err.println("Could not write to log file: " + e.getMessage());
        }
    }

    private static BufferedWriter open(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void close() throws IOException {
        if (run != null) {
            run.close();
        }
        if (conflicts != null) {
            conflicts.close();
        }
    }
}
