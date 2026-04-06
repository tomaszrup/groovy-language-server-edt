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

import com.google.gson.JsonObject;

import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class HierarchyProvidersJdtFlowTest {

    @Test
    void prepareTypeHierarchyBuildsItemFromSelectedMethodDeclaringType() throws Exception {
        String uri = "file:///TypeHierarchy.groovy";
        String content = "class Demo { void greet() {} }";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IMethod method = mock(IMethod.class);
        IType type = mock(IType.class);
        ISourceRange sourceRange = mock(ISourceRange.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 18)).thenReturn(new IJavaElement[] { method });
        when(documentManager.remapToWorkingCopyElement(method)).thenReturn(null);
        when(method.getDeclaringType()).thenReturn(type);
        when(documentManager.resolveElementUri(type)).thenReturn(uri);
        when(documentManager.remapToWorkingCopyElement(type)).thenReturn(null);
        when(type.getElementName()).thenReturn("Demo");
        when(type.getFullyQualifiedName()).thenReturn("demo.Demo");
        when(type.getSourceRange()).thenReturn(sourceRange);
        when(type.getNameRange()).thenReturn(nameRange);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(content.length());
        when(nameRange.getOffset()).thenReturn(6);
        when(nameRange.getLength()).thenReturn(4);

        TypeHierarchyProvider provider = new TypeHierarchyProvider(documentManager);
        List<TypeHierarchyItem> items = provider.prepareTypeHierarchy(
                new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(0, 18)));

        assertEquals(1, items.size());
        assertEquals("Demo", items.get(0).getName());
        assertEquals(SymbolKind.Class, items.get(0).getKind());
        assertEquals("demo", items.get(0).getDetail());
    }

    @Test
    void prepareTypeHierarchyBuildsItemFromSelectedFieldDeclaringType() throws Exception {
        String uri = "file:///TypeHierarchyField.groovy";
        String content = "class Demo { String value }";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IField field = mock(IField.class);
        IType type = mock(IType.class);
        ISourceRange sourceRange = mock(ISourceRange.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 20)).thenReturn(new IJavaElement[] { field });
        when(documentManager.remapToWorkingCopyElement(field)).thenReturn(null);
        when(field.getDeclaringType()).thenReturn(type);
        when(documentManager.resolveElementUri(type)).thenReturn(uri);
        when(documentManager.remapToWorkingCopyElement(type)).thenReturn(null);
        when(type.getElementName()).thenReturn("Demo");
        when(type.getFullyQualifiedName()).thenReturn("demo.Demo");
        when(type.getSourceRange()).thenReturn(sourceRange);
        when(type.getNameRange()).thenReturn(nameRange);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(content.length());
        when(nameRange.getOffset()).thenReturn(6);
        when(nameRange.getLength()).thenReturn(4);

        TypeHierarchyProvider provider = new TypeHierarchyProvider(documentManager);
        List<TypeHierarchyItem> items = provider.prepareTypeHierarchy(
                new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(0, 20)));

        assertEquals(1, items.size());
        assertEquals("Demo", items.get(0).getName());
    }

    @Test
    void prepareCallHierarchyBuildsMethodItemFromSelectedMethod() throws Exception {
        String uri = "file:///CallHierarchy.groovy";
        String content = "class Demo { void greet() {} }";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IMethod method = mock(IMethod.class);
        IType type = mock(IType.class);
        ISourceRange sourceRange = mock(ISourceRange.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, 18)).thenReturn(new IJavaElement[] { method });
        when(documentManager.remapToWorkingCopyElement(method)).thenReturn(null);
        when(documentManager.resolveElementUri(method)).thenReturn(uri);
        when(method.getElementName()).thenReturn("greet");
        when(method.isConstructor()).thenReturn(false);
        when(method.getDeclaringType()).thenReturn(type);
        when(type.getFullyQualifiedName()).thenReturn("demo.Demo");
        when(method.getHandleIdentifier()).thenReturn("demo#greet");
        when(method.getSourceRange()).thenReturn(sourceRange);
        when(method.getNameRange()).thenReturn(nameRange);
        when(sourceRange.getOffset()).thenReturn(13);
        when(sourceRange.getLength()).thenReturn(14);
        when(nameRange.getOffset()).thenReturn(18);
        when(nameRange.getLength()).thenReturn(5);
        when(documentManager.getContent(uri)).thenReturn(content);

        CallHierarchyProvider provider = new CallHierarchyProvider(documentManager);
        List<CallHierarchyItem> items = provider.prepareCallHierarchy(
                new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), new Position(0, 18)));

        assertEquals(1, items.size());
        assertEquals("greet", items.get(0).getName());
        assertEquals(SymbolKind.Method, items.get(0).getKind());
        assertEquals("demo.Demo", items.get(0).getDetail());
    }

    @Test
    void getIncomingCallsGroupsJdtMatchesByCaller() throws Exception {
        String callerUri = "file:///Caller.groovy";
        String callerContent = "class Caller { void call() { greet() } }";
        DocumentManager documentManager = mock(DocumentManager.class);
        IMethod targetMethod = mock(IMethod.class);
        IJavaElement matchElement = mock(IJavaElement.class);
        IMethod callerMethod = mock(IMethod.class);
        IType callerType = mock(IType.class);
        IResource resource = mock(IResource.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);
        SearchPattern pattern = mock(SearchPattern.class);
        SearchMatch match = mock(SearchMatch.class);
        ISourceRange sourceRange = mock(ISourceRange.class);
        ISourceRange nameRange = mock(ISourceRange.class);
        JsonObject data = new JsonObject();
        data.addProperty("handleId", "target#handle");
        CallHierarchyItem item = new CallHierarchyItem();
        item.setData(data);

        when(match.getElement()).thenReturn(matchElement);
        when(matchElement.getParent()).thenReturn(callerMethod);
        when(callerMethod.getHandleIdentifier()).thenReturn("caller#call");
        when(callerMethod.getElementName()).thenReturn("call");
        when(callerMethod.isConstructor()).thenReturn(false);
        when(callerMethod.getDeclaringType()).thenReturn(callerType);
        when(callerType.getFullyQualifiedName()).thenReturn("demo.Caller");
        when(documentManager.resolveElementUri(callerMethod)).thenReturn(callerUri);
        when(documentManager.remapToWorkingCopyElement(callerMethod)).thenReturn(null);
        when(callerMethod.getSourceRange()).thenReturn(sourceRange);
        when(callerMethod.getNameRange()).thenReturn(nameRange);
        when(sourceRange.getOffset()).thenReturn(15);
        when(sourceRange.getLength()).thenReturn(22);
        when(nameRange.getOffset()).thenReturn(20);
        when(nameRange.getLength()).thenReturn(4);
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(29);
        when(match.getLength()).thenReturn(5);
        when(documentManager.getContent(callerUri)).thenReturn(callerContent);

        try (MockedStatic<SearchPattern> searchPattern = Mockito.mockStatic(SearchPattern.class);
                MockedStatic<SearchEngine> searchEngine = Mockito.mockStatic(SearchEngine.class);
                MockedStatic<JdtSearchSupport> searchSupport = Mockito.mockStatic(JdtSearchSupport.class);
                MockedStatic<org.eclipse.jdt.core.JavaCore> javaCore = Mockito.mockStatic(org.eclipse.jdt.core.JavaCore.class)) {
            javaCore.when(() -> org.eclipse.jdt.core.JavaCore.create("target#handle")).thenReturn(targetMethod);
            searchPattern.when(() -> SearchPattern.createPattern(targetMethod, IJavaSearchConstants.REFERENCES))
                    .thenReturn(pattern);
            searchEngine.when(SearchEngine::createWorkspaceScope).thenReturn(scope);
            searchSupport.when(() -> JdtSearchSupport.resolveResourceUri(documentManager, resource)).thenReturn(callerUri);
            searchSupport.when(() -> JdtSearchSupport.search(eq(pattern), eq(scope), any(SearchRequestor.class), eq(null)))
                    .thenAnswer(invocation -> {
                        SearchRequestor requestor = invocation.getArgument(2);
                        requestor.acceptSearchMatch(match);
                        return null;
                    });

            CallHierarchyProvider provider = new CallHierarchyProvider(documentManager);
            List<CallHierarchyIncomingCall> calls = provider.getIncomingCalls(new CallHierarchyIncomingCallsParams(item));

            assertEquals(1, calls.size());
            assertEquals("call", calls.get(0).getFrom().getName());
            assertTrue(calls.get(0).getFromRanges().size() == 1);
        }
    }
}