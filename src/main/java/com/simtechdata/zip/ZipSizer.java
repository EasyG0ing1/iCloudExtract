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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Adds up how much space an extraction would need, without extracting anything (for {@code -getTotal}).
 *
 * <p>Only each zip's table of contents is read, never the file data, so it is fast. Every file entry goes through the
 * same steps as a real extraction (name decoding, file-and-folder conflict planning, dropping the mirror folder when
 * consolidating, name sanitizing) and is then placed by a {@link TreeSimulator}, which knows which entries would be
 * skipped as identical copies, lose to a different file, replace an older one, or be renamed.
 */
public final class ZipSizer {

    private ZipSizer() {
    }

    /**
     * @param zips            the zip files in the order they would be extracted
     * @param consolidate     whether the mirror folders would be dropped so all zips merge into one tree
     * @param overwrite       what happens when a different file is already at a path
     * @param caseInsensitive whether the destination file system ignores letter case in names
     * @param progress        receives a line of text for each zip as it is read
     */
    public static SizeReport measure(List<Path> zips, boolean consolidate, OverwriteMode overwrite,
                                     boolean caseInsensitive, Consumer<String> progress) {
        TreeSimulator tree = new TreeSimulator(overwrite, caseInsensitive);
        List<String> unreadableZips = new ArrayList<>();
        long fileEntries = 0;
        long entryBytes = 0;
        long unreadableEntries = 0;
        int zipsRead = 0;

        for (int n = 0; n < zips.size(); n++) {
            Path zip = zips.get(n);
            String zipName = zip.getFileName().toString();
            String mirror = EntryNames.mirrorName(zipName);
            progress.accept("[" + (n + 1) + "/" + zips.size() + "] Reading " + zipName);
            tree.startZip();

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
                    tree.add(sanitized, size, c.entry().getCrc(), c.entry().getTime());
                }
                zipsRead++;
            } catch (Exception e) {
                unreadableZips.add(zipName + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }

        return new SizeReport(zipsRead, fileEntries, entryBytes,
                tree.fileCount(), tree.totalBytes(),
                tree.identicalSkipped(), tree.identicalBytes(),
                tree.conflictsSkipped(), tree.conflictBytes(),
                tree.replacedFiles(), tree.replacedBytes(),
                tree.renamed(), unreadableEntries + tree.failed(), unreadableZips);
    }
}
