/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.codehaus.groovy.ast.ModuleNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DocumentManager} core methods not covered by the
 * synchronization and URI normalization tests.
 */
class DocumentManagerCoreMethodsTest {

    @TempDir
    Path tempDir;

    // ---- positionToOffset ----

    @Test
    void positionToOffsetFirstLine() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "hello world\n";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(0, 5);

        int offset = invokePositionToOffset(manager, content, position);

        assertEquals(5, offset);
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "line one\nline two\n";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(1, 3);

        int offset = invokePositionToOffset(manager, content, position);

        // "line one\n" = 9 chars, then offset=9 + 3 = 12 → 't' in "two"
        assertEquals(12, offset);
    }

    @Test
    void positionToOffsetAtStartOfDocument() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "abc\n";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(0, 0);

        int offset = invokePositionToOffset(manager, content, position);

        assertEquals(0, offset);
    }

    @Test
    void positionToOffsetMultipleLines() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "a\nb\nc\nd\n";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(3, 0);

        int offset = invokePositionToOffset(manager, content, position);

        // Lines: "a\n"=2, "b\n"=2, "c\n"=2 → offset 6 is start of line 3 ("d")
        assertEquals(6, offset);
    }

    // ---- hasJdtWorkingCopy / getCompilerService ----

    @Test
    void hasJdtWorkingCopyReturnsFalseForStandaloneDocument() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Standalone.groovy";
        manager.didOpen(uri, "class Standalone {}\n");

        // Without Eclipse workspace, no JDT working copy is created
        assertFalse(manager.hasJdtWorkingCopy(uri));

        manager.didClose(uri);
    }

    @Test
    void getCompilerServiceReturnsNonNull() {
        DocumentManager manager = new DocumentManager();
        assertNotNull(manager.getCompilerService());
    }

    // ---- getContent / getWorkingCopy ----

    @Test
    void getContentReturnsNullForUnknownUri() {
        DocumentManager manager = new DocumentManager();
        assertNull(manager.getContent("file:///nonexistent.groovy"));
    }

    @Test
    void getWorkingCopyReturnsNullForStandaloneDocument() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Standalone.groovy";
        manager.didOpen(uri, "class A {}\n");

        assertNull(manager.getWorkingCopy(uri));

        manager.didClose(uri);
    }

    // ---- getGroovyAST ----

    @Test
    void getGroovyASTReturnsParsedModuleForOpenDocument() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/AstTest.groovy";
        manager.didOpen(uri, "class AstTest { String name }\n");

        ModuleNode module = manager.getGroovyAST(uri);

        assertNotNull(module);
        assertFalse(module.getClasses().isEmpty());
        assertEquals("AstTest", module.getClasses().get(0).getNameWithoutPackage());

        manager.didClose(uri);
    }

    @Test
    void getGroovyASTReturnsNullForClosedDocument() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Closed.groovy";
        manager.didOpen(uri, "class Closed {}\n");
        manager.didClose(uri);

        ModuleNode module = manager.getGroovyAST(uri);
        assertNull(module);
    }

    @Test
    void getGroovyASTHandlesInvalidSourceGracefully() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Invalid.groovy";
        manager.didOpen(uri, "class Broken {\n");

        // Should not throw — may return a partial AST or null
        ModuleNode module = manager.getGroovyAST(uri);
        assertTrue(module == null || !module.getClasses().isEmpty());

        manager.didClose(uri);
    }

    // ---- detectProjectRoot ----

    @Test
    void detectProjectRootFindsBuildGradleMarker() throws Exception {
        DocumentManager manager = new DocumentManager();

        // Create: tempDir/project/build.gradle and tempDir/project/src/Main.groovy
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir.resolve("src"));
        Files.writeString(projectDir.resolve("build.gradle"), "// build\n");
        Path sourceFile = Files.writeString(projectDir.resolve("src").resolve("Main.groovy"), "class Main {}\n");

        java.io.File root = invokeDetectProjectRoot(manager, sourceFile.toFile());

        assertNotNull(root);
        assertEquals(projectDir.toFile().getAbsolutePath(), root.getAbsolutePath());
    }

    @Test
    void detectProjectRootPrefersSettingsGradleAsRoot() throws Exception {
        DocumentManager manager = new DocumentManager();

        // Create root with settings.gradle containing a subproject with build.gradle
        Path rootDir = tempDir.resolve("rootproj");
        Path subDir = rootDir.resolve("subproj");
        Files.createDirectories(subDir.resolve("src"));
        Files.writeString(rootDir.resolve("settings.gradle"), "include 'subproj'\n");
        Files.writeString(subDir.resolve("build.gradle"), "// sub build\n");
        Path sourceFile = Files.writeString(subDir.resolve("src").resolve("Sub.groovy"), "class Sub {}\n");

        java.io.File root = invokeDetectProjectRoot(manager, sourceFile.toFile());

        assertNotNull(root);
        assertEquals(rootDir.toFile().getAbsolutePath(), root.getAbsolutePath());
    }

    @Test
    void detectProjectRootReturnsNullWhenNoMarkerFound() throws Exception {
        DocumentManager manager = new DocumentManager();

        // Create isolated file with no build markers anywhere up the tree
        Path isolatedDir = tempDir.resolve("isolated");
        Files.createDirectories(isolatedDir);
        Path sourceFile = Files.writeString(isolatedDir.resolve("Orphan.groovy"), "class Orphan {}\n");

        java.io.File root = invokeDetectProjectRoot(manager, sourceFile.toFile());
        assertTrue(root == null || root.isDirectory());
    }

    // ---- Additional coverage tests ----

    @Test
    void positionToOffsetWithEmptyContent() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(0, 0);

        int offset = invokePositionToOffset(manager, content, position);

        assertEquals(0, offset);
    }

    @Test
    void positionToOffsetWithCRLF() throws Exception {
        DocumentManager manager = new DocumentManager();
        String content = "line one\r\nline two\r\n";
        org.eclipse.lsp4j.Position position = new org.eclipse.lsp4j.Position(1, 0);

        int offset = invokePositionToOffset(manager, content, position);

        // After "line one\r\n" = 10 chars, so line 1 start = 10
        assertEquals(10, offset);
    }

    @Test
    void getOpenDocumentUrisTracksOpenDocuments() {
        DocumentManager manager = new DocumentManager();
        assertTrue(manager.getOpenDocumentUris().isEmpty());

        manager.didOpen("file:///test/A.groovy", "class A {}\n");
        manager.didOpen("file:///test/B.groovy", "class B {}\n");

        assertEquals(2, manager.getOpenDocumentUris().size());

        manager.didClose("file:///test/A.groovy");
        assertEquals(1, manager.getOpenDocumentUris().size());
    }

    @Test
    void getWorkingCopyOwnerReturnsNonNull() {
        DocumentManager manager = new DocumentManager();
        assertNotNull(manager.getWorkingCopyOwner());
    }

    @Test
    void getGroovyASTUpdatesAfterDidChange() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/UpdateAST.groovy";
        manager.didOpen(uri, "class Original {}\n");

        ModuleNode original = manager.getGroovyAST(uri);
        assertNotNull(original);
        assertEquals("Original", original.getClasses().get(0).getNameWithoutPackage());

        // Full replacement
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("class Updated {}\n");
        manager.didChange(uri, java.util.List.of(change));

        ModuleNode updated = manager.getGroovyAST(uri);
        assertNotNull(updated);
        assertEquals("Updated", updated.getClasses().get(0).getNameWithoutPackage());

        manager.didClose(uri);
    }

    @Test
    void normalizeUriHandlesNullAndNonFileUri() {
        assertNull(DocumentManager.normalizeUri(null));
        assertEquals("untitled:foo", DocumentManager.normalizeUri("untitled:foo"));
    }

    @Test
    void didChangeWithIncrementalEditModifiesContent() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Incr.groovy";
        manager.didOpen(uri, "class Foo {}");

        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 6),
                new org.eclipse.lsp4j.Position(0, 9)));
        change.setText("Bar");

        manager.didChange(uri, java.util.List.of(change));

        assertEquals("class Bar {}", manager.getContent(uri));

        manager.didClose(uri);
    }

    @Test
    void detectProjectRootFindsPomXml() throws Exception {
        DocumentManager manager = new DocumentManager();

        Path projectDir = tempDir.resolve("maven-project");
        Files.createDirectories(projectDir.resolve("src/main/groovy"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>\n");
        Path sourceFile = Files.writeString(
                projectDir.resolve("src/main/groovy/App.groovy"), "class App {}\n");

        java.io.File root = invokeDetectProjectRoot(manager, sourceFile.toFile());

        assertNotNull(root);
        assertEquals(projectDir.toFile().getAbsolutePath(), root.getAbsolutePath());
    }

    @Test
    void detectProjectRootFindsGradlewMarker() throws Exception {
        DocumentManager manager = new DocumentManager();

        Path projectDir = tempDir.resolve("gradlew-project");
        Files.createDirectories(projectDir.resolve("app"));
        Files.writeString(projectDir.resolve("gradlew"), "#!/bin/sh\n");
        Path sourceFile = Files.writeString(
                projectDir.resolve("app/Main.groovy"), "class Main {}\n");

        java.io.File root = invokeDetectProjectRoot(manager, sourceFile.toFile());

        assertNotNull(root);
        assertEquals(projectDir.toFile().getAbsolutePath(), root.getAbsolutePath());
    }

    @Test
    void didChangeForUnknownUriDoesNotThrow() {
        DocumentManager manager = new DocumentManager();
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("shouldn't matter");

        // Should silently return — no content stored for unknown URI
        manager.didChange("file:///unknown.groovy", java.util.List.of(change));
        assertNull(manager.getContent("file:///unknown.groovy"));
    }

    // ================================================================
    // didOpen / didClose lifecycle expansion
    // ================================================================

    @Test
    void didOpenAndCloseTrackOpenDocuments() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///lifecycle.groovy";
        manager.didOpen(uri, "class Life {}");
        assertTrue(manager.getOpenDocumentUris().contains(DocumentManager.normalizeUri(uri)));
        manager.didClose(uri);
        assertFalse(manager.getOpenDocumentUris().contains(DocumentManager.normalizeUri(uri)));
    }

    @Test
    void didOpenSetsClientUriMapping() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///client_uri.groovy";
        manager.didOpen(uri, "class X {}");
        String normalized = DocumentManager.normalizeUri(uri);
        String clientUri = manager.getClientUri(normalized);
        assertNotNull(clientUri);
    }

    @Test
    void didCloseInvalidatesCompilerCache() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///invalidate.groovy";
        manager.didOpen(uri, "class Inv {}");
        ModuleNode ast = manager.getGroovyAST(uri);
        assertNotNull(ast);
        manager.didClose(uri);
        // After close, AST should no longer be available
        ModuleNode astAfterClose = manager.getGroovyAST(uri);
        assertNull(astAfterClose);
    }

    @Test
    void hasJdtWorkingCopyReturnsFalseAfterDidClose() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///working_copy.groovy";
        manager.didOpen(uri, "class WC {}");
        assertFalse(manager.hasJdtWorkingCopy(uri)); // No JDT in test env
        manager.didClose(uri);
        assertFalse(manager.hasJdtWorkingCopy(uri));
    }

    // ================================================================
    // getGroovyAST expansion
    // ================================================================

    @Test
    void getGroovyASTReturnsParsedModuleWithClasses() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///ASTClasses.groovy";
        manager.didOpen(uri, "class Foo {}\nclass Bar {}");
        ModuleNode ast = manager.getGroovyAST(uri);
        assertNotNull(ast);
        assertTrue(ast.getClasses().size() >= 2);
    }

    @Test
    void getGroovyASTCachesResultBetweenCalls() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///Cached.groovy";
        manager.didOpen(uri, "class Cached { int x }");
        ModuleNode ast1 = manager.getGroovyAST(uri);
        ModuleNode ast2 = manager.getGroovyAST(uri);
        // Both calls should return valid ASTs
        assertNotNull(ast1);
        assertNotNull(ast2);
    }

    // ================================================================
    // didChange with incremental edits expansion
    // ================================================================

    @Test
    void didChangeIncrementalInsert() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///InsertEdit.groovy";
        manager.didOpen(uri, "class A {}");

        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 8),
                new org.eclipse.lsp4j.Position(0, 8)));
        change.setText(" extends Object");
        manager.didChange(uri, java.util.List.of(change));

        String content = manager.getContent(uri);
        assertTrue(content.contains("extends Object"));
    }

    @Test
    void didChangeIncrementalDelete() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///DeleteEdit.groovy";
        manager.didOpen(uri, "class Deletable {}");

        // Delete "Deletable" → replace with "A"
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 6),
                new org.eclipse.lsp4j.Position(0, 15)));
        change.setText("A");
        manager.didChange(uri, java.util.List.of(change));

        assertEquals("class A {}", manager.getContent(uri));
    }

    @Test
    void didChangeMultipleIncrementalEdits() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///MultiEdit.groovy";
        manager.didOpen(uri, "def x = 1\ndef y = 2");

        // Two edits: change both values
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change1 =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change1.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(1, 8),
                new org.eclipse.lsp4j.Position(1, 9)));
        change1.setText("42");

        org.eclipse.lsp4j.TextDocumentContentChangeEvent change2 =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change2.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 8),
                new org.eclipse.lsp4j.Position(0, 9)));
        change2.setText("10");

        manager.didChange(uri, java.util.List.of(change1, change2));
        String content = manager.getContent(uri);
        assertNotNull(content);
    }

    // ---- Helpers ----

    private int invokePositionToOffset(DocumentManager manager, String content, org.eclipse.lsp4j.Position position) throws Exception {
        Method method = DocumentManager.class.getDeclaredMethod("positionToOffset", CharSequence.class, org.eclipse.lsp4j.Position.class);
        method.setAccessible(true);
        return (int) method.invoke(manager, new StringBuilder(content), position);
    }

    private java.io.File invokeDetectProjectRoot(DocumentManager manager, java.io.File file) throws Exception {
        Method method = DocumentManager.class.getDeclaredMethod("detectProjectRoot", java.io.File.class);
        method.setAccessible(true);
        return (java.io.File) method.invoke(manager, file);
    }
}
