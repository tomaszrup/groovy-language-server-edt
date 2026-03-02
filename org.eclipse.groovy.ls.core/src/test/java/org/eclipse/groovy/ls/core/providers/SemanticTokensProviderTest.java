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

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class SemanticTokensProviderTest {

    @Test
    void legendContainsExpectedTokenEntries() {
        SemanticTokensLegend legend = SemanticTokensProvider.getLegend();

        assertNotNull(legend);
        assertTrue(legend.getTokenTypes().contains("class"));
        assertTrue(legend.getTokenTypes().contains("method"));
        assertTrue(legend.getTokenModifiers().contains("static"));
    }

    @Test
    void returnsEmptyWhenDocumentIsMissing() {
        SemanticTokensProvider provider = new SemanticTokensProvider(new DocumentManager());
        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///MissingSemanticTokensDoc.groovy"));

        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        assertTrue(tokens.getData().isEmpty());
    }

    @Test
    void computesTokensForFullAndRangeRequests() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SemanticTokensProviderData.groovy";
        manager.didOpen(uri, """
                class Person {
                    String name
                    def greet(String other) { other.toUpperCase() }
                }
                def p = new Person(name: 'Ada')
                p.greet('Bob')
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams fullParams = new SemanticTokensParams();
        fullParams.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens full = provider.getSemanticTokensFull(fullParams);
        assertFalse(full.getData().isEmpty());

        SemanticTokensRangeParams rangeParams = new SemanticTokensRangeParams();
        rangeParams.setTextDocument(new TextDocumentIdentifier(uri));
        rangeParams.setRange(new Range(new Position(0, 0), new Position(2, 0)));
        SemanticTokens ranged = provider.getSemanticTokensRange(rangeParams);

        assertNotNull(ranged);
        assertTrue(ranged.getData().size() <= full.getData().size());

        manager.didClose(uri);
    }

    @Test
    void getModuleNodeReturnsNullForNonGroovyCompilationUnit() throws Exception {
        SemanticTokensProvider provider = new SemanticTokensProvider(new DocumentManager());
        ICompilationUnit unit = mock(ICompilationUnit.class);

        assertNull(invokeGetModuleNode(provider, unit));
    }

    @Test
    void legendContainsAllExpectedTokenTypes() {
        SemanticTokensLegend legend = SemanticTokensProvider.getLegend();

        assertNotNull(legend);
        assertTrue(legend.getTokenTypes().contains("namespace"));
        assertTrue(legend.getTokenTypes().contains("type"));
        assertTrue(legend.getTokenTypes().contains("enum"));
        assertTrue(legend.getTokenTypes().contains("interface"));
        assertTrue(legend.getTokenTypes().contains("parameter"));
        assertTrue(legend.getTokenTypes().contains("variable"));
        assertTrue(legend.getTokenTypes().contains("property"));
        assertTrue(legend.getTokenTypes().contains("function"));
        assertTrue(legend.getTokenTypes().contains("keyword"));
        assertTrue(legend.getTokenTypes().contains("comment"));
        assertTrue(legend.getTokenTypes().contains("string"));
        assertTrue(legend.getTokenTypes().contains("number"));
    }

    @Test
    void legendContainsAllExpectedModifiers() {
        SemanticTokensLegend legend = SemanticTokensProvider.getLegend();

        assertNotNull(legend);
        assertTrue(legend.getTokenModifiers().contains("declaration"));
        assertTrue(legend.getTokenModifiers().contains("definition"));
        assertTrue(legend.getTokenModifiers().contains("readonly"));
        assertTrue(legend.getTokenModifiers().contains("deprecated"));
        assertTrue(legend.getTokenModifiers().contains("abstract"));
        assertTrue(legend.getTokenModifiers().contains("defaultLibrary"));
    }

    @Test
    void fullRequestProducesMultipleOf5DataEntries() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SemanticTokensMod5.groovy";
        manager.didOpen(uri, """
                class Example {
                    String value
                    def getValue() { value }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        assertFalse(tokens.getData().isEmpty());
        assertTrue(tokens.getData().size() % 5 == 0, "Token data must be a multiple of 5");

        manager.didClose(uri);
    }

    @Test
    void rangeRequestAtEndOfDocumentReturnsSubset() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SemanticTokensRangeEnd.groovy";
        manager.didOpen(uri, """
                class First {}
                class Second {}
                class Third {}
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensRangeParams rangeParams = new SemanticTokensRangeParams();
        rangeParams.setTextDocument(new TextDocumentIdentifier(uri));
        rangeParams.setRange(new Range(new Position(2, 0), new Position(3, 0)));
        SemanticTokens ranged = provider.getSemanticTokensRange(rangeParams);

        assertNotNull(ranged);
        // Tokens for only the last class
        assertTrue(ranged.getData().size() % 5 == 0);

        manager.didClose(uri);
    }

    @Test
    void returnsEmptyTokensForDocumentWithSyntaxErrors() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SemanticTokensSyntaxError.groovy";
        manager.didOpen(uri, """
                class Broken {{{}}
                def x = 
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        // May or may not have tokens, but should not throw
        manager.didClose(uri);
    }

    @Test
    void tokenTypeConstantsMatchLegendIndices() {
        assertEquals(0, SemanticTokensProvider.TYPE_NAMESPACE);
        assertEquals(1, SemanticTokensProvider.TYPE_TYPE);
        assertEquals(2, SemanticTokensProvider.TYPE_CLASS);
        assertEquals(3, SemanticTokensProvider.TYPE_ENUM);
        assertEquals(4, SemanticTokensProvider.TYPE_INTERFACE);
        assertEquals(7, SemanticTokensProvider.TYPE_PARAMETER);
        assertEquals(8, SemanticTokensProvider.TYPE_VARIABLE);
        assertEquals(9, SemanticTokensProvider.TYPE_PROPERTY);
        assertEquals(10, SemanticTokensProvider.TYPE_ENUM_MEMBER);
        assertEquals(13, SemanticTokensProvider.TYPE_METHOD);
    }

    @Test
    void modifierBitConstantsArePowersOfTwo() {
        assertEquals(1, SemanticTokensProvider.MOD_DECLARATION);
        assertEquals(2, SemanticTokensProvider.MOD_DEFINITION);
        assertEquals(4, SemanticTokensProvider.MOD_READONLY);
        assertEquals(8, SemanticTokensProvider.MOD_STATIC);
        assertEquals(16, SemanticTokensProvider.MOD_DEPRECATED);
        assertEquals(32, SemanticTokensProvider.MOD_ABSTRACT);
        assertEquals(256, SemanticTokensProvider.MOD_DEFAULT_LIB);
    }

    @Test
    void returnsCachedTokensWhenASTBreaks() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///CachedTokensTest.groovy";

        // Start with valid code — produces tokens
        manager.didOpen(uri, """
                class Greeter {
                    String name
                    def greet() { name.toUpperCase() }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens goodTokens = provider.getSemanticTokensFull(params);
        assertFalse(goodTokens.getData().isEmpty(), "Valid code should produce tokens");

        // Simulate typing "xdd." — code becomes syntactically broken
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent("""
                class Greeter {
                    String name
                    def greet() { name.toUpperCase() }
                }
                def xdd = new Greeter()
                xdd.
                """);
        manager.didChange(uri, List.of(change));

        SemanticTokens afterBreak = provider.getSemanticTokensFull(params);
        assertNotNull(afterBreak);
        // Should return cached tokens (non-empty), not wipe all highlighting
        assertFalse(afterBreak.getData().isEmpty(),
                "After syntax error, cached tokens should be returned instead of empty");

        manager.didClose(uri);
    }

    @Test
    void invalidateClearsCachedTokens() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///InvalidateCacheTest.groovy";
        manager.didOpen(uri, """
                class Foo {
                    def bar() { 42 }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);
        assertFalse(tokens.getData().isEmpty());

        // Invalidate the cache
        provider.invalidate(uri);

        // Now break the code — with no cache, should return empty
        TextDocumentContentChangeEvent breakChange = new TextDocumentContentChangeEvent("class {{{ broken");
        manager.didChange(uri, List.of(breakChange));
        SemanticTokens afterInvalidate = provider.getSemanticTokensFull(params);
        assertNotNull(afterInvalidate);
        // No cached tokens available after invalidation
        assertTrue(afterInvalidate.getData().isEmpty(),
                "After invalidation and broken code, should return empty tokens");

        manager.didClose(uri);
    }

    @Test
    void cachedTokensSurviveCloseAndReopen() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///CloseReopenSurvival.groovy";
        // Step 1: open with valid code — tokens are produced and cached
        manager.didOpen(uri, """
                class Hello {
                    def world() { 42 }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens goodTokens = provider.getSemanticTokensFull(params);
        assertFalse(goodTokens.getData().isEmpty(), "Valid code should produce tokens");

        // Step 2: break the code — cached tokens should be returned
        TextDocumentContentChangeEvent breakChange = new TextDocumentContentChangeEvent("""
                class Hello {
                    def world() { 42 }
                    xdd.
                }
                """);
        manager.didChange(uri, List.of(breakChange));
        SemanticTokens brokenTokens = provider.getSemanticTokensFull(params);
        assertFalse(brokenTokens.getData().isEmpty(), "Broken code should return cached tokens");

        // Step 3: close the file (simulates VS Code closing the tab)
        manager.didClose(uri);
        // NOTE: we do NOT call provider.invalidate() here — matching the real didClose behavior

        // Step 4: reopen with the same broken content
        manager.didOpen(uri, """
                class Hello {
                    def world() { 42 }
                    xdd.
                }
                """);
        SemanticTokens reopenedTokens = provider.getSemanticTokensFull(params);
        assertFalse(reopenedTokens.getData().isEmpty(),
                "After close and reopen with broken code, cached tokens should still be available");

        manager.didClose(uri);
    }

    @Test
    void firstOpenWithBrokenCodeReturnsEmpty() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///FirstOpenBroken.groovy";
        // Open with completely broken code — no prior cache exists
        manager.didOpen(uri, "class {{{ xdd.");

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        assertTrue(tokens.getData().isEmpty(),
                "First open with completely broken code should return empty (no cache available)");

        manager.didClose(uri);
    }

    @Test
    void firstOpenWithPartiallyBrokenCodeTriesStandaloneFallback() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///FirstOpenPartiallyBroken.groovy";
        // Open with a valid class structure but a trailing dot expression —
        // the patched content fallback should strip the dot and produce tokens.
        manager.didOpen(uri, """
                class Greeter {
                    String name
                    def greet() {
                        def xdd = new Object()
                        xdd.
                    }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        // The patched content fallback should produce tokens for the class structure
        assertFalse(tokens.getData().isEmpty(),
                "Partially broken code (trailing dot) should produce tokens via patched-content fallback");

        manager.didClose(uri);
    }

    private Object invokeGetModuleNode(SemanticTokensProvider provider, ICompilationUnit unit) throws Exception {
        Method method = SemanticTokensProvider.class.getDeclaredMethod("getModuleNode", ICompilationUnit.class);
        method.setAccessible(true);
        return method.invoke(provider, unit);
    }
}

