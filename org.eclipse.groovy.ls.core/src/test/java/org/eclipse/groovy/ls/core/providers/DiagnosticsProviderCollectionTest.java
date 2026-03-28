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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
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
}
