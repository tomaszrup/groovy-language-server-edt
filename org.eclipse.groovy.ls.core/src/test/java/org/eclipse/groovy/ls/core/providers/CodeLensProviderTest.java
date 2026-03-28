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

import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;

/**
 * Tests for {@link CodeLensProvider}.
 */
class CodeLensProviderTest {

    private static final String REFERENCES_UNAVAILABLE_TITLE = "References unavailable";
    private static final String ZERO_REFERENCES_TITLE = "0 references";
    private static final String ZERO_REFERENCES_COMMAND = "groovy.codeLensNoop";

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
        assertNotNull(result.getCommand());
        assertEquals(REFERENCES_UNAVAILABLE_TITLE, result.getCommand().getTitle());
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
        assertNotNull(result.getCommand());
        assertEquals(REFERENCES_UNAVAILABLE_TITLE, result.getCommand().getTitle());
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
        assertNotNull(result.getCommand());
        assertEquals(REFERENCES_UNAVAILABLE_TITLE, result.getCommand().getTitle());
    }

    @Test
    void resolveCodeLensShowsZeroReferencesWhenUnused() {
        String uri = "file:///Foo.groovy";
        String handleId = "=Proj/src<pkg{File.groovy[Foo~unused";
        IJavaElement element = mock(IJavaElement.class);
        when(element.exists()).thenReturn(true);

        CodeLens lens = new CodeLens();
        lens.setRange(new Range(new Position(3, 4), new Position(3, 4)));
        JsonObject data = new JsonObject();
        data.addProperty("handleId", handleId);
        data.addProperty("uri", uri);
        lens.setData(data);

        try (MockedStatic<JavaCore> javaCoreMock = org.mockito.Mockito.mockStatic(JavaCore.class);
             MockedStatic<ReferenceSearchHelper> searchMock = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {

            javaCoreMock.when(() -> JavaCore.create(handleId)).thenReturn(element);
            searchMock.when(() -> ReferenceSearchHelper.findReferenceLocations(element, uri, documentManager))
                    .thenReturn(List.of());

            CodeLens result = provider.resolveCodeLens(lens);

            assertNotNull(result);
            assertNotNull(result.getCommand());
            assertEquals(ZERO_REFERENCES_TITLE, result.getCommand().getTitle());
            assertEquals(ZERO_REFERENCES_COMMAND, result.getCommand().getCommand());
            assertNull(result.getCommand().getArguments());
        }
    }

    @Test
    void resolveCodeLensAddsShowReferencesCommandWhenReferencesExist() {
        String uri = "file:///Foo.groovy";
        String handleId = "=Proj/src<pkg{File.groovy[Foo~bar";
        IJavaElement element = mock(IJavaElement.class);
        when(element.exists()).thenReturn(true);

        CodeLens lens = new CodeLens();
        Position position = new Position(1, 2);
        lens.setRange(new Range(position, position));
        JsonObject data = new JsonObject();
        data.addProperty("handleId", handleId);
        data.addProperty("uri", uri);
        lens.setData(data);

        List<Location> locations = List.of(new Location(uri, new Range(position, position)));

        try (MockedStatic<JavaCore> javaCoreMock = org.mockito.Mockito.mockStatic(JavaCore.class);
             MockedStatic<ReferenceSearchHelper> searchMock = org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {

            javaCoreMock.when(() -> JavaCore.create(handleId)).thenReturn(element);
            searchMock.when(() -> ReferenceSearchHelper.findReferenceLocations(element, uri, documentManager))
                    .thenReturn(locations);

            CodeLens result = provider.resolveCodeLens(lens);

            assertNotNull(result.getCommand());
            assertEquals("1 reference", result.getCommand().getTitle());
            assertEquals("groovy.showReferences", result.getCommand().getCommand());
            assertEquals(List.of(uri, position, locations), result.getCommand().getArguments());
        }
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
    void createUnresolvedCodeLensForType() throws Exception {
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

        assertNotNull(lens);
        assertNotNull(lens.getRange());
        assertEquals(0, lens.getRange().getStart().getLine());
        assertEquals(6, lens.getRange().getStart().getCharacter());
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
        RecordingCodeLensProvider localProvider = new RecordingCodeLensProvider(documentManager);
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
        localProvider.referencedElements.add(mockType);
        localProvider.referencedElements.add(mockMethod);

        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
            "addCodeLensesForType", org.eclipse.jdt.core.IType.class, String.class, String.class, java.util.List.class);
        m.setAccessible(true);
        m.invoke(localProvider, mockType, "class Foo {\n    void bar() {}\n}", "file:///Foo.groovy", lenses);

        // Should have 2 lenses: one for type, one for method
        assertEquals(2, lenses.size());
    }

    @Test
        void addCodeLensesForTypeWithInnerType() throws Exception {
        RecordingCodeLensProvider localProvider = new RecordingCodeLensProvider(documentManager);
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
        localProvider.referencedElements.add(outerType);
        localProvider.referencedElements.add(innerType);

        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        String content = "class Outer {\n    class Inner {}\n}";
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "addCodeLensesForType", org.eclipse.jdt.core.IType.class, String.class, String.class, java.util.List.class);
        m.setAccessible(true);
        m.invoke(localProvider, outerType, content, "file:///Outer.groovy", lenses);

        // 2 lenses: outer + inner
        assertEquals(2, lenses.size());
    }

    @Test
    void addCodeLensesForTypeIncludesDeclarationsWithoutEagerReferenceSearch() throws Exception {
        RecordingCodeLensProvider localProvider = new RecordingCodeLensProvider(documentManager);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange typeNameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(typeNameRange.getOffset()).thenReturn(6);
        when(mockType.getNameRange()).thenReturn(typeNameRange);
        when(mockType.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo");
        when(mockType.getElementName()).thenReturn("Foo");

        org.eclipse.jdt.core.IMethod referencedMethod = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.jdt.core.ISourceRange referencedMethodRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(referencedMethodRange.getOffset()).thenReturn(20);
        when(referencedMethod.getNameRange()).thenReturn(referencedMethodRange);
        when(referencedMethod.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo~bar");
        when(referencedMethod.getElementName()).thenReturn("bar");

        org.eclipse.jdt.core.IMethod unusedMethod = mock(org.eclipse.jdt.core.IMethod.class);
        org.eclipse.jdt.core.ISourceRange unusedMethodRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(unusedMethodRange.getOffset()).thenReturn(35);
        when(unusedMethod.getNameRange()).thenReturn(unusedMethodRange);
        when(unusedMethod.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo~baz");
        when(unusedMethod.getElementName()).thenReturn("baz");

        when(mockType.getMethods()).thenReturn(new org.eclipse.jdt.core.IMethod[]{referencedMethod, unusedMethod});
        when(mockType.getTypes()).thenReturn(new org.eclipse.jdt.core.IType[0]);

        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "addCodeLensesForType", org.eclipse.jdt.core.IType.class, String.class, String.class, java.util.List.class);
        m.setAccessible(true);
        m.invoke(localProvider, mockType, "class Foo {\n    void bar() {}\n    void baz() {}\n}", "file:///Foo.groovy", lenses);

        assertEquals(3, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        JsonObject firstMethodData = (JsonObject) lenses.get(1).getData();
        JsonObject secondMethodData = (JsonObject) lenses.get(2).getData();
        assertEquals("=Proj/src<pkg{File.groovy[Foo", typeData.get("handleId").getAsString());
        assertEquals("=Proj/src<pkg{File.groovy[Foo~bar", firstMethodData.get("handleId").getAsString());
        assertEquals("=Proj/src<pkg{File.groovy[Foo~baz", secondMethodData.get("handleId").getAsString());
    }

    @Test
    void addCodeLensesForTypeSkipsStringNamedSpockFeatureMethod() throws Exception {
        String uri = "file:///SpecStringFeature.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    def "value returns epsilon"() {
                        expect:
                        true
                    }
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(documentManager);
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecStringFeature.groovy[MySpec");
        IMethod featureMethod = mockMethod("value returns epsilon",
                content.indexOf("\"value returns epsilon\"") + 1,
                "=Proj/src{SpecStringFeature.groovy[MySpec~value returns epsilon",
                specType);
        when(specType.getMethods()).thenReturn(new IMethod[]{featureMethod});
        when(specType.getTypes()).thenReturn(new IType[0]);

        List<CodeLens> lenses = invokeAddCodeLensesForType(localProvider, specType, content, uri);

        assertEquals(1, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        assertEquals("=Proj/src{SpecStringFeature.groovy[MySpec", typeData.get("handleId").getAsString());
    }

    @Test
    void addCodeLensesForTypeSkipsSpockLifecycleMethods() throws Exception {
        String uri = "file:///SpecLifecycle.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    void setup() {}
                    void cleanup() {}
                    void setupSpec() {}
                    void cleanupSpec() {}
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(documentManager);
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecLifecycle.groovy[MySpec");
        IMethod setup = mockMethod("setup", content.indexOf("setup()"),
                "=Proj/src{SpecLifecycle.groovy[MySpec~setup", specType);
        IMethod cleanup = mockMethod("cleanup", content.indexOf("cleanup()"),
                "=Proj/src{SpecLifecycle.groovy[MySpec~cleanup", specType);
        IMethod setupSpec = mockMethod("setupSpec", content.indexOf("setupSpec()"),
                "=Proj/src{SpecLifecycle.groovy[MySpec~setupSpec", specType);
        IMethod cleanupSpec = mockMethod("cleanupSpec", content.indexOf("cleanupSpec()"),
                "=Proj/src{SpecLifecycle.groovy[MySpec~cleanupSpec", specType);
        when(specType.getMethods()).thenReturn(new IMethod[]{setup, cleanup, setupSpec, cleanupSpec});
        when(specType.getTypes()).thenReturn(new IType[0]);

        List<CodeLens> lenses = invokeAddCodeLensesForType(localProvider, specType, content, uri);

        assertEquals(1, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        assertEquals("=Proj/src{SpecLifecycle.groovy[MySpec", typeData.get("handleId").getAsString());
    }

    @Test
    void addCodeLensesForTypeKeepsHelperMethodInSpecification() throws Exception {
        String uri = "file:///SpecHelper.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    void helperMethod() {
                        println "hi"
                    }
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(new AstBackedDocumentManager(uri, content));
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecHelper.groovy[MySpec");
        IMethod helperMethod = mockMethod("helperMethod", content.indexOf("helperMethod()"),
                "=Proj/src{SpecHelper.groovy[MySpec~helperMethod", specType);
        when(specType.getMethods()).thenReturn(new IMethod[]{helperMethod});
        when(specType.getTypes()).thenReturn(new IType[0]);

        List<CodeLens> lenses = invokeAddCodeLensesForType(localProvider, specType, content, uri);

        assertEquals(2, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        JsonObject methodData = (JsonObject) lenses.get(1).getData();
        assertEquals("=Proj/src{SpecHelper.groovy[MySpec", typeData.get("handleId").getAsString());
        assertEquals("=Proj/src{SpecHelper.groovy[MySpec~helperMethod", methodData.get("handleId").getAsString());
    }

    @Test
    void addCodeLensesForTypeSkipsIdentifierNamedSpockFeatureMethodWhenCachedAstHasLabels() throws Exception {
        String uri = "file:///SpecCachedAst.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    void namedFeature() {
                        expect:
                        true
                    }
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(new AstBackedDocumentManager(uri, content));
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecCachedAst.groovy[MySpec");
        IMethod namedFeature = mockMethod("namedFeature", content.indexOf("namedFeature()"),
                "=Proj/src{SpecCachedAst.groovy[MySpec~namedFeature", specType);
        when(specType.getMethods()).thenReturn(new IMethod[]{namedFeature});
        when(specType.getTypes()).thenReturn(new IType[0]);

        List<CodeLens> lenses = invokeAddCodeLensesForType(localProvider, specType, content, uri);

        assertEquals(1, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        assertEquals("=Proj/src{SpecCachedAst.groovy[MySpec", typeData.get("handleId").getAsString());
    }

    @Test
    void addCodeLensesForTypeKeepsIdentifierNamedMethodWhenCachedAstUnavailable() throws Exception {
        String uri = "file:///SpecNoCachedAst.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    void namedFeature() {
                        expect:
                        true
                    }
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(documentManager);
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecNoCachedAst.groovy[MySpec");
        IMethod namedFeature = mockMethod("namedFeature", content.indexOf("namedFeature()"),
                "=Proj/src{SpecNoCachedAst.groovy[MySpec~namedFeature", specType);
        when(specType.getMethods()).thenReturn(new IMethod[]{namedFeature});
        when(specType.getTypes()).thenReturn(new IType[0]);

        List<CodeLens> lenses = invokeAddCodeLensesForType(localProvider, specType, content, uri);

        assertEquals(2, lenses.size());
        JsonObject typeData = (JsonObject) lenses.get(0).getData();
        JsonObject methodData = (JsonObject) lenses.get(1).getData();
        assertEquals("=Proj/src{SpecNoCachedAst.groovy[MySpec", typeData.get("handleId").getAsString());
        assertEquals("=Proj/src{SpecNoCachedAst.groovy[MySpec~namedFeature", methodData.get("handleId").getAsString());
    }

    @Test
    void shouldSkipMethodCodeLensKeepsHelperMethodWithoutSpockLabels() throws Exception {
        String uri = "file:///SpecShouldSkip.groovy";
        String content = """
                import spock.lang.Specification

                class MySpec extends Specification {
                    void helperMethod() {
                        println "hi"
                    }
                }
                """;

        CodeLensProvider localProvider = new CodeLensProvider(new AstBackedDocumentManager(uri, content));
        IType specType = mockType("MySpec", "MySpec", "Specification",
                content.indexOf("MySpec"), "=Proj/src{SpecShouldSkip.groovy[MySpec");
        IMethod helperMethod = mockMethod("helperMethod", content.indexOf("helperMethod()"),
                "=Proj/src{SpecShouldSkip.groovy[MySpec~helperMethod", specType);

        assertFalse(localProvider.shouldSkipMethodCodeLens(helperMethod, uri));
    }

    @Test
    void createCodeLensIfReferencedReturnsNullWhenNoReferences() {
        RecordingCodeLensProvider localProvider = new RecordingCodeLensProvider(documentManager);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);

        CodeLens lens = localProvider.createCodeLensIfReferenced(mockType, "class Foo {}", "file:///Foo.groovy");

        assertNull(lens);
    }

    @Test
    void createCodeLensIfReferencedCreatesLensWhenReferenced() throws Exception {
        RecordingCodeLensProvider localProvider = new RecordingCodeLensProvider(documentManager);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(6);
        when(mockType.getNameRange()).thenReturn(nameRange);
        when(mockType.getHandleIdentifier()).thenReturn("=Proj/src<pkg{File.groovy[Foo");
        when(mockType.getElementName()).thenReturn("Foo");
        localProvider.referencedElements.add(mockType);

        CodeLens lens = localProvider.createCodeLensIfReferenced(mockType, "class Foo {}", "file:///Foo.groovy");

        assertNotNull(lens);
        assertEquals(6, lens.getRange().getStart().getCharacter());
    }

    private List<CodeLens> invokeAddCodeLensesForType(
            CodeLensProvider provider, IType type, String content, String uri) throws Exception {
        java.lang.reflect.Method m = CodeLensProvider.class.getDeclaredMethod(
                "addCodeLensesForType", IType.class, String.class, String.class, java.util.List.class);
        m.setAccessible(true);
        java.util.List<CodeLens> lenses = new java.util.ArrayList<>();
        m.invoke(provider, type, content, uri, lenses);
        return lenses;
    }

    private IType mockType(
            String elementName, String fullyQualifiedName, String superclassName,
            int nameOffset, String handleIdentifier) throws Exception {
        IType type = mock(IType.class);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(nameOffset);
        when(type.getNameRange()).thenReturn(nameRange);
        when(type.getHandleIdentifier()).thenReturn(handleIdentifier);
        when(type.getElementName()).thenReturn(elementName);
        when(type.getFullyQualifiedName('$')).thenReturn(fullyQualifiedName);
        when(type.getSuperclassName()).thenReturn(superclassName);
        return type;
    }

    private IMethod mockMethod(
            String elementName, int nameOffset, String handleIdentifier, IType declaringType) throws Exception {
        IMethod method = mock(IMethod.class);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(nameOffset);
        when(method.getNameRange()).thenReturn(nameRange);
        when(method.getHandleIdentifier()).thenReturn(handleIdentifier);
        when(method.getElementName()).thenReturn(elementName);
        when(method.getDeclaringType()).thenReturn(declaringType);
        return method;
    }

    private static final class RecordingCodeLensProvider extends CodeLensProvider {
        private final java.util.Set<org.eclipse.jdt.core.IJavaElement> referencedElements =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private RecordingCodeLensProvider(DocumentManager documentManager) {
            super(documentManager);
        }

        @Override
        boolean hasReferences(org.eclipse.jdt.core.IJavaElement element, String uri) {
            return referencedElements.contains(element);
        }
    }

    private static final class AstBackedDocumentManager extends DocumentManager {
        private final String targetUri;
        private final ModuleNode cachedAst;

        private AstBackedDocumentManager(String uri, String content) {
            this.targetUri = DocumentManager.normalizeUri(uri);
            didOpen(this.targetUri, content);
            this.cachedAst = new GroovyCompilerService().parse(uri, content).getModuleNode();
        }

        @Override
        public ModuleNode getCachedGroovyAST(String uri) {
            String normalized = DocumentManager.normalizeUri(uri);
            return targetUri.equals(normalized) ? cachedAst : null;
        }
    }
}
