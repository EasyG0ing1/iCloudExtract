package com.simtechdata.zip;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.simtechdata.admin.Progress;
import com.simtechdata.admin.Settings;
import com.simtechdata.enums.*;
import com.simtechdata.files.Checksums;
import com.simtechdata.files.ConflictPlanner;
import com.simtechdata.files.FileNames;
import com.simtechdata.files.FolderMerger;
import com.simtechdata.admin.RunLog;
import com.simtechdata.records.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Extracts one zip file. Every entry is handled on its own: a failure is logged and counted, and the
 * remaining entries are still extracted (the opposite of {@code unzip}, which can abandon the rest of an
 * archive after one bad entry).
 *
 * <p>One instance is shared by all worker threads; each zip is handled by exactly one thread.
 */
public final class ZipExtractor {

    private static final int MAX_CONSOLE_ERRORS_PER_ZIP = 20;
    private static final int LOCK_STRIPES = 256;
    private static final Object DIR_LOCK = new Object();

    private final Settings settings;
    private final RunLog   log;
    private final Path     root;
    private final Progress progress;
    private final Object[] pathLocks = new Object[LOCK_STRIPES];
    private final Set<Path> redirectsLogged = ConcurrentHashMap.newKeySet();

    /** Files written by this run (key: see {@link #key}) mapped to the zip they came from. */
    private final Map<String, String> writtenThisRun = new ConcurrentHashMap<>();

    public ZipExtractor(Settings settings, RunLog log) {
        this(settings, log, Progress.NONE);
    }

    public ZipExtractor(Settings settings, RunLog log, Progress progress) {
        this.settings = settings;
        this.log = log;
        this.progress = progress;
        this.root = settings.destination().toAbsolutePath().normalize();
        for (int i = 0; i < LOCK_STRIPES; i++) {
            pathLocks[i] = new Object();
        }
    }

