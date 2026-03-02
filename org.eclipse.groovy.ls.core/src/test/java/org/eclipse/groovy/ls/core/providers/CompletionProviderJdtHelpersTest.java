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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

class CompletionProviderJdtHelpersTest {

    @Test
    void methodToCompletionItemBuildsSnippetAndDetails() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        IType owner = mock(IType.class);

        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "QInteger;"});
        when(method.getParameterNames()).thenReturn(new String[] {"name", "age"});
        when(method.getReturnType()).thenReturn("QString;");
        when(method.isConstructor()).thenReturn(false);
        when(owner.getElementName()).thenReturn("Person");

        CompletionItem item = invokeMethodToCompletionItem(provider, method, "greet", owner, "0");

        assertNotNull(item);
        assertEquals(CompletionItemKind.Method, item.getKind());
        assertTrue(item.getLabel().contains("greet("));
        assertTrue(item.getInsertText().contains("${1:name}"));
        assertTrue(item.getDetail().contains("Person"));
    }

    @Test
    void resolveElementTypeSupportsTypeFieldAndMethodElements() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);
        IType directType = mock(IType.class);

        when(project.findType("String")).thenReturn(resolvedType);

        IField field = mock(IField.class);
        when(field.getTypeSignature()).thenReturn("QString;");
        when(field.getDeclaringType()).thenReturn(null);

        IMethod method = mock(IMethod.class);
        when(method.getReturnType()).thenReturn("QString;");
        when(method.getDeclaringType()).thenReturn(null);

        assertSame(directType, invokeResolveElementType(provider, directType, project));
        assertSame(resolvedType, invokeResolveElementType(provider, field, project));
        assertSame(resolvedType, invokeResolveElementType(provider, method, project));
    }

    @Test
    void addMembersOfTypeIncludesHierarchyAndFiltersDuplicateMethods() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IType base = mock(IType.class);
        IType superType = mock(IType.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);

        when(base.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(base)).thenReturn(new IType[] {superType});
        when(base.getElementName()).thenReturn("Base");
        when(superType.getElementName()).thenReturn("SuperType");

        IMethod baseMethod = method("work", "QString;", new String[] {"QString;"}, new String[] {"task"}, false);
        IMethod duplicateSuperMethod = method("work", "QString;", new String[] {"QString;"}, new String[] {"task"}, false);
        IMethod superMethod = method("assist", "QInteger;", new String[0], new String[0], false);
        when(base.getMethods()).thenReturn(new IMethod[] {baseMethod});
        when(superType.getMethods()).thenReturn(new IMethod[] {duplicateSuperMethod, superMethod});

        IField baseField = field("value", "QString;", false);
        IField superField = field("count", "QInteger;", false);
        when(base.getFields()).thenReturn(new IField[] {baseField});
        when(superType.getFields()).thenReturn(new IField[] {superField});

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, base, "", false, items);

        assertTrue(items.stream().anyMatch(i -> "value".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "count".equals(i.getLabel())));
        assertEquals(1, items.stream().filter(i -> i.getLabel().startsWith("work(")).count());
        assertTrue(items.stream().anyMatch(i -> i.getLabel().startsWith("assist(")));
    }

    private CompletionItem invokeMethodToCompletionItem(
            CompletionProvider provider, IMethod method, String name, IType owner, String sortPrefix) throws Exception {
        Method privateMethod = CompletionProvider.class.getDeclaredMethod(
                "methodToCompletionItem", IMethod.class, String.class, IType.class, String.class);
        privateMethod.setAccessible(true);
        return (CompletionItem) privateMethod.invoke(provider, method, name, owner, sortPrefix);
    }

    private IType invokeResolveElementType(
            CompletionProvider provider, IJavaElement element, IJavaProject project) throws Exception {
        Method privateMethod = CompletionProvider.class.getDeclaredMethod(
                "resolveElementType", IJavaElement.class, IJavaProject.class);
        privateMethod.setAccessible(true);
        return (IType) privateMethod.invoke(provider, element, project);
    }

    private void invokeAddMembersOfType(
            CompletionProvider provider, IType type, String prefix, boolean staticOnly, List<CompletionItem> items)
            throws Exception {
        Method privateMethod = CompletionProvider.class.getDeclaredMethod(
                "addMembersOfType", IType.class, String.class, boolean.class, List.class);
        privateMethod.setAccessible(true);
        privateMethod.invoke(provider, type, prefix, staticOnly, items);
    }

    private IMethod method(String name, String returnType, String[] parameterTypes, String[] parameterNames,
                           boolean isConstructor) throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn(name);
        when(method.getReturnType()).thenReturn(returnType);
        when(method.getParameterTypes()).thenReturn(parameterTypes);
        when(method.getParameterNames()).thenReturn(parameterNames);
        when(method.getFlags()).thenReturn(0);
        when(method.isConstructor()).thenReturn(isConstructor);
        return method;
    }

    private IField field(String name, String typeSignature, boolean isStatic) throws Exception {
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn(name);
        when(field.getTypeSignature()).thenReturn(typeSignature);
        when(field.getFlags()).thenReturn(isStatic ? java.lang.reflect.Modifier.STATIC : 0);
        return field;
    }
}

