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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ScopedTypeLookupSupportTest {

    @Test
    void findTypePrefersScopedExactMatchBeforeProjectLookup() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        TypeNameMatch exactMatch = mock(TypeNameMatch.class);
        IType exactType = mock(IType.class);

        when(exactMatch.getType()).thenReturn(exactType);
        when(exactType.getFullyQualifiedName('.')).thenReturn("demo.Widget");

        try (MockedStatic<SearchScopeHelper> scopeHelperMock = org.mockito.Mockito.mockStatic(SearchScopeHelper.class);
             MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            scopeHelperMock.when(() -> SearchScopeHelper.getSourceRoots(project, "file:///demo/Widget.groovy"))
                    .thenReturn(List.of(root));
            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES)))
                    .thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameMatchRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameMatchRequestor requestor = invocation.getArgument(6);
                        requestor.acceptTypeNameMatch(exactMatch);
                        return null;
                    });

            IType resolved = ScopedTypeLookupSupport.findType(project, "demo.Widget", "file:///demo/Widget.groovy");

            assertSame(exactType, resolved);
        }
    }

    @Test
    void findTypeFallsBackToBinaryNameReplacementWhenDirectLookupMisses() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("demo.Outer$Inner")).thenReturn(null);
        IType nestedType = mock(IType.class);
        when(project.findType("demo.Outer.Inner")).thenReturn(nestedType);

        try (MockedStatic<SearchScopeHelper> scopeHelperMock = org.mockito.Mockito.mockStatic(SearchScopeHelper.class)) {
            scopeHelperMock.when(() -> SearchScopeHelper.getSourceRoots(project, "file:///demo/Outer.groovy"))
                    .thenReturn(List.of());

            IType resolved = ScopedTypeLookupSupport.findType(project, "demo.Outer$Inner", "file:///demo/Outer.groovy");

            assertSame(nestedType, resolved);
        }
    }

    @Test
    void findTypeReturnsNullForBlankCandidate() throws Exception {
        assertNull(ScopedTypeLookupSupport.findType(mock(IJavaProject.class), "", "file:///demo/Test.groovy"));
    }
}