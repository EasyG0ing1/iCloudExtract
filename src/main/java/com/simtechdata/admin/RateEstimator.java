package com.simtechdata.admin;

import java.util.ArrayDeque;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Estimates how long a run still needs. The program tells it the grand total of bytes to deal with ({@link #expect})
 * and, as every file is finished, how many bytes that file was ({@link #handled}). The speed is the bytes handled per
 * second of real (wall-clock) time over the last minute or so, which already includes every thread working at once,
 * so no per-file timing is needed. The time left is the bytes still to handle divided by that speed.
 *
 * <p>A file counts as handled whether it was written or skipped (an identical copy is already there): both are
 * finished work and the total includes both, so the estimate stays honest when much of the data is already on disk.
 *
 * <p>Thread safe. The clock is a parameter so the arithmetic can be tested.
 */
public final class RateEstimator {

    /** How far back the speed looks. */
    static final long WINDOW_NANOS = 60_000_000_000L;
    /** The recent speed is trusted only when the window covers at least this much time; before that, the average so far. */
    static final long MIN_SPAN_NANOS = 3_000_000_000L;
    /** Files finishing closer together than this share one sample. */
    private static final long MERGE_NANOS = 100_000_000L;

    private final LongSupplier clock;
    /** Pairs of {nanoTime, bytes handled so far}, oldest first, none older than the window. */
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private long expected = -1;
    private long handled;
    /** When the run started (when this object was made): work that precedes the first finished file still took time. */
    private final long startNanos;

    public RateEstimator(LongSupplier clock) {
        this.clock = clock;
        this.startNanos = clock.getAsLong();
        samples.addLast(new long[] {startNanos, 0});
    }

    /** The grand total of bytes the run will handle; known once the size pass has finished. */
    public synchronized void expect(long totalBytes) {
        expected = Math.max(0, totalBytes);
    }

    /** True once {@link #expect} has been called. */
    public synchronized boolean hasTotal() {
        return expected >= 0;
    }

    /** A file of this many bytes has been dealt with (written, skipped or failed). */
    public synchronized void handled(long bytes) {
        long now = clock.getAsLong();
        handled += Math.max(0, bytes);
        long[] last = samples.peekLast();
        if (last != null && last.length == 2 && now - last[0] < MERGE_NANOS && samples.size() > 1) {
            last[1] = handled;   // many files finish within a moment: keep one sample for them
        } else {
            samples.addLast(new long[] {now, handled});
        }
        prune(now);
    }

    /** Bytes handled so far. */
    public synchronized long handledBytes() {
        return handled;
    }

    /**
     * Whole seconds left, or empty while there is not enough to go on: the total is not known yet, or nothing has
     * been handled, or no speed can be worked out.
     */
    public synchronized OptionalLong secondsRemaining() {
        if (expected < 0 || handled <= 0) {
            return OptionalLong.empty();
        }
        long now = clock.getAsLong();
        prune(now);
        long left = Math.max(0, expected - handled);
        if (left == 0) {
            return OptionalLong.of(0);
        }
        double bytesPerNano = 0;
        long[] oldest = samples.peekFirst();
        if (oldest != null && now - oldest[0] >= MIN_SPAN_NANOS) {
            bytesPerNano = (handled - oldest[1]) / (double) (now - oldest[0]);
        }
        if (bytesPerNano <= 0 && now > startNanos) {
            // Too early for a recent speed, or nothing finished lately: use the average since the run started.
            bytesPerNano = handled / (double) (now - startNanos);
        }
        if (bytesPerNano <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.round(left / bytesPerNano / 1_000_000_000.0));
    }

    /** Drops samples older than the window, but keeps one at or before its start as the anchor. */
    private void prune(long now) {
        while (samples.size() > 1) {
            var it = samples.iterator();
            it.next();
            long[] second = it.next();
            if (now - second[0] >= WINDOW_NANOS) {
                samples.removeFirst();
            } else {
                break;
            }
        }
    }
}
