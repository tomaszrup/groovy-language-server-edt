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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;

class RenameProviderTest {

    @Test
    void renameFallsBackToAstAndProducesWorkspaceEdit() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameProviderFallback.groovy";
        manager.didOpen(uri, """
                class Person {
                    String name
                    void greet() { println name }
                }
                def p = new Person(name: 'Ada')
                println p.name
                """);

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 11)); // "name"
        params.setNewName("displayName");

        WorkspaceEdit edit = provider.rename(params);

        assertNotNull(edit);
        assertNotNull(edit.getChanges());
        List<TextEdit> fileEdits = edit.getChanges().get(uri);
        assertNotNull(fileEdits);
        assertTrue(fileEdits.size() >= 3);
        assertTrue(fileEdits.stream().allMatch(e -> "displayName".equals(e.getNewText())));

        manager.didClose(uri);
    }

    @Test
    void renameReturnsNullWhenContentMissing() {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///MissingRenameFile.groovy"));
        params.setPosition(new Position(0, 0));
        params.setNewName("x");

        assertNull(provider.rename(params));
    }

    @Test
    void addRenameEditSkipsMatchesWithoutResource() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        SearchMatch match = mock(SearchMatch.class);
        when(match.getResource()).thenReturn(null);

        Map<String, List<TextEdit>> edits = new HashMap<>();
        invokeAddRenameEdit(provider, match, "newName", edits);

        assertTrue(edits.isEmpty());
    }

    @Test
    void helperMethodsTranslateOffsetsAndWords() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        String content = "first line\nsecond line";

        assertEquals(new Position(1, 3), invokeOffsetToPosition(provider, content, 14));
        assertEquals("second", invokeExtractWordAt(provider, content, 12));
        assertEquals(12, invokePositionToOffset(provider, content, new Position(1, 1)));
    }

    // ---- rename: method name across file ----

    @Test
    void renameMethodNameAcrossAllUsages() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameProviderMethod.groovy";
        manager.didOpen(uri, """
                class Calculator {
                    int compute(int x) { x * 2 }
                    void run() {
                        compute(5)
                        println compute(10)
                    }
                }
                """);

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 8)); // "compute"
        params.setNewName("calculate");

        WorkspaceEdit edit = provider.rename(params);

        assertNotNull(edit);
        List<TextEdit> fileEdits = edit.getChanges().get(uri);
        assertNotNull(fileEdits);
        assertTrue(fileEdits.size() >= 3, "Should rename all occurrences");
        assertTrue(fileEdits.stream().allMatch(e -> "calculate".equals(e.getNewText())));

        manager.didClose(uri);
    }

    // ---- rename: class name ----

    @Test
    void renameClassName() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameProviderClassName.groovy";
        manager.didOpen(uri, """
                class Service {
                    void run() {}
                }
                def svc = new Service()
                """);

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 7)); // "Service"
        params.setNewName("Worker");

        WorkspaceEdit edit = provider.rename(params);

        assertNotNull(edit);
        List<TextEdit> fileEdits = edit.getChanges().get(uri);
        assertNotNull(fileEdits);
        assertTrue(fileEdits.size() >= 2, "Should rename class declaration and usage");
        assertTrue(fileEdits.stream().allMatch(e -> "Worker".equals(e.getNewText())));

        manager.didClose(uri);
    }

    // ---- rename: returns null for whitespace position ----

    @Test
    void renameReturnsNullForWhitespacePosition() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameProviderWhitespace.groovy";
        manager.didOpen(uri, "class Foo {\n\n}");

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 0));
        params.setNewName("x");

        assertNull(provider.rename(params));

        manager.didClose(uri);
    }

    // ---- offsetToPosition: edge cases ----

    @Test
    void offsetToPositionAtZero() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertEquals(new Position(0, 0), invokeOffsetToPosition(provider, "hello", 0));
    }

    @Test
    void offsetToPositionAtEndOfContent() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertEquals(new Position(0, 5), invokeOffsetToPosition(provider, "hello", 5));
    }

    @Test
    void offsetToPositionMultipleLines() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        String content = "a\nb\nc\nd";
        assertEquals(new Position(2, 0), invokeOffsetToPosition(provider, content, 4));
    }

    // ---- extractWordAt: edge cases ----

    @Test
    void extractWordAtReturnsNullForNonIdentifier() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertNull(invokeExtractWordAt(provider, "  +  ", 2));
    }

    @Test
    void extractWordAtHandlesUnderscores() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertEquals("my_var", invokeExtractWordAt(provider, "int my_var = 5", 6));
    }

    @Test
    void extractWordAtEndOfString() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertEquals("end", invokeExtractWordAt(provider, "the end", 5));
    }

    // ---- renameFromGroovyAST integration ----

    @Test
    void renameFromGroovyASTRenamesFieldUsageIncludingPropertyAccess() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameASTField.groovy";
        manager.didOpen(uri, """
                class Cfg {
                    String host
                    void show() { println host }
                }
                def c = new Cfg()
                c.host = 'localhost'
                println c.host
                """);

        RenameProvider provider = new RenameProvider(manager);
        WorkspaceEdit edit = invokeRenameFromGroovyAST(provider, uri, new Position(1, 11), "server");

        assertNotNull(edit);
        List<TextEdit> edits = edit.getChanges().get(uri);
        assertNotNull(edits);
        assertTrue(edits.size() >= 4);
        assertTrue(edits.stream().allMatch(e -> "server".equals(e.getNewText())));

        manager.didClose(uri);
    }

    @Test
    void renameFromGroovyASTReturnsNullForMissingContent() throws Exception {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        assertNull(invokeRenameFromGroovyAST(provider, "file:///NoContent.groovy", new Position(0, 0), "x"));
    }

    private void invokeAddRenameEdit(RenameProvider provider, SearchMatch match, String newName,
                                     Map<String, List<TextEdit>> editsByUri) throws Exception {
        Method method = RenameProvider.class.getDeclaredMethod("addRenameEdit",
                SearchMatch.class, String.class, Map.class);
        method.setAccessible(true);
        method.invoke(provider, match, newName, editsByUri);
    }

    private Position invokeOffsetToPosition(RenameProvider provider, String content, int offset) throws Exception {
        Method method = RenameProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        method.setAccessible(true);
        return (Position) method.invoke(provider, content, offset);
    }

    private String invokeExtractWordAt(RenameProvider provider, String content, int offset) throws Exception {
        Method method = RenameProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, content, offset);
    }

    private int invokePositionToOffset(RenameProvider provider, String content, Position position) throws Exception {
        Method method = RenameProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        method.setAccessible(true);
        return (int) method.invoke(provider, content, position);
    }

    private WorkspaceEdit invokeRenameFromGroovyAST(RenameProvider provider, String uri,
                                                     Position position, String newName) throws Exception {
        Method method = RenameProvider.class.getDeclaredMethod("renameFromGroovyAST",
                String.class, Position.class, String.class);
        method.setAccessible(true);
        return (WorkspaceEdit) method.invoke(provider, uri, position, newName);
    }
}

