package org.eclipse.groovy.ls.perf;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level performance tests for the Groovy Language Server.
 * <p>
 * Each test benchmarks a specific LSP feature against a synthetic 50-project
 * workspace and enforces p95 latency thresholds. The server is launched once
 * for the entire class, and files are opened/closed per-test to simulate
 * realistic IDE usage.
 *
 * <h2>Running</h2>
 * <pre>{@code
 * ./gradlew :org.eclipse.groovy.ls.perf:perfTest
 * }</pre>
 *
 * <h2>Configuration (system properties)</h2>
 * <ul>
 *   <li>{@code groovy.ls.server.dir} — path to assembled server product</li>
 *   <li>{@code groovy.ls.java.home} — JDK for the server JVM</li>
 *   <li>{@code groovy.ls.perf.server.timeout.seconds} — max wait for Ready (default 180)</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GroovyLsPerformanceTest {

    // ---- Thresholds (p95 in milliseconds) ----

    private static final long COMPLETION_DOT_P95_MS         = 2000;
    private static final long COMPLETION_TYPE_P95_MS         = 500;
    private static final long HOVER_P95_MS                   = 1000;
    private static final long HOVER_CACHE_HIT_P95_MS         = 200;
    private static final long DIAGNOSTICS_EDIT_P95_MS        = 3000;
    private static final long DIAGNOSTICS_UNUSED_P95_MS      = 3000;
    private static final long SEMANTIC_TOKENS_P95_MS         = 1500;
    private static final long SEMANTIC_TOKENS_LARGE_P95_MS   = 3000;
    private static final long REFERENCES_LOCAL_P95_MS        = 3000;
    private static final long REFERENCES_WIDE_P95_MS         = 8000;
    private static final long RENAME_P95_MS                  = 8000;
    private static final long CONCURRENT_MAX_MS              = 5000;

    // ---- Iteration counts ----

    private static final int COMPLETION_ITERATIONS    = 30;
    private static final int HOVER_ITERATIONS         = 50;
    private static final int DIAGNOSTICS_ITERATIONS   = 30;
    private static final int SEMANTIC_ITERATIONS      = 30;
    private static final int SEMANTIC_LARGE_ITERATIONS= 20;
    private static final int REFERENCES_ITERATIONS    = 20;
    private static final int RENAME_ITERATIONS        = 10;
    private static final int CHURN_ROUNDS             = 3;
    private static final int CONCURRENT_ROUNDS        = 5;

    // ---- Request timeout ----

    private static final long REQUEST_TIMEOUT_MS = 60_000;

    // ---- Fixtures ----

    private LspClientHarness harness;
    private WorkspaceGenerator generator;
    private Path workspaceRoot;
    private List<WorkspaceGenerator.FileInfo> files;
    private final List<PerformanceResult> allResults = new ArrayList<>();

    // ========================================================================
    // Setup & Teardown
    // ========================================================================

    @BeforeAll
    void setUp() throws Exception {
        // 1. Resolve configuration
        String serverDir = System.getProperty("groovy.ls.server.dir");
        String javaHome  = System.getProperty("groovy.ls.java.home", System.getProperty("java.home"));
        long timeout     = Long.parseLong(System.getProperty("groovy.ls.perf.server.timeout.seconds", "180"));

        assertNotNull(serverDir, "System property 'groovy.ls.server.dir' must point to the assembled product");
        assertTrue(Files.isDirectory(Path.of(serverDir)), "Server directory does not exist: " + serverDir);

        // 2. Generate synthetic workspace
        System.out.println("[PerfTest] Generating synthetic workspace (50 projects, ~100 Java + 3 traits + 100 Groovy tests each)...");
        generator = new WorkspaceGenerator(50);
        workspaceRoot = generator.generate(Files.createTempDirectory("groovy-ls-perf-workspace"));
        files = generator.getRepresentativeFiles();
        System.out.println("[PerfTest] Workspace generated at: " + workspaceRoot);
        System.out.println("[PerfTest] Representative files: " + files.size());
        System.out.println("[PerfTest] Stub JARs: " + generator.getJarPaths().size());

        // 3. Start the language server
        harness = new LspClientHarness(Path.of(serverDir), javaHome, workspaceRoot, timeout);
        harness.start();

        // 4. Send classpath updates with stub JARs
        harness.sendClasspathUpdates(generator.getProjectNames(), generator.getJarPaths());

        // 5. Open representative files to warm the caches
        System.out.println("[PerfTest] Opening " + files.size() + " representative files...");
        for (WorkspaceGenerator.FileInfo fi : files) {
            harness.didOpen(fi.toUri(), fi.readContent());
        }

        // Let the server settle (initial diagnostics, indexing of 400 JARs)
        System.out.println("[PerfTest] Waiting 15s for server to settle after opening files and indexing JARs...");
        Thread.sleep(15_000);

        // Warmup: fire a type completion to force JDT index to finish
        System.out.println("[PerfTest] Warmup: triggering type-name completion to flush JDT indexer...");
        WorkspaceGenerator.FileInfo warmupFile = files.get(0);
        String warmupContent = warmupFile.readContent();
        String warmupModified = insertLineAfter(warmupContent, 5, "        Stri");
        harness.didChange(warmupFile.toUri(), warmupModified, 9999);
        Thread.sleep(200);
        try {
            harness.completion(warmupFile.toUri(), 6, 12)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[PerfTest] Warmup completion timed out (non-fatal): " + e.getMessage());
        }
        harness.didChange(warmupFile.toUri(), warmupContent, 10000);
        Thread.sleep(500);

        System.out.println("[PerfTest] Setup complete. Starting benchmarks.");
    }

    @AfterAll
    void tearDown() throws Exception {
        // Generate reports before shutting down
        if (!allResults.isEmpty()) {
            Path reportDir = Path.of(System.getProperty("user.dir"), "build", "reports", "performance");
            new PerformanceReporter(reportDir).report(allResults);
        }

        if (harness != null) {
            // Close all opened files
            if (files != null) {
                for (WorkspaceGenerator.FileInfo fi : files) {
                    try { harness.didClose(fi.toUri()); } catch (Exception ignored) {}
                }
            }
            harness.shutdown();
        }

        // Clean up workspace
        if (workspaceRoot != null) {
            System.out.println("[PerfTest] Cleaning up workspace: " + workspaceRoot);
            deleteRecursive(workspaceRoot);
        }
    }

    // ========================================================================
    // Completion benchmarks
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("Completion: after dot (member access)")
    void testCompletionAfterDot() throws Exception {
        PerformanceResult result = new PerformanceResult("Completion (dot)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        // Find a line with a field reference to put a dot after
        String content = target.readContent();
        String[] lines = content.split("\n");

        // Find a method body line — we'll insert "field0." into a trait method
        int targetLine = findLineContaining(lines, "def count");
        if (targetLine < 0) targetLine = findLineContaining(lines, "def result");
        if (targetLine < 0) targetLine = 15; // fallback

        for (int i = 0; i < COMPLETION_ITERATIONS; i++) {
            // Insert "entries0." on a new line inside a trait method
            String modified = insertLineAfter(content, targetLine,
                    "        def tempVar" + i + " = entries0.");
            harness.didChange(target.toUri(), modified, i + 2);

            // Small delay for server to process the change
            Thread.sleep(100);

            long start = System.currentTimeMillis();
            harness.completion(target.toUri(), targetLine + 1, 37)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            result.record(elapsed);

            // Restore original content
            harness.didChange(target.toUri(), content, i + 100);
            Thread.sleep(50);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(COMPLETION_DOT_P95_MS);
    }

    @Test
    @Order(2)
    @DisplayName("Completion: type name")
    void testCompletionTypeNames() throws Exception {
        PerformanceResult result = new PerformanceResult("Completion (type name)");
        WorkspaceGenerator.FileInfo target = files.get(1 % files.size());

        String content = target.readContent();
        String[] lines = content.split("\n");
        int targetLine = findLineContaining(lines, "def ");
        if (targetLine < 0) targetLine = 10;

        for (int i = 0; i < COMPLETION_ITERATIONS; i++) {
            // Type a partial class name
            String modified = insertLineAfter(content, targetLine,
                    "        Servi");
            harness.didChange(target.toUri(), modified, i + 2);
            Thread.sleep(100);

            long start = System.currentTimeMillis();
            harness.completion(target.toUri(), targetLine + 1, 13)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            result.record(elapsed);

            harness.didChange(target.toUri(), content, i + 100);
            Thread.sleep(50);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(COMPLETION_TYPE_P95_MS);
    }

    // ========================================================================
    // Hover benchmarks
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("Hover: on method call")
    void testHoverOnMethod() throws Exception {
        PerformanceResult result = new PerformanceResult("Hover (method)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        String content = target.readContent();
        String[] lines = content.split("\n");
        // Hover over a method declaration in the trait
        int hoverLine = findLineContaining(lines, "def computeStatistics");
        if (hoverLine < 0) hoverLine = findLineContaining(lines, "def processItems");
        if (hoverLine < 0) hoverLine = findLineContaining(lines, "def audit");
        if (hoverLine < 0) hoverLine = 10;
        int hoverCol = lines[hoverLine].indexOf("compute");
        if (hoverCol < 0) hoverCol = lines[hoverLine].indexOf("process");
        if (hoverCol < 0) hoverCol = lines[hoverLine].indexOf("audit");
        if (hoverCol < 0) hoverCol = 8;

        for (int i = 0; i < HOVER_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            var hover = harness.hover(target.toUri(), hoverLine, hoverCol)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(HOVER_P95_MS);
    }

    @Test
    @Order(4)
    @DisplayName("Hover: cache hit (same position repeated)")
    void testHoverCacheHit() throws Exception {
        PerformanceResult result = new PerformanceResult("Hover (cache hit)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        String[] lines = target.readContent().split("\n");
        int hoverLine = findLineContaining(lines, "trait ");
        if (hoverLine < 0) hoverLine = findLineContaining(lines, "class ");
        if (hoverLine < 0) hoverLine = 5;
        int hoverCol = lines[hoverLine].indexOf("Trait");
        if (hoverCol < 0) hoverCol = lines[hoverLine].indexOf("Service");
        if (hoverCol < 0) hoverCol = 6;

        // Warm the cache with one call
        harness.hover(target.toUri(), hoverLine, hoverCol)
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Now measure cache hits
        for (int i = 0; i < HOVER_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            harness.hover(target.toUri(), hoverLine, hoverCol)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(HOVER_CACHE_HIT_P95_MS);
    }

    // ========================================================================
    // Diagnostics benchmarks
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Diagnostics: after introducing syntax error")
    void testDiagnosticsAfterEdit() throws Exception {
        PerformanceResult result = new PerformanceResult("Diagnostics (syntax error)");
        WorkspaceGenerator.FileInfo target = files.get(2 % files.size());

        String content = target.readContent();
        String[] lines = content.split("\n");
        int editLine = findLineContaining(lines, "return ");
        if (editLine < 0) editLine = lines.length / 2;

        for (int i = 0; i < DIAGNOSTICS_ITERATIONS; i++) {
            // Register diagnostics listener before making the edit
            CompletableFuture<List<Diagnostic>> diagFuture = harness.awaitDiagnostics(target.toUri());

            // Introduce a syntax error — unclosed string
            String broken = insertLineAfter(content, editLine,
                    "        def broken" + i + " = \"unclosed string");
            harness.didChange(target.toUri(), broken, i + 2);

            long start = System.currentTimeMillis();
            List<Diagnostic> diags = diagFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            result.record(elapsed);

            // Restore
            harness.didChange(target.toUri(), content, i + 100);
            Thread.sleep(200); // let the server settle
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(DIAGNOSTICS_EDIT_P95_MS);
    }

    @Test
    @Order(6)
    @DisplayName("Diagnostics: unused import detection")
    void testDiagnosticsUnusedImport() throws Exception {
        PerformanceResult result = new PerformanceResult("Diagnostics (unused import)");

        // Open a fresh file with a known unused import
        String uri = workspaceRoot.resolve("module-0/src/main/groovy/com/example/module_0/UnusedImportTest.groovy").toUri().toString();
        String source = """
                package com.example.module_0

                import java.util.List
                import java.util.concurrent.ConcurrentHashMap
                import java.util.regex.Pattern

                class UnusedImportTest {
                    List<String> items = []
                    
                    def process() {
                        return items.size()
                    }
                }
                """;

        for (int i = 0; i < DIAGNOSTICS_ITERATIONS; i++) {
            CompletableFuture<List<Diagnostic>> diagFuture = harness.awaitDiagnostics(uri);

            harness.didOpen(uri, source);

            long start = System.currentTimeMillis();
            List<Diagnostic> diags = diagFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            result.record(elapsed);
            harness.didClose(uri);
            Thread.sleep(200);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(DIAGNOSTICS_UNUSED_P95_MS);
    }

    // ========================================================================
    // Semantic Tokens benchmarks
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("Semantic Tokens: full document (~200 lines)")
    void testSemanticTokensFull() throws Exception {
        PerformanceResult result = new PerformanceResult("Semantic Tokens (normal)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        for (int i = 0; i < SEMANTIC_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            var tokens = harness.semanticTokensFull(target.toUri())
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(SEMANTIC_TOKENS_P95_MS);
    }

    @Test
    @Order(8)
    @DisplayName("Semantic Tokens: large file (~500 lines)")
    void testSemanticTokensLargeFile() throws Exception {
        PerformanceResult result = new PerformanceResult("Semantic Tokens (large file)");

        // Find the LargeServiceSpec file
        WorkspaceGenerator.FileInfo largeFile = files.stream()
                .filter(f -> f.simpleClassName().equals("LargeServiceSpec"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LargeServiceSpec not found in representative files"));

        // Ensure it's open
        harness.didOpen(largeFile.toUri(), largeFile.readContent());
        Thread.sleep(1000); // let server index it

        for (int i = 0; i < SEMANTIC_LARGE_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            var tokens = harness.semanticTokensFull(largeFile.toUri())
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        harness.didClose(largeFile.toUri());

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(SEMANTIC_TOKENS_LARGE_P95_MS);
    }

    // ========================================================================
    // References / Rename benchmarks
    // ========================================================================

    @Test
    @Order(9)
    @DisplayName("References: local symbol (used in 3 files)")
    void testReferencesLocalSymbol() throws Exception {
        PerformanceResult result = new PerformanceResult("References (local)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        String[] lines = target.readContent().split("\n");
        // Find "computeStatistics0" which is defined in the trait and referenced cross-project
        int refLine = findLineContaining(lines, "def computeStatistics");
        if (refLine < 0) refLine = findLineContaining(lines, "def processItems");
        if (refLine < 0) refLine = findLineContaining(lines, "def audit");
        if (refLine < 0) refLine = 10;

        int refCol = lines[refLine].indexOf("compute");
        if (refCol < 0) refCol = lines[refLine].indexOf("process");
        if (refCol < 0) refCol = lines[refLine].indexOf("audit");
        if (refCol < 0) refCol = 8;

        for (int i = 0; i < REFERENCES_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            var refs = harness.references(target.toUri(), refLine, refCol)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(REFERENCES_LOCAL_P95_MS);
    }

    @Test
    @Order(10)
    @DisplayName("References: widely-used type (cross-project)")
    void testReferencesWidelyUsedType() throws Exception {
        PerformanceResult result = new PerformanceResult("References (wide)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        String[] lines = target.readContent().split("\n");
        // Find the trait declaration — this type is referenced by downstream modules
        int classLine = findLineContaining(lines, "trait Trait0_0");
        if (classLine < 0) classLine = findLineContaining(lines, "trait ");
        if (classLine < 0) classLine = 5;

        int classCol = lines[classLine].indexOf("Trait");
        if (classCol < 0) classCol = 6;

        for (int i = 0; i < REFERENCES_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            var refs = harness.references(target.toUri(), classLine, classCol)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(REFERENCES_WIDE_P95_MS);
    }

    @Test
    @Order(11)
    @DisplayName("Rename: method used in multiple files")
    void testRenameSymbol() throws Exception {
        PerformanceResult result = new PerformanceResult("Rename (method)");
        WorkspaceGenerator.FileInfo target = files.get(0);

        String[] lines = target.readContent().split("\n");
        int methodLine = findLineContaining(lines, "def computeStatistics");
        if (methodLine < 0) methodLine = findLineContaining(lines, "def processItems");
        if (methodLine < 0) methodLine = findLineContaining(lines, "def audit");
        if (methodLine < 0) methodLine = 10;

        int methodCol = lines[methodLine].indexOf("compute");
        if (methodCol < 0) methodCol = lines[methodLine].indexOf("process");
        if (methodCol < 0) methodCol = lines[methodLine].indexOf("audit");
        if (methodCol < 0) methodCol = 8;

        for (int i = 0; i < RENAME_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            // Request rename (prepare only — we don't apply the edit to keep the workspace stable)
            var edit = harness.rename(target.toUri(), methodLine, methodCol, "renamedMethod" + i)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertP95Threshold(RENAME_P95_MS);
    }

    // ========================================================================
    // Document open/close churn benchmark
    // ========================================================================

    @Test
    @Order(12)
    @DisplayName("Document churn: rapid open/close of 50 files")
    void testDocumentOpenCloseChurn() throws Exception {
        PerformanceResult result = new PerformanceResult("Document churn (50 files)");

        // Gather all Groovy files from the first 5 projects (test files)
        List<Path> churnFiles = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            Path dir = workspaceRoot.resolve("module-" + p + "/src/test/groovy/com/example/module_" + p);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(f -> f.toString().endsWith(".groovy"))
                            .limit(10)
                            .forEach(churnFiles::add);
                }
            }
        }
        assertTrue(churnFiles.size() >= 20, "Expected at least 20 files for churn test, found " + churnFiles.size());

        for (int round = 0; round < CHURN_ROUNDS; round++) {
            long start = System.currentTimeMillis();

            // Open all files rapidly
            for (Path f : churnFiles) {
                harness.didOpen(f.toUri().toString(), Files.readString(f));
            }

            // Close all files rapidly
            for (Path f : churnFiles) {
                harness.didClose(f.toUri().toString());
            }

            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);

            // Brief pause between rounds
            Thread.sleep(500);
        }

        System.out.println(result);
        allResults.add(result);
        // No strict p95 threshold — just verify no crash and reasonable timing
        result.assertMaxThreshold(30_000); // Should complete within 30s per round
    }

    // ========================================================================
    // Concurrent requests benchmark
    // ========================================================================

    @Test
    @Order(13)
    @DisplayName("Concurrent: mixed hover + completion + semantic tokens")
    void testConcurrentRequests() throws Exception {
        PerformanceResult result = new PerformanceResult("Concurrent (mixed)");

        for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
            List<CompletableFuture<?>> futures = new ArrayList<>();
            long start = System.currentTimeMillis();

            // Fire 10 hover requests across different files
            for (int i = 0; i < Math.min(10, files.size()); i++) {
                WorkspaceGenerator.FileInfo fi = files.get(i);
                futures.add(harness.hover(fi.toUri(), 10, 8));
            }

            // Fire 5 completion requests
            for (int i = 0; i < Math.min(5, files.size()); i++) {
                WorkspaceGenerator.FileInfo fi = files.get(i);
                futures.add(harness.completion(fi.toUri(), 10, 8));
            }

            // Fire 5 semantic token requests
            for (int i = 0; i < Math.min(5, files.size()); i++) {
                WorkspaceGenerator.FileInfo fi = files.get(i);
                futures.add(harness.semanticTokensFull(fi.toUri()));
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(CONCURRENT_MAX_MS, TimeUnit.MILLISECONDS);

            long elapsed = System.currentTimeMillis() - start;
            result.record(elapsed);
        }

        System.out.println(result);
        allResults.add(result);
        result.assertMaxThreshold(CONCURRENT_MAX_MS);
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Find the 0-based line index containing the given substring.
     * Returns -1 if not found.
     */
    private static int findLineContaining(String[] lines, String substring) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(substring)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Insert a new line after the given 0-based line index.
     */
    private static String insertLineAfter(String content, int afterLine, String newLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
            if (i == afterLine) {
                sb.append(newLine).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Recursively delete a directory tree.
     */
    private static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(GroovyLsPerformanceTest::deleteRecursive);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[PerfTest] Failed to delete: " + path + " — " + e.getMessage());
        }
    }
}
