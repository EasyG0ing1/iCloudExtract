package com.simtechdata.admin;

import com.simtechdata.enums.StorageKind;

/**
 * Picks the number of zip files to handle at the same time when the user did not give {@code -threads}: one per CPU
 * core when every drive involved is an SSD, otherwise one (more threads slow a hard drive down).
 */
public final class ThreadChooser {

    /** The number to use and a line telling the user how it was decided. */
    public record Choice(int threads, String message) {
    }

    private ThreadChooser() {
    }

    /**
     * @param source      the drive the zip files are read from
     * @param destination the drive the files are written to, or null when nothing is written ({@code -getTotal})
     * @param cores       the number of CPU cores
     */
    public static Choice choose(StorageKind source, StorageKind destination, int cores) {
        int cpu = Math.max(1, cores);
        boolean writes = destination != null;
        if (source == StorageKind.SSD && (!writes || destination == StorageKind.SSD)) {
            return new Choice(cpu, "Threads: " + cpu + " (one per CPU core; "
                    + (writes ? "the zip files and the destination are both on SSDs" : "the zip files are on an SSD")
                    + "). Use -threads <n> to choose another number.");
        }
        String why;
        if (source == StorageKind.HDD) {
            why = "the zip files are on a hard drive";
        } else if (writes && destination == StorageKind.HDD) {
            why = "the destination is on a hard drive";
        } else if (source == StorageKind.UNKNOWN) {
            why = "could not tell what kind of drive the zip files are on";
        } else {
            why = "could not tell what kind of drive the destination is on";
        }
        return new Choice(1, "Threads: 1 (" + why + "). Use -threads <n> to choose another number.");
    }
}
