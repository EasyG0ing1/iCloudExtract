package com.simtechdata.records;

import com.simtechdata.enums.Kind;

import java.util.List;
import java.util.Set;

/**
 * Planning result; {@code targets} and {@code kinds} are parallel to the input items.
 */
public record Plan(List<List<String>> targets, List<Kind> kinds, Set<String> legitimateFiles, Set<String> placeholders) {
}
