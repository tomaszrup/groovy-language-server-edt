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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CompletionProviderPublicTypeSearchTest {

    @Test
    void getCompletionsReturnsTypeMatchesFromSourceAndLibraries() throws Exception {
        String uri = "file:///CompletionTypeSearch.groovy";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        IJavaSearchScope sourceScope = mock(IJavaSearchScope.class);
        IJavaSearchScope libraryScope = mock(IJavaSearchScope.class);

        when(documentManager.getContent(uri)).thenReturn("Widg");
        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(project);
        when(project.exists()).thenReturn(true);
        when(project.getElementName()).thenReturn("demo-project");

        CompletionProvider provider = new CompletionProvider(documentManager);
        CompletionParams params = new CompletionParams(new TextDocumentIdentifier(uri), new Position(0, 4));

        try (MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES)))
                    .thenReturn(sourceScope);
            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.APPLICATION_LIBRARIES)))
                    .thenReturn(libraryScope);

            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PREFIX_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(sourceScope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameRequestor requestor = invocation.getArgument(6);
                        requestor.acceptType(0, "demo".toCharArray(), "Widget".toCharArray(), null, "/tmp/Widget");
                        return null;
                    });
            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PREFIX_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(libraryScope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameRequestor requestor = invocation.getArgument(6);
                        requestor.acceptType(0, "lib".toCharArray(), "WidgetUtil".toCharArray(), null, "/tmp/WidgetUtil");
                        return null;
                    });

            List<CompletionItem> items = provider.getCompletions(params);

            assertTrue(items.stream().anyMatch(item -> "Widget".equals(item.getLabel())));
            assertTrue(items.stream().anyMatch(item -> "WidgetUtil".equals(item.getLabel())));
        }
    }

    @Test
    void getCompletionsReturnsAnnotationTypeWithImportEdit() throws Exception {
        String uri = "file:///CompletionAnnotationSearch.groovy";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);

        when(documentManager.getContent(uri)).thenReturn("@Dep");
        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(project);
        when(project.exists()).thenReturn(true);
        when(project.getElementName()).thenReturn("demo-project");

        CompletionProvider provider = new CompletionProvider(documentManager);
        CompletionParams params = new CompletionParams(new TextDocumentIdentifier(uri), new Position(0, 4));

        try (MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES)))
                    .thenReturn(scope);

            searchSupportMock.when(() -> JdtSearchSupport.searchAllTypeNames(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PATTERN_MATCH),
                    org.mockito.ArgumentMatchers.any(char[].class),
                    org.mockito.ArgumentMatchers.eq(SearchPattern.R_PREFIX_MATCH),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.TYPE),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(org.eclipse.jdt.core.search.TypeNameRequestor.class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH),
                    org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        org.eclipse.jdt.core.search.TypeNameRequestor requestor = invocation.getArgument(6);
                        requestor.acceptType(Flags.AccAnnotation, "com.acme".toCharArray(), "Deprecated".toCharArray(), null, "/tmp/Deprecated");
                        return null;
                    });

            List<CompletionItem> items = provider.getCompletions(params);

            assertEquals(1, items.stream().filter(item -> "Deprecated".equals(item.getLabel())).count());
            CompletionItem item = items.stream().filter(candidate -> "Deprecated".equals(candidate.getLabel())).findFirst().orElseThrow();
            assertTrue(item.getAdditionalTextEdits().stream().anyMatch(edit -> edit.getNewText().contains("import com.acme.Deprecated")));
        }
    }
}