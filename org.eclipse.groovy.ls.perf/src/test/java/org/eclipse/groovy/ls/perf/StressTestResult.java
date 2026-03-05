package org.eclipse.groovy.ls.perf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extends {@link PerformanceResult} with success/rejection/timeout/error
 * counters for stress and load tests.  Provides a saturation ratio and
 * a structured summary suitable for Markdown reports.
 *
 * <p>Thread-safe: counters use {@link AtomicInteger} so concurrent test
 * threads can call {@link #recordSuccess(long)}, {@link #recordRejection()},
 * etc. without external synchronisation.</p>
 */
public class StressTestResult extends PerformanceResult {

    private final AtomicInteger successCount   = new AtomicInteger();
    private final AtomicInteger rejectionCount = new AtomicInteger();
    private final AtomicInteger timeoutCount   = new AtomicInteger();
    private final AtomicInteger errorCount     = new AtomicInteger();

    public StressTestResult(String featureName) {
        super(featureName);
    }

    // ---- Recording helpers ----

    /** Record a successful request with its latency. */
    public void recordSuccess(long elapsedMs) {
        record(elapsedMs);          // delegate to parent for percentile tracking
        successCount.incrementAndGet();
    }

    /** Record a rejected request (executor queue full). */
    public void recordRejection() {
        rejectionCount.incrementAndGet();
    }

    /** Record a timed-out request. */
    public void recordTimeout() {
        timeoutCount.incrementAndGet();
    }

    /** Record an unexpected error. */
    public void recordError() {
        errorCount.incrementAndGet();
    }

    // ---- Accessors ----

    public int getSuccessCount()   { return successCount.get(); }
    public int getRejectionCount() { return rejectionCount.get(); }
    public int getTimeoutCount()   { return timeoutCount.get(); }
    public int getErrorCount()     { return errorCount.get(); }

    /** Total requests attempted (all outcomes). */
    public int getTotalCount() {
        return getSuccessCount() + getRejectionCount() + getTimeoutCount() + getErrorCount();
    }

    /**
     * Fraction of requests that were <em>not</em> successful (0.0 = all ok,
     * 1.0 = all failed).
     */
    public double getSaturationRatio() {
        int total = getTotalCount();
        return total == 0 ? 0.0 : 1.0 - ((double) getSuccessCount() / total);
    }

    // ---- Threshold enforcement ----

    /**
     * Assert that the rejection count does not exceed the given limit.
     */
    public void assertMaxRejections(int limit) {
        int actual = getRejectionCount();
        if (actual > limit) {
            throw new AssertionError(String.format(
                    "STRESS FAILURE: %s — %d rejections exceeds limit %d%n" +
                    "  total=%d, success=%d, rejected=%d, timeout=%d, error=%d, saturation=%.1f%%",
                    getFeatureName(), actual, limit,
                    getTotalCount(), getSuccessCount(), getRejectionCount(),
                    getTimeoutCount(), getErrorCount(), getSaturationRatio() * 100));
        }
    }

    /**
     * Assert zero rejections — the queue should never overflow at this load.
     */
    public void assertNoRejections() {
        assertMaxRejections(0);
    }

    /**
     * Assert that the timeout count does not exceed the given limit.
     */
    public void assertMaxTimeouts(int limit) {
        int actual = getTimeoutCount();
        if (actual > limit) {
            throw new AssertionError(String.format(
                    "STRESS FAILURE: %s — %d timeouts exceeds limit %d%n" +
                    "  total=%d, success=%d, rejected=%d, timeout=%d, error=%d",
                    getFeatureName(), actual, limit,
                    getTotalCount(), getSuccessCount(), getRejectionCount(),
                    getTimeoutCount(), getErrorCount()));
        }
    }

    // ---- Reporting ----

    /**
     * Returns a single Markdown table row for this result.
     */
    public String toMarkdownRow() {
        return String.format("| %s | %d | %d | %d | %d | %d | %d | %d | %d | %.1f%% |",
                getFeatureName(),
                getTotalCount(),
                getSuccessCount(),
                getRejectionCount(),
                getTimeoutCount(),
                getErrorCount(),
                getIterationCount() > 0 ? getP50() : 0,
                getIterationCount() > 0 ? getP95() : 0,
                getIterationCount() > 0 ? getMax() : 0,
                getSaturationRatio() * 100);
    }

    /**
     * Returns the Markdown table header suitable for rows from
     * {@link #toMarkdownRow()}.
     */
    public static String markdownHeader() {
        return "| Scenario | Total | Success | Rejected | Timeout | Error | p50 ms | p95 ms | Max ms | Saturation |"
                + "\n|----------|-------|---------|----------|---------|-------|--------|--------|--------|------------|";
    }

    @Override
    public String toString() {
        return String.format(
                "%s: total=%d, success=%d, rejected=%d, timeout=%d, error=%d, saturation=%.1f%%"
                + (getIterationCount() > 0
                        ? ", p50=%d ms, p95=%d ms, max=%d ms"
                        : ""),
                getFeatureName(),
                getTotalCount(), getSuccessCount(), getRejectionCount(),
                getTimeoutCount(), getErrorCount(), getSaturationRatio() * 100,
                getIterationCount() > 0 ? getP50() : 0,
                getIterationCount() > 0 ? getP95() : 0,
                getIterationCount() > 0 ? getMax() : 0);
    }
}
