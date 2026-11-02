package com.simtechdata.admin;

import com.simtechdata.enums.LogAction;
import com.simtechdata.enums.LogReason;

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
 * The log of one run: a single CSV file named {@code iCloudExtract-yyyy-MM-dd_HH-mm-ss.csv} (the start time of the
 * run), written to the folder the program was started from. Every line, whether it is a conflict, an error, a warning
 * or a note about the run itself, has the same six columns, so the file opens in a spreadsheet:
 *
 * <pre>date/time,ZipFile,Reason,Action,File(Path),Discussion</pre>
 *
 * <p>The file is created when the first line is written. Fields are quoted as CSV requires. A field that starts with
 * {@code = + - @} gets a leading apostrophe so that a spreadsheet cannot mistake a hostile file name from a zip for a
 * formula.
 */
public final class RunLog implements Closeable {

    /** The first line of the file. */
    public static final String HEADER = "date/time,ZipFile,Reason,Action,File(Path),Discussion";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path file;
    private BufferedWriter writer;

    public RunLog(Path directory) {
        this(directory, LocalDateTime.now());
    }

    /** The start time is a parameter so the file name can be tested. */
    RunLog(Path directory, LocalDateTime started) {
        this.file = directory.resolve("iCloudExtract-" + FILE_STAMP.format(started) + ".csv");
    }

    /** The log file of this run. */
    public Path file() {
        return file;
    }

    /**
     * Adds one line.
     *
     * @param zipFile    the zip file it is about, or empty when it concerns the whole run
     * @param reason     why the line is there
     * @param action     what the program did about it
     * @param path       the file or folder concerned, relative to the destination where possible, or empty
     * @param discussion a sentence saying what happened
     */
    public synchronized void record(String zipFile, LogReason reason, LogAction action, String path,
                                    String discussion) {
        try {
            if (writer == null) {
                boolean fresh = !Files.exists(file) || Files.size(file) == 0;
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) {
                    writer.write(HEADER);
                    writer.write('\n');
                }
            }
            writer.write(String.join(",",
                    STAMP.format(LocalDateTime.now()),
                    csv(zipFile), csv(reason.label()), csv(action.label()), csv(path), csv(discussion)));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            System.err.println("Could not write to log file: " + e.getMessage());
        }
    }

    /** A note about the run or, when {@code zipFile} is given, about one zip. */
    public void info(String zipFile, String message) {
        record(zipFile, LogReason.RUN, LogAction.INFO, "", message);
    }

    /** A warning about the run or, when {@code zipFile} is given, about one zip. */
    public void warn(String zipFile, String message) {
        record(zipFile, LogReason.WARNING, LogAction.WARNING, "", message);
    }

    /**
     * One CSV field. Quotes it when it holds a comma, a quote or a line break (doubling any quotes), and puts an
     * apostrophe in front of a value that starts with a character a spreadsheet treats as the start of a formula.
     */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
