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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.providers.ReferenceSearchHelper;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileRename;
import org.eclipse.lsp4j.RenameFilesParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class GroovyWorkspaceServiceFileEventsTest {

    @Test
    void handleWatchedGroovyFileChangeRefreshesWorkspaceAndPublishesDiagnostics() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        GroovyWorkspaceService service = new GroovyWorkspaceService(server, new DocumentManager());
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
                List.of(new FileEvent("file:///workspace/src/App.groovy", FileChangeType.Changed)));

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile file = mock(IFile.class);
        IProject project = mock(IProject.class);

        when(workspace.getRoot()).thenReturn(root);
        when(root.findFilesForLocationURI(URI.create("file:///workspace/src/App.groovy")))
                .thenReturn(new IFile[] { file });
        when(file.getProject()).thenReturn(project);
        when(project.isOpen()).thenReturn(true);

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<ReferenceSearchHelper> referenceSearch = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);

            invokePrivate(service, "handleWatchedFileChanges",
                    new Class<?>[] { DidChangeWatchedFilesParams.class },
                    new Object[] { params });

                verify(file).refreshLocal(org.mockito.ArgumentMatchers.eq(org.eclipse.core.resources.IResource.DEPTH_ZERO),
                    org.mockito.ArgumentMatchers.any(org.eclipse.core.runtime.NullProgressMonitor.class));
                verify(project).refreshLocal(org.mockito.ArgumentMatchers.eq(org.eclipse.core.resources.IResource.DEPTH_ONE),
                    org.mockito.ArgumentMatchers.any(org.eclipse.core.runtime.NullProgressMonitor.class));
            referenceSearch.verify(ReferenceSearchHelper::clearCaches, times(1));
        } finally {
            server.shutdown().join();
        }

        assertEquals(0, server.scheduleDebouncedBuildCount);
        assertEquals(1, server.recordingTextDocumentService.publishDiagnosticsForOpenDocumentsCount);
        assertEquals(1, server.recordingTextDocumentService.refreshCodeLensesCount);
    }

    @Test
    void handleWatchedJavaFileChangeRefreshesWorkspaceAndSchedulesBuild() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        GroovyWorkspaceService service = new GroovyWorkspaceService(server, new DocumentManager());
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
                List.of(new FileEvent("file:///workspace/src/App.java", FileChangeType.Changed)));

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile file = mock(IFile.class);
        IProject project = mock(IProject.class);

        when(workspace.getRoot()).thenReturn(root);
        when(root.findFilesForLocationURI(URI.create("file:///workspace/src/App.java")))
                .thenReturn(new IFile[] { file });
        when(file.getProject()).thenReturn(project);
        when(project.isOpen()).thenReturn(true);

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<ReferenceSearchHelper> referenceSearch = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);

            invokePrivate(service, "handleWatchedFileChanges",
                    new Class<?>[] { DidChangeWatchedFilesParams.class },
                    new Object[] { params });

                verify(file).refreshLocal(org.mockito.ArgumentMatchers.eq(org.eclipse.core.resources.IResource.DEPTH_ZERO),
                    org.mockito.ArgumentMatchers.any(org.eclipse.core.runtime.NullProgressMonitor.class));
                verify(project).refreshLocal(org.mockito.ArgumentMatchers.eq(org.eclipse.core.resources.IResource.DEPTH_ONE),
                    org.mockito.ArgumentMatchers.any(org.eclipse.core.runtime.NullProgressMonitor.class));
            referenceSearch.verifyNoInteractions();
        } finally {
            server.shutdown().join();
        }

        assertEquals(1, server.scheduleDebouncedBuildCount);
        assertEquals(0, server.recordingTextDocumentService.publishDiagnosticsForOpenDocumentsCount);
        assertEquals(1, server.recordingTextDocumentService.refreshCodeLensesCount);
    }

    @Test
    void didRenameFilesWithGroovyRenamePublishesDiagnosticsAsynchronously() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        server.recordingTextDocumentService.publishDiagnosticsLatch = new CountDownLatch(1);
        GroovyWorkspaceService service = new GroovyWorkspaceService(server, new DocumentManager());
        RenameFilesParams params = new RenameFilesParams(List.of(
                new FileRename("file:///workspace/src/OldName.groovy", "file:///workspace/src/NewName.groovy")));

        try (MockedStatic<ReferenceSearchHelper> referenceSearch = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {
            service.didRenameFiles(params);

            assertTrue(server.recordingTextDocumentService.publishDiagnosticsLatch.await(5, TimeUnit.SECONDS));
            referenceSearch.verify(ReferenceSearchHelper::clearCaches, times(1));
        } finally {
            server.shutdown().join();
        }

        assertEquals(0, server.triggerFullBuildCount);
        assertEquals(1, server.recordingTextDocumentService.publishDiagnosticsForOpenDocumentsCount);
    }

    @Test
    void didRenameFilesWithJavaRenameTriggersFullBuildAsynchronously() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        server.triggerFullBuildLatch = new CountDownLatch(1);
        GroovyWorkspaceService service = new GroovyWorkspaceService(server, new DocumentManager());
        RenameFilesParams params = new RenameFilesParams(List.of(
                new FileRename("file:///workspace/src/OldName.java", "file:///workspace/src/NewName.java")));

        try (MockedStatic<ReferenceSearchHelper> referenceSearch = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {
            service.didRenameFiles(params);

            assertTrue(server.triggerFullBuildLatch.await(5, TimeUnit.SECONDS));
            referenceSearch.verify(ReferenceSearchHelper::clearCaches, times(1));
        } finally {
            server.shutdown().join();
        }

        assertEquals(1, server.triggerFullBuildCount);
        assertEquals(0, server.recordingTextDocumentService.publishDiagnosticsForOpenDocumentsCount);
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class RecordingGroovyLanguageServer extends GroovyLanguageServer {
        private final RecordingGroovyTextDocumentService recordingTextDocumentService;
        private int scheduleDebouncedBuildCount;
        private int triggerFullBuildCount;
        private CountDownLatch triggerFullBuildLatch;

        private RecordingGroovyLanguageServer() {
            recordingTextDocumentService = new RecordingGroovyTextDocumentService(this, new DocumentManager());
        }

        @Override
        GroovyTextDocumentService getGroovyTextDocumentService() {
            return recordingTextDocumentService;
        }

        @Override
        void scheduleDebouncedBuild() {
            scheduleDebouncedBuildCount++;
        }

        @Override
        void triggerFullBuild() {
            triggerFullBuildCount++;
            if (triggerFullBuildLatch != null) {
                triggerFullBuildLatch.countDown();
            }
        }
    }

    private static final class RecordingGroovyTextDocumentService extends GroovyTextDocumentService {
        private int publishDiagnosticsForOpenDocumentsCount;
        private int refreshCodeLensesCount;
        private CountDownLatch publishDiagnosticsLatch;

        private RecordingGroovyTextDocumentService(GroovyLanguageServer server, DocumentManager documentManager) {
            super(server, documentManager);
        }

        @Override
        void publishDiagnosticsForOpenDocuments() {
            publishDiagnosticsForOpenDocumentsCount++;
            if (publishDiagnosticsLatch != null) {
                publishDiagnosticsLatch.countDown();
            }
        }

        @Override
        void refreshCodeLenses() {
            refreshCodeLensesCount++;
        }
    }
}