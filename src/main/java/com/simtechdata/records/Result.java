package com.simtechdata.records;

import com.simtechdata.enums.Outcome;

import java.nio.file.Path;

public record Result(String entryName, Path target, long size, long crc, Outcome outcome) {
}
