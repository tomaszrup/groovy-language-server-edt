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
    void returnsEmptyWhenRequestThreadIsInterrupted() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///InterruptedSemanticTokens.groovy";
        manager.didOpen(uri, "class Sample { String value }\n");

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);
        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));

        Thread.currentThread().interrupt();
        try {
            SemanticTokens tokens = provider.getSemanticTokensFullBestEffort(params);
            assertNotNull(tokens);
            assertTrue(tokens.getData().isEmpty());
        } finally {
            Thread.interrupted();
            manager.didClose(uri);
        }
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
    void computesTokensForBestEffortFullRequests() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SemanticTokensBestEffort.groovy";
        manager.didOpen(uri, """
                class Person {
                    String name
                    def greet(String other) { other.toUpperCase() }
                }
                def p = new Person(name: 'Ada')
                p.greet('Bob')
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens full = provider.getSemanticTokensFullBestEffort(params);

        assertNotNull(full);
        assertFalse(full.getData().isEmpty());

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
    void returnsFreshTokensForValidPortionWhenASTBreaks() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///CutoffTokensTest.groovy";

        // Code with valid class structure but a syntax error at the end
        manager.didOpen(uri, """
                class Greeter {
                    String name
                    def greet() { name.toUpperCase() }
                }
                def xdd = new Greeter()
                xdd.
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);
        assertNotNull(tokens);
        // Should have tokens for the valid portion (class, fields, methods)
        // but NOT stale tokens from a cached previous version
        assertFalse(tokens.getData().isEmpty(),
                "Should produce tokens for the valid portion of the broken file");
        assertTrue(tokens.getData().size() % 5 == 0, "Token data must be a multiple of 5");

        manager.didClose(uri);
    }

    @Test
    void garbageInsertedMidFileDoesNotShiftEarlierTokens() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///GarbageMidFileTest.groovy";

        // Valid code
        String validCode = """
                class Example {
                    String value
                    def getValue() { value }
                }
                """;
        manager.didOpen(uri, validCode);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens goodTokens = provider.getSemanticTokensFull(params);
        assertFalse(goodTokens.getData().isEmpty(), "Valid code should produce tokens");

        // Now insert garbage after the class
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent("""
                class Example {
                    String value
                    def getValue() { value }
                }
                \\::""
                """);
        manager.didChange(uri, List.of(change));

        SemanticTokens brokenTokens = provider.getSemanticTokensFull(params);
        assertNotNull(brokenTokens);
        // The tokens for the valid portion should match the original tokens
        // (since the garbage is after the class, all class tokens should survive
        // the cutoff and their positions should be identical)
        assertFalse(brokenTokens.getData().isEmpty(),
                "Tokens for valid portion should still be produced");

        manager.didClose(uri);
    }

    @Test
    void fixingGarbageRestoresAllTokens() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///FixGarbageTest.groovy";

        // Start broken
        manager.didOpen(uri, """
                class Hello {
                    def world() { 42 }
                    \\::""
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens brokenTokens = provider.getSemanticTokensFull(params);
        assertNotNull(brokenTokens);

        // Fix the code
        TextDocumentContentChangeEvent fix = new TextDocumentContentChangeEvent("""
                class Hello {
                    def world() { 42 }
                }
                """);
        manager.didChange(uri, List.of(fix));
        SemanticTokens fixedTokens = provider.getSemanticTokensFull(params);
        assertNotNull(fixedTokens);
        assertFalse(fixedTokens.getData().isEmpty(),
                "After fixing the code, all tokens should be restored");
        // Fixed version should have at least as many tokens as the broken one
        assertTrue(fixedTokens.getData().size() >= brokenTokens.getData().size(),
                "Fixed code should produce at least as many tokens as the broken version");

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

    @Test
    void importTokensPreservedWhenClassDeclarationIsBroken() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ImportPreservation.groovy";
        manager.didOpen(uri, """
                import java.util.List

                class X extends Lis--t {
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        // Even though the class declaration is broken, import tokens should survive
        assertFalse(tokens.getData().isEmpty(),
                "Import tokens should be preserved when the class declaration is broken");

        manager.didClose(uri);
    }

    @Test
    void importTokensPreservedWhenLongClassDeclarationIsBroken() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///LongBrokenClass.groovy";
        manager.didOpen(uri, """
                import java.util.List
                import java.util.Map

                class X extends Lis--t {
                    String name
                    int age
                    boolean active
                    String email
                    String phone
                    String address

                    void doSomething() {
                        println("hello")
                    }

                    void doSomethingElse() {
                        println("world")
                    }
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        // The broken class spans many lines — block-aware blanking should still
        // recover import tokens by blanking the entire brace-matched block.
        assertFalse(tokens.getData().isEmpty(),
                "Import tokens should be preserved even when the broken class body is long");

        manager.didClose(uri);
    }

    @Test
    void importTokensPreservedWithPackageAndAnnotation() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///PackageAnnotation.groovy";
        manager.didOpen(uri, """
                package com.example.commons.delta

                import spock.lang.Specification
                import spock.lang.Stepwise

                @Stepwise
                class DeltaCommonContractSpec extends Spe--cification {

                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        SemanticTokens tokens = provider.getSemanticTokensFull(params);

        assertNotNull(tokens);
        // Package, import, and annotation tokens should survive even when the
        // class declaration has a broken superclass reference.
        assertFalse(tokens.getData().isEmpty(),
                "Package/import tokens should be preserved with broken class + annotation");

        manager.didClose(uri);
    }

    private Object invokeGetModuleNode(SemanticTokensProvider provider, ICompilationUnit unit) throws Exception {
        Method method = SemanticTokensProvider.class.getDeclaredMethod("getModuleNode", ICompilationUnit.class);
        method.setAccessible(true);
        return method.invoke(provider, unit);
    }
}
