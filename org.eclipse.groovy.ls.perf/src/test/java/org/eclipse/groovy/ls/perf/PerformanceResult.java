package org.eclipse.groovy.ls.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures latency measurements for a single performance benchmark and
 * provides statistical analysis (p50, p95, p99, max) with threshold enforcement.
 */
public class PerformanceResult {

    private final String featureName;
    private final List<Long> latenciesMs = new ArrayList<>();

    public PerformanceResult(String featureName) {
        this.featureName = featureName;
    }

    /** Record a single measurement in milliseconds. */
    public void record(long elapsedMs) {
        latenciesMs.add(elapsedMs);
    }

    public String getFeatureName() {
        return featureName;
    }

    public int getIterationCount() {
        return latenciesMs.size();
    }

    public List<Long> getLatencies() {
        return Collections.unmodifiableList(latenciesMs);
    }

    // ---- Statistical accessors ----

    public long getMin() {
        return sorted().isEmpty() ? 0 : sorted().get(0);
    }

    public long getMax() {
        return sorted().isEmpty() ? 0 : sorted().get(sorted().size() - 1);
    }

    public double getMean() {
        return latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    public long getP50() {
        return percentile(50);
    }

    public long getP95() {
        return percentile(95);
    }

    public long getP99() {
        return percentile(99);
    }

    /**
     * Compute the nth percentile (0–100) of recorded latencies.
     */
    public long percentile(int n) {
        List<Long> s = sorted();
        if (s.isEmpty()) return 0;
        int idx = (int) Math.ceil(n / 100.0 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(idx, s.size() - 1)));
    }

    public double getThroughputOpsPerSec() {
        long totalMs = latenciesMs.stream().mapToLong(Long::longValue).sum();
        if (totalMs == 0) return 0;
        return (latenciesMs.size() * 1000.0) / totalMs;
    }

    // ---- Threshold enforcement ----

    /**
     * Assert that the p95 latency is within the given limit.
     *
     * @param p95LimitMs maximum acceptable p95 latency in milliseconds
     * @throws AssertionError if the threshold is exceeded, with a detailed message
     */
    public void assertP95Threshold(long p95LimitMs) {
        long actual = getP95();
        if (actual > p95LimitMs) {
            throw new AssertionError(String.format(
                    "PERFORMANCE REGRESSION: %s — p95 latency %d ms exceeds threshold %d ms%n" +
                    "  iterations=%d, p50=%d ms, p95=%d ms, p99=%d ms, max=%d ms, mean=%.1f ms",
                    featureName, actual, p95LimitMs,
                    getIterationCount(), getP50(), getP95(), getP99(), getMax(), getMean()));
        }
    }

    /**
     * Assert that the max latency is within the given limit.
     */
    public void assertMaxThreshold(long maxLimitMs) {
        long actual = getMax();
        if (actual > maxLimitMs) {
            throw new AssertionError(String.format(
                    "PERFORMANCE REGRESSION: %s — max latency %d ms exceeds threshold %d ms%n" +
                    "  iterations=%d, p50=%d ms, p95=%d ms, p99=%d ms, max=%d ms, mean=%.1f ms",
                    featureName, actual, maxLimitMs,
                    getIterationCount(), getP50(), getP95(), getP99(), getMax(), getMean()));
        }
    }

    @Override
    public String toString() {
        return String.format("%s: n=%d, p50=%d ms, p95=%d ms, p99=%d ms, max=%d ms, mean=%.1f ms, throughput=%.2f ops/s",
                featureName, getIterationCount(), getP50(), getP95(), getP99(), getMax(), getMean(), getThroughputOpsPerSec());
    }

    // ---- Internal ----

    private List<Long> sortedCache;

    private List<Long> sorted() {
        if (sortedCache == null || sortedCache.size() != latenciesMs.size()) {
            sortedCache = new ArrayList<>(latenciesMs);
            Collections.sort(sortedCache);
        }
        return sortedCache;
    }
}
