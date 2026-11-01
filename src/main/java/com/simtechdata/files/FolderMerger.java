package com.simtechdata.files;

import com.simtechdata.admin.RunLog;
import com.simtechdata.enums.OverwriteMode;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Moves the contents of one folder into another, merging folders that exist on both sides. When a file
 * with the same name is already there, a matching size means identical (no CRC-32 is read): the source file is deleted (the
 * destination already has it); one of a different size is renamed {@code -N} if the file system merely treats the
 * names as the same, otherwise the {@code -overwrite} setting decides (by default it is left in place).
 */
public final class FolderMerger {

    /** What a merge did. */
    public static final class Stats {
        public int moved;
        public int duplicatesRemoved;
        public int overwritten;
        public int renamed;
        public int conflictsLeft;
        public int redirectedToFolderSuffix;
    }

    private final Path   root;
    private final RunLog log;
    private final String        label;
    private final OverwriteMode overwrite;
    private final boolean       useCrc;

    /**
     * @param root  the destination root, used only to print shorter paths in the log
     * @param label what to put in front of log lines (a zip or folder name)
     */
    public FolderMerger(Path root, RunLog log, String label, OverwriteMode overwrite) {
        this(root, log, label, overwrite, false);
    }

    /** @param useCrc also compare CRC-32 when deciding that two files with the same name and size are identical */
    public FolderMerger(Path root, RunLog log, String label, OverwriteMode overwrite, boolean useCrc) {
        this.useCrc = useCrc;
        this.root = root;
        this.log = log;
        this.label = label;
        this.overwrite = overwrite;
    }

    /** Moves every child of {@code source} into {@code destination}; removes {@code source} if left empty. */
    public void mergeContents(Path source, Path destination, Stats stats) throws IOException {
        List<Path> children;
        try (Stream<Path> listing = Files.list(source)) {
            children = listing.sorted().toList();
        }
        for (Path child : children) {
            mergeItem(child, destination.resolve(child.getFileName().toString()), stats);
        }
        try (Stream<Path> remaining = Files.list(source)) {
            if (remaining.findAny().isEmpty()) {
                Files.delete(source);
            }
        }
    }

    /** Moves one file or folder to {@code target}, which may already exist. */
    public void mergeItem(Path item, Path target, Stats stats) throws IOException {
        boolean itemIsDir = Files.isDirectory(item, NOFOLLOW_LINKS);
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            Files.move(item, target);
            stats.moved++;
            return;
        }
        boolean targetIsDir = Files.isDirectory(target, NOFOLLOW_LINKS);

        if (itemIsDir && targetIsDir) {
            mergeContents(item, target, stats);
        } else if (!itemIsDir && !targetIsDir) {
            if (sameContent(item, target)) {
                // Already there and identical: drop the source copy without a log line.
                Files.delete(item);
                stats.duplicatesRemoved++;
            } else if (FileNames.isCaseVariant(target)) {
                moveToFreeName(item, target, stats);
            } else if (overwrite == OverwriteMode.ALWAYS || (overwrite == OverwriteMode.NEWEST
                    && Files.getLastModifiedTime(item).toMillis() > Files.getLastModifiedTime(target).toMillis())) {
                Files.move(item, target, StandardCopyOption.REPLACE_EXISTING);
                stats.overwritten++;
                log.conflict(label + " :: overwritten: '" + root.relativize(target) + "' replaced by " + item);
            } else {
                stats.conflictsLeft++;
                log.conflict(label + " :: left in place, a different file is already at '"
                        + root.relativize(target) + "': " + item);
            }
        } else if (itemIsDir) {
            // A folder wants the name of an existing file: the file keeps the name, the folder gets -folder.
            Path folder = target.resolveSibling(target.getFileName() + ConflictPlanner.FOLDER_SUFFIX);
            stats.redirectedToFolderSuffix++;
            log.conflict(label + " :: '" + root.relativize(target) + "' is a file, so the folder from '" + item
                    + "' goes to '" + folder.getFileName() + "'");
            mergeItem(item, folder, stats);
        } else {
            // A file wants the name of an existing folder: the folder moves to -folder, the file takes the name.
            Path folder = target.resolveSibling(target.getFileName() + ConflictPlanner.FOLDER_SUFFIX);
            stats.redirectedToFolderSuffix++;
            log.conflict(label + " :: '" + root.relativize(target) + "' is a folder, so it moves to '"
                    + folder.getFileName() + "' and the file from '" + item + "' takes its name");
            mergeItem(target, folder, stats);
            mergeItem(item, target, stats);
        }
    }

    /** The names differ only by case: keep both, the incoming file under the first free {@code name-N}. */
    private void moveToFreeName(Path item, Path target, Stats stats) throws IOException {
        Path candidate = target;
        while (true) {
            candidate = candidate.resolveSibling(FileNames.nextCollisionName(candidate.getFileName().toString()));
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) {
                Files.move(item, candidate);
                stats.renamed++;
                log.conflict(label + " :: name collision: a different file is already named '"
                        + root.relativize(target) + "' (this file system treats the names as the same). Moved as '"
                        + root.relativize(candidate) + "': " + item);
                return;
            }
            if (Files.isRegularFile(candidate, NOFOLLOW_LINKS) && sameContent(item, candidate)) {
                Files.delete(item);
                stats.duplicatesRemoved++;
                return;
            }
        }
    }

    private boolean sameContent(Path a, Path b) throws IOException {
        // Files already on disk were not written by this run, so name and size are the whole test, unless
        // the user asked for -verify crc.
        return Files.size(a) == Files.size(b) && (!useCrc || Checksums.crc32(a) == Checksums.crc32(b));
    }
}
