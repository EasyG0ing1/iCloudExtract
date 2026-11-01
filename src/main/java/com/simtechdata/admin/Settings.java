package com.simtechdata.admin;

import com.simtechdata.enums.OverwriteMode;
import com.simtechdata.enums.VerifyMode;

import java.nio.file.Path;

/** Resolved command-line settings. */
public record Settings(
        Path destination,
        VerifyMode verify,
        boolean consolidate,
        OverwriteMode overwrite,
        boolean checkZip,
        int threads) {

    /** The same settings with another thread count. */
    public Settings withThreads(int n) {
        return new Settings(destination, verify, consolidate, overwrite, checkZip, n);
    }
}
