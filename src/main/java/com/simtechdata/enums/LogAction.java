package com.simtechdata.enums;

/** What the program did about it: the {@code Action} column. */
public enum LogAction {
    INFO("Info"),
    WARNING("Warning"),
    SKIPPED("Skipped"),
    OVERWRITTEN("Overwritten"),
    RENAMED("Renamed"),
    REDIRECTED("Redirected"),
    MOVED("Moved"),
    LEFT_IN_PLACE("Left in place"),
    NOT_WRITTEN("Not written"),
    FAILED("Failed");

    private final String label;

    LogAction(String label) {
        this.label = label;
    }

    /** The text written in the log. */
    public String label() {
        return label;
    }
}
