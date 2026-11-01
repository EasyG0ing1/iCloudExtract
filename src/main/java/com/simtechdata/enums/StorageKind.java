package com.simtechdata.enums;

/** What kind of drive a path lives on. */
public enum StorageKind {
    /** A solid state drive (SATA, NVMe, USB). */
    SSD,
    /** A spinning hard drive ("bare metal"). */
    HDD,
    /** Could not be determined (network share, virtual disk, a USB adapter that hides the drive type, ...). */
    UNKNOWN
}
