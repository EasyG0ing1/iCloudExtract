package com.simtechdata.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateEstimatorTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final RateEstimator estimator = new RateEstimator(now::get);

    private void advance(long seconds) {
        now.addAndGet(seconds * SECOND);
    }

    @Test
    void thereIsNoEstimateUntilTheTotalAndSomeProgressAreKnown() {
        assertFalse(estimator.secondsRemaining().isPresent());
        estimator.handled(100);                       // progress, but no total yet
        assertFalse(estimator.secondsRemaining().isPresent());

        RateEstimator other = new RateEstimator(now::get);
        other.expect(1_000);                          // a total, but nothing handled yet
        assertFalse(other.secondsRemaining().isPresent());
    }

    @Test
    void aSteadySpeedGivesTheTimeLeft() {
        estimator.expect(1_000);
        for (int i = 0; i < 10; i++) {
            advance(1);
            estimator.handled(50);                    // 50 bytes a second
        }
        // 500 of 1000 handled after 10 s: 500 left at 50 per second = 10 s
        assertEquals(10, estimator.secondsRemaining().getAsLong());
    }

    @Test
    void theEstimateFollowsTheRecentSpeedNotTheOldAverage() {
        estimator.expect(100_000);
        // two minutes at 10 bytes a second ...
        for (int i = 0; i < 120; i++) {
            advance(1);
            estimator.handled(10);
        }
        // ... then 30 seconds at 1000 bytes a second
        for (int i = 0; i < 30; i++) {
            advance(1);
            estimator.handled(1_000);
        }
        long handled = estimator.handledBytes();
        long left = 100_000 - handled;
        long seconds = estimator.secondsRemaining().getAsLong();
        // The old average (about 214 bytes a second) would say about 328 s; the last minute holds 30 s at 1000 and
        // 30 s at 10, about 505 bytes a second, so roughly 130 s.
        assertTrue(seconds < left / 214, "should be faster than the long-run average, was " + seconds);
        assertTrue(seconds > left / 1000, "should be slower than the newest speed alone, was " + seconds);
    }

    @Test
    void whenNothingFinishesForAWhileTheAverageSoFarIsUsed() {
        estimator.expect(10_000);
        advance(1);
        estimator.handled(1_000);                     // 1000 bytes in the first second
        advance(120);                                 // then two quiet minutes (one huge file in progress)
        long seconds = estimator.secondsRemaining().getAsLong();
        // average so far: 1000 bytes in 121 s; 9000 left, so about 1089 s
        assertTrue(seconds > 1_000 && seconds < 1_200, "was " + seconds);
    }

    @Test
    void earlyOnTheAverageSinceTheRunStartedIsUsed() {
        estimator.expect(1_000);
        estimator.handled(0);
        advance(2);
        estimator.handled(100);                       // 100 bytes in 2 s, window still shorter than 3 s
        assertEquals(18, estimator.secondsRemaining().getAsLong());   // 900 left at 50 a second
    }

    @Test
    void finishedWorkMeansNothingLeft() {
        estimator.expect(100);
        advance(1);
        estimator.handled(100);
        assertEquals(0, estimator.secondsRemaining().getAsLong());
        advance(1);
        estimator.handled(50);                        // more than the total (the total was a little low)
        assertEquals(0, estimator.secondsRemaining().getAsLong());
    }

    @Test
    void manyFilesFinishingAtOnceDoNotGrowTheSampleListWithoutLimit() {
        estimator.expect(10_000_000);
        for (int i = 0; i < 100_000; i++) {
            estimator.handled(10);                    // all at the same instant
        }
        advance(10);
        assertTrue(estimator.secondsRemaining().isPresent());
        assertEquals(1_000_000, estimator.handledBytes());
    }

    @Test
    void severalThreadsMayReportAtTheSameTime() throws Exception {
        estimator.expect(8_000);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1_000; i++) {
                    estimator.handled(1);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(8_000, estimator.handledBytes());
    }
}
