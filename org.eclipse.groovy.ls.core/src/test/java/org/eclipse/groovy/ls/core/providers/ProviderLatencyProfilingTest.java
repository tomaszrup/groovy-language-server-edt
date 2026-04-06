/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;

/**
 * In-process profiling of providers that can run without the full Eclipse/OSGi
 * runtime. These providers use the standalone Groovy compiler path (no JDT).
 *
 * <h2>What this measures</h2>
 * <ul>
 *   <li><b>Groovy compiler parse time</b> — {@code GroovyCompilerService.parse()}</li>
 *   <li><b>Semantic tokens visitor</b> — AST walk + delta-encoded token output</li>
 *   <li><b>Document symbols from AST</b> — Groovy AST → DocumentSymbol tree</li>
 *   <li><b>Folding ranges</b> — text scanning + AST walk for class/method ranges</li>
 * </ul>
 *
 * <h2>What this does NOT measure</h2>
 * JDT-dependent operations (codeSelect, reconcile, type hierarchy, SearchEngine)
 * require the out-of-process {@code org.eclipse.groovy.ls.perf} harness.
 *
 * <h2>Running</h2>
 * <pre>{@code
 * ./gradlew :org.eclipse.groovy.ls.core:test --tests "*ProviderLatencyProfilingTest"
 * }</pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderLatencyProfilingTest {

    // ---- Iteration counts ----
    private static final int WARMUP = 5;
    private static final int ITERATIONS = 50;

    // ---- Test fixtures ----

    /** Small realistic Groovy file (~40 lines). */
    private static final String SMALL_SOURCE = """
            package com.example.sample

            import java.util.List
            import java.util.Map
            import java.util.stream.Collectors

            trait AppContextTest {
                String appName = "TestApp"
                int version = 1

                String getFullName() {
                    return "${appName}-v${version}"
                }
            }

            interface SoemethingTest {
                default int add(int a, int b) {
                    return a + b
                }
                int getS()
            }

            class SampleService implements SoemethingTest {
                List<String> items = []
                Map<String, Integer> counts = [:]

                def processItems(List<String> input, boolean deduplicate) {
                    if (deduplicate) {
                        items = input.stream()
                            .distinct()
                            .collect(Collectors.toList())
                    } else {
                        items.addAll(input)
                    }
                    return items.size()
                }

                int getS() { return items.size() }
            }
            """;

    /** Medium Groovy file (~150 lines) — Spock specification with multiple features. */
    private static final String MEDIUM_SOURCE = generateSpockSpec(150);

    /** Large Groovy file (~500 lines) — stress test for AST walkers. */
    private static final String LARGE_SOURCE = generateSpockSpec(500);

    // ---- Infrastructure ----

    private final GroovyCompilerService compilerService = new GroovyCompilerService();
    private DocumentManager documentManager;

    private final List<ProfilingResult> allResults = new ArrayList<>();

    @BeforeAll
    void setUp() {
        documentManager = new DocumentManager();
        // Pre-open files so DocumentManager has content cached
        documentManager.didOpen("file:///test/Small.groovy", SMALL_SOURCE);
        documentManager.didOpen("file:///test/Medium.groovy", MEDIUM_SOURCE);
        documentManager.didOpen("file:///test/Large.groovy", LARGE_SOURCE);

        // Wait briefly for background parsing to finish
        pauseMillis(500);

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║    IN-PROCESS PROVIDER LATENCY PROFILING                  ║");
        System.out.println("║    (standalone Groovy compiler — no JDT/OSGi)             ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Small source:  %4d lines   (%5d chars)                 ║%n",
                SMALL_SOURCE.split("\n").length, SMALL_SOURCE.length());
        System.out.printf( "║  Medium source: %4d lines   (%5d chars)                 ║%n",
                MEDIUM_SOURCE.split("\n").length, MEDIUM_SOURCE.length());
        System.out.printf( "║  Large source:  %4d lines   (%5d chars)                 ║%n",
                LARGE_SOURCE.split("\n").length, LARGE_SOURCE.length());
        System.out.printf( "║  Warmup: %d iterations, Measured: %d iterations            ║%n",
                WARMUP, ITERATIONS);
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @AfterAll
    void printSummary() {
        if (allResults.isEmpty()) return;

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      PROVIDER LATENCY PROFILING — SUMMARY                                ║");
        System.out.println("╠═══════════════════════════════════════════╦════════╦════════╦════════╦════════╦═══════════╣");
        System.out.println("║ Operation                                 ║ p50 µs ║ p95 µs ║ p99 µs ║ max µs ║ mean µs  ║");
        System.out.println("╠═══════════════════════════════════════════╬════════╬════════╬════════╬════════╬═══════════╣");
        for (ProfilingResult r : allResults) {
            System.out.printf("║ %-41s ║ %6d ║ %6d ║ %6d ║ %6d ║ %7.0f   ║%n",
                    truncate(r.name, 41), r.p50(), r.p95(), r.p99(), r.max(), r.mean());
        }
        System.out.println("╚═══════════════════════════════════════════╩════════╩════════╩════════╩════════╩═══════════╝");
        System.out.println();
    }

    // ========================================================================
    // 1. Groovy Compiler Parse Time
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("Parse: small file (~40 lines)")
    void parseSmall() {
        ProfilingResult result = profile("Parse small (40 lines)", () -> {
            compilerService.parse("file:///bench/Small.groovy", SMALL_SOURCE);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    @Test
    @Order(2)
    @DisplayName("Parse: medium file (~150 lines)")
    void parseMedium() {
        ProfilingResult result = profile("Parse medium (150 lines)", () -> {
            compilerService.parse("file:///bench/Medium.groovy", MEDIUM_SOURCE);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    @Test
    @Order(3)
    @DisplayName("Parse: large file (~500 lines)")
    void parseLarge() {
        ProfilingResult result = profile("Parse large (500 lines)", () -> {
            compilerService.parse("file:///bench/Large.groovy", LARGE_SOURCE);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    // ========================================================================
    // 2. Semantic Tokens
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("Semantic Tokens: small file (~40 lines)")
    void semanticTokensSmall() {
        allResults.add(profile("SemanticTokens small (40 lines)", () -> {
            ModuleNode ast = parseAST(SMALL_SOURCE, "Small");
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(SMALL_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();
        }));
    }

    @Test
    @Order(5)
    @DisplayName("Semantic Tokens: medium file (~150 lines)")
    void semanticTokensMedium() {
        allResults.add(profile("SemanticTokens medium (150 lines)", () -> {
            ModuleNode ast = parseAST(MEDIUM_SOURCE, "Medium");
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(MEDIUM_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();
        }));
    }

    @Test
    @Order(6)
    @DisplayName("Semantic Tokens: large file (~500 lines)")
    void semanticTokensLarge() {
        allResults.add(profile("SemanticTokens large (500 lines)", () -> {
            ModuleNode ast = parseAST(LARGE_SOURCE, "Large");
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(LARGE_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();
        }));
    }

    @Test
    @Order(7)
    @DisplayName("Semantic Tokens: visitor only (pre-parsed AST, small)")
    void semanticTokensVisitorOnly() {
        // Measure just the AST walk, excluding parse time
        ModuleNode ast = parseAST(SMALL_SOURCE, "Small");
        allResults.add(profile("SemanticTokens visitor only (40 lines)", () -> {
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(SMALL_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();
        }));
    }

    @Test
    @Order(8)
    @DisplayName("Semantic Tokens: visitor only (pre-parsed AST, large)")
    void semanticTokensVisitorOnlyLarge() {
        ModuleNode ast = parseAST(LARGE_SOURCE, "Large");
        allResults.add(profile("SemanticTokens visitor only (500 lines)", () -> {
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(LARGE_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();
        }));
    }

    // ========================================================================
    // 3. Folding Ranges
    // ========================================================================

    @Test
    @Order(9)
    @DisplayName("Folding Ranges: small file (~40 lines)")
    void foldingRangesSmall() {
        FoldingRangeProvider provider = new FoldingRangeProvider(documentManager);
        ProfilingResult result = profile("FoldingRange small (40 lines)", () -> {
            FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                    new TextDocumentIdentifier("file:///test/Small.groovy"));
            provider.getFoldingRanges(params);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    @Test
    @Order(10)
    @DisplayName("Folding Ranges: large file (~500 lines)")
    void foldingRangesLarge() {
        FoldingRangeProvider provider = new FoldingRangeProvider(documentManager);
        ProfilingResult result = profile("FoldingRange large (500 lines)", () -> {
            FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                    new TextDocumentIdentifier("file:///test/Large.groovy"));
            provider.getFoldingRanges(params);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    // ========================================================================
    // 4. Document Symbols (Groovy AST fallback path)
    // ========================================================================

    @Test
    @Order(11)
    @DisplayName("Document Symbols: small file (AST fallback)")
    void documentSymbolsSmall() {
        DocumentSymbolProvider provider = new DocumentSymbolProvider(documentManager);
        ProfilingResult result = profile("DocSymbol AST small (40 lines)", () -> {
            DocumentSymbolParams params = new DocumentSymbolParams(
                    new TextDocumentIdentifier("file:///test/Small.groovy"));
            provider.getDocumentSymbols(params);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    @Test
    @Order(12)
    @DisplayName("Document Symbols: large file (AST fallback)")
    void documentSymbolsLarge() {
        DocumentSymbolProvider provider = new DocumentSymbolProvider(documentManager);
        ProfilingResult result = profile("DocSymbol AST large (500 lines)", () -> {
            DocumentSymbolParams params = new DocumentSymbolParams(
                    new TextDocumentIdentifier("file:///test/Large.groovy"));
            provider.getDocumentSymbols(params);
        });
        allResults.add(result);
        assertTrue(result.max() >= 0);
    }

    // ========================================================================
    // 5. Combined: what happens when a file is opened?
    // ========================================================================

    @Test
    @Order(13)
    @DisplayName("File open: parse + semanticTokens + foldingRange + docSymbol (small)")
    void combinedFileOpenSmall() {
        FoldingRangeProvider foldingProvider = new FoldingRangeProvider(documentManager);
        DocumentSymbolProvider symbolProvider = new DocumentSymbolProvider(documentManager);

        allResults.add(profile("Combined file-open (40 lines)", () -> {
            // 1. Parse
            compilerService.parse("file:///bench/Combined.groovy", SMALL_SOURCE);

            // 2. Semantic tokens
            ModuleNode ast = parseAST(SMALL_SOURCE, "Combined");
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(SMALL_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();

            // 3. Folding ranges
            foldingProvider.getFoldingRanges(new FoldingRangeRequestParams(
                    new TextDocumentIdentifier("file:///test/Small.groovy")));

            // 4. Document symbols
            symbolProvider.getDocumentSymbols(new DocumentSymbolParams(
                    new TextDocumentIdentifier("file:///test/Small.groovy")));
        }));
    }

    @Test
    @Order(14)
    @DisplayName("File open: parse + semanticTokens + foldingRange + docSymbol (large)")
    void combinedFileOpenLarge() {
        FoldingRangeProvider foldingProvider = new FoldingRangeProvider(documentManager);
        DocumentSymbolProvider symbolProvider = new DocumentSymbolProvider(documentManager);

        allResults.add(profile("Combined file-open (500 lines)", () -> {
            compilerService.parse("file:///bench/CombinedLarge.groovy", LARGE_SOURCE);

            ModuleNode ast = parseAST(LARGE_SOURCE, "CombinedLarge");
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(LARGE_SOURCE, null);
            visitor.visitModule(ast);
            visitor.getEncodedTokens();

            foldingProvider.getFoldingRanges(new FoldingRangeRequestParams(
                    new TextDocumentIdentifier("file:///test/Large.groovy")));

            symbolProvider.getDocumentSymbols(new DocumentSymbolParams(
                    new TextDocumentIdentifier("file:///test/Large.groovy")));
        }));
    }

    // ========================================================================
    // Profiling infrastructure
    // ========================================================================

    private ProfilingResult profile(String name, Runnable operation) {
        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            operation.run();
        }

        // Measure
        long[] timings = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            operation.run();
            timings[i] = (System.nanoTime() - start) / 1_000; // convert to microseconds
        }

        ProfilingResult result = new ProfilingResult(name, timings);
        System.out.printf("  %-45s  p50=%6d µs  p95=%6d µs  max=%6d µs  mean=%7.0f µs%n",
                name, result.p50(), result.p95(), result.max(), result.mean());
        return result;
    }

    private ModuleNode parseAST(String source, String name) {
        GroovyCompilerService.ParseResult result =
                compilerService.parse("file:///bench/" + name + ".groovy", source);
        assertNotNull(result.getModuleNode(), "Expected AST for " + name);
        return result.getModuleNode();
    }

    // ---- Result class ----

    private static class ProfilingResult {
        final String name;
        final long[] sortedMicros;

        ProfilingResult(String name, long[] micros) {
            this.name = name;
            this.sortedMicros = micros.clone();
            java.util.Arrays.sort(this.sortedMicros);
        }

        long p50() { return percentile(50); }
        long p95() { return percentile(95); }
        long p99() { return percentile(99); }
        long max() { return sortedMicros[sortedMicros.length - 1]; }

        double mean() {
            long sum = 0;
            for (long v : sortedMicros) sum += v;
            return (double) sum / sortedMicros.length;
        }

        long percentile(int n) {
            int idx = (int) Math.ceil(n / 100.0 * sortedMicros.length) - 1;
            return sortedMicros[Math.max(0, Math.min(idx, sortedMicros.length - 1))];
        }
    }

    private void pauseMillis(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    // ---- Source generators ----

    /**
     * Generate a Groovy Spock specification of roughly the given line count.
     * Exercises traits, interfaces, methods, closures, string interpolation,
     * and various Groovy language features.
     */
    private static String generateSpockSpec(int approxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example.generated\n\n");
        sb.append("import java.util.List\n");
        sb.append("import java.util.Map\n");
        sb.append("import java.util.stream.Collectors\n");
        sb.append("import java.util.concurrent.ConcurrentHashMap\n\n");

        // Trait
        sb.append("trait Cacheable {\n");
        sb.append("    Map<String, Object> cache = new ConcurrentHashMap<>()\n\n");
        sb.append("    def getFromCache(String key) {\n");
        sb.append("        return cache.get(key)\n");
        sb.append("    }\n\n");
        sb.append("    void putInCache(String key, Object value) {\n");
        sb.append("        cache.put(key, value)\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        // Interface
        sb.append("interface Processor {\n");
        sb.append("    default List<String> transform(List<String> items) {\n");
        sb.append("        return items.collect { it.toUpperCase() }\n");
        sb.append("    }\n");
        sb.append("    int getProcessedCount()\n");
        sb.append("}\n\n");

        // Main class with many methods
        sb.append("class GeneratedService implements Cacheable, Processor {\n");
        sb.append("    List<String> items = []\n");
        sb.append("    int processedCount = 0\n\n");

        int linesWritten = 30; // approx lines so far
        int methodNum = 0;

        while (linesWritten < approxLines - 5) {
            // Generate varied method patterns
            switch (methodNum % 5) {
                case 0:
                    sb.append(String.format("""
                        def computeStatistics%d(List<Integer> values) {
                            def sum = values.sum() ?: 0
                            def avg = values.isEmpty() ? 0 : sum / values.size()
                            def max = values.max() ?: 0
                            def min = values.min() ?: 0
                            putInCache("stats%d", [sum: sum, avg: avg, max: max, min: min])
                            return [sum: sum, avg: avg, max: max, min: min]
                        }

                    """, methodNum, methodNum));
                    linesWritten += 10;
                    break;
                case 1:
                    sb.append(String.format("""
                        String formatReport%d(Map<String, Object> data, boolean verbose) {
                            def sb = new StringBuilder()
                            data.each { key, value ->
                                if (verbose) {
                                    sb.append("${key}: ${value} (type: ${value?.class?.simpleName})\\n")
                                } else {
                                    sb.append("${key}=${value}\\n")
                                }
                            }
                            processedCount++
                            return sb.toString()
                        }

                    """, methodNum));
                    linesWritten += 14;
                    break;
                case 2:
                    sb.append(String.format("""
                        List<String> filterItems%d(List<String> source, String pattern) {
                            return source.stream()
                                .filter { it.contains(pattern) }
                                .map { it.trim().toLowerCase() }
                                .distinct()
                                .collect(Collectors.toList())
                        }

                    """, methodNum));
                    linesWritten += 9;
                    break;
                case 3:
                    sb.append(String.format("""
                        def processWithRetry%d(Closure action, int maxRetries) {
                            int attempts = 0
                            while (attempts < maxRetries) {
                                try {
                                    def result = action.call()
                                    putInCache("retry%d", [attempts: attempts, result: result])
                                    return result
                                } catch (Exception e) {
                                    attempts++
                                    if (attempts >= maxRetries) {
                                        throw new RuntimeException("Failed after ${maxRetries} attempts", e)
                                    }
                                    Thread.sleep(100 * attempts)
                                }
                            }
                        }

                    """, methodNum, methodNum));
                    linesWritten += 17;
                    break;
                case 4:
                    sb.append(String.format("""
                        Map<String, List<String>> groupByPrefix%d(List<String> words) {
                            def grouped = words.groupBy { it.length() > 2 ? it.substring(0, 3) : it }
                            grouped.each { prefix, group ->
                                items.addAll(group)
                            }
                            processedCount += words.size()
                            return grouped
                        }

                    """, methodNum));
                    linesWritten += 10;
                    break;
                default:
                    throw new IllegalStateException("Unexpected method pattern: " + (methodNum % 5));
            }
            methodNum++;
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
