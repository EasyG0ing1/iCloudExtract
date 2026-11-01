package com.simtechdata.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ProgressTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(5_000 * SECOND);
    private final Progress progress = new Progress(true, now::get);

    private void advance(long seconds) {
        now.addAndGet(seconds * SECOND);
    }

    @Test
    void theLineStartsWithTheSpinnerAndTheTime() {
        assertEquals("/ 00:00:00", progress.statusText('/'));
        advance(3725);
        assertEquals("- 01:02:05", progress.statusText('-'));
    }

    @Test
    void zipsDoneAreShownOutOfTheTotal() {
        progress.totalZips(18);
        assertEquals("/ 00:00:00  (0/18)", progress.statusText('/'));
        progress.begin("Part 1.zip");
        progress.begin("Part 2.zip");
        progress.end("Part 1.zip");
        assertEquals("/ 00:00:00  (1/18)  Unzipping Part 2.zip", progress.statusText('/'));
        progress.end("Part 2.zip");
        assertEquals("/ 00:00:00  (2/18)", progress.statusText('/'));
    }

    @Test
    void endingAZipThatNeverStartedDoesNotCountIt() {
        progress.totalZips(3);
        progress.end("Part 9.zip");
        assertEquals("/ 00:00:00  (0/3)", progress.statusText('/'));
    }

    @Test
    void theEstimateShowsDashesUntilTheTotalIsKnown() {
        progress.totalZips(18);
        progress.estimating();
        progress.begin("Part 1.zip");
        assertEquals("/ 00:00:00  est. --:--:-- left  (0/18)  Unzipping Part 1.zip", progress.statusText('/'));
        advance(1);
        progress.bytesHandled(100);
        advance(1);
        assertTrue(progress.statusText('/').contains("est. --:--:-- left"), "no total yet");
    }

    @Test
    void theEstimateAppearsOnceTheTotalIsKnown() {
        progress.totalZips(18);
        progress.estimating();
        progress.begin("Part 1.zip");
        progress.expectBytes(1_000);
        for (int i = 0; i < 10; i++) {
            advance(1);
            progress.bytesHandled(50);
        }
        // 500 of 1000 bytes in 10 s: 10 s left
        assertEquals("/ 00:00:10  est. 00:00:10 left  (0/18)  Unzipping Part 1.zip", progress.statusText('/'));
    }

    @Test
    void theEstimateIsLeftOutWhenNotRequested() {
        progress.expectBytes(1_000);
        progress.bytesHandled(10);
        assertEquals("/ 00:00:00", progress.statusText('/'));
    }

    @Test
    void aLongLineIsCutToFitTheScreen() {
        progress.totalZips(18);
        for (int n = 1; n <= 18; n++) {
            progress.begin("iCloud Drive Part " + n + " of 18.zip");
        }
        String line = progress.statusText('/');
        assertEquals(79, line.length());
        assertTrue(line.endsWith("..."));
    }

    @Test
    void clockFormatsHoursMinutesSeconds() {
        assertEquals("00:00:00", Progress.clock(0));
        assertEquals("00:01:05", Progress.clock(65));
        assertEquals("12:00:01", Progress.clock(43201));
    }
}
