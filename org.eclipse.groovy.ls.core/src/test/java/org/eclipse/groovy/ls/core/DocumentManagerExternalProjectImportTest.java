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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class DocumentManagerExternalProjectImportTest {

    @TempDir
    Path tempDir;

    @Test
    void lookupCompilationUnitPrefersMostSpecificProjectMatch() throws Exception {
        DocumentManager manager = new DocumentManager();
        URI uri = tempDir.resolve("workspace/sample/src/main/groovy/demo/Demo.groovy").toUri();
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile fallbackFile = mock(IFile.class);
        IFile specificFile = mock(IFile.class);
        IProject fallbackProject = mock(IProject.class);
        IProject specificProject = mock(IProject.class);
        IResource linkedRoot = mock(IResource.class);
        ICompilationUnit fallbackCu = mock(ICompilationUnit.class);
        ICompilationUnit specificCu = mock(ICompilationUnit.class);
        IJavaProject fallbackJavaProject = mock(IJavaProject.class);
        IJavaProject specificJavaProject = mock(IJavaProject.class);
        IProjectDescription fallbackDescription = mockProjectDescription("/tmp/workspace-root");
        IClasspathEntry fallbackLibraryEntry = mockClasspathEntry(IClasspathEntry.CPE_LIBRARY, "/libs/one.jar");
        IClasspathEntry specificLibraryEntryOne = mockClasspathEntry(IClasspathEntry.CPE_LIBRARY, "/libs/one.jar");
        IClasspathEntry specificLibraryEntryTwo = mockClasspathEntry(IClasspathEntry.CPE_LIBRARY, "/libs/two.jar");

        when(workspace.getRoot()).thenReturn(root);
        when(root.findFilesForLocationURI(uri)).thenReturn(new IFile[] { fallbackFile, specificFile });
        when(fallbackFile.getProject()).thenReturn(fallbackProject);
        when(specificFile.getProject()).thenReturn(specificProject);
        when(fallbackProject.findMember("linked")).thenReturn(null);
        when(fallbackProject.getDescription()).thenReturn(fallbackDescription);
        when(fallbackProject.getLocation()).thenReturn(new org.eclipse.core.runtime.Path("/tmp/workspace-root"));
        when(fallbackProject.getName()).thenReturn("ExtGroovy_sample");
        when(fallbackJavaProject.getRawClasspath()).thenReturn(new IClasspathEntry[] { fallbackLibraryEntry });

        when(specificProject.findMember("linked")).thenReturn(linkedRoot);
        when(linkedRoot.isLinked()).thenReturn(true);
        when(linkedRoot.getLocation()).thenReturn(
                new org.eclipse.core.runtime.Path("/tmp/workspace-root/subproject"));
        when(specificProject.getName()).thenReturn("consumer");
        when(specificJavaProject.getRawClasspath()).thenReturn(new IClasspathEntry[] {
                specificLibraryEntryOne,
                specificLibraryEntryTwo
        });

        try (MockedStatic<ResourcesPlugin> resourcesMock = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
             MockedStatic<JavaCore> javaCoreMock = org.mockito.Mockito.mockStatic(JavaCore.class)) {

            resourcesMock.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCoreMock.when(() -> JavaCore.create(fallbackFile)).thenReturn(fallbackCu);
            javaCoreMock.when(() -> JavaCore.create(specificFile)).thenReturn(specificCu);
            javaCoreMock.when(() -> JavaCore.create(fallbackProject)).thenReturn(fallbackJavaProject);
            javaCoreMock.when(() -> JavaCore.create(specificProject)).thenReturn(specificJavaProject);

            ICompilationUnit result = invokeLookupCompilationUnit(manager, uri);

            assertSame(specificCu, result);
        }
    }

    @Test
    void importExternalProjectCreatesLinkedProjectAndClasspath() throws Exception {
        DocumentManager manager = new DocumentManager();
        Path projectRoot = tempDir.resolve("external-sample");
        Files.createDirectories(projectRoot.resolve("src/main/groovy"));

        String projectName = "ExtGroovy_" + projectRoot.getFileName();
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        IProjectDescription description = mock(IProjectDescription.class);
        IFolder linkedRoot = mock(IFolder.class);
        IJavaProject javaProject = mock(IJavaProject.class);

        when(workspace.getRoot()).thenReturn(root);
        when(workspace.newProjectDescription(projectName)).thenReturn(description);
        when(root.getProjects()).thenReturn(new IProject[0]);
        when(root.getProject(projectName)).thenReturn(project);
        when(project.exists()).thenReturn(false);
        when(project.getFolder("linked")).thenReturn(linkedRoot);
        when(project.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/" + projectName));
        when(project.getName()).thenReturn(projectName);
        when(javaProject.getRawClasspath()).thenReturn(new IClasspathEntry[0]);
        when(linkedRoot.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/" + projectName + "/linked"));
        when(linkedRoot.getFolder(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String srcDir = invocation.getArgument(0, String.class);
            IFolder folder = mock(IFolder.class);
            when(folder.getLocation()).thenReturn(
                    org.eclipse.core.runtime.Path.fromOSString(projectRoot.resolve(srcDir).toString()));
            when(folder.getFullPath()).thenReturn(
                    new org.eclipse.core.runtime.Path("/" + projectName + "/linked/" + srcDir));
            return folder;
        });

        try (MockedStatic<ResourcesPlugin> resourcesMock = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
             MockedStatic<JavaCore> javaCoreMock = org.mockito.Mockito.mockStatic(JavaCore.class)) {

            resourcesMock.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCoreMock.when(() -> JavaCore.create(project)).thenReturn(javaProject);
            javaCoreMock.when(() -> JavaCore.newSourceEntry(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> mockClasspathEntry(
                            IClasspathEntry.CPE_SOURCE,
                            invocation.getArgument(0, org.eclipse.core.runtime.IPath.class).toString()));
            javaCoreMock.when(() -> JavaCore.newContainerEntry(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> mockClasspathEntry(
                            IClasspathEntry.CPE_CONTAINER,
                            invocation.getArgument(0, org.eclipse.core.runtime.IPath.class).toString()));

            invokeImportExternalProject(manager, projectRoot);
        }

        verify(description).setNatureIds(new String[] {
                JavaCore.NATURE_ID,
                "org.eclipse.jdt.groovy.core.groovyNature"
        });
        verify(project).create(org.mockito.ArgumentMatchers.eq(description), org.mockito.ArgumentMatchers.any());
        verify(project).open(org.mockito.ArgumentMatchers.any());
        verify(linkedRoot).createLink(
                org.mockito.ArgumentMatchers.eq(org.eclipse.core.runtime.Path.fromOSString(projectRoot.toString())),
                org.mockito.ArgumentMatchers.eq(IResource.ALLOW_MISSING_LOCAL),
                org.mockito.ArgumentMatchers.any());

        org.mockito.ArgumentCaptor<IClasspathEntry[]> entriesCaptor =
                org.mockito.ArgumentCaptor.forClass(IClasspathEntry[].class);
        verify(javaProject).setRawClasspath(
                entriesCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(new org.eclipse.core.runtime.Path("/" + projectName + "/bin")),
                org.mockito.ArgumentMatchers.any());

        List<IClasspathEntry> appliedEntries = Arrays.asList(entriesCaptor.getValue());
        assertTrue(appliedEntries.stream().anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                && entry.getPath().toString().equals("/" + projectName + "/linked/src/main/groovy")));
        assertTrue(appliedEntries.stream().anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                && entry.getPath().toString().startsWith("org.eclipse.jdt.launching.JRE_CONTAINER")));
    }

    private ICompilationUnit invokeLookupCompilationUnit(DocumentManager manager, URI uri) throws Exception {
        Method method = DocumentManager.class.getDeclaredMethod("lookupCompilationUnit", URI.class);
        method.setAccessible(true);
        return (ICompilationUnit) method.invoke(manager, uri);
    }

    private void invokeImportExternalProject(DocumentManager manager, java.io.File projectRoot) throws Exception {
        Method method = DocumentManager.class.getDeclaredMethod("importExternalProject", java.io.File.class);
        method.setAccessible(true);
        method.invoke(manager, projectRoot);
    }

    private void invokeImportExternalProject(DocumentManager manager, Path projectRoot) throws Exception {
        invokeImportExternalProject(manager, projectRoot.toFile());
    }

    private IProjectDescription mockProjectDescription(String location) throws Exception {
        IProjectDescription description = mock(IProjectDescription.class);
        when(description.getLocationURI()).thenReturn(new java.io.File(location).toURI());
        return description;
    }

    private IClasspathEntry mockClasspathEntry(int kind, String path) {
        IClasspathEntry entry = mock(IClasspathEntry.class);
        when(entry.getEntryKind()).thenReturn(kind);
        when(entry.getPath()).thenReturn(new org.eclipse.core.runtime.Path(path));
        return entry;
    }
}