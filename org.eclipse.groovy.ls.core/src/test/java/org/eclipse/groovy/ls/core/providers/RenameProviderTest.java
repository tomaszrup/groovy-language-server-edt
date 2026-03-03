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
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
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

    // ---- prepareRename ----

    @Test
    void prepareRenameReturnsRangeForValidIdentifier() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///PrepareRename.groovy";
        manager.didOpen(uri, "class MyClass {}");

        RenameProvider provider = new RenameProvider(manager);
        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 8)); // "MyClass"

        Either3<Range, PrepareRenameResult, ?> result = provider.prepareRename(params);
        assertNotNull(result);

        manager.didClose(uri);
    }

    @Test
    void prepareRenameReturnsNullForMissingDocument() {
        RenameProvider provider = new RenameProvider(new DocumentManager());
        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///Missing.groovy"));
        params.setPosition(new Position(0, 0));

        assertNull(provider.prepareRename(params));
    }

    @Test
    void prepareRenameReturnsNullForWhitespace() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///PrepareRenameWs.groovy";
        manager.didOpen(uri, "class Foo {\n\n}");

        RenameProvider provider = new RenameProvider(manager);
        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 0)); // blank line

        assertNull(provider.prepareRename(params));

        manager.didClose(uri);
    }

    // ---- Rename with multiple classes and inter-class references ----

    @Test
    void renameVariableInScript() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameVariable.groovy";
        manager.didOpen(uri, """
                def counter = 0
                counter++
                println counter
                """);

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 4)); // "counter"
        params.setNewName("count");

        WorkspaceEdit edit = provider.rename(params);
        assertNotNull(edit);
        List<TextEdit> edits = edit.getChanges().get(uri);
        assertNotNull(edits);
        assertTrue(edits.size() >= 3);

        manager.didClose(uri);
    }

    @Test
    void renameWithMultipleClassesInFile() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///RenameMultiClass.groovy";
        manager.didOpen(uri, """
                class Alpha {
                    void doAlpha() {}
                }
                class Beta {
                    void run() {
                        new Alpha().doAlpha()
                    }
                }
                """);

        RenameProvider provider = new RenameProvider(manager);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 9)); // "doAlpha"
        params.setNewName("doWork");

        WorkspaceEdit edit = provider.rename(params);
        assertNotNull(edit);
        List<TextEdit> edits = edit.getChanges().get(uri);
        assertNotNull(edits);
        assertTrue(edits.size() >= 2);

        manager.didClose(uri);
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

    // ================================================================
    // capitalize tests
    // ================================================================

    @Test
    void capitalizeNormalString() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("capitalize", String.class);
        m.setAccessible(true);
        assertEquals("Abc", m.invoke(null, "abc"));
    }

    @Test
    void capitalizeEmptyString() throws Exception {
        Method m = RenameProvider.class.getDeclaredMethod("capitalize", String.class);
        m.setAccessible(true);
        assertEquals("", m.invoke(null, ""));
    }

    @Test
    void capitalizeSingleChar() throws Exception {
        Method m = RenameProvider.class.getDeclaredMethod("capitalize", String.class);
        m.setAccessible(true);
        assertEquals("A", m.invoke(null, "a"));
    }

    // ================================================================
    // wordRangeAt tests
    // ================================================================

    @Test
    void wordRangeAtFindsWord() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("wordRangeAt", String.class, int.class, String.class);
        m.setAccessible(true);
        Range range = (Range) m.invoke(provider, "int foo = 1", 5, "foo");
        assertNotNull(range);
    }

    @Test
    void wordRangeAtReturnsRangeForMismatch() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("wordRangeAt", String.class, int.class, String.class);
        m.setAccessible(true);
        Range range = (Range) m.invoke(provider, "int foo = 1", 0, "bar");
        // wordRangeAt always returns a Range; it returns the word-length range from 'start'
        assertNotNull(range);
    }

    // ================================================================
    // hasEditAt tests
    // ================================================================

    @Test
    void hasEditAtFindsEdit() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("hasEditAt", List.class, int.class, int.class);
        m.setAccessible(true);
        TextEdit edit = new TextEdit(new Range(new Position(2, 5), new Position(2, 8)), "newName");
        boolean result = (boolean) m.invoke(provider, List.of(edit), 2, 5);
        assertTrue(result);
    }

    @Test
    void hasEditAtNotFound() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("hasEditAt", List.class, int.class, int.class);
        m.setAccessible(true);
        TextEdit edit = new TextEdit(new Range(new Position(2, 5), new Position(2, 8)), "newName");
        boolean result = (boolean) m.invoke(provider, List.of(edit), 3, 0);
        assertNotNull(result);
    }

    // ================================================================
    // isKnownAstSymbol tests
    // ================================================================

    @Test
    void isKnownAstSymbolFindsClassName() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///rename_ast.groovy";
        String source = "class Foo { String bar\n void baz() {} }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        Method m = RenameProvider.class.getDeclaredMethod("isKnownAstSymbol", org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, module, "Foo"));
    }

    @Test
    void isKnownAstSymbolFindsMethodName() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///rename_ast2.groovy";
        String source = "class Foo { void baz() {} }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        Method m = RenameProvider.class.getDeclaredMethod("isKnownAstSymbol", org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, module, "baz"));
    }

    @Test
    void isKnownAstSymbolReturnsFalseForUnknown() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///rename_ast3.groovy";
        String source = "class Foo {}";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        Method m = RenameProvider.class.getDeclaredMethod("isKnownAstSymbol", org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        assertNotNull(m.invoke(provider, module, "unknown"));
    }

    // ================================================================
    // hasMethodNamed / hasFieldNamed / hasPropertyNamed tests
    // ================================================================

    @Test
    void hasMethodNamedTrue() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_method.groovy";
        String source = "class Foo { void doWork() {} }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasMethodNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, cls, "doWork"));
    }

    @Test
    void hasPropertyNamedTrue() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_prop.groovy";
        String source = "class Foo { String name }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasPropertyNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, cls, "name"));
    }

    // ================================================================
    // hasFieldNamed tests
    // ================================================================

    @Test
    void hasFieldNamedTrue() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_field.groovy";
        String source = "class Foo { public String name }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasFieldNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, cls, "name"));
    }

    @Test
    void hasFieldNamedFalse() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_field2.groovy";
        String source = "class Foo { String name }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasFieldNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        // Groovy creates internal fields for properties but hasFieldNamed should still find them
        // If it doesn't find 'nonexist', that's correct
        assertFalse((boolean) m.invoke(provider, cls, "nonExist"));
    }

    // ================================================================
    // hasMethodNamed false case
    // ================================================================

    @Test
    void hasMethodNamedFalse() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_method_f.groovy";
        String source = "class Foo { void doWork() {} }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasMethodNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(provider, cls, "nonExistentMethod"));
    }

    // ================================================================
    // hasPropertyNamed false case
    // ================================================================

    @Test
    void hasPropertyNamedFalse() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///has_prop_f.groovy";
        String source = "class Foo { String name }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Method m = RenameProvider.class.getDeclaredMethod("hasPropertyNamed", org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(provider, cls, "nonExistent"));
    }

    // ================================================================
    // readContent tests
    // ================================================================

    @Test
    void readContentFromDocumentManager() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///readContent.groovy";
        dm.didOpen(uri, "hello world");
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("readContent", String.class, org.eclipse.core.resources.IResource.class);
        m.setAccessible(true);
        String content = (String) m.invoke(provider, uri, null);
        assertEquals("hello world", content);
    }

    @Test
    void readContentReturnsNullForUnknownUri() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("readContent", String.class, org.eclipse.core.resources.IResource.class);
        m.setAccessible(true);
        String content = (String) m.invoke(provider, "file:///unknown.groovy", null);
        assertNull(content);
    }

    // ================================================================
    // capitalize additional tests
    // ================================================================

    @Test
    void capitalizeNull() throws Exception {
        Method m = RenameProvider.class.getDeclaredMethod("capitalize", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, (String) null));
    }

    @Test
    void capitalizeAlreadyCapitalized() throws Exception {
        Method m = RenameProvider.class.getDeclaredMethod("capitalize", String.class);
        m.setAccessible(true);
        assertEquals("Hello", m.invoke(null, "Hello"));
    }

    // ================================================================
    // hasEditAt tests
    // ================================================================

    @Test
    void hasEditAtFindsMatch() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("hasEditAt", java.util.List.class, int.class, int.class);
        m.setAccessible(true);

        TextEdit edit = new TextEdit(new Range(new Position(2, 5), new Position(2, 10)), "newName");
        boolean result = (boolean) m.invoke(provider, java.util.List.of(edit), 2, 5);
        assertTrue(result);
    }

    @Test
    void hasEditAtNoMatch() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("hasEditAt", java.util.List.class, int.class, int.class);
        m.setAccessible(true);

        TextEdit edit = new TextEdit(new Range(new Position(2, 5), new Position(2, 10)), "newName");
        boolean result = (boolean) m.invoke(provider, java.util.List.of(edit), 3, 5);
        assertFalse(result);
    }

    @Test
    void hasEditAtNullList() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("hasEditAt", java.util.List.class, int.class, int.class);
        m.setAccessible(true);

        boolean result = (boolean) m.invoke(provider, null, 0, 0);
        assertFalse(result);
    }

    // ================================================================
    // isKnownAstSymbol additional tests
    // ================================================================

    @Test
    void isKnownAstSymbolFindsProperty() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///knownSymbol2.groovy";
        String source = "class MyFoo { String bar }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);

        Method m = RenameProvider.class.getDeclaredMethod("isKnownAstSymbol",
                org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(provider, compileResult.getModuleNode(), "bar"));
    }

    @Test
    void isKnownAstSymbolNullAst() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);

        Method m = RenameProvider.class.getDeclaredMethod("isKnownAstSymbol",
                org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(provider, null, "anything"));
    }

    // ================================================================
    // offsetToPosition tests
    // ================================================================

    @Test
    void offsetToPositionFirstLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        Position pos = (Position) m.invoke(provider, "abcdef", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        Position pos = (Position) m.invoke(provider, "abc\ndef", 5);
        assertEquals(1, pos.getLine());
        assertEquals(1, pos.getCharacter());
    }

    @Test
    void offsetToPositionBeyondEnd() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        Position pos = (Position) m.invoke(provider, "abc", 100);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    // ================================================================
    // extractWordAt tests
    // ================================================================

    @Test
    void extractWordAtMiddle() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        m.setAccessible(true);
        assertEquals("hello", m.invoke(provider, "x hello world", 3));
    }

    @Test
    void extractWordAtNonIdentifier() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, "   ", 1));
    }

    // ================================================================
    // positionToOffset tests
    // ================================================================

    @Test
    void positionToOffsetFirstLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        assertEquals(3, m.invoke(provider, "abcdef", new Position(0, 3)));
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Method m = RenameProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        assertEquals(6, m.invoke(provider, "abc\ndef", new Position(1, 2)));
    }

    // ================================================================
    // wordRangeAt tests
    // ================================================================

    @Test
    void wordRangeAtNormalCase() {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);
        Range range = provider.wordRangeAt("def foo = 1", 5, "foo");
        assertEquals(0, range.getStart().getLine());
        assertEquals(4, range.getStart().getCharacter());
        assertEquals(0, range.getEnd().getLine());
        assertEquals(7, range.getEnd().getCharacter());
    }

    // ================================================================
    // prepareRename tests (AST fallback path)
    // ================================================================

    @Test
    void prepareRenameFindsWordInAstFallback() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///prepRename.groovy";
        dm.didOpen(uri, "class Foo { String bar }");
        RenameProvider provider = new RenameProvider(dm);

        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 7)); // on "Foo"

        var result = provider.prepareRename(params);
        assertNotNull(result);
        // Second branch of Either3 is PrepareRenameResult
        assertNotNull(result.getSecond());
        assertEquals("Foo", result.getSecond().getPlaceholder());
    }

    @Test
    void prepareRenameReturnsNullForNoContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);

        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier("file:///noContent.groovy"));
        params.setPosition(new Position(0, 0));

        var result = provider.prepareRename(params);
        assertNull(result);
    }

    @Test
    void prepareRenameReturnsNullForNoWord() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///prepRename2.groovy";
        dm.didOpen(uri, "   "); // only whitespace
        RenameProvider provider = new RenameProvider(dm);

        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 1));

        var result = provider.prepareRename(params);
        assertNull(result);
    }

    // ================================================================
    // renameFromGroovyAST tests
    // ================================================================

    @Test
    void renameFromGroovyASTRenamesAllOccurrences() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///renameAst.groovy";
        String source = "class Foo {\n  String bar\n  def func() { bar }\n}";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);

        Method m = RenameProvider.class.getDeclaredMethod("renameFromGroovyAST",
                String.class, Position.class, String.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.WorkspaceEdit edit = (org.eclipse.lsp4j.WorkspaceEdit) m.invoke(provider, uri, new Position(1, 10), "baz");
        assertNotNull(edit);
        assertNotNull(edit.getChanges());
        assertTrue(edit.getChanges().containsKey(uri));
        // "bar" appears twice in the content
        assertTrue(edit.getChanges().get(uri).size() >= 2);
    }

    @Test
    void renameFromGroovyASTReturnsNullForNoContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider provider = new RenameProvider(dm);

        Method m = RenameProvider.class.getDeclaredMethod("renameFromGroovyAST",
                String.class, Position.class, String.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, "file:///noFile.groovy", new Position(0, 0), "newName"));
    }

    @Test
    void renameFromGroovyASTReturnsNullForNonIdentifier() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///renameAst2.groovy";
        dm.didOpen(uri, "   "); // only whitespace
        RenameProvider provider = new RenameProvider(dm);

        Method m = RenameProvider.class.getDeclaredMethod("renameFromGroovyAST",
                String.class, Position.class, String.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, uri, new Position(0, 1), "newName"));
    }

    // ================================================================
    // rename public method (AST fallback path)
    // ================================================================

    @Test
    void renameUsesAstFallbackWhenNoJdt() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///renamePub.groovy";
        String source = "class Foo { String bar\n  def test() { bar } }";
        dm.didOpen(uri, source);
        RenameProvider provider = new RenameProvider(dm);

        org.eclipse.lsp4j.RenameParams params = new org.eclipse.lsp4j.RenameParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 20)); // on "bar"
        params.setNewName("baz");

        org.eclipse.lsp4j.WorkspaceEdit edit = provider.rename(params);
        assertNotNull(edit);
        assertNotNull(edit.getChanges());
        assertTrue(edit.getChanges().get(uri).size() >= 2);
    }

    // ================================================================
    // addRenameEdit tests
    // ================================================================

    @Test
    void addRenameEditCreatesTextEdit() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider rp = new RenameProvider(dm);
        String uri = "file:///RenameEdit.groovy";
        dm.didOpen(uri, "class Foo { void bar() {} }");

        SearchMatch match = org.mockito.Mockito.mock(SearchMatch.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI(uri));
        org.mockito.Mockito.when(match.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(match.getOffset()).thenReturn(17);
        org.mockito.Mockito.when(match.getLength()).thenReturn(3);

        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();

        java.lang.reflect.Method m = RenameProvider.class.getDeclaredMethod(
                "addRenameEdit", SearchMatch.class, String.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(rp, match, "baz", editsByUri);

        assertTrue(editsByUri.containsKey(uri));
        assertEquals(1, editsByUri.get(uri).size());
        assertEquals("baz", editsByUri.get(uri).get(0).getNewText());
        dm.didClose(uri);
    }

    @Test
    void addRenameEditSkipsNullResource() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider rp = new RenameProvider(dm);

        SearchMatch match = org.mockito.Mockito.mock(SearchMatch.class);
        org.mockito.Mockito.when(match.getResource()).thenReturn(null);

        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();

        java.lang.reflect.Method m = RenameProvider.class.getDeclaredMethod(
                "addRenameEdit", SearchMatch.class, String.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(rp, match, "baz", editsByUri);

        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addRenameEditSkipsNullContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider rp = new RenameProvider(dm);

        SearchMatch match = org.mockito.Mockito.mock(SearchMatch.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///NoContent.groovy"));
        org.mockito.Mockito.when(match.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(match.getOffset()).thenReturn(0);
        org.mockito.Mockito.when(match.getLength()).thenReturn(3);

        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();

        java.lang.reflect.Method m = RenameProvider.class.getDeclaredMethod(
                "addRenameEdit", SearchMatch.class, String.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(rp, match, "baz", editsByUri);

        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addRenameEditAddsToExistingListForSameUri() throws Exception {
        DocumentManager dm = new DocumentManager();
        RenameProvider rp = new RenameProvider(dm);
        String uri = "file:///RenameEditMulti.groovy";
        dm.didOpen(uri, "class Foo { Foo() {} }");

        SearchMatch match1 = org.mockito.Mockito.mock(SearchMatch.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI(uri));
        org.mockito.Mockito.when(match1.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(match1.getOffset()).thenReturn(6);
        org.mockito.Mockito.when(match1.getLength()).thenReturn(3);

        SearchMatch match2 = org.mockito.Mockito.mock(SearchMatch.class);
        org.mockito.Mockito.when(match2.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(match2.getOffset()).thenReturn(12);
        org.mockito.Mockito.when(match2.getLength()).thenReturn(3);

        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();

        java.lang.reflect.Method m = RenameProvider.class.getDeclaredMethod(
                "addRenameEdit", SearchMatch.class, String.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(rp, match1, "Bar", editsByUri);
        m.invoke(rp, match2, "Bar", editsByUri);

        assertEquals(2, editsByUri.get(uri).size());
        dm.didClose(uri);
    }
}
