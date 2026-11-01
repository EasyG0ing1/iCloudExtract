package com.icloudextract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the zip files to extract. A single zip file is used as is. For a folder, every {@code .zip}
 * file in it is used, ordered by the number in its name (for example {@code Part 1 of 18} through
 * {@code Part 18 of 18}). Gaps in the numbering are reported as warnings, never as errors.
 */
final class ZipSourceResolver {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern OF_TOTAL = Pattern.compile("(?i)\\bof\\s+(\\d+)");

    private ZipSourceResolver() {
    }

    /** A usage problem the user can fix (bad path, broken sequence). */
    static final class SourceException extends Exception {
        SourceException(String message) {
            super(message);
        }
    }

    record Resolved(List<Path> zips, List<String> warnings) {
    }

    static Resolved resolve(Path source) throws SourceException, IOException {
        if (!Files.exists(source)) {
            throw new SourceException("Source does not exist: " + source);
        }
        if (Files.isRegularFile(source)) {
            return new Resolved(List.of(source), List.of());
        }
        if (!Files.isDirectory(source)) {
            throw new SourceException("Source is neither a file nor a folder: " + source);
        }

        List<Path> zips;
        try (Stream<Path> listing = Files.list(source)) {
            zips = listing
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        if (zips.isEmpty()) {
            throw new SourceException("No .zip files found in " + source);
        }
        if (zips.size() == 1) {
            return new Resolved(zips, List.of());
        }
        return sequence(zips);
    }

    /**
     * Orders the zips by their sequence number. Gaps and unnumbered names are not errors: every zip is
     * kept, and anything notable is reported as a warning so the caller can show it to the user.
     */
    static Resolved sequence(List<Path> zips) {
        List<String> warnings = new ArrayList<>();
        List<Path> numbered = new ArrayList<>();
        List<long[]> numbers = new ArrayList<>();
        List<Path> unnumbered = new ArrayList<>();
        for (Path zip : zips) {
            long[] groups = digitGroups(zip.getFileName().toString());
            if (groups.length == 0) {
                unnumbered.add(zip);
            } else {
                numbered.add(zip);
                numbers.add(groups);
            }
        }

        // The sequence number is the first digit group whose value differs in every file name
        // ("Part 10 of 18": the 10, not the repeated 18).
        int position = -1;
        int minGroups = numbers.stream().mapToInt(a -> a.length).min().orElse(0);
        for (int k = 0; k < minGroups && position < 0; k++) {
            Set<Long> seen = new HashSet<>();
            boolean distinct = true;
            for (long[] groups : numbers) {
                distinct &= seen.add(groups[k]);
            }
            if (distinct) {
                position = k;
            }
        }

        List<Path> sorted = new ArrayList<>();
        if (position < 0) {
            if (!numbered.isEmpty()) {
                warnings.add("Could not tell which number in the zip file names is the sequence number, "
                        + "so they are listed in name order.");
            }
            sorted.addAll(zips);
            sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
        } else {
            final int pos = position;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < numbered.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingLong(i -> numbers.get(i)[pos]));
            List<Long> missing = new ArrayList<>();
            long previous = -1;
            for (int index : order) {
                long value = numbers.get(index)[pos];
                if (previous >= 0) {
                    for (long gap = previous + 1; gap < value; gap++) {
                        missing.add(gap);
                    }
                }
                previous = value;
                sorted.add(numbered.get(index));
            }
            if (!missing.isEmpty()) {
                warnings.add("There is no zip file for sequence number(s) " + missing + ".");
            }
            unnumbered.sort(Comparator.comparing(p -> p.getFileName().toString()));
            if (!unnumbered.isEmpty()) {
                warnings.add(unnumbered.size() + " zip file(s) have no number in their name and are listed last.");
            }
            sorted.addAll(unnumbered);
        }

        Set<Long> totals = new HashSet<>();
        for (Path zip : sorted) {
            Matcher m = OF_TOTAL.matcher(zip.getFileName().toString());
            if (m.find()) {
                totals.add(Long.parseLong(m.group(1)));
            }
        }
        if (totals.size() == 1) {
            long total = totals.iterator().next();
            if (total != sorted.size()) {
                warnings.add("The file names say \"of " + total + "\" but " + sorted.size()
                        + " zip file(s) were found.");
            }
        }
        return new Resolved(sorted, warnings);
    }

    private static long[] digitGroups(String name) {
        List<Long> values = new ArrayList<>();
        Matcher m = DIGITS.matcher(name);
        while (m.find()) {
            try {
                values.add(Long.parseLong(m.group()));
            } catch (NumberFormatException e) {
                // absurdly long digit run: not a sequence number
            }
        }
        return values.stream().mapToLong(Long::longValue).toArray();
    }
}
