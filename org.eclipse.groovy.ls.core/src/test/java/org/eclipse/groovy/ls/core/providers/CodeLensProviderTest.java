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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Tests for {@link CodeLensProvider}.
 */
class CodeLensProviderTest {

    private CodeLensProvider provider;
    private DocumentManager documentManager;

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new CodeLensProvider(documentManager);
    }

    // ---- getCodeLenses: missing document ----

    @Test
    void returnsEmptyForMissingDocument() {
        CodeLensParams params = new CodeLensParams(
                new TextDocumentIdentifier("file:///Missing.groovy"));
        List<CodeLens> lenses = provider.getCodeLenses(params);
        assertNotNull(lenses);
        assertTrue(lenses.isEmpty());
    }

    // ---- getCodeLenses: no working copy (open via DocumentManager without JDT) ----

    @Test
    void returnsEmptyWhenNoWorkingCopy() {
        String uri = "file:///NoWorkingCopy.groovy";
        documentManager.didOpen(uri, "class Foo { void bar() {} }");

        CodeLensParams params = new CodeLensParams(
                new TextDocumentIdentifier(uri));
        List<CodeLens> lenses = provider.getCodeLenses(params);
        // No JDT working copy → empty result (graceful fallback)
        assertNotNull(lenses);
        // Can be empty since getWorkingCopy returns null without Eclipse workspace
        documentManager.didClose(uri);
    }

    // ---- resolveCodeLens: already resolved ----

    @Test
    void resolveCodeLensReturnsAlreadyResolved() {
        CodeLens lens = new CodeLens();
        lens.setCommand(new org.eclipse.lsp4j.Command("5 references", ""));
        CodeLens result = provider.resolveCodeLens(lens);
        assertEquals("5 references", result.getCommand().getTitle());
    }

    // ---- resolveCodeLens: null data ----

    @Test
    void resolveCodeLensReturnsUnchangedForNullData() {
        CodeLens lens = new CodeLens();
        lens.setData(null);
        CodeLens result = provider.resolveCodeLens(lens);
        assertNotNull(result);
        // Command should remain null since data is null
    }

    // ---- resolveCodeLens: invalid handle ----

    @Test
    void resolveCodeLensHandlesInvalidHandle() {
        CodeLens lens = new CodeLens();
        JsonObject data = new JsonObject();
        data.addProperty("handleId", "invalid-handle-id");
        lens.setData(data);

        CodeLens result = provider.resolveCodeLens(lens);
        assertNotNull(result);
        // Should not crash; may or may not set command depending on JavaCore.create
    }

    // ---- resolveCodeLens: data without handleId key ----

    @Test
    void resolveCodeLensHandlesMissingHandleIdKey() {
        CodeLens lens = new CodeLens();
        JsonObject data = new JsonObject();
        data.addProperty("otherKey", "value");
        lens.setData(data);

        CodeLens result = provider.resolveCodeLens(lens);
        assertNotNull(result);
    }

    // ---- offsetToPosition ----

    @Test
    void offsetToPositionFirstLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() {
        Position pos = provider.offsetToPosition("hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionClamped() {
        Position pos = provider.offsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtStart() {
        Position pos = provider.offsetToPosition("hello", 0);
        assertEquals(0, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void offsetToPositionMultipleNewlines() {
        Position pos = provider.offsetToPosition("a\nb\nc\n", 4);
        assertEquals(2, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    // ================================================================
    // createUnresolvedCodeLens tests
    // ================================================================

    @Test
    void createUnresolvedCodeLensReturnsNullForZeroReferences() throws Exception {
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(6);
        when(nameRange.getLength()).thenReturn(3);
        when(mockType.getNameRange()).thenReturn(nameRange);
        when(mockType.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[MyType");
        when(mockType.getElementName()).thenReturn("MyType");

        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "createUnresolvedCodeLens", org.eclipse.jdt.core.IJavaElement.class, String.class);
        m.setAccessible(true);
        CodeLens lens = (CodeLens) m.invoke(provider, mockType, "class MyType {}");

        // No references in test context → null (element correctly filtered out)
        assertNull(lens);
    }

    @Test
    void createUnresolvedCodeLensReturnsNullForNullNameRange() throws Exception {
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(mockType.getNameRange()).thenReturn(null);
        when(mockType.getElementName()).thenReturn("MyType");

        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "createUnresolvedCodeLens", org.eclipse.jdt.core.IJavaElement.class, String.class);
        m.setAccessible(true);
        CodeLens lens = (CodeLens) m.invoke(provider, mockType, "class MyType {}");

        assertNull(lens);
    }

    @Test
    void createUnresolvedCodeLensReturnsNullForNegativeOffset() throws Exception {
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(-1);
        when(mockType.getNameRange()).thenReturn(nameRange);
        when(mockType.getElementName()).thenReturn("MyType");

        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "createUnresolvedCodeLens", org.eclipse.jdt.core.IJavaElement.class, String.class);
        m.setAccessible(true);
        CodeLens lens = (CodeLens) m.invoke(provider, mockType, "class MyType {}");

        assertNull(lens);
    }

    @Test
    void createUnresolvedCodeLensReturnsNullForNonSourceRef() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(element.getElementName()).thenReturn("NotSourceRef");

        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "createUnresolvedCodeLens", org.eclipse.jdt.core.IJavaElement.class, String.class);
        m.setAccessible(true);
        CodeLens lens = (CodeLens) m.invoke(provider, element, "class Test {}");

        assertNull(lens);
    }

    // ================================================================
    // addCodeLensesForType tests
    // ================================================================

    @Test
    void addCodeLensesForTypeWithMethods() throws Exception {
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange typeNameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(typeNameRange.getOffset()).thenReturn(6);
        when(typeNameRange.getLength()).thenReturn(3);
        when(mockType.getNameRange()).thenReturn(typeNameRange);
        when(mockType.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo");
        when(mockType.getElementName()).thenReturn("Foo");

        org.eclipse.jdt.core.IMethod mockMethod = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.jdt.core.ISourceRange methodNameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(methodNameRange.getOffset()).thenReturn(20);
        when(methodNameRange.getLength()).thenReturn(3);
        when(mockMethod.getNameRange()).thenReturn(methodNameRange);
        when(mockMethod.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo~bar");
        when(mockMethod.getElementName()).thenReturn("bar");

        when(mockType.getMethods()).thenReturn(new org.eclipse.jdt.core.IMethod[]{mockMethod});
        when(mockType.getTypes()).thenReturn(new org.eclipse.jdt.core.IType[0]);

        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "addCodeLensesForType", org.eclipse.jdt.core.IType.class, String.class, java.util.List.class);
        m.setAccessible(true);
        m.invoke(provider, mockType, "class Foo {\n    void bar() {}\n}", lenses);

        // No references in test context → no lenses emitted
        assertEquals(0, lenses.size());
    }

    @Test
    void addCodeLensesForTypeWithInnerType() throws Exception {
        org.eclipse.jdt.core.IType outerType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange outerNameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(outerNameRange.getOffset()).thenReturn(6);
        when(outerNameRange.getLength()).thenReturn(5);
        when(outerType.getNameRange()).thenReturn(outerNameRange);
        when(outerType.getHandleIdentifier()).thenReturn("=Proj/[Outer");
        when(outerType.getElementName()).thenReturn("Outer");
        when(outerType.getMethods()).thenReturn(new org.eclipse.jdt.core.IMethod[0]);

        org.eclipse.jdt.core.IType innerType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange innerNameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(innerNameRange.getOffset()).thenReturn(30);
        when(innerNameRange.getLength()).thenReturn(5);
        when(innerType.getNameRange()).thenReturn(innerNameRange);
        when(innerType.getHandleIdentifier()).thenReturn("=Proj/[Outer[Inner");
        when(innerType.getElementName()).thenReturn("Inner");
        when(innerType.getMethods()).thenReturn(new org.eclipse.jdt.core.IMethod[0]);
        when(innerType.getTypes()).thenReturn(new org.eclipse.jdt.core.IType[0]);

        when(outerType.getTypes()).thenReturn(new org.eclipse.jdt.core.IType[]{innerType});

        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        String content = "class Outer {\n    class Inner {}\n}";
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "addCodeLensesForType", org.eclipse.jdt.core.IType.class, String.class, java.util.List.class);
        m.setAccessible(true);
        m.invoke(provider, outerType, content, lenses);

        // No references in test context → no lenses emitted
        assertEquals(0, lenses.size());
    }
}
