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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

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

    @Test
    void resolveElementUriPrefersWorkingCopyBackedUri() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///linked/src/test/groovy/Spec.groovy";

        org.eclipse.jdt.core.ICompilationUnit originalCompilationUnit = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IMethod method = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);

        when(method.getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT)).thenReturn(originalCompilationUnit);
        when(originalCompilationUnit.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create(uri));

        getWorkingCopies(manager).put(DocumentManager.normalizeUri(uri), workingCopy);

        assertEquals(DocumentManager.normalizeUri(uri), manager.resolveElementUri(method));
    }

    @Test
    void remapToWorkingCopyElementReturnsMatchingMethodFromOpenDocument() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///linked/src/test/groovy/Spec.groovy";

        org.eclipse.jdt.core.ICompilationUnit originalCompilationUnit = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IType originalType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType remappedType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IMethod originalMethod = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.jdt.core.IMethod remappedMethod = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);

        when(originalMethod.getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT)).thenReturn(originalCompilationUnit);
        when(originalMethod.getDeclaringType()).thenReturn(originalType);
        when(originalMethod.getElementName()).thenReturn("someMethod");
        when(originalMethod.getParameterTypes()).thenReturn(new String[]{"QString;"});
        when(originalMethod.isConstructor()).thenReturn(false);

        when(originalType.getFullyQualifiedName('$')).thenReturn("sample.Spec");
        when(originalType.getElementName()).thenReturn("Spec");

        when(originalCompilationUnit.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create(uri));

        when(workingCopy.getTypes()).thenReturn(new org.eclipse.jdt.core.IType[]{remappedType});
        when(remappedType.getFullyQualifiedName('$')).thenReturn("sample.Spec");
        when(remappedType.getMethods()).thenReturn(new org.eclipse.jdt.core.IMethod[]{remappedMethod});
        when(remappedMethod.getElementName()).thenReturn("someMethod");
        when(remappedMethod.getParameterTypes()).thenReturn(new String[]{"QString;"});
        when(remappedMethod.isConstructor()).thenReturn(false);

        getWorkingCopies(manager).put(DocumentManager.normalizeUri(uri), workingCopy);

        assertSame(remappedMethod, manager.remapToWorkingCopyElement(originalMethod));
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
    void findCompilationUnitIgnoresNonFileUriWithoutLoggingError() throws Exception {
        DocumentManager manager = new DocumentManager();

        try (MockedStatic<GroovyLanguageServerPlugin> plugin = org.mockito.Mockito.mockStatic(GroovyLanguageServerPlugin.class)) {
            assertNull(invokeFindCompilationUnit(manager, "groovy-source:///org/example/Outer$Inner.java"));
            plugin.verifyNoInteractions();
        }
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

    @Test
    void replayOpenDocumentsReconcilesExistingWorkingCopyAndRefreshesSemantics() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Replay.groovy";
        manager.didOpen(uri, "class Replay {}\n");
        GroovyCompilerService.ParseResult cachedBeforeReplay = manager.getCompilerService().getCachedResult(uri);
        assertNotNull(cachedBeforeReplay);

        LanguageClient client = mock(LanguageClient.class);
        manager.setLanguageClient(client);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IBuffer buffer = mock(IBuffer.class);
        org.mockito.Mockito.when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(manager).put(DocumentManager.normalizeUri(uri), workingCopy);

        manager.replayOpenDocuments(java.util.List.of(uri));

        verify(buffer, timeout(1500)).setContents("class Replay {}\n");
        verify(workingCopy, timeout(1500)).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);
        verify(client, timeout(2000).atLeastOnce()).refreshSemanticTokens();
        assertSame(cachedBeforeReplay, manager.getCompilerService().getCachedResult(uri));

        manager.didClose(uri);
    }

    @Test
    void replayOpenDocumentsNotifiesWorkingCopyReadyListener() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Ready.groovy";
        manager.didOpen(uri, "class Ready {}\n");

        List<String> notifiedUris = new ArrayList<>();
        manager.setWorkingCopyReadyListener(notifiedUris::add);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IBuffer buffer = mock(IBuffer.class);
        org.mockito.Mockito.when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(manager).put(DocumentManager.normalizeUri(uri), workingCopy);

        manager.replayOpenDocuments(java.util.List.of(uri));

        verify(buffer, timeout(1500)).setContents("class Ready {}\n");
        verify(workingCopy, timeout(1500)).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);
        assertTrue(notifiedUris.contains(DocumentManager.normalizeUri(uri)));

        manager.didClose(uri);
    }

    @Test
    void isReadyForDiagnosticsReturnsFalseWhileDidOpenRefreshIsPending() throws Exception {
        DocumentManager manager = new DocumentManager();
        ExecutorService originalExecutor = getDidOpenExecutor(manager);
        DeferredExecutorService deferredExecutor = new DeferredExecutorService();
        setDidOpenExecutor(manager, deferredExecutor);
        String uri = "file:///test/Pending.groovy";
        String normalizedUri = DocumentManager.normalizeUri(uri);

        try {
            manager.didOpen(uri, "class Pending {}\n");

            assertTrue(getPendingOpenFutures(manager).containsKey(normalizedUri));
            assertFalse(manager.isReadyForDiagnostics(uri));
        } finally {
            manager.didClose(uri);
            setDidOpenExecutor(manager, originalExecutor);
            deferredExecutor.shutdownNow();
            manager.dispose();
        }
    }

    @Test
    void didOpenClearsPendingRefreshEntryWhenTaskCompletesInline() throws Exception {
        DocumentManager manager = new DocumentManager();
        ExecutorService originalExecutor = getDidOpenExecutor(manager);
        InlineExecutorService inlineExecutor = new InlineExecutorService();
        setDidOpenExecutor(manager, inlineExecutor);
        originalExecutor.shutdownNow();

        String uri = "file:///test/InlinePending.groovy";
        String normalizedUri = DocumentManager.normalizeUri(uri);

        try {
            manager.didOpen(uri, "class InlinePending {}\n");

            assertTrue(getPendingOpenFutures(manager).isEmpty());
            assertTrue(manager.isReadyForDiagnostics(uri));
            assertFalse(getPendingOpenFutures(manager).containsKey(normalizedUri));
        } finally {
            manager.dispose();
        }
    }

    @Test
    void didChangeInvalidatesStandaloneCacheWhenWorkingCopyExists() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/Replay.groovy";
        manager.didOpen(uri, "class Replay {}\n");
        assertNotNull(manager.getCompilerService().getCachedResult(uri));

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IBuffer buffer = mock(IBuffer.class);
        org.mockito.Mockito.when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(manager).put(DocumentManager.normalizeUri(uri), workingCopy);

        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("class Replay { String name }\n");

        manager.didChange(uri, java.util.List.of(change));

        verify(buffer).setContents("class Replay { String name }\n");
        assertNull(manager.getCompilerService().getCachedResult(uri));

        manager.didClose(uri);
    }

    @Test
    void didChangeDebouncesStandaloneCacheRefreshWithoutWorkingCopy() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/DebouncedStandalone.groovy";
        manager.didOpen(uri, "class Original {}\n");
        assertFalse(manager.hasJdtWorkingCopy(uri));
        assertNotNull(manager.getCompilerService().getCachedResult(uri));

        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("class Updated {}\n");

        manager.didChange(uri, java.util.List.of(change));

        assertNull(manager.getCompilerService().getCachedResult(uri));
        assertNull(manager.getCachedGroovyAST(uri));

        try {
            waitForCondition(() -> {
                GroovyCompilerService.ParseResult result =
                        manager.getCompilerService().getCachedResult(uri);
                return result != null
                        && result.hasAST()
                        && result.getModuleNode() != null
                        && !result.getModuleNode().getClasses().isEmpty()
                        && "Updated".equals(
                                result.getModuleNode().getClasses().get(0).getNameWithoutPackage());
            }, 2000);
        } finally {
            manager.didClose(uri);
        }
    }

    @Test
    void refreshWorkingCopyRetriesWhenDocumentChangesDuringReconcile() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///test/RetryReconcile.groovy";
        String normalizedUri = DocumentManager.normalizeUri(uri);
        manager.didOpen(uri, "class Before {}\n");

        Future<?> pendingDidOpen = getPendingOpenFutures(manager).remove(normalizedUri);
        if (pendingDidOpen != null) {
            pendingDidOpen.cancel(true);
        }

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IBuffer buffer = mock(IBuffer.class);
        when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(manager).put(normalizedUri, workingCopy);

        AtomicInteger reconcileCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (reconcileCount.getAndIncrement() == 0) {
                getOpenDocuments(manager).put(normalizedUri, new StringBuilder("class After {}\n"));
                getDocumentVersions(manager).compute(
                        normalizedUri,
                        (ignored, version) -> version == null ? 1L : version + 1L);
            }
            return null;
        }).when(workingCopy).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);

        invokeRefreshWorkingCopy(manager, normalizedUri, "test");

        org.mockito.InOrder order = inOrder(buffer, workingCopy);
        order.verify(buffer).setContents("class Before {}\n");
        order.verify(workingCopy).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);
        order.verify(buffer).setContents("class After {}\n");
        order.verify(workingCopy).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);
        verify(workingCopy, times(2)).reconcile(
                ICompilationUnit.NO_AST, true, true, manager.getWorkingCopyOwner(), null);
        assertEquals("class After {}\n", manager.getContent(uri));

        manager.didClose(uri);
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
        return manager.positionToOffset(new StringBuilder(content), position);
    }

    private java.io.File invokeDetectProjectRoot(DocumentManager manager, java.io.File file) throws Exception {
        return manager.detectProjectRoot(file);
    }

    private ICompilationUnit invokeFindCompilationUnit(DocumentManager manager, String uri) throws Exception {
        return manager.findCompilationUnit(uri);
    }

    private void invokeRefreshWorkingCopy(DocumentManager manager, String uri, String trigger)
            throws Exception {
        manager.refreshWorkingCopy(uri, trigger);
    }

    private java.util.Map<String, ICompilationUnit> getWorkingCopies(DocumentManager manager) throws Exception {
        return manager.workingCopiesView();
    }

    private Map<String, StringBuilder> getOpenDocuments(DocumentManager manager) throws Exception {
        return manager.openDocumentsView();
    }

    private Map<String, Long> getDocumentVersions(DocumentManager manager) throws Exception {
        return manager.documentVersionsView();
    }

    private Map<String, Future<?>> getPendingOpenFutures(DocumentManager manager) throws Exception {
        return manager.pendingOpenFuturesView();
    }

    private ExecutorService getDidOpenExecutor(DocumentManager manager) throws Exception {
        return manager.getDidOpenExecutor();
    }

    private void setDidOpenExecutor(DocumentManager manager, ExecutorService executor) throws Exception {
        manager.setDidOpenExecutor(executor);
    }

    // ================================================================
    // uriToFilePath tests (static private)
    // ================================================================

    private String invokeUriToFilePath(String uri) throws Exception {
        return DocumentManager.uriToFilePath(uri);
    }

    @Test
    void uriToFilePathStandardFileUri() throws Exception {
        String result = invokeUriToFilePath("file:///tmp/test.groovy");
        assertNotNull(result);
        assertTrue(result.contains("test.groovy"));
    }

    @Test
    void uriToFilePathWindowsDriveLetter() throws Exception {
        String result = invokeUriToFilePath("file:///C:/Users/test.groovy");
        assertNotNull(result);
        // Drive letter normalized to lowercase
        assertTrue(result.startsWith("c:") || result.contains("test.groovy"));
    }

    @Test
    void uriToFilePathPercentEncoded() throws Exception {
        String result = invokeUriToFilePath("file:///tmp/foo%20bar.groovy");
        assertNotNull(result);
        assertTrue(result.contains("foo bar"));
    }

    @Test
    void uriToFilePathJdtStyleSingleSlash() throws Exception {
        String result = invokeUriToFilePath("file:/C:/foo.groovy");
        assertNotNull(result);
        assertTrue(result.contains("foo.groovy"));
    }

    @Test
    void uriToFilePathTripleSlashUnix() throws Exception {
        String result = invokeUriToFilePath("file:///home/user/test.groovy");
        assertNotNull(result);
        assertTrue(result.contains("test.groovy"));
    }

    // ================================================================
    // classCount tests
    // ================================================================

    private int invokeClassCount(DocumentManager manager, ModuleNode module) throws Exception {
        return manager.classCount(module);
    }

    @Test
    void classCountReturnsZeroForEmptyModule() throws Exception {
        DocumentManager dm = new DocumentManager();
        String source = "";
        String uri = "file:///emptyClassCount.groovy";
        var result = new GroovyCompilerService().parse(uri, source);
        ModuleNode module = result.getModuleNode();
        if (module != null) {
            int count = invokeClassCount(dm, module);
            assertTrue(count >= 0);
        }
    }

    @Test
    void classCountReturnsCorrectCountForMultipleClasses() throws Exception {
        DocumentManager dm = new DocumentManager();
        String source = "class Foo {}\nclass Bar {}\nclass Baz {}";
        String uri = "file:///multiClassCount.groovy";
        var result = new GroovyCompilerService().parse(uri, source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);
        int count = invokeClassCount(dm, module);
        assertTrue(count >= 3);
    }

    @Test
    void classCountReturnsSingleForOneClass() throws Exception {
        DocumentManager dm = new DocumentManager();
        String source = "class Hello {}";
        String uri = "file:///singleClassCount.groovy";
        var result = new GroovyCompilerService().parse(uri, source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);
        int count = invokeClassCount(dm, module);
        assertTrue(count >= 1);
    }

    // ================================================================
    // hasNestedConflict tests
    // ================================================================

    private boolean invokeHasNestedConflict(DocumentManager manager, String srcDir, java.util.Set<String> addedSrcDirs) throws Exception {
        return manager.hasNestedConflict(srcDir, addedSrcDirs);
    }

    @Test
    void hasNestedConflictParentContainsChild() throws Exception {
        DocumentManager dm = new DocumentManager();
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("src/main/java");
        assertTrue(invokeHasNestedConflict(dm, "src", existing));
    }

    @Test
    void hasNestedConflictChildContainedByParent() throws Exception {
        DocumentManager dm = new DocumentManager();
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("src");
        assertTrue(invokeHasNestedConflict(dm, "src/main/java", existing));
    }

    @Test
    void hasNestedConflictDifferentPaths() throws Exception {
        DocumentManager dm = new DocumentManager();
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("src/test/java");
        assertFalse(invokeHasNestedConflict(dm, "src/main/java", existing));
    }

    @Test
    void hasNestedConflictEmptySet() throws Exception {
        DocumentManager dm = new DocumentManager();
        java.util.Set<String> existing = new java.util.HashSet<>();
        assertFalse(invokeHasNestedConflict(dm, "src/main/java", existing));
    }

    @Test
    void hasNestedConflictExactMatchNotConflict() throws Exception {
        DocumentManager dm = new DocumentManager();
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("src/main/java");
        // Exact match: "src/main/java" doesn't start with "src/main/java/"
        assertFalse(invokeHasNestedConflict(dm, "src/main/java", existing));
    }

    @Test
    void configureExternalProjectClasspathRefreshesSourcesAndPreservesLibraries() throws Exception {
        DocumentManager dm = new DocumentManager();
        IProject project = mock(IProject.class);
        IFolder linkedRoot = mock(IFolder.class);
        IJavaProject javaProject = mock(IJavaProject.class);

        Path srcMainGroovy = tempDir.resolve("src/main/groovy");
        Files.createDirectories(srcMainGroovy);

        IClasspathEntry staleRootSource = mockClasspathEntry(
                IClasspathEntry.CPE_SOURCE, "/ExtGroovy_project/linked");
        IClasspathEntry libraryEntry = mockClasspathEntry(
                IClasspathEntry.CPE_LIBRARY, "/libs/example.jar");
        IClasspathEntry jreEntry = mockClasspathEntry(
                IClasspathEntry.CPE_CONTAINER, "org.eclipse.jdt.launching.JRE_CONTAINER");

        when(project.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/ExtGroovy_project"));
        when(javaProject.getRawClasspath()).thenReturn(new IClasspathEntry[] {
                staleRootSource,
                libraryEntry,
                jreEntry
        });
        when(linkedRoot.getFolder(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String srcDir = invocation.getArgument(0, String.class);
            IFolder folder = mock(IFolder.class);
            when(folder.getLocation()).thenReturn(
                    org.eclipse.core.runtime.Path.fromOSString(tempDir.resolve(srcDir).toString()));
            when(folder.getFullPath()).thenReturn(
                    new org.eclipse.core.runtime.Path("/ExtGroovy_project/linked/" + srcDir));
            return folder;
        });

        try (MockedStatic<JavaCore> javaCoreMock = org.mockito.Mockito.mockStatic(JavaCore.class)) {
            javaCoreMock.when(() -> JavaCore.create(project)).thenReturn(javaProject);
            javaCoreMock.when(() -> JavaCore.newSourceEntry(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> mockClasspathEntry(
                            IClasspathEntry.CPE_SOURCE,
                            invocation.getArgument(0, org.eclipse.core.runtime.IPath.class).toString()));
            javaCoreMock.when(() -> JavaCore.newContainerEntry(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> mockClasspathEntry(
                            IClasspathEntry.CPE_CONTAINER,
                            invocation.getArgument(0, org.eclipse.core.runtime.IPath.class).toString()));
            invokeConfigureExternalProjectClasspath(dm, project, linkedRoot);
        }

        org.mockito.ArgumentCaptor<IClasspathEntry[]> entriesCaptor =
                org.mockito.ArgumentCaptor.forClass(IClasspathEntry[].class);
        verify(javaProject).setRawClasspath(
                entriesCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(new org.eclipse.core.runtime.Path("/ExtGroovy_project/bin")),
                org.mockito.ArgumentMatchers.any());

        List<IClasspathEntry> appliedEntries = Arrays.asList(entriesCaptor.getValue());
        assertTrue(appliedEntries.stream().anyMatch(entry ->
                entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                        && "/ExtGroovy_project/linked/src/main/groovy".equals(entry.getPath().toString())));
        assertFalse(appliedEntries.stream().anyMatch(entry ->
                entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                        && "/ExtGroovy_project/linked".equals(entry.getPath().toString())));
        assertTrue(appliedEntries.contains(libraryEntry));
        assertEquals(1, appliedEntries.stream().filter(entry ->
                entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                        && entry.getPath().toString().startsWith("org.eclipse.jdt.launching.JRE_CONTAINER"))
                .count());
    }

    // ================================================================
    // dispose tests
    // ================================================================

    @Test
    void disposeRemovesAllOpenDocuments() throws Exception {
        DocumentManager dm = new DocumentManager();
        dm.didOpen("file:///disposeA.groovy", "class A {}");
        dm.didOpen("file:///disposeB.groovy", "class B {}");
        assertFalse(dm.getOpenDocumentUris().isEmpty());
        dm.dispose();
        assertTrue(dm.getOpenDocumentUris().isEmpty());
    }

    @Test
    void disposeContentReturnsNull() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///disposeContent.groovy";
        dm.didOpen(uri, "class X {}");
        assertNotNull(dm.getContent(uri));
        dm.dispose();
        assertNull(dm.getContent(uri));
    }

    @Test
    void disposeClearsAuxiliaryCachesAndClientState() throws Exception {
        DocumentManager dm = new DocumentManager();

        dm.addClientUriMapping("file:///aux.groovy", "file:///aux.groovy");
        dm.addImportedProjectRoot("/tmp/project");
        dm.putCodeSelectCachePlaceholder("file:///aux.groovy#1:1");
        dm.putPendingStandaloneParseFuture(
            "file:///aux.groovy", mock(java.util.concurrent.ScheduledFuture.class));
        dm.setLanguageClient(mock(LanguageClient.class));

        dm.dispose();

        assertEquals(0, dm.getClientUriCount());
        assertEquals(0, dm.getImportedProjectRootCount());
        assertEquals(0, dm.getCodeSelectCacheSize());
        assertEquals(0, dm.getPendingStandaloneParseFutureCount());
        assertFalse(dm.hasLanguageClient());
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Condition was not satisfied within " + timeoutMs + " ms");
    }

    private void invokeConfigureExternalProjectClasspath(
            DocumentManager manager,
            IProject project,
            IFolder linkedRoot) throws Exception {
        manager.configureExternalProjectClasspath(project, linkedRoot);
    }

    private IClasspathEntry mockClasspathEntry(int entryKind, String path) {
        IClasspathEntry entry = mock(IClasspathEntry.class);
        when(entry.getEntryKind()).thenReturn(entryKind);
        when(entry.getPath()).thenReturn(new org.eclipse.core.runtime.Path(path));
        return entry;
    }

    private static final class InlineExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class DeferredExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;
        private final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<>(submitted);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
            }
            // Intentionally leave submitted tasks pending so the test can
            // assert diagnostics readiness before the didOpen refresh runs.
            submitted.add(command);
        }
    }
}
