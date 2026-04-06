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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ReferenceSearchHelperJdtTest {

    @Test
    void findReferenceLocationsUsesJdtMatchesToBuildLocations() throws Exception {
        String declarationUri = "file:///Decl.groovy";
        String targetUri = "file:///Use.groovy";
        DocumentManager documentManager = mock(DocumentManager.class);
        IJavaElement element = mock(IJavaElement.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        SearchMatch match = mock(SearchMatch.class);
        IResource resource = mock(IResource.class);

        when(element.getJavaProject()).thenReturn(javaProject);
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(6);
        when(match.getLength()).thenReturn(4);
        when(documentManager.getContent(targetUri)).thenReturn("alpha beta gamma");

        try (MockedStatic<SearchPattern> searchPattern = Mockito.mockStatic(SearchPattern.class);
                MockedStatic<SearchScopeHelper> scopeHelper = Mockito.mockStatic(SearchScopeHelper.class);
                MockedStatic<JdtSearchSupport> searchSupport = Mockito.mockStatic(JdtSearchSupport.class)) {
            searchPattern.when(() -> SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES))
                    .thenReturn(pattern);
            scopeHelper.when(() -> SearchScopeHelper.createSourceScope(javaProject, declarationUri))
                    .thenReturn(scope);
            searchSupport.when(() -> JdtSearchSupport.resolveResourceUri(documentManager, resource))
                    .thenReturn(targetUri);
            searchSupport.when(() -> JdtSearchSupport.search(eq(pattern), eq(scope), any(SearchRequestor.class), eq(null)))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(match);
                        return null;
                    });

            List<Location> locations = ReferenceSearchHelper.findReferenceLocations(
                    element,
                    declarationUri,
                    documentManager);

            assertEquals(1, locations.size());
            assertEquals(targetUri, locations.get(0).getUri());
            assertEquals(new Range(new Position(0, 6), new Position(0, 10)), locations.get(0).getRange());
        }
    }

    @Test
    void findReferenceLocationsFallsBackToZeroRangeWhenContentIsUnavailable() throws Exception {
        String declarationUri = "file:///Decl.groovy";
        String targetUri = "file:///Use.groovy";
        DocumentManager documentManager = mock(DocumentManager.class);
        IJavaElement element = mock(IJavaElement.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        SearchMatch match = mock(SearchMatch.class);
        IResource resource = mock(IResource.class);

        when(element.getJavaProject()).thenReturn(javaProject);
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(3);
        when(match.getLength()).thenReturn(2);
        when(documentManager.getContent(targetUri)).thenReturn(null);

        try (MockedStatic<SearchPattern> searchPattern = Mockito.mockStatic(SearchPattern.class);
                MockedStatic<SearchScopeHelper> scopeHelper = Mockito.mockStatic(SearchScopeHelper.class);
                MockedStatic<JdtSearchSupport> searchSupport = Mockito.mockStatic(JdtSearchSupport.class)) {
            searchPattern.when(() -> SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES))
                    .thenReturn(pattern);
            scopeHelper.when(() -> SearchScopeHelper.createSourceScope(javaProject, declarationUri))
                    .thenReturn(scope);
            searchSupport.when(() -> JdtSearchSupport.resolveResourceUri(documentManager, resource))
                    .thenReturn(targetUri);
            searchSupport.when(() -> JdtSearchSupport.search(eq(pattern), eq(scope), any(SearchRequestor.class), eq(null)))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(match);
                        return null;
                    });

            List<Location> locations = ReferenceSearchHelper.findReferenceLocations(
                    element,
                    declarationUri,
                    documentManager);

            assertEquals(1, locations.size());
            assertEquals(new Range(new Position(0, 0), new Position(0, 0)), locations.get(0).getRange());
        }
    }

    @Test
    void hasReferencesReturnsTrueWhenJdtSearchFindsFirstMatch() throws Exception {
        String declarationUri = "file:///Decl.groovy";
        DocumentManager documentManager = mock(DocumentManager.class);
        IJavaElement element = mock(IJavaElement.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        SearchMatch match = mock(SearchMatch.class);

        when(element.getJavaProject()).thenReturn(javaProject);

        try (MockedStatic<SearchPattern> searchPattern = Mockito.mockStatic(SearchPattern.class);
                MockedStatic<SearchScopeHelper> scopeHelper = Mockito.mockStatic(SearchScopeHelper.class);
                MockedStatic<JdtSearchSupport> searchSupport = Mockito.mockStatic(JdtSearchSupport.class)) {
            searchPattern.when(() -> SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES))
                    .thenReturn(pattern);
            scopeHelper.when(() -> SearchScopeHelper.createSourceScope(javaProject, declarationUri))
                    .thenReturn(scope);
            searchSupport.when(() -> JdtSearchSupport.search(eq(pattern), eq(scope), any(SearchRequestor.class), any()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(match);
                        return null;
                    });

            assertTrue(ReferenceSearchHelper.hasReferences(element, declarationUri, documentManager));
        }
    }
}