    public ZipReport extract(Path zip) {
        String zipName = zip.getFileName().toString();
        ZipReport report = new ZipReport(zipName);
        String mirror = EntryNames.mirrorName(zipName);

        try (ZipFile zf = ZipFile.builder()
                .setPath(zip)
                // Latin-1 can decode any byte, so opening never fails on odd names; we decode the raw
                // name bytes ourselves (see EntryNames.decode).
                .setCharset(StandardCharsets.ISO_8859_1)
                .setUseUnicodeExtraFields(false)
                .get()) {

            if (settings.checkZip() && !checkIntegrity(zf, report)) {
                report.integrityFailed = true;
                return report;
            }

            List<Candidate> candidates = readEntries(zf, report, zipName);
            Plan plan = planConflicts(candidates, report, zipName);

            boolean verifying = settings.verify() != VerifyMode.NONE;
            List<Result> results = new ArrayList<>();
            // Paths this zip has already written (or found already in place); a second entry for the same
            // path is a different file that must not replace the first.
            Set<Path> handledByThisZip = new HashSet<>();

            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                List<String> mapped = plan.targets().get(i);
                if (settings.consolidate()) {
                    // Consolidating: the folder that mirrors the zip's file name is not part of the data.
                    mapped = EntryNames.withoutMirror(mapped, mirror);
                    if (mapped.isEmpty()) {
                        continue;
                    }
                }
                try {
                    if (c.directory()) {
                        report.directories++;
                        resolveFolder(mapped, zipName);
                        continue;
                    }
                    report.entries++;
                    if (plan.kinds().get(i) == Kind.PLACEHOLDER) {
                        report.placeholders++;
                        continue;
                    }
                    Result r = extractFile(zf, c, mapped, report, zipName, handledByThisZip);
                    if (verifying) {
                        results.add(r);
                    }
                } catch (Exception e) {
                    fail(report, zipName, c.name(), e);
                }
            }

            if (verifying) {
                verify(results, report, zipName);
            }
        } catch (Exception e) {
            report.fatal = e.toString();
            log.error(zipName + " :: could not process archive: " + e);
        }
        return report;
    }

    // --- reading and planning

    private List<Candidate> readEntries(ZipFile zf, ZipReport report, String zipName) {
        List<Candidate> candidates = new ArrayList<>();
        Enumeration<ZipArchiveEntry> en = zf.getEntriesInPhysicalOrder();
        while (en.hasMoreElements()) {
            ZipArchiveEntry e = en.nextElement();
            String name = EntryNames.decode(e.getRawName());
            boolean dir = EntryNames.isDirectoryName(name) || e.isDirectory();
            List<String> segs;
            try {
                segs = EntryNames.segments(name);
            } catch (EntryNames.UnsafeNameException ex) {
                if (!dir) {
                    report.entries++;
                }
                fail(report, zipName, name, ex);
                continue;
            }
            if (segs.isEmpty()) {
                continue;
            }
            candidates.add(new Candidate(e, name, dir, segs));
        }
        return candidates;
    }

    private Plan planConflicts(List<Candidate> candidates, ZipReport report, String zipName) {
        List<Item> items = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            items.add(new Item(c.segments(), c.directory(), c.entry().getSize()));
        }
        Plan plan = ConflictPlanner.plan(items);
        report.folderRedirects = plan.legitimateFiles().size();
        for (String path : plan.legitimateFiles()) {
            log.conflict(zipName + " :: '" + path + "' is both a file and a folder. Kept as a file; "
                    + "its contents go to '" + path + ConflictPlanner.FOLDER_SUFFIX + "'");
        }
        for (String path : plan.placeholders()) {
            log.conflict(zipName + " :: '" + path + "' is an empty file that is also used as a folder. "
                    + "Treated as a folder and not written");
        }
        return plan;
    }

    // --- integrity (-checkzip)

    private boolean checkIntegrity(ZipFile zf, ZipReport report) {
        boolean ok = true;
        byte[] buffer = new byte[1 << 20];
        Enumeration<ZipArchiveEntry> en = zf.getEntriesInPhysicalOrder();
        while (en.hasMoreElements()) {
            ZipArchiveEntry e = en.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String name = EntryNames.decode(e.getRawName());
            try {
                if (!zf.canReadEntryData(e)) {
                    throw new IOException("unsupported compression method or encryption");
                }
                CRC32 crc = new CRC32();
                long total = 0;
                try (InputStream in = zf.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        crc.update(buffer, 0, n);
                        total += n;
                    }
                }
                if (e.getSize() >= 0 && total != e.getSize()) {
                    throw new IOException("size " + total + " does not match recorded size " + e.getSize());
                }
                if (e.getCrc() >= 0 && crc.getValue() != e.getCrc()) {
                    throw new IOException("CRC does not match the recorded value");
                }
            } catch (IOException | RuntimeException ex) {
                ok = false;
                log.error(report.name + " :: integrity check failed for '" + name + "': " + ex.getMessage());
                printError(report, report.name + " :: integrity check failed for '" + name + "': " + ex.getMessage());
            }
        }
        return ok;
    }

    // --- extraction

    /** Creates (and returns) the folder for a path, redirecting around any file that is in the way. */
    private Path resolveFolder(List<String> segments, String zipName) throws IOException {
        Path current = root;
        for (String raw : segments) {
            String seg = FileNames.sanitize(raw);
            Path p = current.resolve(seg);
            if (Files.isDirectory(p, NOFOLLOW_LINKS)) {
                current = p;
                continue;
            }
            if (Files.exists(p, NOFOLLOW_LINKS)) {
                String redirected = seg + ConflictPlanner.FOLDER_SUFFIX;
                Path q = current.resolve(redirected);
                if (Files.exists(q, NOFOLLOW_LINKS) && !Files.isDirectory(q, NOFOLLOW_LINKS)) {
                    throw new IOException("cannot create folder '" + seg + "': both it and '" + redirected
                            + "' exist as files");
                }
                if (redirectsLogged.add(p)) {
                    log.conflict(zipName + " :: a file named '" + root.relativize(p)
                            + "' already exists where a folder is needed; using '" + redirected + "' instead");
                }
                p = q;
            }
            Files.createDirectories(p);
            current = p;
        }
        if (!current.normalize().startsWith(root)) {
            throw new IOException("path escapes the destination folder");
        }
        return current;
    }

    private Result extractFile(ZipFile zf, Candidate c, List<String> mapped, ZipReport report, String zipName,
                               Set<Path> handledByThisZip) throws IOException {
        ZipArchiveEntry e = c.entry();
        Path parent = resolveFolder(mapped.subList(0, mapped.size() - 1), zipName);
        Path target = parent.resolve(FileNames.sanitize(mapped.getLast()));
        if (!target.normalize().startsWith(root)) {
            throw new IOException("path escapes the destination folder");
        }
        String relative = root.relativize(target).toString();
        long size = e.getSize();
        long crc = e.getCrc();

        if (Files.isDirectory(target, NOFOLLOW_LINKS)) {
            moveDirectoryAside(target, zipName);
        }

        Decision first = decide(target, e, handledByThisZip);
        if (first == Decision.RENAME) {
            // A different file already has this name, either because this zip lists the name twice or
            // because the file system treats names that differ only by letter case as the same name.
            Renamed renamed = freeName(target, e);
            String taken = relative;
            target = renamed.path();
            relative = root.relativize(target).toString();
            if (renamed.identical()) {
                handledByThisZip.add(target);
                return skipped(Decision.SKIP_IDENTICAL, zipName, relative, target, c, report);
            }
            report.renamed++;
            log.conflict(zipName + " :: name collision: a different file is already named '" + taken
                    + "' (this file system treats the names as the same). Written as '" + relative + "'");
            first = Decision.NEW;
        }
        if (first == Decision.SKIP_IDENTICAL) {
            handledByThisZip.add(target);
            return skipped(first, zipName, relative, target, c, report);
        }
        if (first == Decision.SKIP_CONFLICT) {
            return skipped(first, zipName, relative, target, c, report);
        }

        if (!zf.canReadEntryData(e)) {
            throw new IOException("unsupported compression method or encryption");
        }

        Path tmp = Files.createTempFile(target.getParent(), ".icx-", ".part");
        try {
            CRC32 streamed = new CRC32();
            long written = 0;
            byte[] buffer = new byte[1 << 20];
            try (InputStream in = zf.getInputStream(e); OutputStream out = Files.newOutputStream(tmp)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    streamed.update(buffer, 0, n);
                    written += n;
                }
            }
            if (size >= 0 && written != size) {
                throw new IOException("wrote " + written + " bytes but the zip records " + size);
            }
            if (crc >= 0 && streamed.getValue() != crc) {
                throw new IOException("CRC of the extracted data does not match the zip's recorded CRC");
            }
            long time = e.getTime();
            if (time >= 0) {
                Files.setLastModifiedTime(tmp, FileTime.fromMillis(time));
            }

            synchronized (lockFor(target)) {
                // Decide again under the lock: another thread may have written this path meanwhile.
                Decision second = decide(target, e, handledByThisZip);
                switch (second) {
                    case NEW -> {
                        Files.move(tmp, target);
                        handledByThisZip.add(target);
                        writtenThisRun.put(key(target), zipName);
                        report.extracted++;
                        return new Result(c.name(), target, size, crc, Outcome.EXTRACTED);
                    }
                    case OVERWRITE -> {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                        handledByThisZip.add(target);
                        writtenThisRun.put(key(target), zipName);
                        report.overwritten++;
                        log.conflict(zipName + " :: overwritten: " + relative);
                        return new Result(c.name(), target, size, crc, Outcome.OVERWRITTEN);
                    }
                    case SKIP_IDENTICAL -> {
                        handledByThisZip.add(target);
                        return skipped(second, zipName, relative, target, c, report);
                    }
                    default -> {
                        // SKIP_CONFLICT, or a RENAME-worthy collision that appeared after we decided
                        // (only possible when several zips are extracted at once).
                        return skipped(Decision.SKIP_CONFLICT, zipName, relative, target, c, report);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Finds the first {@code name-N} that is free, or that already holds an identical copy of the entry. */
    private Renamed freeName(Path target, ZipArchiveEntry e) throws IOException {
        Path candidate = target;
        while (true) {
            candidate = candidate.resolveSibling(FileNames.nextCollisionName(candidate.getFileName().toString()));
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) {
                return new Renamed(candidate, false);
            }
            if (Files.isRegularFile(candidate, NOFOLLOW_LINKS) && isIdentical(candidate, e)) {
                return new Renamed(candidate, true);
            }
        }
    }

    /**
     * A file this run wrote is compared by CRC-32. A file that was already there is trusted on its name
     * and size alone (an earlier run put it there, and reading tens of thousands of files back is slow),
     * unless the user asked for {@code -verify crc}.
     */
    private boolean isIdentical(Path file, ZipArchiveEntry e) throws IOException {
        if (e.getSize() < 0 || Files.size(file) != e.getSize()) {
            return false;
        }
        boolean checkCrc = settings.verify() == VerifyMode.CRC || writtenThisRun.containsKey(key(file));
        return !checkCrc || (e.getCrc() >= 0 && Checksums.crc32(file) == e.getCrc());
    }

    /** Names the file system treats as the same (letter case, Unicode normalization) share a key. */
    private static String key(Path p) {
        return Normalizer.normalize(p.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private Decision decide(Path target, ZipArchiveEntry e, Set<Path> handledByThisZip) throws IOException {
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            return Decision.NEW;
        }
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            return Decision.SKIP_CONFLICT;
        }
        if (isIdentical(target, e)) {
            return Decision.SKIP_IDENTICAL;
        }
        if (handledByThisZip.contains(target) || FileNames.isCaseVariant(target)) {
            return Decision.RENAME;
        }
        return switch (settings.overwrite()) {
            case NONE -> Decision.SKIP_CONFLICT;
            case ALWAYS -> Decision.OVERWRITE;
            case NEWEST -> e.getTime() > Files.getLastModifiedTime(target).toMillis()
                    ? Decision.OVERWRITE : Decision.SKIP_CONFLICT;
        };
    }

    private Result skipped(Decision decision, String zipName, String relative, Path target, Candidate c,
                           ZipReport report) {
        long size = c.entry().getSize();
        long crc = c.entry().getCrc();
        if (decision == Decision.SKIP_IDENTICAL) {
            report.skippedIdentical++;
            // Only a copy this run wrote is worth a log line; a file that was already on disk is skipped silently.
            String source = writtenThisRun.get(key(target));
            if (source != null) {
                log.conflict(zipName + " :: skipped, identical to the copy already extracted from " + source
                        + " in this run (CRC " + String.format("%08X", crc) + "): " + relative);
            }
            return new Result(c.name(), target, size, crc, Outcome.SKIPPED_IDENTICAL);
        }
        report.skippedConflict++;
        String why = settings.overwrite() == OverwriteMode.NONE
                ? "a different file already exists (use -overwrite to replace it)"
                : "the existing file is the same age or newer";
        log.conflict(zipName + " :: skipped, " + why + ": " + relative);
        return new Result(c.name(), target, size, crc, Outcome.SKIPPED_CONFLICT);
    }

    /** One lock per path, shared by names that differ only by letter case so they are written one at a time. */
    private Object lockFor(Path target) {
        return pathLocks[(target.toString().toLowerCase(Locale.ROOT).hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    /** A file is about to be written where an earlier extraction left a folder: move the folder aside. */
    private void moveDirectoryAside(Path dir, String zipName) throws IOException {
        synchronized (DIR_LOCK) {
            if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) {
                return;
            }
            Path folder = dir.resolveSibling(dir.getFileName() + ConflictPlanner.FOLDER_SUFFIX);
            log.conflict(zipName + " :: a folder named '" + root.relativize(dir) + "' already exists but this zip "
                    + "has a file with that name. Moving the folder to '" + folder.getFileName() + "'");
            new FolderMerger(root, log, zipName, settings.overwrite(),
                    settings.verify() == VerifyMode.CRC).mergeItem(dir, folder, new FolderMerger.Stats());
        }
    }

    // ---------------------------------------------------------------- verification (-verify)

    private void verify(List<Result> results, ZipReport report, String zipName) {
        boolean crcMode = settings.verify() == VerifyMode.CRC;
        for (Result r : results) {
            if (r.outcome() == Outcome.SKIPPED_CONFLICT) {
                report.verifyUnchecked++;
                log.warn(zipName + " :: not verified, a different file was kept at: " + root.relativize(r.target()));
                continue;
            }
            String problem = null;
            try {
                if (!Files.isRegularFile(r.target(), NOFOLLOW_LINKS)) {
                    problem = "file is missing";
                } else if (r.size() >= 0 && Files.size(r.target()) != r.size()) {
                    problem = "size is " + Files.size(r.target()) + " but the zip records " + r.size();
                } else if (crcMode && r.crc() >= 0 && Checksums.crc32(r.target()) != r.crc()) {
                    problem = "CRC does not match the zip's recorded value";
                }
            } catch (IOException e) {
                problem = "could not be read: " + e.getMessage();
            }
            if (problem == null) {
                report.verified++;
            } else {
                report.verifyFailed++;
                String message = zipName + " :: verify failed for '" + root.relativize(r.target()) + "': " + problem;
                log.error(message);
                printError(report, message);
            }
        }
    }

    // ---------------------------------------------------------------- errors

    private void fail(ZipReport report, String zipName, String entryName, Exception e) {
        report.failed++;
        String detail = e.getMessage() != null ? e.getMessage() : e.toString();
        String message = zipName + " :: could not extract '" + entryName + "': " + detail;
        log.error(message);
        printError(report, message);
    }

    private void printError(ZipReport report, String message) {
        if (report.errorsPrinted++ < MAX_CONSOLE_ERRORS_PER_ZIP) {
            progress.error("  ERROR " + message);
        } else if (report.errorsPrinted == MAX_CONSOLE_ERRORS_PER_ZIP + 1) {
            progress.error("  (further errors for " + report.name + " are in " + RunLog.RUN_LOG_NAME + ")");
        }
    }
}
