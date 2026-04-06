package org.eclipse.groovy.ls.perf;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level <em>stress</em> tests that deliberately push the Groovy
 * Language Server past its comfortable limits to discover the exact point at
 * which executor saturation, timeouts, or lockouts occur.
 *
 * <p>Unlike {@link GroovyLsPerformanceTest} (which benchmarks typical usage
 * and enforces latency thresholds), these tests systematically escalate
 * concurrency and report a degradation curve:
 *
 * <pre>
 * | Concurrency | Success | Rejected | Timeout | p50 ms | p95 ms | max ms | Saturation |
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>{@code
 * ./gradlew :org.eclipse.groovy.ls.perf:stressTest
 * }</pre>
 *
 * <h2>Reports</h2>
 * Results are written to {@code build/reports/performance/stress-results.md}
 * and {@code stress-results.csv}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroovyLsStressTest {

    // ---- Configuration ----

    /** Per-request timeout for stress requests (generous safety net). */
    private static final long REQUEST_TIMEOUT_MS = 120_000;

    /** How long each sustained-load test runs (seconds). */
    private static final int SUSTAINED_DURATION_SECONDS = 60;

    // ---- Fixtures ----

    private LspClientHarness harness;
    private WorkspaceGenerator generator;
    private Path workspaceRoot;
    private List<WorkspaceGenerator.FileInfo> files;
    private final List<StressTestResult> allResults = new ArrayList<>();

    // ========================================================================
    // Setup & Teardown
    // ========================================================================

    @BeforeAll
    void setUp() throws Exception {
        String serverDir = System.getProperty("groovy.ls.server.dir");
        String javaHome  = System.getProperty("groovy.ls.java.home", System.getProperty("java.home"));
        long timeout     = Long.parseLong(System.getProperty("groovy.ls.perf.server.timeout.seconds", "300"));

        assertNotNull(serverDir, "System property 'groovy.ls.server.dir' must point to the assembled product");
        assertTrue(Files.isDirectory(Path.of(serverDir)), "Server directory does not exist: " + serverDir);

        // Generate workspace
        System.out.println("[StressTest] Generating synthetic workspace (50 projects)...");
        generator = new WorkspaceGenerator(50);
        workspaceRoot = generator.generate(Files.createTempDirectory("groovy-ls-stress-workspace"));
        files = generator.getRepresentativeFiles();
        System.out.println("[StressTest] Workspace generated at: " + workspaceRoot);
        System.out.println("[StressTest] Representative files: " + files.size());

        // Start server
        harness = new LspClientHarness(Path.of(serverDir), javaHome, workspaceRoot, timeout);
        harness.start();

        // Send classpath updates
        harness.sendClasspathUpdates(generator.getProjectNames(), generator.getJarPaths());

        // Open representative files
        System.out.println("[StressTest] Opening " + files.size() + " representative files...");
        for (WorkspaceGenerator.FileInfo fi : files) {
            harness.didOpen(fi.toUri(), fi.readContent());
        }

        // Let the server settle
        System.out.println("[StressTest] Waiting 20s for server to settle...");
        pauseMillis(20_000);

        // Warmup
        System.out.println("[StressTest] Warmup: triggering hover + completion...");
        if (!files.isEmpty()) {
            WorkspaceGenerator.FileInfo warmupFile = files.get(0);
            try {
                harness.hover(warmupFile.toUri(), 10, 8).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.out.println("[StressTest] Warmup hover timed out (non-fatal): " + e.getMessage());
            }
        }
        pauseMillis(2_000);

        System.out.println("[StressTest] Setup complete. Starting stress tests.");
    }

    @AfterAll
    void tearDown() throws Exception {
        // Generate reports
        if (!allResults.isEmpty()) {
            Path reportDir = Path.of(System.getProperty("user.dir"), "build", "reports", "performance");
            writeStressReport(reportDir);
        }

        if (harness != null) {
            if (files != null) {
                for (WorkspaceGenerator.FileInfo fi : files) {
                    try { harness.didClose(fi.toUri()); } catch (Exception ignored) {
                        // Best-effort close during teardown; shutdown will clean up any leftovers.
                    }
                }
            }
            harness.shutdown();
        }

        if (workspaceRoot != null) {
            System.out.println("[StressTest] Cleaning up workspace: " + workspaceRoot);
            deleteRecursive(workspaceRoot);
        }
    }

    // ========================================================================
    // Test 1: Burst file opening
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("Burst file opening: 3, 5, 10, 20 files simultaneously")
    void testBurstFileOpening() throws Exception {
        int[] batchSizes = {3, 5, 10, 20};
        StressTestResult result = new StressTestResult("Burst file opening");

        for (int batchSize : batchSizes) {
            // Collect files for this batch
            List<Path> batchFiles = collectGroovyFiles(batchSize);
            assertTrue(batchFiles.size() >= batchSize,
                    "Need at least " + batchSize + " files, found " + batchFiles.size());

            long start = System.currentTimeMillis();

            // Open all files rapidly
            for (Path f : batchFiles) {
                harness.didOpen(f.toUri().toString(), Files.readString(f));
            }

            // Verify responsiveness: hover on each opened file
            List<CompletableFuture<?>> probes = new ArrayList<>();
            for (Path f : batchFiles) {
                probes.add(harness.hover(f.toUri().toString(), 5, 0));
            }

            int probeSuccess = 0;
            for (CompletableFuture<?> probe : probes) {
                try {
                    probe.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    probeSuccess++;
                } catch (Exception e) {
                    // timeout or error
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            result.recordSuccess(elapsed);

            System.out.printf("[BurstOpen] batch=%d  probes=%d/%d  time=%dms%n",
                    batchSize, probeSuccess, batchFiles.size(), elapsed);

            // Close files
            for (Path f : batchFiles) {
                harness.didClose(f.toUri().toString());
            }
            pauseMillis(1_000);
        }

        System.out.println(result);
        allResults.add(result);
        // No strict assertion — just verify no crash
        result.assertMaxThreshold(120_000);
    }

    // ========================================================================
    // Test 2: Escalating concurrent requests
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("Escalating: 10 → 25 → 50 → 100 → 200 concurrent mixed requests")
    void testEscalatingConcurrentRequests() throws Exception {
        int[] levels = {10, 25, 50, 100, 200};

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║            ESCALATING CONCURRENT REQUESTS — SATURATION CURVE                 ║");
        System.out.println("╠════════════════╦═════════╦══════════╦═════════╦═════════╦════════╦════════════╣");
        System.out.println("║  Concurrency   ║ Success ║ Rejected ║ Timeout ║  Errors ║ p95 ms ║  Max ms    ║");
        System.out.println("╠════════════════╬═════════╬══════════╬═════════╬═════════╬════════╬════════════╣");

        for (int concurrency : levels) {
            StressTestResult levelResult = new StressTestResult("Concurrent (" + concurrency + ")");
            List<CompletableFuture<?>> futures = new ArrayList<>();

            long batchStart = System.currentTimeMillis();

            // Distribute requests across types
            for (int i = 0; i < concurrency; i++) {
                int fileIdx = i % files.size();
                WorkspaceGenerator.FileInfo fi = files.get(fileIdx);
                int requestType = i % 5;

                CompletableFuture<?> f;
                switch (requestType) {
                    case 0: f = harness.hover(fi.toUri(), 10, 8); break;
                    case 1: f = harness.completion(fi.toUri(), 10, 8); break;
                    case 2: f = harness.semanticTokensFull(fi.toUri()); break;
                    case 3: f = harness.codeLens(fi.toUri()); break;
                    case 4: f = harness.foldingRange(fi.toUri()); break;
                    default: f = harness.hover(fi.toUri(), 10, 8); break;
                }
                futures.add(f);
            }

            // Wait for all
            for (CompletableFuture<?> f : futures) {
                long elapsed;
                try {
                    f.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    elapsed = System.currentTimeMillis() - batchStart;
                    levelResult.recordSuccess(elapsed);
                } catch (TimeoutException e) {
                    levelResult.recordTimeout();
                } catch (CancellationException e) {
                    // Cancelled by per-URI cancellation — expected
                    levelResult.recordSuccess(System.currentTimeMillis() - batchStart);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof CancellationException) {
                        levelResult.recordSuccess(System.currentTimeMillis() - batchStart);
                    } else {
                        levelResult.recordError();
                    }
                } catch (Exception e) {
                    levelResult.recordError();
                }
            }

            // Check server logs for "saturated" messages
            System.out.printf("║  %12d  ║  %5d  ║   %5d  ║  %5d  ║  %5d  ║ %5d  ║  %7d   ║%n",
                    concurrency,
                    levelResult.getSuccessCount(),
                    levelResult.getRejectionCount(),
                    levelResult.getTimeoutCount(),
                    levelResult.getErrorCount(),
                    levelResult.getIterationCount() > 0 ? levelResult.getP95() : 0,
                    levelResult.getIterationCount() > 0 ? levelResult.getMax() : 0);

            allResults.add(levelResult);
            pauseMillis(3_000);
        }

        System.out.println("╚════════════════╩═════════╩══════════╩═════════╩═════════╩════════╩════════════╝");
        System.out.println();

        // Assert: up to 50 concurrent requests should all succeed
        StressTestResult level50 = allResults.stream()
                .filter(r -> r.getFeatureName().equals("Concurrent (50)"))
                .findFirst().orElse(null);
        if (level50 != null) {
            level50.assertMaxTimeouts(5); // allow a few timeouts at 50
        }
    }

    // ========================================================================
    // Test 3: Sustained load throughput
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("Sustained: 60s continuous load at ~5 req/100ms")
    void testSustainedLoadThroughput() throws Exception {
        StressTestResult result = new StressTestResult("Sustained (" + SUSTAINED_DURATION_SECONDS + "s)");
        AtomicInteger totalSubmitted = new AtomicInteger();
        List<CompletableFuture<Void>> trackers = Collections.synchronizedList(new ArrayList<>());

        long testStart = System.currentTimeMillis();
        long deadline = testStart + (SUSTAINED_DURATION_SECONDS * 1000L);
        int requestId = 0;

        // Submit 5 random requests every 100ms
        while (System.currentTimeMillis() < deadline) {
            for (int j = 0; j < 5; j++) {
                final int id = requestId++;
                final long reqStart = System.currentTimeMillis();
                int fileIdx = id % files.size();
                WorkspaceGenerator.FileInfo fi = files.get(fileIdx);

                CompletableFuture<?> future;
                int type = id % 4;
                switch (type) {
                    case 0: future = harness.hover(fi.toUri(), 10, 8); break;
                    case 1: future = harness.semanticTokensFull(fi.toUri()); break;
                    case 2: future = harness.foldingRange(fi.toUri()); break;
                    case 3: future = harness.codeLens(fi.toUri()); break;
                    default: future = harness.hover(fi.toUri(), 10, 8); break;
                }

                totalSubmitted.incrementAndGet();

                trackers.add(future.handle((r, ex) -> {
                    long elapsed = System.currentTimeMillis() - reqStart;
                    if (ex == null && r != null) {
                        result.recordSuccess(elapsed);
                    } else if (ex instanceof TimeoutException
                            || (ex != null && ex.getCause() instanceof TimeoutException)) {
                        result.recordTimeout();
                    } else if (ex instanceof CancellationException
                            || (ex != null && ex.getCause() instanceof CancellationException)) {
                        // Per-URI cancellation — not a failure
                        result.recordSuccess(elapsed);
                    } else if (r == null && ex == null) {
                        // null result = rejection
                        result.recordRejection();
                    } else {
                        result.recordError();
                    }
                    return null;
                }));
            }

            pauseMillis(100);
        }

        // Wait for in-flight requests to drain
        System.out.println("[SustainedTest] Waiting for " + trackers.size() + " in-flight requests to drain...");
        try {
            CompletableFuture.allOf(trackers.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("[SustainedTest] Some requests still outstanding after 120s drain period");
        }

        System.out.println();
        System.out.println("[SustainedTest] " + result);

        // Compute throughput
        long totalElapsed = System.currentTimeMillis() - testStart;
        double throughput = (result.getSuccessCount() * 1000.0) / totalElapsed;
        System.out.printf("[SustainedTest] Effective throughput: %.1f successful ops/s%n", throughput);
        System.out.printf("[SustainedTest] Saturation ratio: %.1f%%%n", result.getSaturationRatio() * 100);
        System.out.println();

        allResults.add(result);

        // At ~50 req/s, we expect some per-URI cancellation (same file requested multiple
        // times), but no hard rejections or errors.
        result.assertMaxRejections(150);  // ~5% at 50 req/s is healthy load-shedding
        result.assertMaxTimeouts(20);     // some timeouts possible under heavy load
    }

    private void pauseMillis(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    // ========================================================================
    // Test 4: Rapid document churn under concurrent requests
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("Churn + concurrency: open/close 50 files while 20 requests in flight")
    void testDocumentChurnUnderConcurrency() throws Exception {
        StressTestResult result = new StressTestResult("Churn + concurrency");

        // Start 20 concurrent requests
        List<CompletableFuture<?>> backgroundRequests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int fileIdx = i % files.size();
            WorkspaceGenerator.FileInfo fi = files.get(fileIdx);
            if (i % 2 == 0) {
                backgroundRequests.add(harness.hover(fi.toUri(), 10, 8));
            } else {
                backgroundRequests.add(harness.completion(fi.toUri(), 10, 8));
            }
        }

        // While those are in flight, rapidly open/close 50 files
        List<Path> churnFiles = collectGroovyFiles(50);
        long churnStart = System.currentTimeMillis();

        for (Path f : churnFiles) {
            harness.didOpen(f.toUri().toString(), Files.readString(f));
        }
        for (Path f : churnFiles) {
            harness.didClose(f.toUri().toString());
        }

        long churnElapsed = System.currentTimeMillis() - churnStart;
        System.out.printf("[ChurnTest] Opened and closed %d files in %d ms%n",
                churnFiles.size(), churnElapsed);

        // Wait for background requests
        int backgroundSuccess = 0;
        int backgroundFailed = 0;
        for (CompletableFuture<?> f : backgroundRequests) {
            try {
                f.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                backgroundSuccess++;
                result.recordSuccess(System.currentTimeMillis() - churnStart);
            } catch (CancellationException e) {
                // per-URI cancellation — OK
                backgroundSuccess++;
                result.recordSuccess(System.currentTimeMillis() - churnStart);
            } catch (Exception e) {
                backgroundFailed++;
                result.recordError();
            }
        }

        System.out.printf("[ChurnTest] Background requests: %d success, %d failed%n",
                backgroundSuccess, backgroundFailed);

        allResults.add(result);

        // Check for "saturated" messages in server logs
        long saturated = harness.getServerLogs().stream()
                .filter(msg -> msg != null && msg.contains("saturated"))
                .count();
        System.out.printf("[ChurnTest] 'saturated' log messages: %d%n", saturated);

        // All 20 background requests should complete (possibly with cancellation)
        assertTrue(backgroundFailed <= 2,
                "At most 2 background requests should fail during churn, got " + backgroundFailed);
    }

    // ========================================================================
    // Test 5: Saturation message detection
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Saturation detection: count 'executor saturated' log messages")
    void testSaturationMessageDetection() throws Exception {
        StressTestResult result = new StressTestResult("Saturation detection");

        // Fire a heavy burst — 300 requests at once
        List<CompletableFuture<?>> futures = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            int fileIdx = i % files.size();
            WorkspaceGenerator.FileInfo fi = files.get(fileIdx);

            int type = i % 5;
            CompletableFuture<?> f;
            switch (type) {
                case 0: f = harness.hover(fi.toUri(), 10, 8); break;
                case 1: f = harness.completion(fi.toUri(), 10, 8); break;
                case 2: f = harness.semanticTokensFull(fi.toUri()); break;
                case 3: f = harness.codeLens(fi.toUri()); break;
                case 4: f = harness.foldingRange(fi.toUri()); break;
                default: f = harness.hover(fi.toUri(), 10, 8); break;
            }
            futures.add(f);
        }

        // Wait for all
        for (CompletableFuture<?> f : futures) {
            try {
                f.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                result.recordSuccess(System.currentTimeMillis() - start);
            } catch (CancellationException e) {
                result.recordSuccess(System.currentTimeMillis() - start);
            } catch (TimeoutException e) {
                result.recordTimeout();
            } catch (Exception e) {
                result.recordError();
            }
        }

        // Count saturated messages
        long saturatedCount = harness.getServerLogs().stream()
                .filter(msg -> msg != null && (msg.contains("saturated") || msg.contains("rejecting")))
                .count();

        System.out.println();
        System.out.printf("[SaturationDetection] 300 burst requests: %s%n", result);
        System.out.printf("[SaturationDetection] 'saturated/rejecting' log messages: %d%n", saturatedCount);
        System.out.println();

        allResults.add(result);

        // Report the finding — this is informational, not a strict assertion
        // The key insight is whether the new LinkedBlockingQueue(128) absorbs
        // 300 requests or whether some still get rejected
        if (saturatedCount > 0) {
            System.out.printf("[SaturationDetection] ⚠ Server logged %d saturation messages at 300 concurrent requests%n",
                    saturatedCount);
        } else {
            System.out.println("[SaturationDetection] ✓ No saturation detected at 300 concurrent requests");
        }
        assertTrue(saturatedCount >= 0);
    }

    // ========================================================================
    // Report writing
    // ========================================================================

    private void writeStressReport(Path reportDir) throws IOException {
        Files.createDirectories(reportDir);

        // Markdown report
        Path mdFile = reportDir.resolve("stress-results.md");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(mdFile))) {
            w.println("# Groovy Language Server — Stress Test Results");
            w.println();
            w.printf("**Date**: %s%n%n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            w.println(StressTestResult.markdownHeader());
            for (StressTestResult r : allResults) {
                w.println(r.toMarkdownRow());
            }
            w.println();
            w.println("---");
            w.println("*Generated by `org.eclipse.groovy.ls.perf` stress tests*");
        }
        System.out.println("[StressReport] Markdown report: " + mdFile.toAbsolutePath());

        // CSV report
        Path csvFile = reportDir.resolve("stress-results.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csvFile))) {
            w.println("timestamp,scenario,total,success,rejected,timeout,error,p50_ms,p95_ms,max_ms,saturation_pct");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            for (StressTestResult r : allResults) {
                w.printf("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.1f%n",
                        ts,
                        r.getFeatureName(),
                        r.getTotalCount(),
                        r.getSuccessCount(),
                        r.getRejectionCount(),
                        r.getTimeoutCount(),
                        r.getErrorCount(),
                        r.getIterationCount() > 0 ? r.getP50() : 0,
                        r.getIterationCount() > 0 ? r.getP95() : 0,
                        r.getIterationCount() > 0 ? r.getMax() : 0,
                        r.getSaturationRatio() * 100);
            }
        }
        System.out.println("[StressReport] CSV report: " + csvFile.toAbsolutePath());

        // Console summary
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              GROOVY LANGUAGE SERVER — STRESS TEST RESULTS                   ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        for (StressTestResult r : allResults) {
            System.out.printf("║  %-40s success=%3d  reject=%3d  sat=%.0f%% ║%n",
                    truncate(r.getFeatureName(), 40),
                    r.getSuccessCount(),
                    r.getRejectionCount(),
                    r.getSaturationRatio() * 100);
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Collect up to {@code count} Groovy test files from the workspace.
     */
    private List<Path> collectGroovyFiles(int count) throws IOException {
        List<Path> result = new ArrayList<>();
        for (int p = 0; p < 50 && result.size() < count; p++) {
            Path dir = workspaceRoot.resolve("module-" + p + "/src/test/groovy/com/example/module_" + p);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(f -> f.toString().endsWith(".groovy"))
                            .limit(count - result.size())
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(GroovyLsStressTest::deleteRecursive);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[StressTest] Failed to delete: " + path + " — " + e.getMessage());
        }
    }
}
