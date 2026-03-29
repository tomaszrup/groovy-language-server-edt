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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DiagnosticsProvider} collection and caching logic,
 * complementing the existing {@link DiagnosticsProviderTest} which covers
 * filtering helpers.
 */
class DiagnosticsProviderCollectionTest {

    // ---- collectDiagnostics via Groovy compiler fallback ----

    @Test
    void collectDiagnosticsReturnsSyntaxErrorsForBrokenSource() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///CollectBroken.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertFalse(diagnostics.isEmpty(), "Should detect syntax errors");
        assertTrue(diagnostics.stream()
                .allMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsUsesCachedSyntaxErrorsBeforeWorkingCopyReconcile() throws Exception {
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public ICompilationUnit getWorkingCopy(String uri) {
                return workingCopy;
            }
        };
        String uri = "file:///CollectBrokenCached.groovy";
        documentManager.didOpen(uri, "class BrokenCached { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> true);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertFalse(diagnostics.isEmpty(), "Should reuse cached syntax diagnostics");
        verifyNoInteractions(workingCopy);

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsSuppressesUnresolvedClassFailuresDuringStartupSyntaxOnlyMode() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///StartupUnresolved.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass StartupUnresolved { Bar field }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> false);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertTrue(diagnostics.isEmpty(), "Startup syntax-only diagnostics should suppress unresolved classes");

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsStillReportsParserErrorsDuringStartupSyntaxOnlyMode() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///StartupSyntaxError.groovy";
        documentManager.didOpen(uri, "class StartupSyntaxError {\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> false);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertFalse(diagnostics.isEmpty(), "Startup syntax-only diagnostics should still report parser errors");
        assertTrue(diagnostics.stream()
                .noneMatch(d -> {
                    String message = messageText(d);
                    return message != null
                            && (message.contains("unable to resolve class")
                                || message.contains("No such class"));
                }));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsSuppressesQuotedUnresolvedClassFailuresDuringStartupSyntaxOnlyMode()
            throws Exception {
        SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
        String uri = "file:///StartupQuotedUnresolved.groovy";
        documentManager.didOpen(uri, "class StartupQuotedUnresolved {}\n");
        documentManager.seedCachedParseResult(
                uri,
                new GroovyCompilerService.ParseResult(
                        null,
                        List.of(new org.codehaus.groovy.syntax.SyntaxException(
                                "unable to resolve class 'foo.Bar'",
                                1,
                                1,
                                1,
                                10))));

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> false);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertTrue(
                diagnostics.isEmpty(),
                "Startup syntax-only diagnostics should suppress quoted unresolved classes");

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsSuppressesWrappedClasspathFailuresDuringStartupSyntaxOnlyMode()
        throws Exception {
    SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
    String uri = "file:///StartupWrappedClasspathFailure.groovy";
    documentManager.didOpen(uri, "class StartupWrappedClasspathFailure {}\n");
    documentManager.seedCachedParseResult(
        uri,
        new GroovyCompilerService.ParseResult(
            null,
            List.of(
                new org.codehaus.groovy.syntax.SyntaxException(
                    "Parse error: No such class: foo.Bar -- while compiling",
                    1,
                    1,
                    1,
                    10),
                new org.codehaus.groovy.syntax.SyntaxException(
                    "Parse error: Groovy:General error during conversion: startup wrapper",
                    1,
                    1,
                    1,
                    10),
                new org.codehaus.groovy.syntax.SyntaxException(
                    "Internal parse error: unable to resolve class 'foo.Bar'",
                    1,
                    1,
                    1,
                    10))));

    DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
    provider.setClasspathChecker(ignored -> false);
    provider.setInitializationCompleteSupplier(() -> false);
    provider.setBuildInProgressSupplier(() -> false);

    List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

    assertTrue(
        diagnostics.isEmpty(),
        "Startup syntax-only diagnostics should suppress wrapped classpath failures");

    documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsReportsUnresolvedClassFailuresAfterStartupWithoutWorkingCopy() throws Exception {
        SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
        String uri = "file:///StandaloneAfterStartup.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass StandaloneAfterStartup { Bar field }\n");
        documentManager.seedCachedParseResult(
                uri,
                new GroovyCompilerService.ParseResult(
                        null,
                        List.of(new org.codehaus.groovy.syntax.SyntaxException(
                                "No such class: foo.Bar -- while compiling",
                                1,
                                8,
                                1,
                                15))));

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> true);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertNull(documentManager.getWorkingCopy(uri), "Test should stay on the standalone fallback path");
        assertTrue(diagnostics.stream()
                .map(DiagnosticsProviderCollectionTest::messageText)
                .anyMatch(message -> message != null
                        && (message.contains("unable to resolve class")
                            || message.contains("No such class"))),
                diagnosticMessages(diagnostics));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsReportsWrappedClasspathFailuresAfterStartupWithoutWorkingCopy()
            throws Exception {
        SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
        String uri = "file:///StandaloneWrappedAfterStartup.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass StandaloneWrappedAfterStartup { Bar field }\n");
        documentManager.seedCachedParseResult(
                uri,
                new GroovyCompilerService.ParseResult(
                        null,
                        List.of(new org.codehaus.groovy.syntax.SyntaxException(
                                "Parse error: No such class: foo.Bar -- while compiling",
                                1,
                                8,
                                1,
                                15))));

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> true);
        provider.setInitializationCompleteSupplier(() -> true);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertNull(documentManager.getWorkingCopy(uri), "Test should stay on the standalone fallback path");
        assertTrue(diagnostics.stream()
                .map(DiagnosticsProviderCollectionTest::messageText)
                .anyMatch(message -> message != null && message.contains("No such class")),
                diagnosticMessages(diagnostics));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsReportsUnresolvedClassFailuresForNoRootProjectOnceClasspathArrives() throws Exception {
        SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
        String uri = "file:///NoRootStandaloneAfterClasspath.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass NoRootStandaloneAfterClasspath { Bar field }\n");
        documentManager.seedCachedParseResult(
                uri,
                new GroovyCompilerService.ParseResult(
                        null,
                        List.of(new org.codehaus.groovy.syntax.SyntaxException(
                                "No such class: foo.Bar -- while compiling",
                                1,
                                8,
                                1,
                                15))));

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> true);
        provider.setInitializationCompleteSupplier(() -> false);
        provider.setStandaloneFallbackReadyChecker(uri::equals);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertNull(documentManager.getWorkingCopy(uri), "Test should stay on the standalone fallback path");
        assertTrue(diagnostics.stream()
                .map(DiagnosticsProviderCollectionTest::messageText)
                .anyMatch(message -> message != null
                        && (message.contains("unable to resolve class")
                            || message.contains("No such class"))),
                diagnosticMessages(diagnostics));

        documentManager.didClose(uri);
    }

