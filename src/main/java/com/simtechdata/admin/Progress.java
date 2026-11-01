package com.simtechdata.admin;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * What the user sees while the program runs: permanent lines for each step, and (on a terminal) a spinning
 * line {@code / - \ |} with the running time as HH:MM:SS, how many zip files are done out of the total, an estimate
 * of the time left, and the current activity. Without a terminal there is no spinner, only the permanent lines.
 */
public final class Progress implements AutoCloseable {

    /** Prints plain lines only; used where no progress display is wanted. */
    public static final Progress NONE = new Progress(false);

    private static final char[] SPINNER = {'/', '-', '\\', '|'};

    private final boolean animate;
    private final LongSupplier clock;
    private final long startNanos;
    private final RateEstimator estimator;
    private final Set<String> active = new LinkedHashSet<>();
    private String activity = "";
    private int totalZips;
    private int doneZips;
    private boolean estimating;
    private int shownWidth;
    private Thread thread;
    private volatile boolean running;

    public Progress(boolean animate) {
        this(animate, System::nanoTime);
    }

    /** The clock is a parameter so the display can be tested. */
    Progress(boolean animate, LongSupplier clock) {
        this.animate = animate;
        this.clock = clock;
        this.startNanos = clock.getAsLong();
        this.estimator = new RateEstimator(clock);
    }

    /** True when the spinner line is shown (standard output is a terminal). */
    public boolean animated() {
        return animate;
    }

    /** How many zip files this run will unzip; shown as {@code (done/total)} on the spinner line. */
    public synchronized void totalZips(int total) {
        totalZips = total;
    }

    /** Turns on the "est. HH:MM:SS left" part of the spinner line; it reads {@code --:--:--} until the total is known. */
    public synchronized void estimating() {
        estimating = true;
    }

    /** The grand total of bytes the run will deal with, once it is known. */
    public void expectBytes(long totalBytes) {
        estimator.expect(totalBytes);
    }

    /** A file of this many bytes is finished (written, skipped or failed). Called from every extraction thread. */
    public void bytesHandled(long bytes) {
        estimator.handled(bytes);
    }

    /** True when standard output is an interactive terminal. */
    public static boolean onTerminal() {
        if (System.console() == null) {
            return false;
        }
        try {
            // Console.isTerminal() exists from Java 22; before that a non-null console meant a terminal.
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(System.console());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    public static String clock(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public String elapsed() {
        return clock(Duration.ofNanos(clock.getAsLong() - startNanos).toSeconds());
    }

    public synchronized void start() {
        if (!animate || running) {
            return;
        }
        running = true;
        thread = new Thread(() -> {
            int tick = 0;
            while (running) {
                render(SPINNER[tick++ % SPINNER.length]);
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "progress");
        thread.setDaemon(true);
        thread.start();
    }

    /** Sets what the spinner line says is going on, e.g. while files are being moved. */
    public synchronized void activity(String text) {
        activity = text;
    }

    /** A zip file is being unzipped; the spinner line lists every zip in progress. */
    public synchronized void begin(String zip) {
        active.add(zip);
        activity = "Unzipping " + String.join(", ", active);
    }

    public synchronized void end(String zip) {
        if (active.remove(zip)) {
            doneZips++;
        }
        activity = active.isEmpty() ? "" : "Unzipping " + String.join(", ", active);
    }

    /** A permanent line on standard output. */
    public synchronized void line(String text) {
        clearSpinner();
        System.out.println(text);
    }

    /** A permanent line on standard error. */
    public synchronized void error(String text) {
        clearSpinner();
        System.err.println(text);
    }

    private synchronized void render(char spin) {
        if (!running) {
            return;
        }
        String text = statusText(spin);
        StringBuilder sb = new StringBuilder("\r").append(text);
        for (int i = text.length(); i < shownWidth; i++) {
            sb.append(' ');
        }
        shownWidth = Math.max(shownWidth, text.length());
        System.out.print(sb);
        System.out.flush();
    }

    /** The spinner line: spinner, time, estimate, zips done, and what is going on (cut to fit 79 columns). */
    synchronized String statusText(char spin) {
        StringBuilder text = new StringBuilder().append(spin).append(' ').append(elapsed());
        if (estimating) {
            OptionalLong left = estimator.secondsRemaining();
            text.append("  est. ").append(left.isPresent() ? clock(left.getAsLong()) : "--:--:--").append(" left");
        }
        if (totalZips > 0) {
            text.append("  (").append(doneZips).append('/').append(totalZips).append(')');
        }
        if (!activity.isEmpty()) {
            text.append("  ").append(activity);
        }
        String result = text.toString();
        return result.length() > 79 ? result.substring(0, 76) + "..." : result;
    }

    private void clearSpinner() {
        if (shownWidth > 0) {
            System.out.print("\r" + " ".repeat(shownWidth) + "\r");
            shownWidth = 0;
        }
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
            clearSpinner();
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
