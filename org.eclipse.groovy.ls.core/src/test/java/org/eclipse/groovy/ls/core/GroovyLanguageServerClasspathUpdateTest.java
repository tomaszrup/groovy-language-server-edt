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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class GroovyLanguageServerClasspathUpdateTest {

    @TempDir
    Path tempDir;

    @Test
    void buildUpdatedClasspathPreservesExistingEntriesAndAttachesSourcesJar() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        IFolder linkedRoot = mock(IFolder.class);
        IClasspathEntry containerEntry = mock(IClasspathEntry.class);
        IClasspathEntry sourceEntry = mock(IClasspathEntry.class);

        Path jarPath = Files.createFile(tempDir.resolve("library.jar"));
        Files.createFile(tempDir.resolve("library-sources.jar"));
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));

        when(project.getFolder("linked")).thenReturn(linkedRoot);
        when(workspace.getRoot()).thenReturn(root);
        when(linkedRoot.exists()).thenReturn(false);
        when(containerEntry.getEntryKind()).thenReturn(IClasspathEntry.CPE_CONTAINER);
        when(sourceEntry.getEntryKind()).thenReturn(IClasspathEntry.CPE_SOURCE);

        Object result;
        try (MockedStatic<ResourcesPlugin> resourcesPlugin = Mockito.mockStatic(ResourcesPlugin.class)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);

            result = invokePrivate(server, "buildUpdatedClasspath",
                new Class<?>[] { IProject.class, IClasspathEntry[].class, List.class },
                new Object[] {
                    project,
                    new IClasspathEntry[] { containerEntry, sourceEntry },
                    List.of(jarPath.toString(), jarPath.toString(), classesDir.toString(), tempDir.resolve("missing").toString())
                });
        }

        @SuppressWarnings("unchecked")
        List<IClasspathEntry> entries = (List<IClasspathEntry>) getField(result, "entries");

        assertEquals(4, entries.size());
        assertEquals(2, getIntField(result, "appliedLibraries"));
        assertEquals(1, getIntField(result, "sourcesAttached"));
        assertEquals(1, getIntField(result, "directoriesAdded"));
        assertTrue(entries.stream()
            .filter(entry -> entry.getPath() != null)
            .anyMatch(entry -> jarPath.toString().equals(entry.getPath().toOSString())));
        assertTrue(entries.stream()
            .filter(entry -> entry.getPath() != null)
            .anyMatch(entry -> classesDir.toString().equals(entry.getPath().toOSString())));

        server.shutdown().join();
    }

    @Test
    void classpathUpdateAppliesComputedClasspathToFallbackProject() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        IFolder linkedRoot = mock(IFolder.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        IClasspathEntry containerEntry = mock(IClasspathEntry.class);
        IClasspathEntry sourceEntry = mock(IClasspathEntry.class);

        Path jarPath = Files.createFile(tempDir.resolve("runtime.jar"));
        Files.createFile(tempDir.resolve("runtime-sources.jar"));
        Path classesDir = Files.createDirectories(tempDir.resolve("output"));
        IPath fullPath = new org.eclipse.core.runtime.Path("/GroovyProject");

        when(workspace.getRoot()).thenReturn(root);
        when(root.getProject("GroovyProject")).thenReturn(project);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("GroovyProject");
        when(project.getFullPath()).thenReturn(fullPath);
        when(project.getFolder("linked")).thenReturn(linkedRoot);
        when(linkedRoot.exists()).thenReturn(false);
        when(containerEntry.getEntryKind()).thenReturn(IClasspathEntry.CPE_CONTAINER);
        when(sourceEntry.getEntryKind()).thenReturn(IClasspathEntry.CPE_SOURCE);
        when(javaProject.getRawClasspath()).thenReturn(new IClasspathEntry[] { containerEntry, sourceEntry });

        JsonObject params = new JsonObject();
        params.addProperty("hasJarEntries", true);
        JsonArray entries = new JsonArray();
        entries.add(jarPath.toString());
        entries.add(classesDir.toString());
        params.add("entries", entries);

        ArgumentCaptor<IClasspathEntry[]> entriesCaptor = ArgumentCaptor.forClass(IClasspathEntry[].class);

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = Mockito.mockStatic(ResourcesPlugin.class);
            MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class, Mockito.CALLS_REAL_METHODS)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            server.classpathUpdate(params);

            verify(javaProject).setRawClasspath(entriesCaptor.capture(), org.mockito.ArgumentMatchers.eq(fullPath.append("bin")),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
        }

        IClasspathEntry[] appliedEntries = entriesCaptor.getValue();
        assertNotNull(appliedEntries);
        assertEquals(4, appliedEntries.length);
        assertTrue(server.areDiagnosticsEnabled());
        assertTrue(server.hasClasspathForProject("GroovyProject"));
        assertEquals("GroovyProject", server.eagerDiagnosticsProjectName);

        server.shutdown().join();
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private int getIntField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static final class RecordingGroovyLanguageServer extends GroovyLanguageServer {
        private String eagerDiagnosticsProjectName;

        @Override
        void publishDiagnosticsForProjectFiles(String projectName) {
            eagerDiagnosticsProjectName = projectName;
        }
    }
}