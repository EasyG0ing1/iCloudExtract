package com.simtechdata.records;

import java.nio.file.Path;
import java.util.List;

public record Resolved(List<Path> zips, List<String> warnings) {}
