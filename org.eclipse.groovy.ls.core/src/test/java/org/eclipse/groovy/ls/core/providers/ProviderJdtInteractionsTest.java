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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ProviderJdtInteractionsTest {

    @Test
    void renameUsesJdtMatchesToBuildWorkspaceEdit() throws Exception {
        String uri = "file:///RenameProviderJdt.groovy";
        String content = "class Demo {}\nnew Demo()\n";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        IJavaElement targetElement = mock(IJavaElement.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        IResource resource = mock(IResource.class);
        SearchMatch match = mock(SearchMatch.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 6)).thenReturn(new IJavaElement[] { targetElement });
        when(documentManager.remapToWorkingCopyElement(targetElement)).thenReturn(targetElement);
        when(documentManager.resolveResourceUri(resource)).thenReturn(uri);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(6);
        when(match.getLength()).thenReturn(4);

        RenameProvider provider = new RenameProvider(documentManager);
        RenameParams params = new RenameParams(new TextDocumentIdentifier(uri), new Position(0, 6), "RenamedDemo");

        try (MockedStatic<SearchPattern> searchPatternMock = org.mockito.Mockito.mockStatic(SearchPattern.class);
             MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchPatternMock.when(() -> SearchPattern.createPattern(targetElement, IJavaSearchConstants.ALL_OCCURRENCES))
                    .thenReturn(pattern);
            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES)))
                    .thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.search(
                    org.mockito.ArgumentMatchers.eq(pattern),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(SearchRequestor.class),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(match);
                        return null;
                    });

            WorkspaceEdit edit = provider.rename(params);

            assertNotNull(edit);
            List<TextEdit> edits = edit.getChanges().get(uri);
            assertNotNull(edits);
            assertTrue(edits.stream().anyMatch(candidate -> "RenamedDemo".equals(candidate.getNewText())
                    && candidate.getRange().getStart().getLine() == 0
                    && candidate.getRange().getStart().getCharacter() == 6));
        }
    }

    @Test
    void renameTypeAlsoRenamesImportSimpleNameMatches() throws Exception {
        String uri = "file:///RenameProviderTypeImports.groovy";
        String content = "import demo.Demo\nclass Demo {}\nnew Demo()\n";
        int selectionOffset = content.indexOf("Demo", content.indexOf("class"));
        int importOffset = content.indexOf("demo.Demo");
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        IType targetType = mock(IType.class);
        SearchPattern mainPattern = mock(SearchPattern.class);
        SearchPattern importPattern = mock(SearchPattern.class);
        IJavaSearchScope projectScope = mock(IJavaSearchScope.class);
        IJavaSearchScope workspaceScope = mock(IJavaSearchScope.class);
        IResource resource = mock(IResource.class);
        SearchMatch declarationMatch = mock(SearchMatch.class);
        SearchMatch importMatch = mock(SearchMatch.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, selectionOffset)).thenReturn(new IJavaElement[] { targetType });
        when(documentManager.remapToWorkingCopyElement(targetType)).thenReturn(targetType);
        when(documentManager.resolveResourceUri(resource)).thenReturn(uri);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(targetType.getElementName()).thenReturn("Demo");
        when(targetType.getMethods()).thenReturn(new IMethod[0]);
        when(declarationMatch.getResource()).thenReturn(resource);
        when(declarationMatch.getOffset()).thenReturn(selectionOffset);
        when(declarationMatch.getLength()).thenReturn(4);
        when(importMatch.getResource()).thenReturn(resource);
        when(importMatch.getOffset()).thenReturn(importOffset);
        when(importMatch.getLength()).thenReturn("demo.Demo".length());

        RenameProvider provider = new RenameProvider(documentManager);
        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri), new Position(1, 6), "RenamedDemo");

        try (MockedStatic<SearchPattern> searchPatternMock = org.mockito.Mockito.mockStatic(SearchPattern.class);
             MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchPatternMock.when(() -> SearchPattern.createPattern(targetType, IJavaSearchConstants.ALL_OCCURRENCES))
                    .thenReturn(mainPattern);
            searchPatternMock.when(() -> SearchPattern.createPattern(
                    targetType,
                    IJavaSearchConstants.REFERENCES,
                    SearchPattern.R_EXACT_MATCH))
                    .thenReturn(importPattern);
            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(
                    org.mockito.ArgumentMatchers.any(IJavaElement[].class),
                    org.mockito.ArgumentMatchers.eq(IJavaSearchScope.SOURCES)))
                    .thenReturn(projectScope);
            searchEngineMock.when(SearchEngine::createWorkspaceScope).thenReturn(workspaceScope);
            searchSupportMock.when(() -> JdtSearchSupport.search(
                    org.mockito.ArgumentMatchers.eq(mainPattern),
                    org.mockito.ArgumentMatchers.eq(projectScope),
                    org.mockito.ArgumentMatchers.any(SearchRequestor.class),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(declarationMatch);
                        return null;
                    });
            searchSupportMock.when(() -> JdtSearchSupport.search(
                    org.mockito.ArgumentMatchers.eq(importPattern),
                    org.mockito.ArgumentMatchers.eq(workspaceScope),
                    org.mockito.ArgumentMatchers.any(SearchRequestor.class),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(importMatch);
                        return null;
                    });

            WorkspaceEdit edit = provider.rename(params);

            assertNotNull(edit);
            List<TextEdit> edits = edit.getChanges().get(uri);
            assertNotNull(edits);
            assertTrue(edits.stream().anyMatch(candidate -> candidate.getRange().getStart().getLine() == 0
                    && candidate.getNewText().equals("RenamedDemo")));
            assertTrue(edits.stream().anyMatch(candidate -> candidate.getRange().getStart().getLine() == 1
                    && candidate.getNewText().equals("RenamedDemo")));
        }
    }

    @Test
    void documentHighlightsUseJdtSearchAndFilterForeignUris() throws Exception {
        String uri = "file:///DocumentHighlightProviderJdt.groovy";
        String otherUri = "file:///OtherDocumentHighlight.groovy";
        String content = "value = value\n";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaElement targetElement = mock(IJavaElement.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        IResource sameResource = mock(IResource.class);
        IResource docCommentResource = mock(IResource.class);
        IResource otherResource = mock(IResource.class);
        SearchMatch sameMatch = mock(SearchMatch.class);
        SearchMatch docCommentMatch = mock(SearchMatch.class);
        SearchMatch otherMatch = mock(SearchMatch.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 0)).thenReturn(new IJavaElement[] { targetElement });
        when(documentManager.remapToWorkingCopyElement(targetElement)).thenReturn(targetElement);
        when(documentManager.resolveResourceUri(sameResource)).thenReturn(uri);
        when(documentManager.resolveResourceUri(docCommentResource)).thenReturn(uri);
        when(documentManager.resolveResourceUri(otherResource)).thenReturn(otherUri);
        when(documentManager.getContent(otherUri)).thenReturn(content);
        when(sameMatch.getResource()).thenReturn(sameResource);
        when(sameMatch.getOffset()).thenReturn(0);
        when(sameMatch.getLength()).thenReturn(5);
        when(sameMatch.getAccuracy()).thenReturn(SearchMatch.A_ACCURATE);
        when(docCommentMatch.getResource()).thenReturn(docCommentResource);
        when(docCommentMatch.getOffset()).thenReturn(8);
        when(docCommentMatch.getLength()).thenReturn(5);
        when(docCommentMatch.isInsideDocComment()).thenReturn(true);
        when(otherMatch.getResource()).thenReturn(otherResource);
        when(otherMatch.getOffset()).thenReturn(0);
        when(otherMatch.getLength()).thenReturn(5);

        DocumentHighlightProvider provider = new DocumentHighlightProvider(documentManager);
        DocumentHighlightParams params = new DocumentHighlightParams(
                new TextDocumentIdentifier(uri), new Position(0, 0));

        try (MockedStatic<SearchPattern> searchPatternMock = org.mockito.Mockito.mockStatic(SearchPattern.class);
             MockedStatic<SearchEngine> searchEngineMock = org.mockito.Mockito.mockStatic(SearchEngine.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchPatternMock.when(() -> SearchPattern.createPattern(targetElement, IJavaSearchConstants.ALL_OCCURRENCES))
                    .thenReturn(pattern);
            searchEngineMock.when(() -> SearchEngine.createJavaSearchScope(org.mockito.ArgumentMatchers.any(IJavaElement[].class)))
                    .thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.search(
                    org.mockito.ArgumentMatchers.eq(pattern),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(SearchRequestor.class),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(sameMatch);
                        requestor.acceptSearchMatch(docCommentMatch);
                        requestor.acceptSearchMatch(otherMatch);
                        return null;
                    });

            List<DocumentHighlight> highlights = provider.getDocumentHighlights(params);

            assertEquals(2, highlights.size());
            assertTrue(highlights.stream().anyMatch(highlight -> highlight.getRange().getStart().getCharacter() == 0));
            assertTrue(highlights.stream().anyMatch(highlight -> highlight.getRange().getStart().getCharacter() == 8));
        }
    }

    @Test
    void referencesUseJdtSearchAndConvertMatchesToLocations() throws Exception {
        String uri = "file:///ReferenceProviderJdt.groovy";
        String otherUri = "file:///ReferenceProviderOther.groovy";
        String content = "value = call()\n";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        IJavaElement targetElement = mock(IJavaElement.class);
        SearchPattern pattern = mock(SearchPattern.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        IResource firstResource = mock(IResource.class);
        IResource secondResource = mock(IResource.class);
        SearchMatch firstMatch = mock(SearchMatch.class);
        SearchMatch secondMatch = mock(SearchMatch.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 0)).thenReturn(new IJavaElement[] { targetElement });
        when(documentManager.remapToWorkingCopyElement(targetElement)).thenReturn(targetElement);
        when(documentManager.resolveResourceUri(firstResource)).thenReturn(uri);
        when(documentManager.resolveResourceUri(secondResource)).thenReturn(otherUri);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(firstMatch.getResource()).thenReturn(firstResource);
        when(firstMatch.getOffset()).thenReturn(0);
        when(firstMatch.getLength()).thenReturn(5);
        when(secondMatch.getResource()).thenReturn(secondResource);
        when(secondMatch.getOffset()).thenReturn(2);
        when(secondMatch.getLength()).thenReturn(4);

        ReferenceProvider provider = new ReferenceProvider(documentManager);
        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier(uri), new Position(0, 0), new ReferenceContext(false));

        try (MockedStatic<SearchPattern> searchPatternMock = org.mockito.Mockito.mockStatic(SearchPattern.class);
             MockedStatic<SearchScopeHelper> searchScopeHelperMock = org.mockito.Mockito.mockStatic(SearchScopeHelper.class);
             MockedStatic<JdtSearchSupport> searchSupportMock = org.mockito.Mockito.mockStatic(JdtSearchSupport.class)) {

            searchPatternMock.when(() -> SearchPattern.createPattern(targetElement, IJavaSearchConstants.REFERENCES))
                    .thenReturn(pattern);
            searchScopeHelperMock.when(() -> SearchScopeHelper.createSourceScope(javaProject, uri)).thenReturn(scope);
            searchSupportMock.when(() -> JdtSearchSupport.search(
                    org.mockito.ArgumentMatchers.eq(pattern),
                    org.mockito.ArgumentMatchers.eq(scope),
                    org.mockito.ArgumentMatchers.any(SearchRequestor.class),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(firstMatch);
                        requestor.acceptSearchMatch(secondMatch);
                        return null;
                    });
            searchSupportMock.when(() -> JdtSearchSupport.readContent(
                    org.mockito.ArgumentMatchers.eq(documentManager),
                    org.mockito.ArgumentMatchers.eq(uri),
                    org.mockito.ArgumentMatchers.eq(firstResource),
                    org.mockito.ArgumentMatchers.anyMap()))
                    .thenReturn(content);
            searchSupportMock.when(() -> JdtSearchSupport.readContent(
                    org.mockito.ArgumentMatchers.eq(documentManager),
                    org.mockito.ArgumentMatchers.eq(otherUri),
                    org.mockito.ArgumentMatchers.eq(secondResource),
                    org.mockito.ArgumentMatchers.anyMap()))
                    .thenReturn(null);

            List<Location> locations = provider.getReferences(params);

                        assertEquals(1, locations.size());
                        assertEquals(uri, locations.get(0).getUri());
                        assertEquals(new Range(new Position(0, 0), new Position(0, 5)), locations.get(0).getRange());
        }
    }
}