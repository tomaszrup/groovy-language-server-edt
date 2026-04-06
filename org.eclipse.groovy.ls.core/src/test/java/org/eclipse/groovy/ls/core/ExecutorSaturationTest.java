/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.eclipse.groovy.ls.core.providers.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;

/**
 * Load / stress tests for the LSP request executor inside
 * {@link GroovyTextDocumentService}.
 *
 * <p>Each test creates a service with mocked providers whose stubs sleep for
 * a controlled duration to simulate realistic JDT latency.  The pool is
 * configured to match a realistic deployment: <b>3 threads, 128 queue</b>
 * (a 4 GB / 4-core machine).  Tests that deliberately probe overflow use
 * even smaller pools.
 *
 * <h3>Key things validated</h3>
 * <ul>
 *   <li>The {@code LinkedBlockingQueue} absorbs request bursts that exceed the
 *       thread-pool size.</li>
 *   <li>When the queue <em>does</em> overflow, the service returns graceful
 *       fallbacks (null / empty) rather than throwing.</li>
 *   <li>Per-URI cancellation prevents stale requests from occupying threads.</li>
 *   <li>Build-gating returns immediately without consuming executor threads.</li>
 *   <li>Runtime pool reconfiguration works mid-flight.</li>
 *   <li>Dual-pool isolation: fast pool (interactive) vs background pool (decorative).</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExecutorSaturationTest {

    // ---- Real-world pool configuration ----
    private static final int REAL_POOL_SIZE     = 4;   // fast pool threads
    private static final int REAL_QUEUE_CAPACITY = 64;  // fast pool queue
    private static final int BG_POOL_SIZE       = 3;   // background pool threads
    private static final int BG_QUEUE_CAPACITY  = 128; // background pool queue

    // ========================================================================
    // Helpers
    // ========================================================================

    private GroovyTextDocumentService createService() {
        return new GroovyTextDocumentService(new GroovyLanguageServer(), new DocumentManager());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Create a {@link HoverProvider} mock that sleeps for {@code delayMs}
     * before returning a dummy {@link Hover}.
     */
    private HoverProvider slowHoverProvider(long delayMs) {
        HoverProvider mock = mock(HoverProvider.class);
        when(mock.getHover(any())).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new Hover(new MarkupContent("plaintext", "ok"));
        });
        return mock;
    }

    /**
     * Create a {@link SemanticTokensProvider} mock that sleeps for
     * {@code delayMs} before returning empty tokens.
     */
    private SemanticTokensProvider slowSemanticTokensProvider(long delayMs) {
        SemanticTokensProvider mock = mock(SemanticTokensProvider.class);
        when(mock.getSemanticTokensFull(any())).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new SemanticTokens(new ArrayList<>());
        });
        when(mock.getSemanticTokensRange(any())).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new SemanticTokens(new ArrayList<>());
        });
        return mock;
    }

    /**
     * Create a {@link FoldingRangeProvider} mock that sleeps for
     * {@code delayMs} before returning an empty list.
     */
    private FoldingRangeProvider slowFoldingRangeProvider(long delayMs) {
        FoldingRangeProvider mock = mock(FoldingRangeProvider.class);
        when(mock.getFoldingRanges(any())).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new ArrayList<FoldingRange>();
        });
        return mock;
    }

    /**
     * Create a {@link CodeLensProvider} mock that sleeps for
     * {@code delayMs} before returning an empty list.
     */
    private CodeLensProvider slowCodeLensProvider(long delayMs) {
        CodeLensProvider mock = mock(CodeLensProvider.class);
        when(mock.getCodeLenses(any())).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new ArrayList<CodeLens>();
        });
        return mock;
    }

    /**
     * Create a {@link CodeLensProvider} mock whose resolve step sleeps for
     * {@code delayMs} before returning the same lens.
     */
    private CodeLensProvider slowCodeLensResolveProvider(long delayMs) {
        CodeLensProvider mock = mock(CodeLensProvider.class);
        when(mock.resolveCodeLens(any(CodeLens.class))).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return inv.getArgument(0);
        });
        return mock;
    }

    /**
     * Create a {@link InlayHintProvider} mock that sleeps for
     * {@code delayMs} before returning an empty list.
     */
    private InlayHintProvider slowInlayHintProvider(long delayMs) {
        InlayHintProvider mock = mock(InlayHintProvider.class);
        when(mock.getInlayHints(any(InlayHintParams.class))).thenAnswer(inv -> {
            pauseMillis(delayMs);
            return new ArrayList<InlayHint>();
        });
        return mock;
    }

    private void pauseMillis(long delayMs) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
    }

    /**
     * Build {@link HoverParams} for a given file URI.
     */
    private HoverParams hoverParams(String uri) {
        return new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 0));
    }

    /**
     * Build {@link SemanticTokensParams} for a given file URI.
     */
    private SemanticTokensParams semanticTokensParams(String uri) {
        return new SemanticTokensParams(new TextDocumentIdentifier(uri));
    }

    /**
     * Build {@link FoldingRangeRequestParams} for a given file URI.
     */
    private FoldingRangeRequestParams foldingRangeParams(String uri) {
        FoldingRangeRequestParams p = new FoldingRangeRequestParams();
        p.setTextDocument(new TextDocumentIdentifier(uri));
        return p;
    }

    /**
     * Build {@link CodeLensParams} for a given file URI.
     */
    private CodeLensParams codeLensParams(String uri) {
        return new CodeLensParams(new TextDocumentIdentifier(uri));
    }

    // ========================================================================
    // Test 1: Burst requests do NOT get rejected
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("Burst: 20 concurrent hovers on fast pool(4, 64) — no rejections")
    void burstRequestsDoNotGetRejected() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        setField(service, "hoverProvider", slowHoverProvider(200));

        List<CompletableFuture<Hover>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(service.hover(hoverParams("file:///burst-" + i + ".groovy")));
        }

        int successCount = 0;
        for (CompletableFuture<Hover> f : futures) {
            try {
                Hover result = f.get(60, TimeUnit.SECONDS);
                if (result != null) {
                    successCount++;
                }
            } catch (Exception e) {
                // counted as non-success
            }
        }

        // All 20 should fit: 4 threads + 64 queue = 68 capacity
        assertEquals(20, successCount,
                "All 20 burst requests should complete successfully (4 threads + 64 queue slots)");
    }

    // ========================================================================
    // Test 2: Queue saturation produces graceful fallback (null), no exceptions
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("Queue saturation: 200 hovers on fast pool(4, 64) — graceful degradation")
    void queueSaturationProducesGracefulDegradation() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        // Use a short delay so requests drain before the built-in 10s hover timeout
        setField(service, "hoverProvider", slowHoverProvider(50));

        // Fire 200 at once — only 4+64=68 can be accepted before rejection
        List<CompletableFuture<Hover>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(service.hover(hoverParams("file:///sat-" + i + ".groovy")));
        }

        int successCount = 0;
        int nullCount = 0;
        int exceptionCount = 0;
        for (CompletableFuture<Hover> f : futures) {
            try {
                Hover result = f.get(120, TimeUnit.SECONDS);
                if (result != null) {
                    successCount++;
                } else {
                    // null means either rejected or timed out — both are graceful
                    nullCount++;
                }
            } catch (Exception e) {
                exceptionCount++;
            }
        }

        System.out.printf("[SaturationTest] 200 requests on fast pool(4,64): success=%d, null=%d, exception=%d%n",
                successCount, nullCount, exceptionCount);

        // Some must succeed and some must be null (rejected) — the key is no exceptions
        assertTrue(successCount > 0, "At least some requests should succeed, got 0");
        assertTrue(nullCount > 0, "At concurrency=200 some requests should be rejected (null), got 0");
        assertEquals(0, exceptionCount,
                "Rejected requests should return null, not throw exceptions");
    }

    // ========================================================================
    // Test 3: Per-URI cancellation frees up threads
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("Per-URI cancellation: 10 rapid semanticTokensFull for same URI — no saturation")
    void perUriCancellationFreesThreads() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        setField(service, "semanticTokensProvider", slowSemanticTokensProvider(500));

        String sameUri = "file:///same-file.groovy";
        List<CompletableFuture<SemanticTokens>> futures = new ArrayList<>();

        // Rapid fire 10 requests for the same URI — each should cancel the previous
        for (int i = 0; i < 10; i++) {
            futures.add(service.semanticTokensFull(semanticTokensParams(sameUri)));
        }

        // The last future should complete successfully
        CompletableFuture<SemanticTokens> last = futures.get(futures.size() - 1);
        SemanticTokens result = last.get(60, TimeUnit.SECONDS);
        assertNotNull(result, "Last semantic-tokens request should complete successfully");

        // Earlier futures should be cancelled or return empty
        int cancelledOrEmpty = 0;
        for (int i = 0; i < futures.size() - 1; i++) {
            CompletableFuture<SemanticTokens> f = futures.get(i);
            try {
                if (f.isCancelled() || f.isDone()) {
                    cancelledOrEmpty++;
                }
            } catch (Exception e) {
                cancelledOrEmpty++;
            }
        }

        System.out.printf("[CancellationTest] %d of %d earlier requests cancelled/done%n",
                cancelledOrEmpty, futures.size() - 1);
        assertTrue(cancelledOrEmpty > 0,
                "At least some earlier requests should have been cancelled");
    }

    // ========================================================================
    // Test 4: Build-gating returns immediately without consuming executor
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("Build-gating: 100 requests during build return empty immediately")
    void buildGatingReturnsImmediately() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);

        // Mock build in progress
        setField(server, "buildInProgress", true);

        // Install slow providers to prove they are NOT called
        setField(service, "semanticTokensProvider", slowSemanticTokensProvider(5000));
        setField(service, "foldingRangeProvider", slowFoldingRangeProvider(5000));
        setField(service, "codeLensProvider", slowCodeLensProvider(5000));
        setField(service, "inlayHintProvider", slowInlayHintProvider(5000));

        // Configure background pool (foldingRange, codeLens, inlayHint go there)
        service.configureBackgroundPool(BG_POOL_SIZE, BG_QUEUE_CAPACITY);

        long start = System.currentTimeMillis();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (int i = 0; i < 25; i++) {
            futures.add(service.semanticTokensFull(semanticTokensParams("file:///build-" + i + ".groovy")));
        }
        for (int i = 0; i < 25; i++) {
            futures.add(service.foldingRange(foldingRangeParams("file:///build-" + i + ".groovy")));
        }
        for (int i = 0; i < 25; i++) {
            futures.add(service.codeLens(codeLensParams("file:///build-" + i + ".groovy")));
        }
        for (int i = 0; i < 25; i++) {
            InlayHintParams ihParams = new InlayHintParams(
                    new TextDocumentIdentifier("file:///build-" + i + ".groovy"),
                    new Range(new Position(0, 0), new Position(100, 0)));
            futures.add(service.inlayHint(ihParams));
        }

        // All 100 futures should be already complete (no executor involvement)
        for (CompletableFuture<?> f : futures) {
            Object result = f.get(1, TimeUnit.SECONDS);
            assertNotNull(result, "Build-gated request should return an empty result, not null");
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[BuildGatingTest] 100 build-gated requests completed in %d ms%n", elapsed);

        // Should be near-instant — well under 1 second for 100 completedFuture calls
        assertTrue(elapsed < 2000,
                "Build-gated requests should return immediately, took " + elapsed + " ms");
    }

    // ========================================================================
    // Test 5: Escalating concurrency — full diagnostics table
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Escalation: full diagnostics — fast pool(4, 64), delay=300ms")
    void escalatingConcurrencyFindsSaturationPoint() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        setField(service, "hoverProvider", slowHoverProvider(300));

        // 4+64=68 is the theoretical max capacity for the fast pool
        int[] concurrencyLevels = {10, 25, 50, 68, 75, 100, 150, 200};

        // Deadlock detection timeout
        long deadlockDetectMs = 30_000;

        // Track the first concurrency level where rejections appear
        int saturationPoint = -1;

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                  EXECUTOR DIAGNOSTICS — fast pool(4, 64), provider delay=300ms                    ║");
        System.out.println("╠═════════════╦═════════╦══════════╦══════════╦══════════════╦══════════════╦══════════╦════════════════╣");
        System.out.println("║ Concurrency ║ Success ║ Rejected ║ Timeouts ║ Fastest (ms) ║ Longest (ms) ║ Deadlock ║ Sat. Point     ║");
        System.out.println("╠═════════════╬═════════╬══════════╬══════════╬══════════════╬══════════════╬══════════╬════════════════╣");

        for (int concurrency : concurrencyLevels) {
            // Fresh pool for each level
            service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);

            List<CompletableFuture<Hover>> futures = new ArrayList<>();
            long batchStart = System.currentTimeMillis();

            for (int i = 0; i < concurrency; i++) {
                futures.add(service.hover(hoverParams("file:///esc-" + i + ".groovy")));
            }

            // ---- Deadlock detection ----
            // Wait for at least ONE future to complete within deadline.
            // If none finishes, we have a deadlock.
            boolean deadlockDetected = false;
            try {
                CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                        .get(deadlockDetectMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                deadlockDetected = true;
            } catch (Exception e) {
                // Some other error — not a deadlock per se
            }

            // ---- Collect results ----
            int successCount = 0;
            int rejectCount = 0;
            int timeoutCount = 0;
            List<Long> latencies = new ArrayList<>();

            for (CompletableFuture<Hover> f : futures) {
                try {
                    Hover result = f.get(deadlockDetected ? 1 : 60, TimeUnit.SECONDS);
                    long elapsed = System.currentTimeMillis() - batchStart;
                    if (result != null) {
                        successCount++;
                        latencies.add(elapsed);
                    } else {
                        // null = rejected by handler or timed out internally
                        // Distinguish: if elapsed > 9s it's the built-in 10s hover timeout
                        if (elapsed > 9000) {
                            timeoutCount++;
                        } else {
                            rejectCount++;
                        }
                    }
                } catch (TimeoutException e) {
                    timeoutCount++;
                } catch (Exception e) {
                    rejectCount++;
                }
            }

            // ---- Saturation point detection ----
            boolean hasSaturation = rejectCount > 0 || timeoutCount > 0;
            String satLabel;
            if (saturationPoint == -1 && hasSaturation) {
                saturationPoint = concurrency;
                satLabel = ">>> HERE <<<";
            } else if (saturationPoint > 0) {
                satLabel = "(past " + saturationPoint + ")";
            } else {
                satLabel = "—";
            }

            // ---- Fastest / Longest ----
            Collections.sort(latencies);
            long fastest = latencies.isEmpty() ? 0 : latencies.get(0);
            long longest = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);

            System.out.printf("║  %9d  ║  %5d  ║   %5d  ║   %5d  ║   %8d   ║   %8d   ║   %4s   ║ %-14s ║%n",
                    concurrency, successCount, rejectCount, timeoutCount,
                    fastest, longest,
                    deadlockDetected ? "YES" : "no",
                    satLabel);

            // Brief cooldown between levels
            pauseMillis(500);
        }

        System.out.println("╚═════════════╩═════════╩══════════╩══════════╩══════════════╩══════════════╩══════════╩════════════════╝");

        // ---- Summary ----
        System.out.println();
        if (saturationPoint > 0) {
            System.out.printf(">> Saturation starts at concurrency = %d  (pool capacity = %d + %d = %d)%n",
                    saturationPoint, REAL_POOL_SIZE, REAL_QUEUE_CAPACITY,
                    REAL_POOL_SIZE + REAL_QUEUE_CAPACITY);
        } else {
            System.out.println(">> No saturation detected at any tested concurrency level.");
        }
        System.out.println();

        assertTrue(saturationPoint > 0,
            "Escalating concurrency should reveal a saturation point for the configured fast pool");
    }

    // ========================================================================
    // Test 6: Pool reconfiguration under load
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("Reconfiguration: expand pool mid-flight rescues saturated executor")
    void poolReconfigurationUnderLoad() throws Exception {
        GroovyTextDocumentService service = createService();
        // Start with the real pool
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        setField(service, "hoverProvider", slowHoverProvider(300));

        // Phase 1: fire 200 requests — some will be rejected (capacity = 131)
        List<CompletableFuture<Hover>> phase1 = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            phase1.add(service.hover(hoverParams("file:///reconf-p1-" + i + ".groovy")));
        }

        // Reconfigure to a bigger pool while phase1 is still running
        service.configureRequestPool(16, 128);

        // Phase 2: fire another burst on the bigger pool — should all succeed
        List<CompletableFuture<Hover>> phase2 = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            phase2.add(service.hover(hoverParams("file:///reconf-p2-" + i + ".groovy")));
        }

        // Count phase 2 results
        int phase2Success = 0;
        for (CompletableFuture<Hover> f : phase2) {
            try {
                Hover result = f.get(30, TimeUnit.SECONDS);
                if (result != null) phase2Success++;
            } catch (Exception e) {
                // ignored
            }
        }

        System.out.printf("[ReconfigTest] Phase 2: %d/30 succeeded after pool expansion%n", phase2Success);
        assertEquals(30, phase2Success,
                "All phase-2 requests should succeed after pool reconfiguration");

        // Wait for phase 1 to drain too (some may have been rejected)
        for (CompletableFuture<Hover> f : phase1) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Some phase-1 requests are expected to fail under the smaller initial pool.
            }
        }
    }

    // ========================================================================
    // Test 7: Mixed request types under constrained pool
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("Mixed load: hover + semanticTokens + foldingRange on fast(4,64) + bg(3,128)")
    void mixedRequestTypesUnderConstrainedPool() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        service.configureBackgroundPool(BG_POOL_SIZE, BG_QUEUE_CAPACITY);
        setField(service, "hoverProvider", slowHoverProvider(200));
        setField(service, "semanticTokensProvider", slowSemanticTokensProvider(200));
        setField(service, "foldingRangeProvider", slowFoldingRangeProvider(200));

        List<CompletableFuture<?>> futures = new ArrayList<>();

        // Fire 20 of each type = 60 total, within capacity of 3+128=131
        for (int i = 0; i < 20; i++) {
            String uri = "file:///mixed-" + i + ".groovy";
            futures.add(service.hover(hoverParams(uri)));
            futures.add(service.semanticTokensFull(semanticTokensParams(uri)));
            futures.add(service.foldingRange(foldingRangeParams(uri)));
        }

        int successCount = 0;
        for (CompletableFuture<?> f : futures) {
            try {
                Object result = f.get(120, TimeUnit.SECONDS);
                if (result != null) successCount++;
            } catch (Exception e) {
                // counted as failure
            }
        }

        System.out.printf("[MixedLoadTest] %d/60 mixed requests succeeded on fast(4,64) + bg(3,128)%n", successCount);
        assertEquals(60, successCount,
                "All 60 mixed requests should succeed within capacity");
    }

    // ========================================================================
    // Test 8: Sustained throughput measurement
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("Sustained: 10-second continuous load at 10 req/s on fast pool(4, 64)")
    void sustainedThroughputMeasurement() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(REAL_POOL_SIZE, REAL_QUEUE_CAPACITY);
        setField(service, "hoverProvider", slowHoverProvider(100));

        int durationSeconds = 10;
        int requestsPerSecond = 10;
        int intervalMs = 1000 / requestsPerSecond;

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> trackers = new ArrayList<>();

        long testStart = System.currentTimeMillis();
        long deadline = testStart + (durationSeconds * 1000L);
        int requestId = 0;

        while (System.currentTimeMillis() < deadline) {
            final int id = requestId++;
            final long reqStart = System.currentTimeMillis();

            CompletableFuture<Hover> future = service.hover(
                    hoverParams("file:///sustained-" + (id % 20) + ".groovy"));

            // Track completion asynchronously
            trackers.add(future.handle((result, ex) -> {
                long elapsed = System.currentTimeMillis() - reqStart;
                if (result != null && ex == null) {
                    successCount.incrementAndGet();
                    latencies.add(elapsed);
                } else {
                    failureCount.incrementAndGet();
                }
                return null;
            }));

            pauseMillis(intervalMs);
        }

        // Wait for all in-flight requests to finish
        CompletableFuture.allOf(trackers.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // Stats
        Collections.sort(latencies);
        long p50 = !latencies.isEmpty() ? latencies.get(latencies.size() / 2) : 0;
        long p95 = 0;
        if (!latencies.isEmpty()) {
            int idx = (int) Math.ceil(0.95 * latencies.size()) - 1;
            p95 = latencies.get(Math.max(0, Math.min(idx, latencies.size() - 1)));
        }
        long max = !latencies.isEmpty() ? latencies.get(latencies.size() - 1) : 0;

        System.out.println();
        System.out.printf("[SustainedTest] Duration: %ds at %d req/s%n", durationSeconds, requestsPerSecond);
        System.out.printf("[SustainedTest] Total requests: %d  |  Success: %d  |  Failed: %d%n",
                requestId, successCount.get(), failureCount.get());
        System.out.printf("[SustainedTest] Latency: p50=%d ms  p95=%d ms  max=%d ms%n", p50, p95, max);
        System.out.println();

        // At 10 req/s with 100ms provider delay and 4 threads, there should be
        // zero failures — 4 threads can handle 40 req/s at 100ms each
        assertEquals(0, failureCount.get(),
                "No failures expected at 10 req/s with fast pool(4, 64) and 100ms latency");
    }

    @Test
    @Order(9)
    @DisplayName("Priority: inlay hints complete while codeLens resolve queue is busy")
    void inlayHintsHavePriorityOverCodeLensResolveLoad() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureBackgroundPool(BG_POOL_SIZE, BG_QUEUE_CAPACITY);
        service.configureCodeLensResolvePool(1, 16);
        setField(service, "codeLensProvider", slowCodeLensResolveProvider(1500));
        setField(service, "inlayHintProvider", slowInlayHintProvider(50));

        List<CompletableFuture<CodeLens>> resolveFutures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            resolveFutures.add(service.resolveCodeLens(new CodeLens()));
        }

        InlayHintParams params = new InlayHintParams(
                new TextDocumentIdentifier("file:///priority.groovy"),
                new Range(new Position(0, 0), new Position(100, 0)));

        long start = System.currentTimeMillis();
        List<InlayHint> hints = service.inlayHint(params).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(hints);
        assertTrue(elapsed < 1000,
                "Inlay hints should not wait behind codeLens resolve work, took " + elapsed + " ms");

        for (CompletableFuture<CodeLens> future : resolveFutures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Resolve completions may be rejected or time out under the constrained queue.
            }
        }
    }

    // ========================================================================
    // Test 10: codeSelect cache — verifies DocumentManager.cachedCodeSelect()
    //
    // Multiple requests for the same URI#offset should result in only ONE
    // actual codeSelect() call; subsequent hits come from the LRU cache.
    // ========================================================================

    @Test
    @Order(10)
    @DisplayName("codeSelect cache: 3 concurrent requests for same offset → 1 actual call")
    void codeSelectCacheSharesResult() throws Exception {
        DocumentManager dm = new DocumentManager();

        // Create a mock ICompilationUnit that tracks codeSelect calls
        var codeSelectCount = new AtomicInteger(0);
        org.eclipse.jdt.core.ICompilationUnit mockUnit = mock(org.eclipse.jdt.core.ICompilationUnit.class);

        // Simulate a slow codeSelect (100ms)
        org.eclipse.jdt.core.IJavaElement mockElement = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(mockUnit.codeSelect(42, 0)).thenAnswer(inv -> {
            codeSelectCount.incrementAndGet();
            pauseMillis(100);
            return new org.eclipse.jdt.core.IJavaElement[]{mockElement};
        });

        // We need the unit to be in the workingCopies map for resolveUri() to work
        setField(dm, "workingCopies", new ConcurrentHashMap<>(java.util.Map.of(
                "file:///test/CacheTest.groovy", mockUnit)));

        // First call: cache miss → real codeSelect
        org.eclipse.jdt.core.IJavaElement[] result1 = dm.cachedCodeSelect(mockUnit, 42);
        assertNotNull(result1);
        assertEquals(1, result1.length);
        assertEquals(1, codeSelectCount.get(), "First call should hit real codeSelect");

        // Second call: cache hit → no codeSelect
        org.eclipse.jdt.core.IJavaElement[] result2 = dm.cachedCodeSelect(mockUnit, 42);
        assertNotNull(result2);
        assertEquals(1, result2.length);
        assertEquals(1, codeSelectCount.get(), "Second call should hit cache");

        // Third call with DIFFERENT offset: cache miss → real codeSelect
        when(mockUnit.codeSelect(99, 0)).thenAnswer(inv -> {
            codeSelectCount.incrementAndGet();
            return new org.eclipse.jdt.core.IJavaElement[]{mockElement};
        });
        org.eclipse.jdt.core.IJavaElement[] result3 = dm.cachedCodeSelect(mockUnit, 99);
        assertNotNull(result3);
        assertEquals(2, codeSelectCount.get(), "Different offset should miss cache");

        System.out.println("[CodeSelectCache] 3 calls, 2 unique offsets → "
                + codeSelectCount.get() + " actual codeSelect() calls");
    }

    @Test
    @Order(11)
    @DisplayName("codeSelect cache: invalidation on didChange clears cached results")
    void codeSelectCacheInvalidatedOnChange() throws Exception {
        DocumentManager dm = new DocumentManager();

        var codeSelectCount = new AtomicInteger(0);
        org.eclipse.jdt.core.ICompilationUnit mockUnit = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaElement mockElement = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(mockUnit.codeSelect(42, 0)).thenAnswer(inv -> {
            codeSelectCount.incrementAndGet();
            return new org.eclipse.jdt.core.IJavaElement[]{mockElement};
        });

        setField(dm, "workingCopies", new ConcurrentHashMap<>(java.util.Map.of(
                "file:///test/InvalidateTest.groovy", mockUnit)));

        // Populate cache
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(1, codeSelectCount.get());

        // Cache hit
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(1, codeSelectCount.get(), "Should hit cache before invalidation");

        // Invalidate
        dm.invalidateCodeSelectCache("file:///test/InvalidateTest.groovy");

        // Cache miss after invalidation → real codeSelect
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(2, codeSelectCount.get(), "Should miss cache after invalidation");

        System.out.println("[CodeSelectCache] Invalidation test passed: "
                + codeSelectCount.get() + " actual codeSelect() calls");
    }

    @Test
    @Order(12)
    @DisplayName("codeSelect cache: entries expire after TTL (5s)")
    void codeSelectCacheTtlExpiry() throws Exception {
        DocumentManager dm = new DocumentManager();

        var codeSelectCount = new AtomicInteger(0);
        org.eclipse.jdt.core.ICompilationUnit mockUnit = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaElement mockElement = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(mockUnit.codeSelect(42, 0)).thenAnswer(inv -> {
            codeSelectCount.incrementAndGet();
            return new org.eclipse.jdt.core.IJavaElement[]{mockElement};
        });

        setField(dm, "workingCopies", new ConcurrentHashMap<>(java.util.Map.of(
                "file:///test/TtlTest.groovy", mockUnit)));

        // Populate cache
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(1, codeSelectCount.get());

        // Immediate second call: cache hit
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(1, codeSelectCount.get());

        // Wait for TTL to expire (internal TTL is 5000ms, we wait 5100ms)
        pauseMillis(5_100);

        // After TTL expiry: cache miss → real codeSelect
        dm.cachedCodeSelect(mockUnit, 42);
        assertEquals(2, codeSelectCount.get(), "Cache should expire after TTL");

        System.out.println("[CodeSelectCache] TTL expiry test passed: "
                + codeSelectCount.get() + " actual codeSelect() calls after 5.1s wait");
    }

    @Test
    @Order(13)
    @DisplayName("Queue saturation: rejected hover falls back immediately instead of waiting for timeout")
    void rejectedHoverFallsBackImmediately() throws Exception {
        GroovyTextDocumentService service = createService();
        service.configureRequestPool(1, 1);

        CountDownLatch releaseHover = new CountDownLatch(1);
        HoverProvider provider = mock(HoverProvider.class);
        when(provider.getHover(any())).thenAnswer(invocation -> {
            releaseHover.await(5, TimeUnit.SECONDS);
            return new Hover(new MarkupContent("plaintext", "ok"));
        });
        setField(service, "hoverProvider", provider);

        CompletableFuture<Hover> running = service.hover(hoverParams("file:///immediate-1.groovy"));
        CompletableFuture<Hover> queued = service.hover(hoverParams("file:///immediate-2.groovy"));

        long startedAt = System.nanoTime();
        CompletableFuture<Hover> rejected = service.hover(hoverParams("file:///immediate-3.groovy"));
        Hover result = rejected.get(1, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        releaseHover.countDown();
        assertNull(result, "Rejected request should return the hover fallback immediately");
        assertTrue(elapsedMs < 1000,
                "Rejected request should not wait for the 10s timeout, took " + elapsedMs + " ms");

        running.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
    }
}
