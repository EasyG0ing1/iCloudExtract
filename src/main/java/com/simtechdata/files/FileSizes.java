package com.simtechdata.files;

import java.util.Locale;

/** Turns byte counts into text people read. */
public final class FileSizes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private FileSizes() {
    }

    /**
     * Formats a size with two decimals and the largest unit that keeps the number at 1 or more, for example
     * {@code 23.40 GB} or {@code 768.00 MB}. Units are decimal (1 KB = 1000 bytes), the way macOS and drive
     * makers count. Sizes under 1 KB are shown as whole bytes ({@code 512 B}).
     */
    public static String format(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        if (bytes < 1000) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1000 && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }
        // Rounding can carry 999.995 up to 1000.00: move to the next unit so the number stays below 1000.
        if (String.format(Locale.ROOT, "%.2f", value).equals("1000.00") && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, UNITS[unit]);
    }

    /** The exact count with thousands separators, for example {@code 83,421,998,123 bytes}. */
    public static String exact(long bytes) {
        return String.format(Locale.ROOT, "%,d bytes", Math.max(0, bytes));
    }
}
