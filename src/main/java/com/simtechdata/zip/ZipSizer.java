package com.simtechdata.zip;

import com.simtechdata.enums.Kind;
import com.simtechdata.enums.OverwriteMode;
import com.simtechdata.files.ConflictPlanner;
import com.simtechdata.files.FileNames;
import com.simtechdata.files.TreeSimulator;
import com.simtechdata.records.Candidate;
import com.simtechdata.records.Item;
import com.simtechdata.records.Plan;
import com.simtechdata.records.SizeReport;
import com.simtechdata.records.ZipFileMeta;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Adds up how much space an extraction would need, without extracting anything (for {@code -getTotal}).
 *
 * <p>Two phases:
 * <ol>
 *   <li><b>Reading</b>, one zip per thread. Only the zip's table of contents is read, never the file data. Each file
 *       entry goes through the same steps as a real extraction (name decoding, file-and-folder conflict planning,
 *       dropping the mirror folder when consolidating, name sanitizing) and becomes a {@link ZipFileMeta}. A thread
 *       builds its own list and, when its zip is done, adds the whole list to the shared master list.</li>
 *   <li><b>Counting</b>, on one thread, after every reader is done. The master list is put in extraction order (zip
 *       number, then position in the zip) and played through a {@link TreeSimulator}, which knows which files would
 *       be skipped as identical copies, lose to a different file, replace an older one, or be renamed.</li>
 * </ol>
 * Whether a file counts depends on the zips before it, so the totals of the zips cannot just be added: only this
 * ordered counting gives the real figure, and it gives the same figure for any number of threads.
 */
public final class ZipSizer {

    private ZipSizer() {
    }

    /** What one reading thread reports back about its zip (the files themselves go to the master list). */
    private record Scan(String zipName, int zipNumber, long fileEntries, long entryBytes, long unreadableEntries,
                        String error) {
    }

    /**
     * @param zips            the zip files in the order they would be extracted
     * @param consolidate     whether the mirror folders would be dropped so all zips merge into one tree
     * @param overwrite       what happens when a different file is already at a path
     * @param caseInsensitive whether the destination file system ignores letter case in names
     * @param threads         how many zips to read at the same time (at least 1)
     * @param progress        receives a line of text when a thread starts on a zip and another when it has finished
     *                        it (possibly from several threads at once)
     */
    public static SizeReport measure(List<Path> zips, boolean consolidate, OverwriteMode overwrite,
                                     boolean caseInsensitive, int threads, Consumer<String> progress) {
        // --- phase 1: read the zips, one per thread, into the master list
        List<ZipFileMeta> master = Collections.synchronizedList(new ArrayList<>());
        List<Scan> scans = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        int total = zips.size();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, Math.max(1, total))));
        try {
            List<Future<Scan>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int number = i + 1;
                final Path zip = zips.get(i);
                futures.add(pool.submit(() -> {
                    // Said at once, so the user sees every zip that is being read right now.
                    progress.accept("Reading " + zip.getFileName() + " ...");
                    List<ZipFileMeta> mine = new ArrayList<>();
                    Scan scan = scan(zip, number, consolidate, mine);
                    master.addAll(mine);
                    // Counting and printing happen together, so the lines always come out as 1, 2, 3 ... even when
                    // several zips finish at the same moment.
                    synchronized (finished) {
                        int done = finished.incrementAndGet();
                        int remaining = total - done;
                        progress.accept("[" + done + "/" + total + "] Finished " + scan.zipName()
                                + (scan.error() == null ? "" : "  (could not be read)")
                                + " - " + remaining + " zip file" + (remaining == 1 ? "" : "s") + " remaining");
                    }
                    return scan;
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    scans.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    scans.add(new Scan(zips.get(i).getFileName().toString(), i + 1, 0, 0, 0, String.valueOf(e.getCause())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    scans.add(new Scan(zips.get(i).getFileName().toString(), i + 1, 0, 0, 0, "interrupted"));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // --- phase 2: extraction order, then the mock extraction
        List<ZipFileMeta> ordered;
        synchronized (master) {
            ordered = new ArrayList<>(master);
        }
        ordered.sort(Comparator.comparingInt(ZipFileMeta::zipNumber).thenComparingInt(ZipFileMeta::entryNumber));

        TreeSimulator tree = new TreeSimulator(overwrite, caseInsensitive);
        int currentZip = -1;
        for (ZipFileMeta file : ordered) {
            if (file.zipNumber() != currentZip) {
                tree.startZip();
                currentZip = file.zipNumber();
            }
            tree.add(file.path(), file.extractedSize(), file.crc(), file.time());
        }

        long fileEntries = 0;
        long entryBytes = 0;
        long unreadableEntries = 0;
        int zipsRead = 0;
        List<String> unreadableZips = new ArrayList<>();
        for (Scan s : scans) {
            fileEntries += s.fileEntries();
            entryBytes += s.entryBytes();
            unreadableEntries += s.unreadableEntries();
            if (s.error() == null) {
                zipsRead++;
            } else {
                unreadableZips.add(s.zipName() + ": " + s.error());
            }
        }
        return new SizeReport(zipsRead, fileEntries, entryBytes,
                tree.fileCount(), tree.totalBytes(),
                tree.identicalSkipped(), tree.identicalBytes(),
                tree.conflictsSkipped(), tree.conflictBytes(),
                tree.replacedFiles(), tree.replacedBytes(),
                tree.renamed(), unreadableEntries + tree.failed(), unreadableZips);
    }

    /** Reads one zip's table of contents and adds a {@link ZipFileMeta} to {@code out} for each file it would write. */
    private static Scan scan(Path zip, int zipNumber, boolean consolidate, List<ZipFileMeta> out) {
        String zipName = zip.getFileName().toString();
        String mirror = EntryNames.mirrorName(zipName);
        long fileEntries = 0;
        long entryBytes = 0;
        long unreadableEntries = 0;

        try (ZipFile zf = ZipFile.builder()
                .setPath(zip)
                // Same as the extraction: Latin-1 opens any archive, the raw name bytes are decoded by us.
                .setCharset(StandardCharsets.ISO_8859_1)
                .setUseUnicodeExtraFields(false)
                .get()) {

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
                        unreadableEntries++;
                    }
                    continue;
                }
                if (!segs.isEmpty()) {
                    candidates.add(new Candidate(e, name, dir, segs));
                }
            }

            List<Item> items = new ArrayList<>(candidates.size());
            for (Candidate c : candidates) {
                items.add(new Item(c.segments(), c.directory(), c.entry().getSize()));
            }
            Plan plan = ConflictPlanner.plan(items);

            int entryNumber = 0;
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                if (c.directory()) {
                    continue;
                }
                List<String> mapped = plan.targets().get(i);
                if (consolidate) {
                    mapped = EntryNames.withoutMirror(mapped, mirror);
                    if (mapped.isEmpty()) {
                        continue;
                    }
                }
                long size = Math.max(0, c.entry().getSize());
                fileEntries++;
                entryBytes += size;
                if (plan.kinds().get(i) == Kind.PLACEHOLDER) {
                    continue;
                }
                List<String> sanitized = new ArrayList<>(mapped.size());
                for (String segment : mapped) {
                    sanitized.add(FileNames.sanitize(segment));
                }
                out.add(new ZipFileMeta(zipNumber, zipName, entryNumber++, List.copyOf(sanitized), size,
                        c.entry().getCrc(), c.entry().getTime()));
            }
            return new Scan(zipName, zipNumber, fileEntries, entryBytes, unreadableEntries, null);
        } catch (Exception e) {
            out.clear();
            return new Scan(zipName, zipNumber, 0, 0, 0, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
