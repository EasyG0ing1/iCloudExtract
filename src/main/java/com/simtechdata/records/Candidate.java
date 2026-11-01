package com.simtechdata.records;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

import java.util.List;

public record Candidate(ZipArchiveEntry entry, String name, boolean directory, List<String> segments) {
}
