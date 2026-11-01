package com.icloudextract;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns raw zip entry names into safe, portable path segments. */
final class EntryNames {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private EntryNames() {
    }

    /** Thrown for names that would escape the destination folder. */
    static final class UnsafeNameException extends Exception {
        UnsafeNameException(String message) {
            super(message);
        }
    }

    /**
     * Windows-1252 characters for the bytes 0x80 to 0x9F; the rest of that code page (0xA0 to 0xFF) is the
     * same as Latin-1. {@code '\uFFFD'} marks the five bytes Windows-1252 leaves undefined.
     */
    private static final char[] CP1252_80_9F = {
            '\u20AC', '\uFFFD', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
            '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\uFFFD', '\u017D', '\uFFFD',
            '\uFFFD', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
            '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\uFFFD', '\u017E', '\u0178'};

    /**
     * Decodes the raw name bytes as UTF-8. A byte that is not valid UTF-8 is almost always a character
     * from an older single-byte code page, so it is read as Windows-1252: {@code caf\xE9} becomes
     * {@code caf\u00E9}, and a stray {@code 0x92} becomes a typographic apostrophe. The five bytes that
     * Windows-1252 does not define become an underscore.
     */
    static String decode(byte[] raw) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return decodeWithEscapes(raw);
        }
    }

    private static String decodeWithEscapes(byte[] raw) {
        StringBuilder result = new StringBuilder(raw.length + 8);
        ByteBuffer in = ByteBuffer.wrap(raw);
        CharBuffer out = CharBuffer.allocate(raw.length + 1);
        while (in.hasRemaining()) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult cr = decoder.decode(in, out, true);
            out.flip();
            result.append(out);
            out.clear();
            if (cr.isError()) {
                for (int i = 0; i < cr.length() && in.hasRemaining(); i++) {
                    result.append(legacyChar(in.get() & 0xFF));
                }
            }
        }
        return result.toString();
    }

    private static char legacyChar(int unsignedByte) {
        if (unsignedByte >= 0xA0) {
            return (char) unsignedByte;
        }
        char c = CP1252_80_9F[unsignedByte - 0x80];
        return c == '\uFFFD' ? '_' : c;
    }

    /**
     * The name to use when a file's own name is already taken by a different file: {@code -1} is added to
     * the part before the extension ({@code song.gcode} becomes {@code song-1.gcode}), and a name that
     * already ends in {@code -<number>} has that number increased ({@code song-1.gcode} becomes
     * {@code song-2.gcode}).
     */
    static String nextCollisionName(String fileName) {
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

    /** True when the (decoded) name denotes a directory entry. */
    static boolean isDirectoryName(String name) {
        return name.endsWith("/");
    }

    /**
     * Splits a decoded entry name into path segments, dropping empty and {@code .} segments.
     *
     * @throws UnsafeNameException for {@code ..} segments, which would escape the destination
     */
    static List<String> segments(String name) throws UnsafeNameException {
        List<String> result = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new UnsafeNameException("path contains '..': " + name);
            }
            if (part.indexOf('\0') >= 0) {
                throw new UnsafeNameException("path contains a NUL character: " + name);
            }
            result.add(part);
        }
        return result;
    }

    /** Makes one path segment legal on the current operating system (a no-op on macOS and Linux). */
    static String sanitize(String segment) {
        return sanitize(segment, WINDOWS);
    }

    static String sanitize(String segment, boolean windows) {
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
}
