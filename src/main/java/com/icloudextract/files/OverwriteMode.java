package com.icloudextract.files;

/** What to do when a different file is already at the place a file should go. */
public enum OverwriteMode {
    /** Existing files that differ are left alone and the conflict is logged. */
    NONE,
    /** Existing files that differ are replaced. */
    ALWAYS,
    /** Existing files are replaced only when the incoming file is newer. */
    NEWEST
}
