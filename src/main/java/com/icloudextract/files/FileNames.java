package com.icloudextract.files;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** File name rules that depend on the file system, not on where a name came from. */
public final class FileNames {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() {
    }

    /**
     * The name to use when a file's own name is already taken by a different file: {@code -1} is added to
     * the part before the extension ({@code song.gcode} becomes {@code song-1.gcode}), and a name that
     * already ends in {@code -<number>} has that number increased ({@code song-1.gcode} becomes
     * {@code song-2.gcode}).
     */
    public static String nextCollisionName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String root = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        int dash = root.lastIndexOf('-');
        if (dash >= 0 && dash < root.length() - 1 && root.substring(dash + 1).chars().allMatch(Character::isDigit)) {
            try {
                long next = Long.parseLong(root.substring(dash + 1)) + 1;
                return root.substring(0, dash) + "-" + next + extension;
            } catch (NumberFormatException e) {
                // an absurdly long run of digits: fall through and append -1
            }
        }
        return root + "-1" + extension;
    }

    /** Makes one path segment legal on the current operating system (a no-op on macOS and Linux). */
    public static String sanitize(String segment) {
        return sanitize(segment, WINDOWS);
    }

    public static String sanitize(String segment, boolean windows) {
        if (!windows) {
            return segment;
        }
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || "<>:\"\\|?*".indexOf(c) >= 0) {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        // Windows silently strips trailing dots and spaces, which would merge distinct names,
        // so escape them instead.
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '.' || sb.charAt(end - 1) == ' ')) {
            end--;
        }
        StringBuilder tail = new StringBuilder();
        for (int i = end; i < sb.length(); i++) {
            tail.append('%').append(String.format("%02X", (int) sb.charAt(i)));
        }
        String result = sb.substring(0, end) + tail;
        int dot = result.indexOf('.');
        String stem = dot < 0 ? result : result.substring(0, dot);
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            result = "_" + result;
        }
        return result.isEmpty() ? "_" : result;
    }

    /**
     * True when {@code target} exists only because the file system ignores letter case (or Unicode
     * normalization): no entry in the folder has exactly this name.
     */
    public static boolean isCaseVariant(Path target) throws IOException {
        String wanted = Normalizer.normalize(target.getFileName().toString(), Normalizer.Form.NFC);
        try (DirectoryStream<Path> names = Files.newDirectoryStream(target.getParent())) {
            for (Path existing : names) {
                String name = Normalizer.normalize(existing.getFileName().toString(), Normalizer.Form.NFC);
                if (name.equals(wanted)) {
                    return false;
                }
            }
        }
        return true;
    }
}
