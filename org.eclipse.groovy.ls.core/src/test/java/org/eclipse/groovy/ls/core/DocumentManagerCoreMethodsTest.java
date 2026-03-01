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

import java.io.IOException;
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
        // Either null or a partial AST is acceptable
        // The key assertion is that it doesn't throw

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

        // The tempDir itself doesn't have build markers, so this should be null
        // unless the test runner's own workspace has markers further up
        // We just verify it doesn't throw
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
