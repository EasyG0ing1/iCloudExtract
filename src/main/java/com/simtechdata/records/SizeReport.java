package com.simtechdata.records;

import java.util.List;

/**
 * What {@code -getTotal} found. All sizes are uncompressed bytes.
 *
 * <p>For ordinary archives {@code entryBytes} equals {@code extractedBytes + identicalBytes + conflictBytes
 * + replacedBytes}: every byte listed in the zips is either kept on disk, left out because an identical copy is
 * already there, left out because a different copy won, or written and then replaced by a later version.
 *
 * @param zipsRead          zip files that were read
 * @param fileEntries       file entries in those zips (folders not counted)
 * @param entryBytes        total size of all those file entries
 * @param extractedFiles    files that would be on disk when the run is finished
 * @param extractedBytes    their total size: the space the extraction needs
 * @param identicalSkipped  entries left out because an identical file would already be extracted
 * @param identicalBytes    their total size
 * @param conflictsSkipped  entries left out because a different file with the same path wins (overwrite off, or
 *                          the existing copy is not older)
 * @param conflictBytes     their total size
 * @param replacedFiles     files that would be written and later replaced by another version
 * @param replacedBytes     their total size
 * @param renamed           entries kept under a {@code name-N} name because of a name collision
 * @param unreadableEntries entries that could not be used (for example a path containing {@code ..})
 * @param unreadableZips    zip files that could not be opened, with the reason
 */
public record SizeReport(
        int zipsRead,
        long fileEntries,
        long entryBytes,
        long extractedFiles,
        long extractedBytes,
        long identicalSkipped,
        long identicalBytes,
        long conflictsSkipped,
        long conflictBytes,
        long replacedFiles,
        long replacedBytes,
        long renamed,
        long unreadableEntries,
        List<String> unreadableZips) {
}
