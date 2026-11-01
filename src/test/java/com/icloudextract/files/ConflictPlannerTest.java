package com.icloudextract.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ConflictPlannerTest {

    private static ConflictPlanner.Item file(String path, long size) {
        return new ConflictPlanner.Item(List.of(path.split("/")), false, size);
    }

    private static ConflictPlanner.Item dir(String path) {
        return new ConflictPlanner.Item(List.of(path.split("/")), true, 0);
    }

    @Test
    void realFileUsedAsFolderKeepsItsNameAndChildrenMoveToFolderSuffix() {
        var plan = ConflictPlanner.plan(List.of(
                file("Drive/Untitled.numbers", 148846),
                file("Drive/Untitled.numbers/preview.jpg", 99328),
                file("Drive/Untitled.numbers/Metadata/Properties.plist", 273),
                file("Drive/Other.numbers", 500)));

        assertEquals(Set.of("Drive/Untitled.numbers"), plan.legitimateFiles());
        assertEquals(List.of("Drive", "Untitled.numbers"), plan.targets().get(0));
        assertEquals(List.of("Drive", "Untitled.numbers-folder", "preview.jpg"), plan.targets().get(1));
        assertEquals(List.of("Drive", "Untitled.numbers-folder", "Metadata", "Properties.plist"),
                plan.targets().get(2));
        assertEquals(List.of("Drive", "Other.numbers"), plan.targets().get(3));
        assertTrue(plan.kinds().stream().allMatch(k -> k == ConflictPlanner.Kind.NORMAL));
    }

    @Test
    void emptyFileUsedAsFolderIsAPlaceholderAndIsNotRenamed() {
        var plan = ConflictPlanner.plan(List.of(
                file("Drive/Folder", 0),
                file("Drive/Folder/a.txt", 10)));

        assertEquals(Set.of("Drive/Folder"), plan.placeholders());
        assertTrue(plan.legitimateFiles().isEmpty());
        assertEquals(ConflictPlanner.Kind.PLACEHOLDER, plan.kinds().get(0));
        assertEquals(List.of("Drive", "Folder", "a.txt"), plan.targets().get(1));
    }

    @Test
    void childrenCameBeforeTheFileInTheArchive() {
        var plan = ConflictPlanner.plan(List.of(
                file("Drive/Doc.pages/Data/x.png", 10),
                file("Drive/Doc.pages", 1234)));

        assertEquals(List.of("Drive", "Doc.pages-folder", "Data", "x.png"), plan.targets().get(0));
        assertEquals(List.of("Drive", "Doc.pages"), plan.targets().get(1));
    }

    @Test
    void nestedConflictsAreRenamedAtEveryLevel() {
        var plan = ConflictPlanner.plan(List.of(
                file("a", 5),
                file("a/b", 5),
                file("a/b/c.txt", 5)));

        assertEquals(List.of("a"), plan.targets().get(0));
        assertEquals(List.of("a-folder", "b"), plan.targets().get(1));
        assertEquals(List.of("a-folder", "b-folder", "c.txt"), plan.targets().get(2));
    }

    @Test
    void explicitDirectoryEntryForALegitFileIsRenamedToo() {
        var plan = ConflictPlanner.plan(List.of(
                file("Doc.pages", 99),
                dir("Doc.pages")));

        assertEquals(List.of("Doc.pages"), plan.targets().get(0));
        assertEquals(List.of("Doc.pages-folder"), plan.targets().get(1));
    }

    @Test
    void entriesWithoutConflictsAreUntouched() {
        var plan = ConflictPlanner.plan(List.of(file("a/b.txt", 3), file("a/c.txt", 4), dir("a/d")));

        assertTrue(plan.legitimateFiles().isEmpty());
        assertTrue(plan.placeholders().isEmpty());
        assertEquals(List.of("a", "d"), plan.targets().get(2));
    }
}
