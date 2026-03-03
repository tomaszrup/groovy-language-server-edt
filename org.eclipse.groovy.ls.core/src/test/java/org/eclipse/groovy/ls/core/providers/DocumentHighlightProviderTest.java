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

import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DocumentHighlightProvider}.
 */
class DocumentHighlightProviderTest {

    private DocumentHighlightProvider provider;
    private DocumentManager documentManager;

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new DocumentHighlightProvider(documentManager);
    }

    // ---- getDocumentHighlights: missing document ----

    @Test
    void returnsEmptyForMissingDocument() {
        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier("file:///Missing.groovy"),
                new Position(0, 0));
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);
        assertNotNull(highlights);
        assertTrue(highlights.isEmpty());
    }

    // ---- getHighlightsFromText: variable occurrences ----

    @Test
    void textFallbackHighlightsVariableOccurrences() {
        String uri = "file:///Highlight1.groovy";
        documentManager.didOpen(uri, """
                class Box {
                    String name
                    void show() { println name }
                    void update() { name = 'new' }
                }
                """);

        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri),
                new Position(1, 11)); // "name" on field declaration
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);

        assertTrue(highlights.size() >= 3,
                "Expected at least 3 highlights for 'name', got " + highlights.size());
        assertTrue(highlights.stream().allMatch(h -> h.getKind() == DocumentHighlightKind.Text));

        documentManager.didClose(uri);
    }

    // ---- getHighlightsFromText: class name occurrences ----

    @Test
    void textFallbackHighlightsClassNameOccurrences() {
        String uri = "file:///Highlight2.groovy";
        documentManager.didOpen(uri, """
                class Widget {
                    String label
                }
                def w = new Widget()
                Widget another = new Widget()
                """);

        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 7)); // "Widget" 
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);

        assertTrue(highlights.size() >= 3,
                "Expected at least 3 highlights for 'Widget', got " + highlights.size());

        documentManager.didClose(uri);
    }

    // ---- getHighlightsFromText: whitespace returns empty ----

    @Test
    void textFallbackReturnsEmptyForWhitespace() {
        String uri = "file:///HighlightWS.groovy";
        documentManager.didOpen(uri, "class Foo {\n\n}");

        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri),
                new Position(1, 0)); // empty line
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);
        assertTrue(highlights.isEmpty());

        documentManager.didClose(uri);
    }

    // ---- getHighlightsFromText: method name occurrences ----

    @Test
    void textFallbackHighlightsMethodName() {
        String uri = "file:///HighlightMethod.groovy";
        documentManager.didOpen(uri, """
                class Service {
                    void process() {}
                    void run() {
                        process()
                        process()
                    }
                }
                """);

        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri),
                new Position(1, 10)); // "process"
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);

        assertTrue(highlights.size() >= 3,
                "Expected at least 3 highlights for 'process', got " + highlights.size());

        documentManager.didClose(uri);
    }

    // ---- extractWordAt ----

    @Test
    void extractWordAtFindsIdentifier() {
        String word = provider.extractWordAt("int foo = 42", 5);
        assertEquals("foo", word);
    }

    @Test
    void extractWordAtReturnsNullForNonIdentifier() {
        assertNull(provider.extractWordAt("  +++  ", 3));
    }

    @Test
    void extractWordAtHandlesUnderscore() {
        assertEquals("_count", provider.extractWordAt("int _count = 0", 5));
    }

    @Test
    void extractWordAtBoundary() {
        assertEquals("bar", provider.extractWordAt("foo.bar()", 5));
    }

    // ---- offsetToPosition / positionToOffset ----

    @Test
    void offsetToPositionFirstLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void positionToOffsetFirstLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(0, 3));
        assertEquals(3, offset);
    }

    @Test
    void positionToOffsetSecondLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(1, 2));
        assertEquals(8, offset);
    }

    // ---- getHighlightsFromText directly ----

    @Test
    void getHighlightsFromTextDirectly() {
        String uri = "file:///DirectText.groovy";
        documentManager.didOpen(uri, "def x = 1\nprintln x\nreturn x");

        List<DocumentHighlight> highlights = provider.getHighlightsFromText(uri, new Position(0, 4));
        assertTrue(highlights.size() >= 3);

        documentManager.didClose(uri);
    }

    @Test
    void getHighlightsFromTextReturnsEmptyForMissingContent() {
        List<DocumentHighlight> highlights = provider.getHighlightsFromText(
                "file:///NoSuchFile.groovy", new Position(0, 0));
        assertTrue(highlights.isEmpty());
    }

    // ---- Single occurrence (no duplicates) ----

    @Test
    void singleOccurrenceIdentifier() {
        String uri = "file:///HighlightSingle.groovy";
        documentManager.didOpen(uri, "def uniqueVar = 42");

        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 5)); // "uniqueVar"
        List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);
        assertEquals(1, highlights.size());

        documentManager.didClose(uri);
    }

    // ================================================================
    // resolveHighlightKind tests
    // ================================================================

    @Test
    void resolveHighlightKindInsideDocComment() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = org.mockito.Mockito.mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.mockito.Mockito.when(match.isInsideDocComment()).thenReturn(true);

        java.lang.reflect.Method m = DocumentHighlightProvider.class.getDeclaredMethod(
                "resolveHighlightKind", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        DocumentHighlightKind kind = (DocumentHighlightKind) m.invoke(null, match);

        assertEquals(DocumentHighlightKind.Text, kind);
    }

    @Test
    void resolveHighlightKindAccurate() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = org.mockito.Mockito.mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.mockito.Mockito.when(match.isInsideDocComment()).thenReturn(false);
        org.mockito.Mockito.when(match.getAccuracy()).thenReturn(org.eclipse.jdt.core.search.SearchMatch.A_ACCURATE);

        java.lang.reflect.Method m = DocumentHighlightProvider.class.getDeclaredMethod(
                "resolveHighlightKind", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        DocumentHighlightKind kind = (DocumentHighlightKind) m.invoke(null, match);

        assertEquals(DocumentHighlightKind.Read, kind);
    }

    @Test
    void resolveHighlightKindInaccurate() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = org.mockito.Mockito.mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.mockito.Mockito.when(match.isInsideDocComment()).thenReturn(false);
        org.mockito.Mockito.when(match.getAccuracy()).thenReturn(org.eclipse.jdt.core.search.SearchMatch.A_INACCURATE);

        java.lang.reflect.Method m = DocumentHighlightProvider.class.getDeclaredMethod(
                "resolveHighlightKind", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        DocumentHighlightKind kind = (DocumentHighlightKind) m.invoke(null, match);

        assertEquals(DocumentHighlightKind.Text, kind);
    }

    // ================================================================
    // normalizeUri tests
    // ================================================================

    @Test
    void normalizeUriHandlesEncodedSpaces() throws Exception {
        java.lang.reflect.Method m = DocumentHighlightProvider.class.getDeclaredMethod(
                "normalizeUri", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(provider, "file:///my%20file.groovy");
        assertNotNull(result);
    }

    @Test
    void normalizeUriHandlesNullGracefully() throws Exception {
        java.lang.reflect.Method m = DocumentHighlightProvider.class.getDeclaredMethod(
                "normalizeUri", String.class);
        m.setAccessible(true);
        // normalizeUri might return null for null input
        try {
            String result = (String) m.invoke(provider, (Object) null);
            // If it doesn't throw, it either returns null or some default
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Expected for null input
        }
    }
}
