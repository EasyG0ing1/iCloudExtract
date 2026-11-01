package com.simtechdata.enums;

/** Why a line is in the log: the {@code Reason} column. */
public enum LogReason {
    RUN("Run"),
    WARNING("Warning"),
    ERROR("Error"),
    INTEGRITY("Integrity check failed"),
    DIFFERENT_FILE("Different file at the same path"),
    IDENTICAL_COPY("Identical copy"),
    NAME_COLLISION("Name collision"),
    FILE_AND_FOLDER("File and folder share a name"),
    EMPTY_PLACEHOLDER("Empty file used as a folder"),
    VERIFY("Verify"),
    ZIP_FINISHED("Zip finished"),
    CONSOLIDATE("Consolidate");

    private final String label;

    LogReason(String label) {
        this.label = label;
    }

    /** The text written in the log. */
    public String label() {
        return label;
    }
}
