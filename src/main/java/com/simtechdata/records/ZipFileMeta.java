package com.simtechdata.records;

import java.util.List;

/**
 * One file that a zip would extract, as {@code -getTotal} needs it. Each reading thread builds a list of these for
 * its zip; once all threads are done, the lists are put in extraction order and played through the simulator.
 *
 * @param zipNumber     position of the zip in the extraction order, starting at 1
 * @param zipName       file name of the zip, for messages
 * @param entryNumber   position of this file among the zip's file entries, in the order they are stored in the zip
 * @param path          where the file would be written, one name per element: already planned for file/folder
 *                      conflicts, without the mirror folder when consolidating, and sanitized for the file system
 * @param extractedSize uncompressed size in bytes
 * @param crc           CRC-32 recorded in the zip, or -1 when unknown
 * @param time          modification time in milliseconds recorded in the zip, or -1 when unknown
 */
public record ZipFileMeta(
        int zipNumber,
        String zipName,
        int entryNumber,
        List<String> path,
        long extractedSize,
        long crc,
        long time) {
}
