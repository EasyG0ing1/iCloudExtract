package com.simtechdata.records;

import java.util.List;

/** One zip entry, reduced to what the planner needs. */

public record Item(List<String> segments, boolean directory, long size) {
}

