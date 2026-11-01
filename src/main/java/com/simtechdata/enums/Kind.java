package com.simtechdata.enums;

public enum Kind {
    /** Extract normally to the mapped path. */
    NORMAL,
    /** Zero-byte file that should have been a folder: do not write it. */
    PLACEHOLDER
}
