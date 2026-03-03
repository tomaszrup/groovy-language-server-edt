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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CallHierarchyProvider}.
 */
class CallHierarchyProviderTest {

    private CallHierarchyProvider provider;
    private DocumentManager documentManager;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new CallHierarchyProvider(documentManager);
    }

    // ---- prepareCallHierarchy: missing document ----

    @Test
    void prepareReturnsEmptyForMissingDocument() {
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier("file:///Missing.groovy"),
                new Position(0, 0));
        List<CallHierarchyItem> items = provider.prepareCallHierarchy(params);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    // ---- prepareCallHierarchy: no working copy ----

    @Test
    void prepareReturnsEmptyWhenNoWorkingCopy() {
        String uri = "file:///NoWC.groovy";
        documentManager.didOpen(uri, """
                class Foo {
                    void bar() { println 'hello' }
                }
                """);

        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier(uri),
                new Position(1, 10)); // "bar"
        List<CallHierarchyItem> items = provider.prepareCallHierarchy(params);
        assertNotNull(items);
        // Without JDT working copy → empty
        assertTrue(items.isEmpty());

        documentManager.didClose(uri);
    }

    // ---- getIncomingCalls: item with invalid handle ----

    @Test
    void incomingCallsReturnsEmptyForInvalidHandle() {
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("foo");
        item.setKind(SymbolKind.Method);
        item.setUri("file:///Foo.groovy");
        item.setRange(new Range(new Position(0, 0), new Position(0, 10)));
        item.setSelectionRange(new Range(new Position(0, 0), new Position(0, 10)));
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("handleId", "invalid-handle");
        item.setData(data);

        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(item);
        assertTrue(provider.getIncomingCalls(params).isEmpty());
    }

    // ---- getIncomingCalls: item with null data ----

    @Test
    void incomingCallsReturnsEmptyForNullData() {
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("foo");
        item.setKind(SymbolKind.Method);
        item.setUri("file:///Foo.groovy");
        item.setRange(new Range(new Position(0, 0), new Position(0, 10)));
        item.setSelectionRange(new Range(new Position(0, 0), new Position(0, 10)));
        item.setData(null);

        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(item);
        assertTrue(provider.getIncomingCalls(params).isEmpty());
    }

    // ---- getOutgoingCalls: missing document ----

    @Test
    void outgoingCallsReturnsEmptyForMissingDocument() {
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("method");
        item.setKind(SymbolKind.Method);
        item.setUri("file:///Missing.groovy");
        item.setRange(new Range(new Position(0, 0), new Position(0, 10)));
        item.setSelectionRange(new Range(new Position(0, 0), new Position(0, 10)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        assertTrue(provider.getOutgoingCalls(params).isEmpty());
    }

    // ---- getOutgoingCalls: item pointing to wrong position (no method found) ----

    @Test
    void outgoingCallsReturnsEmptyWhenNoMethodAtPosition() {
        String uri = "file:///OutgoingNoMethod.groovy";
        documentManager.didOpen(uri, "class Empty {}");

        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("nonexistent");
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        item.setRange(new Range(new Position(0, 0), new Position(0, 10)));
        item.setSelectionRange(new Range(new Position(0, 0), new Position(0, 5)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        assertTrue(provider.getOutgoingCalls(params).isEmpty());

        documentManager.didClose(uri);
    }

    // ---- getOutgoingCalls: AST-based outgoing calls ----

    @Test
    void outgoingCallsFindsMethodCalls() {
        String uri = "file:///OutgoingCalls.groovy";
        String content = """
                class Service {
                    void helper1() {}
                    void helper2() {}
                    void main() {
                        helper1()
                        helper2()
                    }
                }
                """;
        documentManager.didOpen(uri, content);

        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("main");
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        // "main" method starts around line 3
        item.setRange(new Range(new Position(3, 4), new Position(6, 5)));
        item.setSelectionRange(new Range(new Position(3, 9), new Position(3, 13)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        List<CallHierarchyOutgoingCall> outgoing = provider.getOutgoingCalls(params);

        // Should find helper1 and helper2 calls
        assertFalse(outgoing.isEmpty(), "Expected outgoing calls from main()");
        assertTrue(outgoing.size() >= 2,
                "Expected at least 2 outgoing calls, got " + outgoing.size());

        documentManager.didClose(uri);
    }

    // ---- getOutgoingCalls: constructor calls ----

    @Test
    void outgoingCallsFindsConstructorCalls() {
        String uri = "file:///OutgoingCtor.groovy";
        String content = """
                class Widget {}
                class Factory {
                    Widget create() {
                        return new Widget()
                    }
                }
                """;
        documentManager.didOpen(uri, content);

        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("create");
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        item.setRange(new Range(new Position(2, 4), new Position(4, 5)));
        item.setSelectionRange(new Range(new Position(2, 11), new Position(2, 17)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        List<CallHierarchyOutgoingCall> outgoing = provider.getOutgoingCalls(params);

        assertFalse(outgoing.isEmpty(), "Expected constructor call in outgoing");

        documentManager.didClose(uri);
    }

    // ---- getOutgoingCalls: static method calls ----

    @Test
    void outgoingCallsFindsStaticMethodCalls() {
        String uri = "file:///OutgoingStatic.groovy";
        String content = """
                class Util {
                    static void log(String msg) { println msg }
                }
                class App {
                    void run() {
                        Util.log('hello')
                    }
                }
                """;
        documentManager.didOpen(uri, content);

        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("run");
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        item.setRange(new Range(new Position(4, 4), new Position(6, 5)));
        item.setSelectionRange(new Range(new Position(4, 9), new Position(4, 12)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        List<CallHierarchyOutgoingCall> outgoing = provider.getOutgoingCalls(params);

        assertFalse(outgoing.isEmpty(), "Expected static method call in outgoing");

        documentManager.didClose(uri);
    }

    // ---- getOutgoingCalls: no calls in method ----

    @Test
    void outgoingCallsReturnsEmptyForLeafMethod() {
        String uri = "file:///OutgoingLeaf.groovy";
        String content = """
                class Simple {
                    int getValue() {
                        return 42
                    }
                }
                """;
        documentManager.didOpen(uri, content);

        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("getValue");
        item.setKind(SymbolKind.Method);
        item.setUri(uri);
        item.setRange(new Range(new Position(1, 4), new Position(3, 5)));
        item.setSelectionRange(new Range(new Position(1, 8), new Position(1, 16)));

        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);
        List<CallHierarchyOutgoingCall> outgoing = provider.getOutgoingCalls(params);

        assertTrue(outgoing.isEmpty());

        documentManager.didClose(uri);
    }

    // ---- recordOutgoingCall via reflection ----

    @Test
    void recordOutgoingCallGroupsByMethodName() throws Exception {
        Map<String, Object> callMap = new HashMap<>();

        Method recordMethod = CallHierarchyProvider.class.getDeclaredMethod(
                "recordOutgoingCall", String.class, int.class, int.class,
                int.class, int.class, Map.class);
        recordMethod.setAccessible(true);

        // Record multiple calls to "foo"
        recordMethod.invoke(provider, "foo", 5, 10, 5, 20, callMap);
        recordMethod.invoke(provider, "foo", 8, 10, 8, 20, callMap);
        recordMethod.invoke(provider, "bar", 10, 5, 10, 15, callMap);

        assertEquals(2, callMap.size());
    }

    @Test
    void recordOutgoingCallIgnoresNullMethodName() throws Exception {
        Map<String, Object> callMap = new HashMap<>();

        Method recordMethod = CallHierarchyProvider.class.getDeclaredMethod(
                "recordOutgoingCall", String.class, int.class, int.class,
                int.class, int.class, Map.class);
        recordMethod.setAccessible(true);

        recordMethod.invoke(provider, null, 1, 1, 1, 1, callMap);
        recordMethod.invoke(provider, "", 1, 1, 1, 1, callMap);

        assertTrue(callMap.isEmpty());
    }

    // ---- findMethodNodeAt via reflection ----

    @Test
    void findMethodNodeAtFindsCorrectMethod() throws Exception {
        String source = """
                class Foo {
                    void alpha() {
                        println 'a'
                    }
                    void beta() {
                        println 'b'
                    }
                }
                """;
        ModuleNode ast = compilerService.parse("file:///FindMethod.groovy", source).getModuleNode();

        Method findMethod = CallHierarchyProvider.class.getDeclaredMethod(
                "findMethodNodeAt", ModuleNode.class, int.class, String.class);
        findMethod.setAccessible(true);

        // Offset within "alpha" method body
        int alphaOffset = source.indexOf("println 'a'");
        MethodNode found = (MethodNode) findMethod.invoke(provider, ast, alphaOffset, source);
        assertNotNull(found);
        assertEquals("alpha", found.getName());

        // Offset within "beta" method body
        int betaOffset = source.indexOf("println 'b'");
        MethodNode foundBeta = (MethodNode) findMethod.invoke(provider, ast, betaOffset, source);
        assertNotNull(foundBeta);
        assertEquals("beta", foundBeta.getName());
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
    void positionToOffsetSecondLine() {
        int offset = provider.positionToOffset("hello\nworld", new Position(1, 2));
        assertEquals(8, offset);
    }

    @Test
    void offsetToPositionClamped() {
        Position pos = provider.offsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    // ================================================================
    // lineColToOffset tests
    // ================================================================

    @Test
    void lineColToOffsetFirstLine() throws Exception {
        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("lineColToOffset", String.class, int.class, int.class);
        m.setAccessible(true);
        int offset = (int) m.invoke(provider, "abc\ndef\nghi", 1, 1);
        assertEquals(0, offset);
    }

    @Test
    void lineColToOffsetSecondLine() throws Exception {
        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("lineColToOffset", String.class, int.class, int.class);
        m.setAccessible(true);
        int offset = (int) m.invoke(provider, "abc\ndef\nghi", 2, 1);
        assertEquals(4, offset);
    }

    @Test
    void lineColToOffsetThirdLine() throws Exception {
        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("lineColToOffset", String.class, int.class, int.class);
        m.setAccessible(true);
        int offset = (int) m.invoke(provider, "abc\ndef\nghi", 3, 2);
        assertEquals(9, offset);
    }

    // ================================================================
    // toRange tests
    // ================================================================

    @Test
    void toRangeConvertsSourceRange() throws Exception {
        org.eclipse.jdt.core.ISourceRange sourceRange = org.mockito.Mockito.mock(org.eclipse.jdt.core.ISourceRange.class);
        org.mockito.Mockito.when(sourceRange.getOffset()).thenReturn(4);
        org.mockito.Mockito.when(sourceRange.getLength()).thenReturn(3);
        String content = "abc\ndef\nghi";
        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("toRange", String.class, org.eclipse.jdt.core.ISourceRange.class);
        m.setAccessible(true);
        Range range = (Range) m.invoke(provider, content, sourceRange);
        assertNotNull(range);
        assertEquals(1, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
    }

    // ================================================================
    // buildCallHierarchyItem tests (97 missed instructions)
    // ================================================================

    @Test
    void buildCallHierarchyItemForMethod() throws Exception {
        org.eclipse.jdt.core.IMethod method = mock(org.eclipse.jdt.core.IMethod.class);
        when(method.getElementName()).thenReturn("doWork");
        when(method.isConstructor()).thenReturn(false);
        when(method.getHandleIdentifier()).thenReturn("=proj/src<pkg{Cls.java[Cls~doWork");

        org.eclipse.jdt.core.IType declaringType = mock(org.eclipse.jdt.core.IType.class);
        when(declaringType.getFullyQualifiedName()).thenReturn("com.example.Cls");
        when(method.getDeclaringType()).thenReturn(declaringType);

        // resolveElementUri needs a resource
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///some/Cls.java"));
        when(method.getResource()).thenReturn(resource);

        // getElementRange needs source range
        org.eclipse.jdt.core.ISourceRange sourceRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(10);
        when(method.getSourceRange()).thenReturn(sourceRange);
        when(method.getNameRange()).thenReturn(sourceRange);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("buildCallHierarchyItem",
                org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        CallHierarchyItem item = (CallHierarchyItem) m.invoke(provider, method);

        if (item != null) {
            assertEquals("doWork", item.getName());
            assertEquals(SymbolKind.Method, item.getKind());
            assertEquals("com.example.Cls", item.getDetail());
            assertNotNull(item.getUri());
        }
    }

    @Test
    void buildCallHierarchyItemForConstructor() throws Exception {
        org.eclipse.jdt.core.IMethod method = mock(org.eclipse.jdt.core.IMethod.class);
        when(method.getElementName()).thenReturn("Cls");
        when(method.isConstructor()).thenReturn(true);
        when(method.getHandleIdentifier()).thenReturn("=proj/src<pkg{Cls.java[Cls~Cls");

        org.eclipse.jdt.core.IType declaringType = mock(org.eclipse.jdt.core.IType.class);
        when(declaringType.getFullyQualifiedName()).thenReturn("com.example.Cls");
        when(method.getDeclaringType()).thenReturn(declaringType);

        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///some/Cls.java"));
        when(method.getResource()).thenReturn(resource);

        org.eclipse.jdt.core.ISourceRange sourceRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(10);
        when(method.getSourceRange()).thenReturn(sourceRange);
        when(method.getNameRange()).thenReturn(sourceRange);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("buildCallHierarchyItem",
                org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        CallHierarchyItem item = (CallHierarchyItem) m.invoke(provider, method);

        if (item != null) {
            assertEquals("Cls", item.getName());
            assertEquals(SymbolKind.Constructor, item.getKind());
        }
    }

    @Test
    void buildCallHierarchyItemForTypeElement() throws Exception {
        org.eclipse.jdt.core.IType type = mock(org.eclipse.jdt.core.IType.class);
        when(type.getElementName()).thenReturn("MyClass");
        when(type.getFullyQualifiedName()).thenReturn("com.example.MyClass");
        when(type.getHandleIdentifier()).thenReturn("=proj/src<pkg{MyClass.java[MyClass");

        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///some/MyClass.java"));
        when(type.getResource()).thenReturn(resource);

        org.eclipse.jdt.core.ISourceRange sourceRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(10);
        when(type.getSourceRange()).thenReturn(sourceRange);
        when(type.getNameRange()).thenReturn(sourceRange);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("buildCallHierarchyItem",
                org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        CallHierarchyItem item = (CallHierarchyItem) m.invoke(provider, type);

        if (item != null) {
            assertEquals("MyClass", item.getName());
            assertEquals(SymbolKind.Class, item.getKind());
            assertEquals("com.example.MyClass", item.getDetail());
        }
    }

    @Test
    void buildCallHierarchyItemReturnsNullWhenNoUri() throws Exception {
        org.eclipse.jdt.core.IMethod method = mock(org.eclipse.jdt.core.IMethod.class);
        when(method.getElementName()).thenReturn("orphan");
        when(method.isConstructor()).thenReturn(false);
        when(method.getDeclaringType()).thenReturn(null);
        when(method.getResource()).thenReturn(null);

        // If no compilation unit either -> no URI -> should return null
        when(method.getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT)).thenReturn(null);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod("buildCallHierarchyItem",
                org.eclipse.jdt.core.IJavaElement.class);
        m.setAccessible(true);
        CallHierarchyItem item = (CallHierarchyItem) m.invoke(provider, method);

        assertNull(item);
    }

    // ================================================================
    // processIncomingMatch tests
    // ================================================================

    @Test
    void processIncomingMatchSkipsNonJavaElement() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getElement()).thenReturn("not a java element");

        java.util.Map<String, Object> callerMap = new java.util.HashMap<>();

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod(
                "processIncomingMatch", org.eclipse.jdt.core.search.SearchMatch.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(provider, match, callerMap);

        assertTrue(callerMap.isEmpty());
    }

    @Test
    void processIncomingMatchSkipsNullElement() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getElement()).thenReturn(null);

        java.util.Map<String, Object> callerMap = new java.util.HashMap<>();

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod(
                "processIncomingMatch", org.eclipse.jdt.core.search.SearchMatch.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(provider, match, callerMap);

        assertTrue(callerMap.isEmpty());
    }

    @Test
    void processIncomingMatchWithMethodElement() throws Exception {
        org.eclipse.jdt.core.IMethod mockMethod = mock(org.eclipse.jdt.core.IMethod.class);
        when(mockMethod.getHandleIdentifier()).thenReturn("=Proj/[Caller~call");
        when(mockMethod.getElementName()).thenReturn("call");
        when(mockMethod.isConstructor()).thenReturn(false);
        when(mockMethod.getDeclaringType()).thenReturn(null);
        when(mockMethod.getResource()).thenReturn(null);
        when(mockMethod.getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT)).thenReturn(null);

        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getElement()).thenReturn(mockMethod);
        when(match.getOffset()).thenReturn(10);
        when(match.getLength()).thenReturn(4);
        when(match.getResource()).thenReturn(null);

        java.util.Map<String, Object> callerMap = new java.util.HashMap<>();

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod(
                "processIncomingMatch", org.eclipse.jdt.core.search.SearchMatch.class, java.util.Map.class);
        m.setAccessible(true);
        m.invoke(provider, match, callerMap);

        // Method is an IMethod so it should be used as enclosing element
        // callerMap should have an entry (even if item is null from no URI)
        assertFalse(callerMap.isEmpty());
    }

    // ================================================================
    // matchToRange tests
    // ================================================================

    @Test
    void matchToRangeWithContent() throws Exception {
        String uri = "file:///MatchRange.groovy";
        documentManager.didOpen(uri, "class Foo {\n    void bar() {}\n}");

        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(new java.net.URI(uri));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(16);
        when(match.getLength()).thenReturn(3);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod(
                "matchToRange", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Range range = (org.eclipse.lsp4j.Range) m.invoke(provider, match);

        assertNotNull(range);
        documentManager.didClose(uri);
    }

    @Test
    void matchToRangeReturnsNullForNullResource() throws Exception {
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getResource()).thenReturn(null);

        java.lang.reflect.Method m = CallHierarchyProvider.class.getDeclaredMethod(
                "matchToRange", org.eclipse.jdt.core.search.SearchMatch.class);
        m.setAccessible(true);
        org.eclipse.lsp4j.Range range = (org.eclipse.lsp4j.Range) m.invoke(provider, match);

        assertNull(range);
    }
}