    @Test
    void collectSyntaxDiagnosticsReportsUnresolvedClassFailuresAfterStartup() throws Exception {
        SeededParseDocumentManager documentManager = new SeededParseDocumentManager();
        String uri = "file:///SyntaxPreviewAfterStartup.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass SyntaxPreviewAfterStartup { Bar field }\n");
        documentManager.seedCachedParseResult(
                uri,
                new GroovyCompilerService.ParseResult(
                        null,
                        List.of(new org.codehaus.groovy.syntax.SyntaxException(
                                "No such class: foo.Bar -- while compiling",
                                1,
                                8,
                                1,
                                15))));

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> true);
        provider.setInitializationCompleteSupplier(() -> true);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectSyntaxDiagnostics(provider, uri);

        assertTrue(diagnostics.stream()
                .map(DiagnosticsProviderCollectionTest::messageText)
                .anyMatch(message -> message != null
                        && (message.contains("unable to resolve class")
                            || message.contains("No such class"))),
                diagnosticMessages(diagnostics));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsReusesCachedStartupParseWithoutReparsing() throws Exception {
        TrackingDocumentManager documentManager = new TrackingDocumentManager();
        String uri = "file:///StartupCachedStandalone.groovy";
        documentManager.didOpen(uri, "import foo.Bar\nclass StartupCachedStandalone { Bar field }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> false);
        provider.setBuildInProgressSupplier(() -> false);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        assertTrue(diagnostics.isEmpty(), "Startup syntax-only diagnostics should still suppress unresolved classes");
        assertEquals(1, documentManager.trackingCompilerService.parseCalls);
        assertEquals(0, documentManager.trackingCompilerService.collectSyntaxErrorsCalls);

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsReturnsEmptyForValidSource() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///CollectValid.groovy";
        documentManager.didOpen(uri, "class Valid { String name }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        // Valid source should produce no errors from the Groovy compiler
        // (unused-import detection may add warnings, but won't here since there are no imports)
        assertTrue(diagnostics.stream()
                .noneMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsIncludesUnusedImportWarnings() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///UnusedImportDiag.groovy";
        String source = "import java.time.LocalDate\n\nclass A { String name }\n";
        documentManager.didOpen(uri, source);

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        // Should find unused import for LocalDate
        boolean hasUnusedImport = diagnostics.stream()
                .anyMatch(d -> messageText(d) != null && messageText(d).contains("LocalDate"));
        assertTrue(hasUnusedImport, "Should detect unused import for LocalDate");

        documentManager.didClose(uri);
    }

    @Test
    void collectDiagnosticsDoesNotFlagUsedImport() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///UsedImportDiag.groovy";
        String source = "import java.time.LocalDate\n\nclass A { LocalDate birthday }\n";
        documentManager.didOpen(uri, source);

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = invokeCollectDiagnostics(provider, uri);

        // No unused import warnings for LocalDate since it's used
        boolean hasUnusedLocalDate = diagnostics.stream()
                .anyMatch(d -> messageText(d) != null
                        && messageText(d).contains("LocalDate")
                        && messageText(d).toLowerCase().contains("unused"));
        assertFalse(hasUnusedLocalDate, "Used import should not be flagged as unused");

        documentManager.didClose(uri);
    }

    // ---- collectDiagnosticsForCodeActions / getLatestDiagnostics ----

    @Test
    void collectDiagnosticsForCodeActionsCachesDiagnostics() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///CacheDiag.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = provider.collectDiagnosticsForCodeActions(uri);
        assertFalse(diagnostics.isEmpty());

        // Verify the cache returns the same diagnostics
        List<Diagnostic> cached = provider.getLatestDiagnostics(uri);
        assertEquals(diagnostics.size(), cached.size());

        documentManager.didClose(uri);
    }

    @Test
    void getLatestDiagnosticsReturnsEmptyForUnknownUri() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        List<Diagnostic> diagnostics = provider.getLatestDiagnostics("file:///never-seen.groovy");

        assertNotNull(diagnostics);
        assertTrue(diagnostics.isEmpty());
    }

    // ---- clearDiagnostics ----

    @Test
    void clearDiagnosticsRemovesCachedEntries() {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///ClearDiag.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.collectDiagnosticsForCodeActions(uri);

        assertFalse(provider.getLatestDiagnostics(uri).isEmpty());

        provider.clearDiagnostics(uri);

        assertTrue(provider.getLatestDiagnostics(uri).isEmpty());

        documentManager.didClose(uri);
    }

    // ---- collectFromGroovyCompiler with various error types ----

    @Test
    void collectFromGroovyCompilerPositionsErrorCorrectly() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///PositionTest.groovy";
        String source = "class GoodStart {\n  def method() {\n    return\n  }\n  def broken = }\n}\n";
        documentManager.didOpen(uri, source);

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = new ArrayList<>();
        invokeCollectFromGroovyCompiler(provider, uri, diagnostics);

        assertFalse(diagnostics.isEmpty());
        // All diagnostics should have valid range
        for (Diagnostic d : diagnostics) {
            assertNotNull(d.getRange());
            assertTrue(d.getRange().getStart().getLine() >= 0);
            assertTrue(d.getRange().getStart().getCharacter() >= 0);
        }

        documentManager.didClose(uri);
    }

    @Test
    void collectFromGroovyCompilerProducesEmptyForValidSource() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///ValidCompiler.groovy";
        documentManager.didOpen(uri, "class ValidGroovy {\n  String name\n  int age\n}\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);

        List<Diagnostic> diagnostics = new ArrayList<>();
        invokeCollectFromGroovyCompiler(provider, uri, diagnostics);

        assertTrue(diagnostics.isEmpty(), "Valid Groovy should produce no compiler errors");

        documentManager.didClose(uri);
    }

    @Test
    void toFileLocationUriReturnsNullForNonFileUri() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        Method method = DiagnosticsProvider.class.getDeclaredMethod("toFileLocationUri", String.class);
        method.setAccessible(true);

        Object result = method.invoke(provider, "groovy-source:///test/Virtual.groovy");

        assertNull(result);
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private List<Diagnostic> invokeCollectDiagnostics(DiagnosticsProvider provider, String uri) throws Exception {
        Method method = DiagnosticsProvider.class.getDeclaredMethod("collectDiagnostics", String.class);
        method.setAccessible(true);
        return (List<Diagnostic>) method.invoke(provider, uri);
    }

    private void invokeCollectFromGroovyCompiler(DiagnosticsProvider provider, String uri, List<Diagnostic> diagnostics) throws Exception {
        Method method = DiagnosticsProvider.class.getDeclaredMethod("collectFromGroovyCompiler", String.class, List.class);
        method.setAccessible(true);
        method.invoke(provider, uri, diagnostics);
    }

    @SuppressWarnings("unchecked")
    private List<Diagnostic> invokeCollectSyntaxDiagnostics(DiagnosticsProvider provider, String uri) throws Exception {
        Method method = DiagnosticsProvider.class.getDeclaredMethod("collectSyntaxDiagnostics", String.class);
        method.setAccessible(true);
        return (List<Diagnostic>) method.invoke(provider, uri);
    }

    /**
     * Extract the plain-text message from a Diagnostic, handling the
     * {@code Either<String, MarkupContent>} return type in LSP4j 1.0.
     */
    private static String messageText(Diagnostic d) {
        Object msg = d.getMessage();
        if (msg == null) return null;
        if (msg instanceof String) return (String) msg;
        // Either<String, MarkupContent>
        if (msg instanceof org.eclipse.lsp4j.jsonrpc.messages.Either) {
            org.eclipse.lsp4j.jsonrpc.messages.Either<?, ?> either =
                    (org.eclipse.lsp4j.jsonrpc.messages.Either<?, ?>) msg;
            Object left = either.getLeft();
            if (left instanceof String) return (String) left;
            Object right = either.getRight();
            if (right instanceof org.eclipse.lsp4j.MarkupContent) {
                return ((org.eclipse.lsp4j.MarkupContent) right).getValue();
            }
        }
        return msg.toString();
    }

    private static String diagnosticMessages(List<Diagnostic> diagnostics) {
        List<String> messages = diagnostics.stream()
                .map(DiagnosticsProviderCollectionTest::messageText)
                .toList();
        return "messages=" + messages;
    }

    private static final class TrackingDocumentManager extends DocumentManager {
        private final TrackingGroovyCompilerService trackingCompilerService =
                new TrackingGroovyCompilerService();

        @Override
        public void didOpen(String uri, String text) {
            super.didOpen(uri, text);
            trackingCompilerService.parse(uri, text);
        }

        @Override
        public void didClose(String uri) {
            super.didClose(uri);
            trackingCompilerService.invalidateDocumentFamily(uri);
        }

        @Override
        public GroovyCompilerService getCompilerService() {
            return trackingCompilerService;
        }
    }

    private static final class SeededParseDocumentManager extends DocumentManager {
        private final SeededGroovyCompilerService seededGroovyCompilerService =
                new SeededGroovyCompilerService();

        void seedCachedParseResult(String uri, GroovyCompilerService.ParseResult result) {
            seededGroovyCompilerService.seedParseResult(uri, result);
        }

        @Override
        public void didClose(String uri) {
            super.didClose(uri);
            seededGroovyCompilerService.invalidateDocumentFamily(uri);
        }

        @Override
        public GroovyCompilerService getCompilerService() {
            return seededGroovyCompilerService;
        }
    }

    private static final class TrackingGroovyCompilerService extends GroovyCompilerService {
        private int parseCalls;
        private int collectSyntaxErrorsCalls;

        @Override
        public ParseResult parse(String uri, String source) {
            parseCalls++;
            return super.parse(uri, source);
        }

        @Override
        public List<org.codehaus.groovy.syntax.SyntaxException> collectSyntaxErrors(
                String uri,
                String source) {
            collectSyntaxErrorsCalls++;
            return super.collectSyntaxErrors(uri, source);
        }
    }

    private static final class SeededGroovyCompilerService extends GroovyCompilerService {
        private final java.util.Map<String, ParseResult> seededResults =
                new java.util.concurrent.ConcurrentHashMap<>();

        void seedParseResult(String uri, ParseResult result) {
            seededResults.put(DocumentManager.normalizeUri(uri), result);
        }

        @Override
        public ParseResult getCachedResult(String uri) {
            ParseResult seeded = seededResults.get(DocumentManager.normalizeUri(uri));
            return seeded != null ? seeded : super.getCachedResult(uri);
        }

        @Override
        public ParseResult parse(String uri, String source) {
            ParseResult seeded = seededResults.get(DocumentManager.normalizeUri(uri));
            return seeded != null ? seeded : super.parse(uri, source);
        }

        @Override
        public void invalidateDocumentFamily(String uri) {
            seededResults.remove(DocumentManager.normalizeUri(uri));
            super.invalidateDocumentFamily(uri);
        }
    }
}
