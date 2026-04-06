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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class HoverProviderJdtFlowTest {

    @Test
    void getHoverUsesJdtAndCachesRepeatedOffsetLookups() throws Exception {
        String uri = "file:///HoverProviderJdtFlow.groovy";
        String content = "class Demo { void greet(String person) {} }";
        DocumentManager documentManager = mock(DocumentManager.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IType interfaceType = mock(IType.class);
        IType implType = mock(IType.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod interfaceMethod = mock(IMethod.class);
        IMethod overrideMethod = mock(IMethod.class);

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);

        when(interfaceType.getFullyQualifiedName()).thenReturn("demo.Greeter");
        when(interfaceType.isInterface()).thenReturn(true);

        when(interfaceMethod.getElementType()).thenReturn(IJavaElement.METHOD);
        when(interfaceMethod.getElementName()).thenReturn("greet");
        when(interfaceMethod.getFlags()).thenReturn(0);
        when(interfaceMethod.isConstructor()).thenReturn(false);
        when(interfaceMethod.getReturnType()).thenReturn("V");
        when(interfaceMethod.getParameterTypes()).thenReturn(new String[] { "QString;" });
        when(interfaceMethod.getParameterNames()).thenReturn(new String[] { "baseName" });
        when(interfaceMethod.getExceptionTypes()).thenReturn(new String[0]);
        when(interfaceMethod.getDeclaringType()).thenReturn(interfaceType);

        when(implType.getFullyQualifiedName()).thenReturn("demo.Demo");
        when(implType.isInterface()).thenReturn(false);
        when(implType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(implType)).thenReturn(new IType[] { interfaceType });

        when(overrideMethod.getElementType()).thenReturn(IJavaElement.METHOD);
        when(overrideMethod.getElementName()).thenReturn("greet");
        when(overrideMethod.getFlags()).thenReturn(0);
        when(overrideMethod.isConstructor()).thenReturn(false);
        when(overrideMethod.getReturnType()).thenReturn("V");
        when(overrideMethod.getParameterTypes()).thenReturn(new String[] { "QString;" });
        when(overrideMethod.getParameterNames()).thenReturn(new String[] { "person" });
        when(overrideMethod.getExceptionTypes()).thenReturn(new String[0]);
        when(overrideMethod.getDeclaringType()).thenReturn(implType);

        when(documentManager.cachedCodeSelect(workingCopy, 24))
                .thenReturn(new IJavaElement[] { interfaceMethod, overrideMethod });

        HoverProvider provider = new HoverProvider(documentManager);
        HoverParams params = new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 24));

        Hover first = provider.getHover(params);
        Hover second = provider.getHover(params);

        assertNotNull(first);
        assertNotNull(second);
        String hoverContent = first.getContents().getRight().getValue();
        assertTrue(hoverContent.contains("person"));
        assertTrue(hoverContent.contains("demo.Demo"));
        verify(documentManager, times(1)).cachedCodeSelect(workingCopy, 24);
    }
}