package com.simtechdata.files;

import com.simtechdata.enums.OverwriteMode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plays an extraction on paper. Files are "written" one at a time into an empty, in-memory destination, using the
 * same rules the real extraction follows, so the bytes that would end up on disk can be added up without
 * extracting anything or touching a disk:
 *
 * <ul>
 *   <li>A path nobody has used yet is new: its size counts.</li>
 *   <li>A path that already holds a file of the same size and CRC-32 is an identical copy: skipped, nothing counts.</li>
 *   <li>A different file whose name is taken (the zip lists a name twice, or the names differ only by letter case on
 *       a case-insensitive file system) is kept under {@code name-1}, {@code name-2} and so on, and counts too, unless
 *       one of those names already holds an identical copy.</li>
 *   <li>Any other different file follows the overwrite setting: left out (none), replacing the older one (always),
 *       or replacing it only when newer.</li>
 *   <li>A file wanted where an earlier file has become a folder moves that folder to {@code name-folder}; a folder
 *       wanted where a file sits is redirected to {@code name-folder}. Both parts are kept.</li>
 * </ul>
 *
 * <p>Call {@link #startZip()} before the entries of each zip, in the order the zips would be extracted.
 * The destination is assumed to be empty to begin with, and one zip is extracted at a time.
 */
public final class TreeSimulator {

    /** What happened to one file. */
    public enum Result { NEW, OVERWRITTEN, RENAMED, SKIPPED_IDENTICAL, SKIPPED_CONFLICT, FAILED }

    private record Node(List<String> segments, long size, long crc, long time) {
        String exact() {
            return String.join("/", segments);
        }
    }

    private final OverwriteMode overwrite;
    private final boolean caseInsensitive;

    /** The files "on disk", by folded path. */
    private final Map<String, Node> files = new HashMap<>();
    /** Folded paths that are folders (every parent of every file written). */
    private final Set<String> folders = new HashSet<>();
    /** Exact paths already written or found in place by the zip being played (a second one is a different file). */
    private final Set<String> handledByThisZip = new HashSet<>();

    private long fileCount;
    private long totalBytes;
    private long identicalSkipped;
    private long identicalBytes;
    private long conflictsSkipped;
    private long conflictBytes;
    private long replacedFiles;
    private long replacedBytes;
    private long renamed;
    private long failed;
    private long leftovers;

    /**
     * @param overwrite       what to do with a different file at an occupied path
     * @param caseInsensitive whether the destination file system treats names that differ only by letter case as
     *                        the same name (see {@link FileSystemProbe})
     */
    public TreeSimulator(OverwriteMode overwrite, boolean caseInsensitive) {
        this.overwrite = overwrite;
        this.caseInsensitive = caseInsensitive;
    }

    /** Marks the start of the next zip. */
    public void startZip() {
        handledByThisZip.clear();
    }

    /**
     * Writes one file into the simulated destination.
     *
     * @param segments the path inside the destination, one name per element, already sanitized for the file system
     * @param size     uncompressed size in bytes (a negative size counts as 0)
     * @param crc      CRC-32 from the zip, or -1 when unknown
     * @param time     modification time in milliseconds, or -1 when unknown
     */
    public Result add(List<String> segments, long size, long crc, long time) {
        if (segments.isEmpty()) {
            return Result.FAILED;
        }
        size = Math.max(0, size);
        List<String> segs = new ArrayList<>(segments);

        // A folder is wanted where a file sits: the folder goes to name-folder instead.
        for (int i = 0; i < segs.size() - 1; i++) {
            if (files.containsKey(key(segs.subList(0, i + 1)))) {
                segs.set(i, segs.get(i) + ConflictPlanner.FOLDER_SUFFIX);
                if (files.containsKey(key(segs.subList(0, i + 1)))) {
                    failed++;
                    return Result.FAILED;
                }
            }
        }

        String targetKey = key(segs);
        if (folders.contains(targetKey)) {
            moveFolderAside(segs);
        }
        String exact = String.join("/", segs);
        Node existing = files.get(targetKey);

        if (existing == null) {
            put(targetKey, new Node(segs, size, crc, time));
            handledByThisZip.add(exact);
            return Result.NEW;
        }
        if (identical(existing, size, crc)) {
            handledByThisZip.add(exact);
            skipIdentical(size);
            return Result.SKIPPED_IDENTICAL;
        }

        boolean sameNameDifferentCase = !nfc(existing.segments().getLast()).equals(nfc(segs.getLast()));
        if (handledByThisZip.contains(exact) || sameNameDifferentCase) {
            return rename(segs, size, crc, time);
        }

        boolean replace = switch (overwrite) {
            case NONE -> false;
            case ALWAYS -> true;
            case NEWEST -> time > existing.time();
        };
        if (!replace) {
            conflictsSkipped++;
            conflictBytes += size;
            return Result.SKIPPED_CONFLICT;
        }
        replacedFiles++;
        replacedBytes += existing.size();
        totalBytes += size - existing.size();
        files.put(targetKey, new Node(segs, size, crc, time));
        handledByThisZip.add(exact);
        return Result.OVERWRITTEN;
    }

    // --- results

    /** Files that would be on disk when everything is extracted. */
    public long fileCount() { return fileCount; }

    /** Their total size in bytes. */
    public long totalBytes() { return totalBytes; }

    public long identicalSkipped() { return identicalSkipped; }

    public long identicalBytes() { return identicalBytes; }

    public long conflictsSkipped() { return conflictsSkipped; }

    public long conflictBytes() { return conflictBytes; }

    public long replacedFiles() { return replacedFiles; }

    public long replacedBytes() { return replacedBytes; }

    public long renamed() { return renamed; }

    /** Files that could not be placed (a name needed as a folder is taken by a file and by its -folder twin). */
    public long failed() { return failed; }

    // --- internals

    /** The name is taken by a different file: try name-1, name-2 and so on, as the real extraction does. */
    private Result rename(List<String> segs, long size, long crc, long time) {
        List<String> parent = segs.subList(0, segs.size() - 1);
        String name = segs.getLast();
        while (true) {
            name = FileNames.nextCollisionName(name);
            List<String> candidate = new ArrayList<>(parent);
            candidate.add(name);
            String candidateKey = key(candidate);
            Node there = files.get(candidateKey);
            if (there == null) {
                put(candidateKey, new Node(candidate, size, crc, time));
                handledByThisZip.add(String.join("/", candidate));
                renamed++;
                return Result.RENAMED;
            }
            if (identical(there, size, crc)) {
                handledByThisZip.add(String.join("/", candidate));
                skipIdentical(size);
                return Result.SKIPPED_IDENTICAL;
            }
        }
    }

    /**
     * A file is wanted at a path that is a folder: the folder (with everything in it) moves to {@code name-folder}.
     * Where a moved file meets one already there, the same size means a duplicate that is dropped; a different
     * one follows the overwrite setting, and if it loses it stays behind (and still takes space).
     */
    private void moveFolderAside(List<String> segs) {
        String folderKey = key(segs);
        String prefix = folderKey + "/";
        int depth = segs.size();

        List<Node> moved = new ArrayList<>();
        var it = files.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                moved.add(entry.getValue());
                it.remove();
            }
        }
        folders.removeIf(k -> k.equals(folderKey) || k.startsWith(prefix));

        for (Node node : moved) {
            fileCount--;
            totalBytes -= node.size();
        }
        for (Node node : moved) {
            List<String> renamedSegs = new ArrayList<>(node.segments());
            renamedSegs.set(depth - 1, renamedSegs.get(depth - 1) + ConflictPlanner.FOLDER_SUFFIX);
            Node moving = new Node(renamedSegs, node.size(), node.crc(), node.time());
            String movedKey = key(renamedSegs);
            Node clash = files.get(movedKey);
            if (clash == null) {
                put(movedKey, moving);
            } else if (clash.size() == moving.size()) {
                skipIdentical(moving.size());
            } else if (overwrite == OverwriteMode.ALWAYS
                    || (overwrite == OverwriteMode.NEWEST && moving.time() > clash.time())) {
                replacedFiles++;
                replacedBytes += clash.size();
                totalBytes += moving.size() - clash.size();
                files.put(movedKey, moving);
            } else {
                put("\0left-behind-" + (leftovers++), moving);
            }
        }
    }

    private void put(String key, Node node) {
        files.put(key, node);
        fileCount++;
        totalBytes += node.size();
        List<String> segs = node.segments();
        for (int i = 0; i < segs.size() - 1; i++) {
            folders.add(key(segs.subList(0, i + 1)));
        }
    }

    private void skipIdentical(long size) {
        identicalSkipped++;
        identicalBytes += size;
    }

    /** Same size and the same CRC-32, both known. */
    private static boolean identical(Node existing, long size, long crc) {
        return existing.size() == size && existing.crc() >= 0 && crc >= 0 && existing.crc() == crc;
    }

    private String key(List<String> segments) {
        String joined = nfc(String.join("/", segments));
        return caseInsensitive ? joined.toLowerCase(Locale.ROOT) : joined;
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }
}
