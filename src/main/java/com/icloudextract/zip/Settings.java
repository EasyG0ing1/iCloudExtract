package com.icloudextract.zip;

import com.icloudextract.files.OverwriteMode;

import java.nio.file.Path;

/** Resolved command-line settings. */
public record Settings(
        Path destination,
        VerifyMode verify,
        boolean consolidate,
        OverwriteMode overwrite,
        boolean checkZip,
        int threads) {

    public enum VerifyMode { NONE, SIZE, CRC }
}
