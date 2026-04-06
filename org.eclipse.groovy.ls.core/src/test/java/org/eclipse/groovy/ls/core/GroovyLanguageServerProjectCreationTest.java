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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
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

class GroovyLanguageServerProjectCreationTest {

    @TempDir
    Path tempDir;

    @Test
    void createSingleRootProjectCreatesLinkedProjectAndSourceClasspath() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Path workspaceDir = Files.createDirectories(tempDir.resolve("workspace"));
        Path sourceDir = Files.createDirectories(workspaceDir.resolve("src/main/groovy"));

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        IProjectDescription description = mock(IProjectDescription.class);
        IFolder linkedRoot = mock(IFolder.class);
        IFolder sourceFolder = mock(IFolder.class);
        IFolder missingSrcFolder = mockFolder(tempDir.resolve("missing-src"), "/GroovyProject/linked/src");
        IJavaProject javaProject = mock(IJavaProject.class);
        IPath projectPath = new org.eclipse.core.runtime.Path("/GroovyProject");
        ArgumentCaptor<IClasspathEntry[]> entriesCaptor = ArgumentCaptor.forClass(IClasspathEntry[].class);

        when(workspace.getRoot()).thenReturn(root);
        when(workspace.newProjectDescription("GroovyProject")).thenReturn(description);
        when(root.getProject("GroovyProject")).thenReturn(project);
        when(project.exists()).thenReturn(false);
        when(project.getFolder("linked")).thenReturn(linkedRoot);
        when(project.getFullPath()).thenReturn(projectPath);
        when(linkedRoot.exists()).thenReturn(false);
        when(linkedRoot.getFolder("src/main/groovy")).thenReturn(sourceFolder);
        when(linkedRoot.getFolder("src")).thenReturn(missingSrcFolder);
        when(sourceFolder.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(sourceDir.toString()));
        when(sourceFolder.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/GroovyProject/linked/src/main/groovy"));

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class, Mockito.CALLS_REAL_METHODS)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            invokePrivate(server, "createSingleRootProject",
                    new Class<?>[] { java.io.File.class, String[].class },
                    new Object[] { workspaceDir.toFile(), new String[] { "src/main/groovy" } });

                verify(project).create(org.mockito.ArgumentMatchers.eq(description),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
                verify(project).open(org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
            verify(linkedRoot).createLink(org.mockito.ArgumentMatchers.any(IPath.class),
                    org.mockito.ArgumentMatchers.eq(IResource.ALLOW_MISSING_LOCAL),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
            verify(javaProject).setRawClasspath(entriesCaptor.capture(), org.mockito.ArgumentMatchers.eq(projectPath.append("bin")),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
        }

        IClasspathEntry[] entries = entriesCaptor.getValue();
        assertEquals(2, entries.length);
        assertEquals("/GroovyProject/linked/src/main/groovy", entries[0].getPath().toString());
        assertTrue(server.subprojectPathToEclipseNameView().containsValue("GroovyProject"));

            server.shutdown().join();
    }

    @Test
    void createEclipseProjectForCreatesIsolatedLinkedProjectAndMappings() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Path subprojectDir = Files.createDirectories(tempDir.resolve("sample-app"));
        Path sourceDir = Files.createDirectories(subprojectDir.resolve("src/main/groovy"));

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject existingNameProject = mock(IProject.class);
        IProject createdProject = mock(IProject.class);
        IProjectDescription description = mock(IProjectDescription.class);
        IFolder linkedRoot = mock(IFolder.class);
        IFolder sourceFolder = mock(IFolder.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        IPath projectPath = new org.eclipse.core.runtime.Path("/Groovy_sample-app_1");
        ArgumentCaptor<IClasspathEntry[]> entriesCaptor = ArgumentCaptor.forClass(IClasspathEntry[].class);

        when(workspace.getRoot()).thenReturn(root);
        when(workspace.newProjectDescription("Groovy_sample-app_1")).thenReturn(description);
        when(root.getProjects()).thenReturn(new IProject[0]);
        when(root.getProject("Groovy_sample-app")).thenReturn(existingNameProject);
        when(root.getProject("Groovy_sample-app_1")).thenReturn(createdProject);
        when(existingNameProject.exists()).thenReturn(true);
        when(createdProject.exists()).thenReturn(false);
        when(createdProject.getFolder("linked")).thenReturn(linkedRoot);
        when(createdProject.getFullPath()).thenReturn(projectPath);
        when(linkedRoot.getFolder("src/main/groovy")).thenReturn(sourceFolder);
        when(sourceFolder.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(sourceDir.toString()));
        when(sourceFolder.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/Groovy_sample-app_1/linked/src/main/groovy"));

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class, Mockito.CALLS_REAL_METHODS)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(createdProject)).thenReturn(javaProject);

            invokePrivate(server, "createEclipseProjectFor",
                    new Class<?>[] { java.io.File.class, String[].class },
                    new Object[] { subprojectDir.toFile(), new String[] { "src/main/groovy" } });

                verify(createdProject).create(org.mockito.ArgumentMatchers.eq(description),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
                verify(createdProject).open(org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
            verify(javaProject).setRawClasspath(entriesCaptor.capture(), org.mockito.ArgumentMatchers.eq(projectPath.append("bin")),
                    org.mockito.ArgumentMatchers.any(NullProgressMonitor.class));
        }

        IClasspathEntry[] entries = entriesCaptor.getValue();
        assertEquals(2, entries.length);
        assertEquals("/Groovy_sample-app_1/linked/src/main/groovy", entries[0].getPath().toString());
        assertEquals("Groovy_sample-app_1",
                server.subprojectPathToEclipseNameView().get(subprojectDir.toString().replace('\\', '/').toLowerCase() + "/"));

                server.shutdown().join();
    }

    private IFolder mockFolder(Path location, String fullPath) {
        IFolder folder = mock(IFolder.class);
        when(folder.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(location.toString()));
        when(folder.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path(fullPath));
        return folder;
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}