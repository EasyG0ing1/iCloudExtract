package com.icloudextract;

import java.nio.file.Path;

/** Resolved command-line settings. */
record Settings(
        Path destination,
        VerifyMode verify,
        boolean consolidate,
        OverwriteMode overwrite,
        boolean checkZip,
        int threads) {

    enum VerifyMode { NONE, SIZE, CRC }

    enum OverwriteMode {
        /** Existing files that differ are left alone and the conflict is logged. */
        NONE,
        /** Existing files that differ are replaced. */
        ALWAYS,
        /** Existing files are replaced only when the zip entry is newer. */
        NEWEST
    }
}
