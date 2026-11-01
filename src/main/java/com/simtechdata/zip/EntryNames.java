package com.simtechdata.zip;

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

/** Turns raw zip entry names into path segments. */
public final class EntryNames {

    private EntryNames() {
    }

    /** Thrown for names that would escape the destination folder. */
    public static final class UnsafeNameException extends Exception {
        public UnsafeNameException(String message) {
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
    public static String decode(byte[] raw) {
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
    /** True when the (decoded) name denotes a directory entry. */
    public static boolean isDirectoryName(String name) {
        return name.endsWith("/");
    }
    /**
     * Splits a decoded entry name into path segments, dropping empty and {@code .} segments.
     *
     * @throws UnsafeNameException for {@code ..} segments, which would escape the destination
     */
    public static List<String> segments(String name) throws UnsafeNameException {
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

    /** The name of the folder that mirrors a zip file in the export: the zip's file name without {@code .zip}. */
    public static String mirrorName(String zipName) {
        return zipName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? zipName.substring(0, zipName.length() - 4) : zipName;
    }

    /**
     * Used when consolidating: drops the first path segment when it is the mirror folder (compared without regard
     * to letter case), so the contents of all the zips merge into one tree. Returns an empty list when the path was
     * only the mirror folder itself; any other path is returned unchanged.
     */
    public static List<String> withoutMirror(List<String> mapped, String mirror) {
        if (!mapped.isEmpty() && mapped.getFirst().equalsIgnoreCase(mirror)) {
            return mapped.subList(1, mapped.size());
        }
        return mapped;
    }
}
