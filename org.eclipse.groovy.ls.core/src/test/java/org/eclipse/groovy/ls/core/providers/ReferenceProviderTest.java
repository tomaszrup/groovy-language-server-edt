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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class ReferenceProviderTest {

    @Test
    void getReferencesFallsBackToTextSearchWhenNoWorkingCopy() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ReferenceProviderFallback.groovy";
        manager.didOpen(uri, """
                class Box { int size }
                def b = new Box(size: 1)
                println b.size
                """);

        ReferenceProvider provider = new ReferenceProvider(manager);
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 16));
        params.setContext(new ReferenceContext(true));

        List<Location> refs = provider.getReferences(params);

        assertTrue(refs.size() >= 3);
        assertTrue(refs.stream().allMatch(loc -> uri.equals(loc.getUri())));

        manager.didClose(uri);
    }

    @Test
    void getReferencesReturnsEmptyWhenDocumentMissing() {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///MissingReferenceFile.groovy"));
        params.setPosition(new Position(0, 0));

        assertTrue(provider.getReferences(params).isEmpty());
    }

    @Test
    void toLocationReturnsNullWhenSearchMatchHasNoResource() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        SearchMatch match = mock(SearchMatch.class);
        when(match.getResource()).thenReturn(null);

        assertNull(invokeToLocation(provider, match));
    }

    @Test
    void helperMethodsConvertOffsetsAndWords() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        String content = "alpha beta\ngamma";

        assertEquals(new Position(1, 2), invokeOffsetToPosition(provider, content, 13));
        assertEquals("beta", invokeExtractWordAt(provider, content, 7));
        assertEquals(12, invokePositionToOffset(provider, content, new Position(1, 1)));
    }

    // ---- getReferences: method references across multiple usages ----

    @Test
    void getReferencesFindsMethodUsages() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ReferenceProviderMethodRef.groovy";
        manager.didOpen(uri, """
                class Service {
                    void process(String data) {}
                    void run() {
                        process('a')
                        process('b')
                        process('c')
                    }
                }
                """);

        ReferenceProvider provider = new ReferenceProvider(manager);
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 10)); // "process"
        params.setContext(new ReferenceContext(true));

        List<Location> refs = provider.getReferences(params);

        assertTrue(refs.size() >= 4, "Should find declaration + 3 usages");
        assertTrue(refs.stream().allMatch(loc -> uri.equals(loc.getUri())));

        manager.didClose(uri);
    }

    // ---- getReferences: class name references ----

    @Test
    void getReferencesFindsClassNameUsages() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ReferenceProviderClassName.groovy";
        manager.didOpen(uri, """
                class Widget {
                    String label
                }
                def w = new Widget()
                Widget another = new Widget()
                """);

        ReferenceProvider provider = new ReferenceProvider(manager);
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 7)); // "Widget"
        params.setContext(new ReferenceContext(true));

        List<Location> refs = provider.getReferences(params);

        assertTrue(refs.size() >= 3, "Should find class decl + 2 new Widget() + Widget type");

        manager.didClose(uri);
    }

    // ---- getReferences: returns empty for whitespace ----

    @Test
    void getReferencesReturnsEmptyForWhitespace() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ReferenceProviderWhitespace.groovy";
        manager.didOpen(uri, "class Foo {\n\n}");

        ReferenceProvider provider = new ReferenceProvider(manager);
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 0));
        params.setContext(new ReferenceContext(true));

        assertTrue(provider.getReferences(params).isEmpty());

        manager.didClose(uri);
    }

    // ---- offsetToPosition edge cases ----

    @Test
    void offsetToPositionAtStart() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        assertEquals(new Position(0, 0), invokeOffsetToPosition(provider, "hello", 0));
    }

    @Test
    void offsetToPositionMultipleNewlines() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        String content = "a\nb\nc\n";
        assertEquals(new Position(2, 0), invokeOffsetToPosition(provider, content, 4));
    }

    // ---- extractWordAt edge cases ----

    @Test
    void extractWordAtReturnsNullForNonIdentifier() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        assertNull(invokeExtractWordAt(provider, "  +++  ", 3));
    }

    @Test
    void extractWordAtHandlesUnderscoreIdentifier() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        assertEquals("_field", invokeExtractWordAt(provider, "int _field = 1", 5));
    }

    // ---- getReferencesFromGroovyAST via reflection ----

    @Test
    void getReferencesFromGroovyASTFindsAllOccurrences() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ReferenceAST.groovy";
        manager.didOpen(uri, """
                class Cfg {
                    String host
                    void show() { println host }
                }
                def c = new Cfg()
                c.host = 'localhost'
                """);

        ReferenceProvider provider = new ReferenceProvider(manager);
        List<Location> refs = invokeGetReferencesFromGroovyAST(provider, uri, new Position(1, 11));

        assertTrue(refs.size() >= 3, "Should find field decl + usage in show() + property access");

        manager.didClose(uri);
    }

    @Test
    void getReferencesFromGroovyASTReturnsEmptyForMissingContent() throws Exception {
        ReferenceProvider provider = new ReferenceProvider(new DocumentManager());
        assertTrue(invokeGetReferencesFromGroovyAST(provider,
                "file:///NoContent.groovy", new Position(0, 0)).isEmpty());
    }

    private Location invokeToLocation(ReferenceProvider provider, SearchMatch match) throws Exception {
        Method method = ReferenceProvider.class.getDeclaredMethod("toLocation", SearchMatch.class);
        method.setAccessible(true);
        return (Location) method.invoke(provider, match);
    }

    private Position invokeOffsetToPosition(ReferenceProvider provider, String content, int offset) throws Exception {
        Method method = ReferenceProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        method.setAccessible(true);
        return (Position) method.invoke(provider, content, offset);
    }

    private String invokeExtractWordAt(ReferenceProvider provider, String content, int offset) throws Exception {
        Method method = ReferenceProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, content, offset);
    }

    @SuppressWarnings("unchecked")
    private List<Location> invokeGetReferencesFromGroovyAST(ReferenceProvider provider, String uri,
                                                             Position position) throws Exception {
        Method method = ReferenceProvider.class.getDeclaredMethod("getReferencesFromGroovyAST",
                String.class, Position.class);
        method.setAccessible(true);
        return (List<Location>) method.invoke(provider, uri, position);
    }

    private int invokePositionToOffset(ReferenceProvider provider, String content, Position position) throws Exception {
        Method method = ReferenceProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        method.setAccessible(true);
        return (int) method.invoke(provider, content, position);
    }
}

