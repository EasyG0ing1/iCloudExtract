package com.icloudextract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves "this path is both a file and a folder" conflicts inside one zip.
 *
 * <p>Apple's export can contain an entry such as {@code Untitled.numbers} (a real file, itself a zip)
 * and, in the same archive, entries such as {@code Untitled.numbers/preview.jpg}. A filesystem cannot
 * hold both under one name, and the common extractors either abort or silently drop one side.
 *
 * <p>Rule applied here:
 * <ul>
 *   <li>A file entry whose path is also used as a folder is a <b>legitimate file</b> when it has
 *       content (size other than zero). It is extracted as a file, and every entry that lives
 *       <em>inside</em> that path is moved into a folder with {@code -folder} appended to the name,
 *       e.g. {@code Untitled.numbers-folder/preview.jpg}.</li>
 *   <li>A zero-byte file entry whose path is used as a folder is treated as a <b>placeholder</b> that
 *       should have been a folder. It is not written, and its children use the plain folder name.</li>
 * </ul>
 */
final class ConflictPlanner {

    static final String FOLDER_SUFFIX = "-folder";

    private ConflictPlanner() {
    }

    /** One zip entry, reduced to what the planner needs. */
    record Item(List<String> segments, boolean directory, long size) {
    }

    enum Kind {
        /** Extract normally to the mapped path. */
        NORMAL,
        /** Zero-byte file that should have been a folder: do not write it. */
        PLACEHOLDER
    }

    /** Planning result; {@code targets} and {@code kinds} are parallel to the input items. */
    record Plan(List<List<String>> targets, List<Kind> kinds, Set<String> legitimateFiles, Set<String> placeholders) {
    }

    static Plan plan(List<Item> items) {
        // Every path that is used as a folder: ancestors of any entry, plus explicit directory entries.
        Set<String> folders = new HashSet<>();
        for (Item item : items) {
            StringBuilder key = new StringBuilder();
            List<String> segs = item.segments();
            for (int i = 0; i < segs.size(); i++) {
                if (i > 0) {
                    key.append('/');
                }
                key.append(segs.get(i));
                boolean isLast = i == segs.size() - 1;
                if (!isLast || item.directory()) {
                    folders.add(key.toString());
                }
            }
        }

        Set<String> legit = new HashSet<>();
        Set<String> placeholders = new HashSet<>();
        for (Item item : items) {
            if (item.directory() || item.segments().isEmpty()) {
                continue;
            }
            String key = String.join("/", item.segments());
            if (folders.contains(key)) {
                if (item.size() == 0) {
                    placeholders.add(key);
                } else {
                    legit.add(key);
                }
            }
        }

        List<List<String>> targets = new ArrayList<>(items.size());
        List<Kind> kinds = new ArrayList<>(items.size());
        for (Item item : items) {
            List<String> segs = item.segments();
            List<String> mapped = new ArrayList<>(segs.size());
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < segs.size(); i++) {
                if (i > 0) {
                    key.append('/');
                }
                key.append(segs.get(i));
                boolean isLast = i == segs.size() - 1;
                String seg = segs.get(i);
                if ((!isLast || item.directory()) && legit.contains(key.toString())) {
                    seg = seg + FOLDER_SUFFIX;
                }
                mapped.add(seg);
            }
            targets.add(mapped);
            boolean placeholder = !item.directory() && !segs.isEmpty()
                    && placeholders.contains(String.join("/", segs));
            kinds.add(placeholder ? Kind.PLACEHOLDER : Kind.NORMAL);
        }
        return new Plan(targets, kinds, legit, placeholders);
    }
}
