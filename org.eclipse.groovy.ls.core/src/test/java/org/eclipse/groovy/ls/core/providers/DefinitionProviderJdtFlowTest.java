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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class DefinitionProviderJdtFlowTest {

    @Test
    void getDefinitionUsesJdtElementResourceLocation() throws Exception {
        String uri = "file:///DefinitionProviderJdt.groovy";
        String content = "class Demo { void greet() {} }";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IMethod method = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 18)).thenReturn(new IJavaElement[] { method });
        when(documentManager.remapToWorkingCopyElement(method)).thenReturn(null);
        when(documentManager.resolveElementUri(method)).thenReturn(uri);
        when(method.getElementName()).thenReturn("greet");
        when(method.getResource()).thenReturn(resource);
        when(resource.getName()).thenReturn("Demo.groovy");
        when(method.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(18);
        when(nameRange.getLength()).thenReturn(5);

        DefinitionProvider provider = new DefinitionProvider(documentManager);
        DefinitionParams params = new DefinitionParams(new TextDocumentIdentifier(uri), new Position(0, 18));

        List<Location> locations = provider.getDefinition(params);

        assertEquals(1, locations.size());
        Location location = locations.get(0);
        assertNotNull(location);
        assertEquals(uri, location.getUri());
        assertEquals(new Range(new Position(0, 18), new Position(0, 23)), location.getRange());
    }

    @Test
    void getDefinitionUsesTempGroovySourceAndNavigatesViaJdtProject() throws Exception {
        String uri = "groovy-source:///demo/temp/Temp.groovy";
        String content = "package demo.temp\nimport demo.Support\nclass Temp { Support value }\n";
        String attachedSource = "package demo;\npublic class Support {}\n";

        DocumentManager documentManager = mock(DocumentManager.class);
        when(documentManager.getWorkingCopy(uri)).thenReturn(null);
        when(documentManager.getContent(uri)).thenReturn(content);

        IProject project = mock(IProject.class);
        when(project.isOpen()).thenReturn(true);
        when(project.members()).thenReturn(new IResource[0]);

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(workspace.getRoot()).thenReturn(root);
        when(root.getProjects()).thenReturn(new IProject[] { project });

        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        when(javaProject.exists()).thenReturn(true);

        IType type = mock(IType.class);
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getFields()).thenReturn(new org.eclipse.jdt.core.IField[0]);
        when(type.getElementName()).thenReturn("Support");
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);
        when(type.getClassFile()).thenReturn(classFile);
        when(classFile.getSource()).thenReturn(attachedSource);
        when(javaProject.findType("demo.Support")).thenReturn(type);

        try (MockedStatic<ResourcesPlugin> resources = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = org.mockito.Mockito.mockStatic(JavaCore.class)) {
            resources.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            DefinitionProvider provider = new DefinitionProvider(documentManager);
            DefinitionParams params = new DefinitionParams(
                    new TextDocumentIdentifier(uri),
                    new Position(2, 15));

            List<Location> locations = provider.getDefinition(params);

            assertEquals(1, locations.size());
            Location location = locations.get(0);
            assertNotNull(location);
            assertEquals("groovy-source:///demo/Support.java", location.getUri());
            assertEquals(new Position(1, 7), location.getRange().getStart());
        }
    }

    @Test
    void findSourceFromBinaryRootUsesExternalBuildPathToFindProjectSource(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("producer");
        Path binaryRoot = projectRoot.resolve("build/classes/java/main");
        Path sourceFile = projectRoot.resolve("src/main/java/demo/Support.java");
        Files.createDirectories(binaryRoot);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo;\npublic class Support {}\n");

        IType type = mock(IType.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        when(type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT)).thenReturn(root);
        when(root.isExternal()).thenReturn(true);
        when(root.getPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(binaryRoot.toString()));

        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        java.lang.reflect.Method method = DefinitionProvider.class.getDeclaredMethod(
                "findSourceFromBinaryRoot", IType.class, String.class);
        method.setAccessible(true);

        Location location = (Location) method.invoke(provider, type, "demo.Support");

        assertNotNull(location);
        assertEquals(sourceFile.toFile().toURI().toString(), location.getUri());
        assertEquals(new Position(0, 0), location.getRange().getStart());
    }
}