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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DefinitionProviderImportResolutionTest {

    @Test
    void resolveImportedClassNodeResolvesExplicitImport() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        ModuleNode module = mock(ModuleNode.class);
        ImportNode importNode = mock(ImportNode.class);
        ClassNode importType = mock(ClassNode.class);
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType resolvedType = mock(IType.class);

        when(module.getImports()).thenReturn(List.of(importNode));
        when(module.getStarImports()).thenReturn(List.of());
        when(importNode.getType()).thenReturn(importType);
        when(importType.getNameWithoutPackage()).thenReturn("Support");
        when(importType.getName()).thenReturn("demo.Support");
        when(project.findType("demo.Support")).thenReturn(resolvedType);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveImportedClassNode",
                ModuleNode.class,
                String.class,
                org.eclipse.jdt.core.IJavaProject.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, module, "Support", project, null);

        assertSame(resolvedType, result);
    }

    @Test
    void resolveStaticImportMemberNavigatesImportedTypeViaJdtProject() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        ModuleNode module = mock(ModuleNode.class);
        ImportNode importNode = mock(ImportNode.class);
        ClassNode importType = mock(ClassNode.class);

        when(module.getStaticImports()).thenReturn(Map.of("assertEquals", importNode));
        when(module.getStaticStarImports()).thenReturn(Map.of());
        when(importNode.getFieldName()).thenReturn("assertEquals");
        when(importNode.getType()).thenReturn(importType);
        when(importType.getName()).thenReturn("org.junit.Assert");
        when(importType.getNameWithoutPackage()).thenReturn("Assert");

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
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getElementName()).thenReturn("Assert");
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);
        when(type.getClassFile()).thenReturn(classFile);
        when(classFile.getSource()).thenReturn("package org.junit;\npublic class Assert {}\n");
        when(javaProject.findType("org.junit.Assert")).thenReturn(type);

        try (MockedStatic<ResourcesPlugin> resources = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = org.mockito.Mockito.mockStatic(JavaCore.class)) {
            resources.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            Method method = DefinitionProvider.class.getDeclaredMethod(
                    "resolveStaticImportMember",
                    ModuleNode.class,
                    String.class,
                    Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
            method.setAccessible(true);

            Location location = (Location) method.invoke(provider, module, "assertEquals", null);

            assertNotNull(location);
            assertEquals("groovy-source:///org/junit/Assert.java", location.getUri());
            assertEquals(new Position(1, 7), location.getRange().getStart());
        }
    }
}