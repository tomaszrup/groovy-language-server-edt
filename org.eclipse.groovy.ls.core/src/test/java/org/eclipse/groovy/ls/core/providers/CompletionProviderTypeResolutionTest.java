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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CompletionProviderTypeResolutionTest {

    @Test
    void searchTypeBySimpleNamePrefersRequestedPackageAndCachesResult() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        IType resolvedType = mock(IType.class);

        when(project.getElementName()).thenReturn("demo-project");
        when(project.findType("preferred.pkg.Widget")).thenReturn(resolvedType);

        try (MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES
                            | IJavaSearchScope.APPLICATION_LIBRARIES
                            | IJavaSearchScope.REFERENCED_PROJECTS)))
                    .thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_EXACT_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameRequestor requestor = invocation.getArgument(6);
                        requestor.acceptType(0, "other.pkg".toCharArray(), "Widget".toCharArray(), null, "/tmp/other");
                        requestor.acceptType(0, "preferred.pkg".toCharArray(), "Widget".toCharArray(), null, "/tmp/preferred");
                        return null;
                    });

            IType first = invokeSearchTypeBySimpleName(provider, project, "Widget", "preferred.pkg");
            IType second = invokeSearchTypeBySimpleName(provider, project, "Widget", "preferred.pkg");

            assertSame(resolvedType, first);
            assertSame(resolvedType, second);
            searchSupportMock.verify(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_EXACT_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.isNull()),
                    times(1));
            verify(project, times(1)).findType("preferred.pkg.Widget");
        }
    }

    @Test
    void resolveTraitTypeFallsBackToSimpleNameSearchAfterDirectLookupsMiss() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        IType resolvedType = mock(IType.class);
        ClassNode ifaceRef = new ClassNode("TraitContract", 0, ClassHelper.OBJECT_TYPE);
        ClassNode owner = new ClassNode("demo.Owner", 0, ClassHelper.OBJECT_TYPE);
        ModuleNode module = new ModuleNode((org.codehaus.groovy.control.SourceUnit) null);

        when(project.getElementName()).thenReturn("demo-project");
        when(project.findType("demo.TraitContract")).thenReturn(null);
        when(project.findType("other.pkg.TraitContract")).thenReturn(resolvedType);
        when(project.findType("groovy.lang.TraitContract")).thenReturn(null);
        when(project.findType("groovy.util.TraitContract")).thenReturn(null);
        when(project.findType("java.lang.TraitContract")).thenReturn(null);
        when(project.findType("java.util.TraitContract")).thenReturn(null);

        try (MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES
                            | IJavaSearchScope.APPLICATION_LIBRARIES
                            | IJavaSearchScope.REFERENCED_PROJECTS)))
                    .thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_EXACT_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameRequestor requestor = invocation.getArgument(6);
                        requestor.acceptType(0, "other.pkg".toCharArray(), "TraitContract".toCharArray(), null, "/tmp/other");
                        return null;
                    });

            IType resolved = invokeResolveTraitType(provider, ifaceRef, owner, module, project);

            assertSame(resolvedType, resolved);
            verify(project).findType("demo.TraitContract");
            verify(project).findType("other.pkg.TraitContract");
            assertTrue(module.getImports().isEmpty());
        }
    }

    private IType invokeSearchTypeBySimpleName(CompletionProvider provider,
            IJavaProject project,
            String simpleName,
            String preferredPackage) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod(
                "searchTypeBySimpleName", IJavaProject.class, String.class, String.class);
        method.setAccessible(true);
        return (IType) method.invoke(provider, project, simpleName, preferredPackage);
    }

    private IType invokeResolveTraitType(CompletionProvider provider,
            ClassNode ifaceRef,
            ClassNode owner,
            ModuleNode module,
            IJavaProject project) throws Exception {
        Class<?> contextClass = null;
        for (Class<?> nested : CompletionProvider.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("TypeResolutionContext")) {
                contextClass = nested;
                break;
            }
        }
        Method method = CompletionProvider.class.getDeclaredMethod(
                "resolveTraitType", ClassNode.class, ClassNode.class, ModuleNode.class, IJavaProject.class, contextClass);
        method.setAccessible(true);
        return (IType) method.invoke(provider, ifaceRef, owner, module, project, null);
    }
}