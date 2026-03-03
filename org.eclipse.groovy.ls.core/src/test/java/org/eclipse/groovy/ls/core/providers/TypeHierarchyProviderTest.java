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

import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TypeHierarchyProvider}.
 */
class TypeHierarchyProviderTest {

    private TypeHierarchyProvider provider;
    private DocumentManager documentManager;

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new TypeHierarchyProvider(documentManager);
    }

    // ---- prepareTypeHierarchy: missing document ----

    @Test
    void prepareReturnsEmptyForMissingDocument() {
        TypeHierarchyPrepareParams params = new TypeHierarchyPrepareParams(
                new TextDocumentIdentifier("file:///Missing.groovy"),
                new Position(0, 0));
        List<TypeHierarchyItem> items = provider.prepareTypeHierarchy(params);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    // ---- prepareTypeHierarchy: no working copy ----

    @Test
    void prepareReturnsEmptyWhenNoWorkingCopy() {
        String uri = "file:///NoWC.groovy";
        documentManager.didOpen(uri, """
                class Animal {}
                class Dog extends Animal {}
                """);

        TypeHierarchyPrepareParams params = new TypeHierarchyPrepareParams(
                new TextDocumentIdentifier(uri),
                new Position(0, 7)); // "Animal"
        List<TypeHierarchyItem> items = provider.prepareTypeHierarchy(params);
        assertNotNull(items);
        // Without JDT → empty
        assertTrue(items.isEmpty());

        documentManager.didClose(uri);
    }

    // ---- getSupertypes: item with empty data (no FQN) ----

    @Test
    void supertypesReturnsEmptyForEmptyData() {
        TypeHierarchyItem item = new TypeHierarchyItem(
                "Foo",
                org.eclipse.lsp4j.SymbolKind.Class,
                "file:///Foo.groovy",
                new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 10)),
                new org.eclipse.lsp4j.Range(new Position(0, 6), new Position(0, 9)));
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        item.setData(data);

        TypeHierarchySupertypesParams params = new TypeHierarchySupertypesParams(item);
        List<TypeHierarchyItem> supertypes = provider.getSupertypes(params);
        assertNotNull(supertypes);
        assertTrue(supertypes.isEmpty());
    }

    // ---- getSupertypes: item with null data ----

    @Test
    void supertypesReturnsEmptyForNullData() {
        TypeHierarchyItem item = new TypeHierarchyItem(
                "Foo",
                org.eclipse.lsp4j.SymbolKind.Class,
                "file:///Foo.groovy",
                new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 10)),
                new org.eclipse.lsp4j.Range(new Position(0, 6), new Position(0, 9)));
        item.setData(null);

        TypeHierarchySupertypesParams params = new TypeHierarchySupertypesParams(item);
        List<TypeHierarchyItem> supertypes = provider.getSupertypes(params);
        assertTrue(supertypes.isEmpty());
    }

    // ---- getSubtypes: item with empty data (no FQN) ----

    @Test
    void subtypesReturnsEmptyForEmptyData() {
        TypeHierarchyItem item = new TypeHierarchyItem(
                "Foo",
                org.eclipse.lsp4j.SymbolKind.Class,
                "file:///Foo.groovy",
                new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 10)),
                new org.eclipse.lsp4j.Range(new Position(0, 6), new Position(0, 9)));
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        item.setData(data);

        TypeHierarchySubtypesParams params = new TypeHierarchySubtypesParams(item);
        List<TypeHierarchyItem> subtypes = provider.getSubtypes(params);
        assertNotNull(subtypes);
        assertTrue(subtypes.isEmpty());
    }

    // ---- getSubtypes: item with null data ----

    @Test
    void subtypesReturnsEmptyForNullData() {
        TypeHierarchyItem item = new TypeHierarchyItem(
                "Bar",
                org.eclipse.lsp4j.SymbolKind.Class,
                "file:///Bar.groovy",
                new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 10)),
                new org.eclipse.lsp4j.Range(new Position(0, 6), new Position(0, 9)));
        item.setData(null);

        TypeHierarchySubtypesParams params = new TypeHierarchySubtypesParams(item);
        List<TypeHierarchyItem> subtypes = provider.getSubtypes(params);
        assertTrue(subtypes.isEmpty());
    }

    // ---- offsetToPosition / positionToOffset ----

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
    void positionToOffsetSecondLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(1, 2));
        assertEquals(8, offset);
    }

    @Test
    void offsetToPositionAtStart() {
        Position pos = provider.offsetToPosition("hello", 0);
        assertEquals(0, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void positionToOffsetClampedBeyondEnd() {
        int offset = provider.positionToOffset("hi", new Position(5, 5));
        assertTrue(offset <= 2);
    }

    // ================================================================
    // extractFqn tests
    // ================================================================

    @Test
    void extractFqnFromJsonObject() throws Exception {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("fqn", "com.example.Foo");
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("extractFqn", com.google.gson.JsonElement.class);
        m.setAccessible(true);
        assertEquals("com.example.Foo", m.invoke(provider, obj));
    }

    @Test
    void extractFqnFromJsonPrimitive() throws Exception {
        com.google.gson.JsonPrimitive prim = new com.google.gson.JsonPrimitive("com.example.Bar");
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("extractFqn", com.google.gson.JsonElement.class);
        m.setAccessible(true);
        assertEquals("com.example.Bar", m.invoke(provider, prim));
    }

    @Test
    void extractFqnFromEmptyObject() throws Exception {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("extractFqn", com.google.gson.JsonElement.class);
        m.setAccessible(true);
        Object result = m.invoke(provider, obj);
        // Should return null for object without fqn
        assertNull(result);
    }

    // ================================================================
    // resolveToType tests
    // ================================================================

    @Test
    void resolveToTypeFromIType() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("resolveToType", org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        Object result = m.invoke(provider, mockType);
        assertEquals(mockType, result);
    }

    @Test
    void resolveToTypeFromIMethod() throws Exception {
        org.eclipse.jdt.core.IMethod mockMethod = org.mockito.Mockito.mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockMethod.getDeclaringType()).thenReturn(mockType);
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("resolveToType", org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        Object result = m.invoke(provider, mockMethod);
        assertEquals(mockType, result);
    }

    // ================================================================
    // getTypeKind tests
    // ================================================================

    @Test
    void getTypeKindInterface() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(true);
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("getTypeKind", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        assertEquals(org.eclipse.lsp4j.SymbolKind.Interface, m.invoke(provider, mockType));
    }

    @Test
    void getTypeKindEnum() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(false);
        org.mockito.Mockito.when(mockType.isEnum()).thenReturn(true);
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("getTypeKind", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        assertEquals(org.eclipse.lsp4j.SymbolKind.Enum, m.invoke(provider, mockType));
    }

    @Test
    void getTypeKindClass() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(false);
        org.mockito.Mockito.when(mockType.isEnum()).thenReturn(false);
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("getTypeKind", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        assertEquals(org.eclipse.lsp4j.SymbolKind.Class, m.invoke(provider, mockType));
    }

    // ================================================================
    // toRange tests
    // ================================================================

    @Test
    void toRangeConvertsOffset() throws Exception {
        org.eclipse.jdt.core.ISourceRange sourceRange = org.mockito.Mockito.mock(org.eclipse.jdt.core.ISourceRange.class);
        org.mockito.Mockito.when(sourceRange.getOffset()).thenReturn(6);
        org.mockito.Mockito.when(sourceRange.getLength()).thenReturn(3);
        String content = "hello\nworld";
        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("toRange", String.class, org.eclipse.jdt.core.ISourceRange.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Range range = (org.eclipse.lsp4j.Range) m.invoke(provider, content, sourceRange);
        assertNotNull(range);
        // offset 6 = line 1, char 0 ("world" starts at offset 6)
        assertEquals(1, range.getStart().getLine());
    }

    // ================================================================
    // buildTypeHierarchyItem tests
    // ================================================================

    @Test
    void buildTypeHierarchyItemWithResource() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///TypeHier.groovy"));
        org.mockito.Mockito.when(mockType.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(mockType.getElementName()).thenReturn("MyClass");
        org.mockito.Mockito.when(mockType.getFullyQualifiedName()).thenReturn("com.example.MyClass");
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(false);
        org.mockito.Mockito.when(mockType.isEnum()).thenReturn(false);
        org.mockito.Mockito.when(mockType.getSourceRange()).thenReturn(null);
        org.mockito.Mockito.when(mockType.getNameRange()).thenReturn(null);

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("buildTypeHierarchyItem", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.TypeHierarchyItem item = (org.eclipse.lsp4j.TypeHierarchyItem) m.invoke(provider, mockType);

        assertNotNull(item);
        assertEquals("MyClass", item.getName());
        assertEquals(org.eclipse.lsp4j.SymbolKind.Class, item.getKind());
        assertEquals("com.example", item.getDetail());
    }

    @Test
    void buildTypeHierarchyItemForInterface() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///TypeHierIface.groovy"));
        org.mockito.Mockito.when(mockType.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(mockType.getElementName()).thenReturn("Runnable");
        org.mockito.Mockito.when(mockType.getFullyQualifiedName()).thenReturn("Runnable");
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(true);
        org.mockito.Mockito.when(mockType.getSourceRange()).thenReturn(null);
        org.mockito.Mockito.when(mockType.getNameRange()).thenReturn(null);

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("buildTypeHierarchyItem", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.TypeHierarchyItem item = (org.eclipse.lsp4j.TypeHierarchyItem) m.invoke(provider, mockType);

        assertNotNull(item);
        assertEquals(org.eclipse.lsp4j.SymbolKind.Interface, item.getKind());
    }

    @Test
    void buildTypeHierarchyItemForEnum() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///TypeHierEnum.groovy"));
        org.mockito.Mockito.when(mockType.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(mockType.getElementName()).thenReturn("Color");
        org.mockito.Mockito.when(mockType.getFullyQualifiedName()).thenReturn("com.Color");
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(false);
        org.mockito.Mockito.when(mockType.isEnum()).thenReturn(true);
        org.mockito.Mockito.when(mockType.getSourceRange()).thenReturn(null);
        org.mockito.Mockito.when(mockType.getNameRange()).thenReturn(null);

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("buildTypeHierarchyItem", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.TypeHierarchyItem item = (org.eclipse.lsp4j.TypeHierarchyItem) m.invoke(provider, mockType);

        assertNotNull(item);
        assertEquals(org.eclipse.lsp4j.SymbolKind.Enum, item.getKind());
    }

    @Test
    void buildTypeHierarchyItemReturnsNullForNoResource() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.getResource()).thenReturn(null);
        org.mockito.Mockito.when(mockType.getCompilationUnit()).thenReturn(null);
        org.mockito.Mockito.when(mockType.getElementName()).thenReturn("Orphan");

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("buildTypeHierarchyItem", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.TypeHierarchyItem item = (org.eclipse.lsp4j.TypeHierarchyItem) m.invoke(provider, mockType);

        assertNull(item);
    }

    @Test
    void buildTypeHierarchyItemWithSourceRangeAndContent() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.core.resources.IResource resource = org.mockito.Mockito.mock(org.eclipse.core.resources.IResource.class);
        org.mockito.Mockito.when(resource.getLocationURI()).thenReturn(new java.net.URI("file:///TypeHierRange.groovy"));
        org.mockito.Mockito.when(mockType.getResource()).thenReturn(resource);
        org.mockito.Mockito.when(mockType.getElementName()).thenReturn("Ranged");
        org.mockito.Mockito.when(mockType.getFullyQualifiedName()).thenReturn("Ranged");
        org.mockito.Mockito.when(mockType.isInterface()).thenReturn(false);
        org.mockito.Mockito.when(mockType.isEnum()).thenReturn(false);
        org.eclipse.jdt.core.ISourceRange srcRange = org.mockito.Mockito.mock(org.eclipse.jdt.core.ISourceRange.class);
        org.mockito.Mockito.when(srcRange.getOffset()).thenReturn(0);
        org.mockito.Mockito.when(srcRange.getLength()).thenReturn(10);
        org.mockito.Mockito.when(mockType.getSourceRange()).thenReturn(srcRange);
        org.mockito.Mockito.when(mockType.getNameRange()).thenReturn(srcRange);

        // Open the document so getContent returns non-null
        String uri = "file:///TypeHierRange.groovy";
        documentManager.didOpen(uri, "class Ranged {}");

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("buildTypeHierarchyItem", org.eclipse.jdt.core.IType.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.TypeHierarchyItem item = (org.eclipse.lsp4j.TypeHierarchyItem) m.invoke(provider, mockType);

        assertNotNull(item);
        assertEquals("Ranged", item.getName());
        documentManager.didClose(uri);
    }

    // ================================================================
    // getTypeRange tests
    // ================================================================

    @Test
    void getTypeRangeReturnsDefaultForNullSourceRange() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.getSourceRange()).thenReturn(null);

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod("getTypeRange", org.eclipse.jdt.core.IType.class, String.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Range range = (org.eclipse.lsp4j.Range) m.invoke(provider, mockType, "file:///test.groovy");

        assertNotNull(range);
        assertEquals(0, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
    }

    // ================================================================
    // getTypeSelectionRange tests
    // ================================================================

    @Test
    void getTypeSelectionRangeReturnsFallbackForNullNameRange() throws Exception {
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(mockType.getNameRange()).thenReturn(null);
        org.eclipse.lsp4j.Range fallback = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(5, 10), new org.eclipse.lsp4j.Position(5, 20));

        java.lang.reflect.Method m = TypeHierarchyProvider.class.getDeclaredMethod(
                "getTypeSelectionRange", org.eclipse.jdt.core.IType.class, String.class, org.eclipse.lsp4j.Range.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Range range = (org.eclipse.lsp4j.Range) m.invoke(provider, mockType, "file:///test.groovy", fallback);

        assertEquals(fallback, range);
    }
}
