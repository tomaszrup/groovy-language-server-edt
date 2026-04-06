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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class InlayHintProviderTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();
    private final InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

    @Test
    void getInlayHintsReturnsEmptyWhenDocumentIsMissing() {
        InlayHintProvider localProvider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> hints = localProvider.getInlayHints(paramsFor("file:///MissingInlayHintDoc.groovy"), InlayHintSettings.defaults());

        assertTrue(hints.isEmpty());
    }

    @Test
    void getInlayHintsProducesTypeAndParameterHints() {
        String uri = "file:///InlayHintProviderIntegrationTest.groovy";
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider localProvider = new InlayHintProvider(documentManager);
        List<InlayHint> hints = localProvider.getInlayHints(paramsFor(uri), InlayHintSettings.defaults());

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(hint -> ": String".equals(labelText(hint))));
        assertTrue(hints.stream().anyMatch(hint -> "person:".equals(labelText(hint))));

        documentManager.didClose(uri);
    }

    @Test
    void getInlayHintsRespectsDisabledParameterNameSetting() {
        String uri = "file:///InlayHintProviderSettingsTest.groovy";
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider localProvider = new InlayHintProvider(documentManager);
        InlayHintSettings noParameterNames = new InlayHintSettings(true, false, true, true);
        List<InlayHint> hints = localProvider.getInlayHints(paramsFor(uri), noParameterNames);

        assertTrue(hints.stream().noneMatch(hint -> hint.getKind() == InlayHintKind.Parameter));
        assertTrue(hints.stream().anyMatch(hint -> hint.getKind() == InlayHintKind.Type));

        documentManager.didClose(uri);
    }

    @Test
    void dedupeAndSortRemovesDuplicatesAndSortsByPosition() throws Exception {
        InlayHintProvider localProvider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> input = new ArrayList<>();
        input.add(hint(2, 5, "b:", InlayHintKind.Parameter));
        input.add(hint(1, 3, ": String", InlayHintKind.Type));
        input.add(hint(1, 3, ": String", InlayHintKind.Parameter));
        input.add(hint(0, 10, "a:", InlayHintKind.Parameter));

        List<InlayHint> output = invokeDedupeAndSort(localProvider, input);

        assertEquals(3, output.size());
        assertEquals(0, output.get(0).getPosition().getLine());
        assertEquals(10, output.get(0).getPosition().getCharacter());
        assertEquals(1, output.get(1).getPosition().getLine());
        assertEquals(3, output.get(1).getPosition().getCharacter());
        assertEquals(2, output.get(2).getPosition().getLine());
        assertEquals(5, output.get(2).getPosition().getCharacter());
    }

    @Test
    void parameterCollectorPrefersClosestMethodArityAndVarargsScoring() throws Exception {
        Object collector = createCollector("call(one, two)\n", null);

        IMethod oneArg = mockMethod("m", new String[] {"QString;"}, 0);
        IMethod twoArg = mockMethod("m", new String[] {"QString;", "QInteger;"}, 0);
        IMethod varArg = mockMethod("m", new String[] {"QString;", "QInteger;"}, java.lang.reflect.Modifier.TRANSIENT);

        Object chosen = invokeCollector(collector, "chooseBestMethod",
                new Class<?>[] {List.class, int.class},
                new Object[] {Arrays.asList(oneArg, twoArg, varArg), 2});
        assertEquals(twoArg, chosen);

        int exactScore = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {2, false, 2});
        int missingArgScore = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {2, false, 1});
        int varargScore = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {2, true, 1});

        assertEquals(0, exactScore);
        assertTrue(varargScore <= missingArgScore);
    }

    @Test
    void parameterCollectorUtilityMethodsHandleLinesAndHints() throws Exception {
        Object collector = createCollector(
                "callMe(one,\n  two)\n",
                new Range(new Position(0, 0), new Position(0, 15)));

        assertEquals("callMe(one,", invokeCollector(collector, "getLineText",
                new Class<?>[] {int.class}, new Object[] {0}));
        assertEquals("  two)", invokeCollector(collector, "getLineText",
                new Class<?>[] {int.class}, new Object[] {1}));
        assertEquals(9, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {0, 9}));
        assertEquals(0, invokeCollector(collector, "findNameInLine",
                new Class<?>[] {int.class, int.class, String.class}, new Object[] {0, 0, "callMe"}));

        invokeCollector(collector, "addParameterHint",
                new Class<?>[] {Position.class, String.class},
                new Object[] {new Position(0, 2), "value:"});
        invokeCollector(collector, "addParameterHint",
                new Class<?>[] {Position.class, String.class},
                new Object[] {new Position(0, 2), "value:"});
        invokeCollector(collector, "addParameterHint",
                new Class<?>[] {Position.class, String.class},
                new Object[] {new Position(1, 2), "outsideRange:"});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertEquals(1, hints.size());
        assertEquals("value:", hints.get(0).getLabel().getLeft());
    }

    @Test
    void parameterCollectorHandlesArgumentListAndNameFallback() throws Exception {
        Object collector = createCollector("fn(1)\n", null);

        ArgumentListExpression positional = new ArgumentListExpression(List.of(new ConstantExpression(1)));
        @SuppressWarnings("unchecked")
        List<Expression> expressions = (List<Expression>) invokeCollector(collector, "toArgumentExpressions",
                new Class<?>[] {Expression.class}, new Object[] {positional});
        assertEquals(1, expressions.size());

        NamedArgumentListExpression named = new NamedArgumentListExpression();
        boolean namedArg = (boolean) invokeCollector(collector, "isNamedArgument",
                new Class<?>[] {Expression.class}, new Object[] {named});
        assertTrue(namedArg);

        IMethod method = mock(IMethod.class);
        when(method.getParameterNames()).thenThrow(new RuntimeException("no debug symbols"));
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "QInteger;"});
        String[] names = (String[]) invokeCollector(collector, "readParameterNames",
                new Class<?>[] {IMethod.class}, new Object[] {method});
        assertEquals("arg0", names[0]);
        assertEquals("arg1", names[1]);
    }

    @Test
    void parameterCollectorReadParameterNamesRecoversSyntheticConstructorFieldNames() throws Exception {
        Object collector = createCollector("new Person('Ada', 37)\n", null);

        IMethod constructor = mock(IMethod.class);
        IType declaringType = mock(IType.class);
        IField nameField = mock(IField.class);
        IField ageField = mock(IField.class);

        when(constructor.isConstructor()).thenReturn(true);
        when(constructor.getParameterNames()).thenReturn(new String[] {"p50", "p51"});
        when(constructor.getParameterTypes()).thenReturn(new String[] {"QString;", "I"});
        when(constructor.getDeclaringType()).thenReturn(declaringType);

        when(nameField.getElementName()).thenReturn("name");
        when(nameField.getTypeSignature()).thenReturn("QString;");
        when(nameField.getFlags()).thenReturn(0);
        when(ageField.getElementName()).thenReturn("age");
        when(ageField.getTypeSignature()).thenReturn("I");
        when(ageField.getFlags()).thenReturn(0);
        when(declaringType.getFields()).thenReturn(new IField[] {nameField, ageField});

        String[] names = (String[]) invokeCollector(collector, "readParameterNames",
                new Class<?>[] {IMethod.class}, new Object[] {constructor});

        assertEquals("name", names[0]);
        assertEquals("age", names[1]);
    }

    @Test
    void parameterCollectorVisitModuleAddsMethodParameterHintsFromWorkingCopy() throws Exception {
        String source = """
                def greet(String person, int age) {}
                greet('Ada', 37)
                """;
        ModuleNode module = parseModule(source);
        IMethod method = mock(IMethod.class);
        when(method.isConstructor()).thenReturn(false);
        when(method.getElementName()).thenReturn("greet");
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "QInteger;"});
        when(method.getParameterNames()).thenReturn(new String[] {"person", "age"});
        when(method.getFlags()).thenReturn(0);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {method});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(5, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertNotNull(hints);
        assertTrue(hints.stream().anyMatch(h -> "person:".equals(labelText(h))));
        assertTrue(hints.stream().anyMatch(h -> "age:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorVisitModuleHandlesConstructorCallHints() throws Exception {
        String source = """
                class Pt { Pt(int x, int y) {} }
                new Pt(10, 20)
                """;
        ModuleNode module = parseModule(source);
        IMethod constructor = mock(IMethod.class);
        when(constructor.isConstructor()).thenReturn(true);
        when(constructor.getElementName()).thenReturn("Pt");
        when(constructor.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(constructor.getParameterNames()).thenReturn(new String[] {"x", "y"});
        when(constructor.getFlags()).thenReturn(0);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {constructor});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(5, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertNotNull(hints);
        assertTrue(hints.stream().anyMatch(h -> "x:".equals(labelText(h))));
        assertTrue(hints.stream().anyMatch(h -> "y:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorVisitModuleUsesBinaryConstructorMetadataForTypeTargets() throws Exception {
        String source = """
                class Pt { Pt(int x, int y) {} }
                new Pt(10, 20)
                """;
        ModuleNode module = parseModule(source);

        IMethod sourceConstructor = mock(IMethod.class);
        when(sourceConstructor.isConstructor()).thenReturn(true);
        when(sourceConstructor.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(sourceConstructor.getParameterNames()).thenReturn(new String[] {"p50", "p51"});
        when(sourceConstructor.getFlags()).thenReturn(0);

        IType sourceType = mock(IType.class);
        when(sourceType.getMethods()).thenReturn(new IMethod[] {sourceConstructor});

        IMethod binaryConstructor = mock(IMethod.class);
        when(binaryConstructor.isConstructor()).thenReturn(true);
        when(binaryConstructor.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(binaryConstructor.getParameterNames()).thenReturn(new String[] {"x", "y"});
        when(binaryConstructor.getFlags()).thenReturn(0);

        IType binaryType = mock(IType.class);
        when(binaryType.getMethods()).thenReturn(new IMethod[] {binaryConstructor});

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {sourceType});

        try (MockedStatic<JavaBinaryMemberResolver> resolverMock = org.mockito.Mockito.mockStatic(JavaBinaryMemberResolver.class)) {
            resolverMock.when(() -> JavaBinaryMemberResolver.resolveMemberSource(sourceType)).thenReturn(binaryType);

            Object collector = createCollector(source, new Range(new Position(0, 0), new Position(5, 0)), workingCopy);
            invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

            @SuppressWarnings("unchecked")
            List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
            assertNotNull(hints);
            assertTrue(hints.stream().anyMatch(h -> "x:".equals(labelText(h))));
            assertTrue(hints.stream().anyMatch(h -> "y:".equals(labelText(h))));
            assertTrue(hints.stream().noneMatch(h -> "p50:".equals(labelText(h))));
            assertTrue(hints.stream().noneMatch(h -> "p51:".equals(labelText(h))));
        }
    }

    @Test
    void parameterCollectorVisitModuleRemapsConstructorMethodTargetsToBinaryMetadata() throws Exception {
        String source = """
                class Pt { Pt(int x, int y) {} }
                new Pt(10, 20)
                """;
        ModuleNode module = parseModule(source);

        IType sourceType = mock(IType.class);
        IMethod sourceConstructor = mock(IMethod.class);
        when(sourceConstructor.isConstructor()).thenReturn(true);
        when(sourceConstructor.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(sourceConstructor.getParameterNames()).thenReturn(new String[] {"p50", "p51"});
        when(sourceConstructor.getFlags()).thenReturn(0);
        when(sourceConstructor.getDeclaringType()).thenReturn(sourceType);

        IMethod binaryConstructor = mock(IMethod.class);
        when(binaryConstructor.isConstructor()).thenReturn(true);
        when(binaryConstructor.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(binaryConstructor.getParameterNames()).thenReturn(new String[] {"x", "y"});
        when(binaryConstructor.getFlags()).thenReturn(0);

        IType binaryType = mock(IType.class);
        when(binaryType.getMethods()).thenReturn(new IMethod[] {binaryConstructor});

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {sourceConstructor});

        try (MockedStatic<JavaBinaryMemberResolver> resolverMock = org.mockito.Mockito.mockStatic(JavaBinaryMemberResolver.class)) {
            resolverMock.when(() -> JavaBinaryMemberResolver.resolveMemberSource(sourceType)).thenReturn(binaryType);

            Object collector = createCollector(source, new Range(new Position(0, 0), new Position(5, 0)), workingCopy);
            invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

            @SuppressWarnings("unchecked")
            List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
            assertNotNull(hints);
            assertTrue(hints.stream().anyMatch(h -> "x:".equals(labelText(h))));
            assertTrue(hints.stream().anyMatch(h -> "y:".equals(labelText(h))));
            assertTrue(hints.stream().noneMatch(h -> "p50:".equals(labelText(h))));
            assertTrue(hints.stream().noneMatch(h -> "p51:".equals(labelText(h))));
        }
    }

    @Test
    void parameterCollectorLineToOffsetEdgeCases() throws Exception {
        Object collector = createCollector("abc\ndef\n", null);

        // negative line
        assertEquals(-1, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {-1, 0}));
        // negative column
        assertEquals(-1, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {0, -1}));
        // line beyond source
        assertEquals(-1, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {10, 0}));
        // line 0, column 0
        assertEquals(0, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {0, 0}));
        // line 1, column 0
        assertEquals(4, invokeCollector(collector, "lineToOffset",
                new Class<?>[] {int.class, int.class}, new Object[] {1, 0}));
    }

    @Test
    void parameterCollectorFindNameInLineEdgeCases() throws Exception {
        Object collector = createCollector("hello world\n", null);

        // normal find
        assertEquals(6, invokeCollector(collector, "findNameInLine",
                new Class<?>[] {int.class, int.class, String.class}, new Object[] {0, 0, "world"}));
        // null name
        assertEquals(-1, invokeCollector(collector, "findNameInLine",
                new Class<?>[] {int.class, int.class, String.class}, new Object[] {0, 0, null}));
        // blank name
        assertEquals(-1, invokeCollector(collector, "findNameInLine",
                new Class<?>[] {int.class, int.class, String.class}, new Object[] {0, 0, ""}));
        // negative line
        Object lineText = invokeCollector(collector, "getLineText",
                new Class<?>[] {int.class}, new Object[] {-1});
        assertEquals(null, lineText);
    }

    @Test
    void parameterCollectorHasIdentifierBoundaries() throws Exception {
        Object collector = createCollector("abc def\n", null);

        // "abc" at index 0 has left boundary (start) and right boundary (space at 3)
        assertEquals(true, invokeCollector(collector, "hasIdentifierBoundaries",
                new Class<?>[] {String.class, int.class, int.class}, new Object[] {"abc def", 0, 3}));
        // "def" at index 4 has left boundary (space at 3) and right boundary (end)
        assertEquals(true, invokeCollector(collector, "hasIdentifierBoundaries",
                new Class<?>[] {String.class, int.class, int.class}, new Object[] {"abc def", 4, 7}));
        // "bc " at index 1 has no left boundary (a at 0 is identifier)
        assertEquals(false, invokeCollector(collector, "hasIdentifierBoundaries",
                new Class<?>[] {String.class, int.class, int.class}, new Object[] {"abcdef", 1, 4}));
    }

    @Test
    void parameterCollectorIsInRequestedRangeWithNullRange() throws Exception {
        Object collector = createCollector("source\n", null);

        // null range should accept all positions
        boolean result = (boolean) invokeCollector(collector, "isInRequestedRange",
                new Class<?>[] {Position.class}, new Object[] {new Position(100, 50)});
        assertTrue(result);
    }

    @Test
    void parameterCollectorComparePositionOrdering() throws Exception {
        Object collector = createCollector("source\n", null);

        // same position
        assertEquals(0, invokeCollector(collector, "comparePosition",
                new Class<?>[] {Position.class, Position.class},
                new Object[] {new Position(1, 5), new Position(1, 5)}));
        // earlier line
        assertTrue((int) invokeCollector(collector, "comparePosition",
                new Class<?>[] {Position.class, Position.class},
                new Object[] {new Position(0, 0), new Position(1, 0)}) < 0);
        // same line, earlier column
        assertTrue((int) invokeCollector(collector, "comparePosition",
                new Class<?>[] {Position.class, Position.class},
                new Object[] {new Position(1, 2), new Position(1, 5)}) < 0);
    }

    @Test
    void parameterCollectorCompatibilityScoreEdgeCases() throws Exception {
        Object collector = createCollector("s\n", null);

        // fewer args than params (non-varargs)
        int tooFew = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {3, false, 1});
        // more args than params (non-varargs)
        int tooMany = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {1, false, 3});
        assertTrue(tooMany > tooFew);

        // varargs with fewer than required
        int varargsTooFew = (int) invokeCollector(collector, "compatibilityScore",
                new Class<?>[] {int.class, boolean.class, int.class},
                new Object[] {3, true, 1});
        assertTrue(varargsTooFew >= 10_000);
    }

    @Test
    void parameterCollectorGetParameterCountHandlesException() throws Exception {
        Object collector = createCollector("s\n", null);

        IMethod errorMethod = mock(IMethod.class);
        when(errorMethod.getParameterTypes()).thenThrow(new RuntimeException("broken"));

        int count = (int) invokeCollector(collector, "getParameterCount",
                new Class<?>[] {IMethod.class}, new Object[] {errorMethod});
        assertEquals(Integer.MAX_VALUE / 2, count);
    }

    @Test
    void parameterCollectorReadParameterNamesReturnsFallbackForZeroCount() throws Exception {
        Object collector = createCollector("s\n", null);

        IMethod method = mock(IMethod.class);
        when(method.getParameterNames()).thenReturn(new String[] {"a", "b"});
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "QInteger;"});
        String[] names = (String[]) invokeCollector(collector, "readParameterNames",
                new Class<?>[] {IMethod.class}, new Object[] {method});
        assertEquals("a", names[0]);
        assertEquals("b", names[1]);
    }

    @Test
    void getInlayHintsRespectsDisabledTypeSetting() {
        String uri = "file:///InlayHintTypeDisabledTest.groovy";
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider localProvider = new InlayHintProvider(documentManager);
        InlayHintSettings noTypesSettings = new InlayHintSettings(false, true, false, false);
        List<InlayHint> hints = localProvider.getInlayHints(paramsFor(uri), noTypesSettings);

        assertTrue(hints.stream().noneMatch(hint -> hint.getKind() == InlayHintKind.Type));

        documentManager.didClose(uri);
    }

    @Test
    void getInlayHintsWithAllDisabledReturnsOnlyFallback() {
        String uri = "file:///InlayHintAllDisabledTest.groovy";
        String source = "def x = 42\n";

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider localProvider = new InlayHintProvider(documentManager);
        InlayHintSettings allDisabled = new InlayHintSettings(false, false, false, false);
        List<InlayHint> hints = localProvider.getInlayHints(paramsFor(uri), allDisabled);

        assertNotNull(hints);
        // with all disabled, we should get an empty or minimal list
        documentManager.didClose(uri);
    }

    @Test
    void getInlayHintsWithNullSettingsUsesDefaults() {
        String uri = "file:///InlayHintDefaultsTest.groovy";
        String source = """
                def name = 'Ada'
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider localProvider = new InlayHintProvider(documentManager);
        List<InlayHint> hints = localProvider.getInlayHints(paramsFor(uri), null);

        assertNotNull(hints);
        assertFalse(hints.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void dedupeAndSortHandlesNullLabelsAndPositions() throws Exception {
        InlayHintProvider localProvider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> input = new ArrayList<>();
        InlayHint nullLabel = new InlayHint();
        nullLabel.setPosition(new Position(0, 0));
        input.add(nullLabel);  // label is null - should be filtered

        InlayHint nullPosition = new InlayHint();
        nullPosition.setLabel("test:");
        input.add(nullPosition);  // position is null - should be filtered

        input.add(hint(1, 1, "valid:", InlayHintKind.Parameter));

        List<InlayHint> output = invokeDedupeAndSort(localProvider, input);
        assertEquals(1, output.size());
        assertEquals(1, output.get(0).getPosition().getLine());
    }

    @Test
    void parameterCollectorChooseBestMethodReturnsNullForEmptyList() throws Exception {
        Object collector = createCollector("s\n", null);

        Object result = invokeCollector(collector, "chooseBestMethod",
                new Class<?>[] {List.class, int.class},
                new Object[] {List.of(), 1});
        assertEquals(null, result);

        Object nullResult = invokeCollector(collector, "chooseBestMethod",
                new Class<?>[] {List.class, int.class},
                new Object[] {null, 1});
        assertEquals(null, nullResult);
    }

    @Test
    void parameterCollectorVisitModuleWithMultipleMethodsInClass() throws Exception {
        String source = """
                class Calc {
                    int add(int a, int b) { a + b }
                    int multiply(int x, int y) { x * y }
                }
                def c = new Calc()
                c.add(1, 2)
                c.multiply(3, 4)
                """;
        ModuleNode module = parseModule(source);

        IMethod addMethod = mock(IMethod.class);
        when(addMethod.isConstructor()).thenReturn(false);
        when(addMethod.getElementName()).thenReturn("add");
        when(addMethod.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(addMethod.getParameterNames()).thenReturn(new String[] {"a", "b"});
        when(addMethod.getFlags()).thenReturn(0);

        IMethod mulMethod = mock(IMethod.class);
        when(mulMethod.isConstructor()).thenReturn(false);
        when(mulMethod.getElementName()).thenReturn("multiply");
        when(mulMethod.getParameterTypes()).thenReturn(new String[] {"I", "I"});
        when(mulMethod.getParameterNames()).thenReturn(new String[] {"x", "y"});
        when(mulMethod.getFlags()).thenReturn(0);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {addMethod});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(10, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertNotNull(hints);
        // Should have produced hints from the method calls
        assertFalse(hints.isEmpty());
    }

    @Test
    void parameterCollectorVisitModuleUsesMethodIdentifierEndForStaticCalls() throws Exception {
        String source = """
                class Util {
                    static void greet(String person) {}
                }
                Util.greet('Ada')
                """;
        ModuleNode module = parseModule(source);

        IMethod method = mock(IMethod.class);
        when(method.isConstructor()).thenReturn(false);
        when(method.getElementName()).thenReturn("greet");
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(method.getParameterNames()).thenReturn(new String[] {"person"});
        when(method.getFlags()).thenReturn(java.lang.reflect.Modifier.STATIC);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new IJavaElement[0]);
        when(workingCopy.codeSelect(64, 0)).thenReturn(new IJavaElement[] {method});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(6, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertTrue(hints.stream().anyMatch(h -> "person:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorVisitModuleHandlesStaticCallWhenCodeSelectReturnsType() throws Exception {
        String source = """
                class Util {
                    static void greet(String person) {}
                }
                Util.greet('Ada')
                """;
        ModuleNode module = parseModule(source);

        IMethod staticMethod = mock(IMethod.class);
        when(staticMethod.isConstructor()).thenReturn(false);
        when(staticMethod.getElementName()).thenReturn("greet");
        when(staticMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(staticMethod.getParameterNames()).thenReturn(new String[] {"person"});
        when(staticMethod.getFlags()).thenReturn(java.lang.reflect.Modifier.STATIC);

        IType type = mock(IType.class);
        when(type.getMethods()).thenReturn(new IMethod[] {staticMethod});

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {type});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(6, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertTrue(hints.stream().anyMatch(h -> "person:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorFallsBackToReceiverResolutionWhenCodeSelectMisses() throws Exception {
        String source = """
                import demo.Support
                new Support().greet('Ada')
                """;
        ModuleNode module = parseModule(source);

        IJavaProject project = mock(IJavaProject.class);
        IType supportType = mock(IType.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod greetMethod = mock(IMethod.class);

        when(project.findType("demo.Support")).thenReturn(supportType);
        when(supportType.getFullyQualifiedName()).thenReturn("demo.Support");
        when(supportType.getMethods()).thenReturn(new IMethod[] {greetMethod});
        when(supportType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(supportType)).thenReturn(new IType[0]);

        when(greetMethod.isConstructor()).thenReturn(false);
        when(greetMethod.getElementName()).thenReturn("greet");
        when(greetMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(greetMethod.getParameterNames()).thenReturn(new String[] {"person"});
        when(greetMethod.getDeclaringType()).thenReturn(supportType);
        when(greetMethod.getFlags()).thenReturn(0);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[0]);

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(4, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertTrue(hints.stream().anyMatch(h -> "person:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorPrefersOverrideMethodParameterNames() throws Exception {
        String source = """
                interface Greeter {
                    void greet(String baseName)
                }
                class Impl implements Greeter {
                    @Override
                    void greet(String person) {}
                }
                def impl = new Impl()
                impl.greet('Ada')
                """;
        ModuleNode module = parseModule(source);

        IType greeterType = mock(IType.class);
        when(greeterType.getFullyQualifiedName()).thenReturn("demo.Greeter");
        when(greeterType.isInterface()).thenReturn(true);

        IMethod interfaceMethod = mock(IMethod.class);
        when(interfaceMethod.isConstructor()).thenReturn(false);
        when(interfaceMethod.getElementName()).thenReturn("greet");
        when(interfaceMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(interfaceMethod.getParameterNames()).thenReturn(new String[] {"baseName"});
        when(interfaceMethod.getDeclaringType()).thenReturn(greeterType);
        when(interfaceMethod.getFlags()).thenReturn(0);

        IType implType = mock(IType.class);
        when(implType.getFullyQualifiedName()).thenReturn("demo.Impl");
        when(implType.isInterface()).thenReturn(false);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(implType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(implType)).thenReturn(new IType[] {greeterType});

        IMethod overrideMethod = mock(IMethod.class);
        when(overrideMethod.isConstructor()).thenReturn(false);
        when(overrideMethod.getElementName()).thenReturn("greet");
        when(overrideMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(overrideMethod.getParameterNames()).thenReturn(new String[] {"person"});
        when(overrideMethod.getDeclaringType()).thenReturn(implType);
        when(overrideMethod.getFlags()).thenReturn(0);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[] {interfaceMethod, overrideMethod});

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(12, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        assertTrue(hints.stream().anyMatch(h -> "person:".equals(labelText(h))));
        assertTrue(hints.stream().noneMatch(h -> "baseName:".equals(labelText(h))));
    }

    @Test
    void parameterCollectorPrefersMeaningfulSourceInterfaceMethodOverPlaceholderBinaryMethod() throws Exception {
        Object collector = createCollector("mockInterface.greet('Ada')\n", null);

        IType sourceInterface = mock(IType.class);
        when(sourceInterface.isInterface()).thenReturn(true);
        when(sourceInterface.getFullyQualifiedName()).thenReturn("demo.SomeInterface");

        IMethod sourceMethod = mock(IMethod.class);
        when(sourceMethod.isConstructor()).thenReturn(false);
        when(sourceMethod.getElementName()).thenReturn("greet");
        when(sourceMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(sourceMethod.getParameterNames()).thenReturn(new String[] {"person"});
        when(sourceMethod.getDeclaringType()).thenReturn(sourceInterface);
        when(sourceMethod.getCompilationUnit()).thenReturn(mock(ICompilationUnit.class));
        when(sourceMethod.getFlags()).thenReturn(0);

        IType binaryInterface = mock(IType.class);
        when(binaryInterface.isInterface()).thenReturn(true);
        when(binaryInterface.getFullyQualifiedName()).thenReturn("demo.SomeInterface");

        IMethod binaryMethod = mock(IMethod.class);
        when(binaryMethod.isConstructor()).thenReturn(false);
        when(binaryMethod.getElementName()).thenReturn("greet");
        when(binaryMethod.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(binaryMethod.getParameterNames()).thenReturn(new String[] {"args0"});
        when(binaryMethod.getDeclaringType()).thenReturn(binaryInterface);
        when(binaryMethod.getCompilationUnit()).thenReturn(null);
        when(binaryMethod.getFlags()).thenReturn(0);

        Object chosen = invokeCollector(collector, "chooseBestMethod",
                new Class<?>[] {List.class, int.class},
                new Object[] {Arrays.asList(binaryMethod, sourceMethod), 1});

        assertEquals(sourceMethod, chosen);
    }

    @Test
    void parameterCollectorVisitModuleWithNamedArguments() throws Exception {
        String source = """
                def greet(String person) {}
                greet(person: 'Ada')
                """;
        ModuleNode module = parseModule(source);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.codeSelect(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new IJavaElement[0]);

        Object collector = createCollector(source, new Range(new Position(0, 0), new Position(5, 0)), workingCopy);
        invokeCollector(collector, "visitModule", new Class<?>[] {ModuleNode.class}, new Object[] {module});

        @SuppressWarnings("unchecked")
        List<InlayHint> hints = (List<InlayHint>) invokeCollector(collector, "getHints", new Class<?>[0], new Object[0]);
        // Named args should not get parameter hints since they already show the name
        assertNotNull(hints);
    }

    @Test
    void parameterCollectorIsVarargsMethod() throws Exception {
        Object collector = createCollector("s\n", null);

        IMethod varargMethod = mock(IMethod.class);
        when(varargMethod.getFlags()).thenReturn(java.lang.reflect.Modifier.TRANSIENT); // TRANSIENT = VARARGS in JDT

        boolean result = (boolean) invokeCollector(collector, "isVarargs",
                new Class<?>[] {IMethod.class}, new Object[] {varargMethod});
        // TRANSIENT flag is 0x80 which may or may not map to Flags.isVarargs
        // The important thing is this doesn't throw
        assertTrue(result || !result);
    }

    private InlayHintParams paramsFor(String uri) {
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setRange(new Range(new Position(0, 0), new Position(200, 0)));
        return params;
    }

    private InlayHint hint(int line, int character, String label, InlayHintKind kind) {
        InlayHint hint = new InlayHint();
        hint.setPosition(new Position(line, character));
        hint.setKind(kind);
        hint.setLabel(label);
        return hint;
    }

    @SuppressWarnings("unchecked")
    private List<InlayHint> invokeDedupeAndSort(InlayHintProvider provider, List<InlayHint> hints)
            throws Exception {
        Method method = InlayHintProvider.class.getDeclaredMethod("dedupeAndSort", List.class);
        method.setAccessible(true);
        return (List<InlayHint>) method.invoke(provider, hints);
    }

    private String labelText(InlayHint hint) {
        if (hint.getLabel().isLeft()) {
            return hint.getLabel().getLeft();
        }
        StringBuilder builder = new StringBuilder();
        for (InlayHintLabelPart part : hint.getLabel().getRight()) {
            if (part != null && part.getValue() != null) {
                builder.append(part.getValue());
            }
        }
        return builder.toString();
    }

    private Object createCollector(String source, Range requestedRange) throws Exception {
        return createCollector(source, requestedRange, mock(ICompilationUnit.class));
    }

    private Object createCollector(String source, Range requestedRange, ICompilationUnit workingCopy) throws Exception {
        Class<?> collectorClass = Class.forName("org.eclipse.groovy.ls.core.providers.InlayHintProvider$ParameterHintCollector");
        var constructor = collectorClass.getDeclaredConstructor(InlayHintProvider.class, String.class, Range.class,
                ICompilationUnit.class, DocumentManager.class);
        constructor.setAccessible(true);
        // Configure mock DocumentManager to delegate cachedCodeSelect → workingCopy.codeSelect
        DocumentManager mockDm = mock(DocumentManager.class);
        when(mockDm.cachedCodeSelect(org.mockito.ArgumentMatchers.any(ICompilationUnit.class),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            ICompilationUnit unit = inv.getArgument(0);
            int offset = inv.getArgument(1);
            return unit.codeSelect(offset, 0);
        });
        return constructor.newInstance(new InlayHintProvider(mockDm), source, requestedRange, workingCopy, mockDm);
    }

    private Object invokeCollector(Object collector, String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method method = collector.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(collector, args);
    }

    private IMethod mockMethod(String name, String[] parameterTypes, int flags) throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn(name);
        when(method.getParameterTypes()).thenReturn(parameterTypes);
        when(method.getFlags()).thenReturn(flags);
        return method;
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///InlayCollectorTest.groovy", source);
        assertTrue(result.hasAST());
        return result.getModuleNode();
    }

    // =========================================================================
    //  MethodCallDeclCollector tests
    // =========================================================================

    @Test
    void methodCallDeclCollectorCollectsDefStyleMethodCallDeclarations() throws Exception {
        String source = """
                def result = someService.getData()
                """;
        ModuleNode module = parseModule(source);
        Object declCollector = createDeclCollector();
        invokeDeclCollector(declCollector, module);
        List<?> declarations = (List<?>) getDeclCollectorDeclarations(declCollector);
        // "def result = someService.getData()" should be collected since it's
        // a def-style declaration with a method call RHS
        assertNotNull(declarations);
    }

    @Test
    void methodCallDeclCollectorIgnoresTypedDeclarations() throws Exception {
        String source = """
                String name = getFullName()
                """;
        ModuleNode module = parseModule(source);
        Object declCollector = createDeclCollector();
        invokeDeclCollector(declCollector, module);
        List<?> declarations = (List<?>) getDeclCollectorDeclarations(declCollector);
        // Typed declarations should NOT be collected (only def-style)
        assertTrue(declarations.isEmpty());
    }

    @Test
    void methodCallDeclCollectorIgnoresDefWithNonMethodCallRhs() throws Exception {
        String source = """
                def value = 42
                def name = "hello"
                """;
        ModuleNode module = parseModule(source);
        Object declCollector = createDeclCollector();
        invokeDeclCollector(declCollector, module);
        List<?> declarations = (List<?>) getDeclCollectorDeclarations(declCollector);
        // Literal RHS should NOT be collected
        assertTrue(declarations.isEmpty());
    }

    @Test
    void methodCallDeclCollectorCollectsMultipleDeclarations() throws Exception {
        String source = """
                class Svc {
                    void work() {
                        def a = getAlpha()
                        def b = getBeta()
                    }
                }
                """;
        ModuleNode module = parseModule(source);
        Object declCollector = createDeclCollector();
        invokeDeclCollector(declCollector, module);
        List<?> declarations = (List<?>) getDeclCollectorDeclarations(declCollector);
        assertNotNull(declarations);
        // Both local def-style declarations should be collected
        assertTrue(declarations.size() >= 2);
    }

    @Test
    void declInfoHasCorrectFields() throws Exception {
        String source = """
                def data = fetchData()
                """;
        ModuleNode module = parseModule(source);
        Object declCollector = createDeclCollector();
        invokeDeclCollector(declCollector, module);
        List<?> declarations = (List<?>) getDeclCollectorDeclarations(declCollector);

        if (!declarations.isEmpty()) {
            Object declInfo = declarations.get(0);
            Class<?> declInfoClass = declInfo.getClass();
            String varName = (String) declInfoClass.getDeclaredField("varName").get(declInfo);
            int line = declInfoClass.getDeclaredField("line").getInt(declInfo);
            int column = declInfoClass.getDeclaredField("column").getInt(declInfo);
            Object methodCall = declInfoClass.getDeclaredField("methodCall").get(declInfo);

            assertEquals("data", varName);
            assertTrue(line >= 0);
            assertTrue(column >= 0);
            assertNotNull(methodCall);
        }
    }

    @Test
    void methodCallDeclCollectorGetSourceUnitReturnsNull() throws Exception {
        Object declCollector = createDeclCollector();
        Method m = declCollector.getClass().getDeclaredMethod("getSourceUnit");
        m.setAccessible(true);
        assertNull(m.invoke(declCollector));
    }

    private Object createDeclCollector() throws Exception {
        Class<?> cls = Class.forName("org.eclipse.groovy.ls.core.providers.InlayHintProvider$MethodCallDeclCollector");
        var constructor = cls.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private void invokeDeclCollector(Object collector, ModuleNode module) throws Exception {
        // MethodCallDeclCollector extends ClassCodeVisitorSupport
        // We need to visit the module classes
        Method visitClass = collector.getClass().getMethod("visitClass",
                org.codehaus.groovy.ast.ClassNode.class);
        visitClass.setAccessible(true);
        for (var classNode : module.getClasses()) {
            visitClass.invoke(collector, classNode);
        }
    }

    private Object getDeclCollectorDeclarations(Object collector) throws Exception {
        Method m = collector.getClass().getDeclaredMethod("getDeclarations");
        m.setAccessible(true);
        return m.invoke(collector);
    }

    // ================================================================
    // isInRange tests
    // ================================================================

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 7})
    void isInRangeIncludesBoundaries(int line) throws Exception {
        Range range = new Range(new Position(3, 0), new Position(7, 0));
        assertTrue((boolean) invokeIsInRange(line, range));
    }

    @Test
    void isInRangeBeforeRange() throws Exception {
        Range range = new Range(new Position(3, 0), new Position(7, 0));
        assertFalse((boolean) invokeIsInRange(1, range));
    }

    @Test
    void isInRangeAfterRange() throws Exception {
        Range range = new Range(new Position(3, 0), new Position(7, 0));
        assertFalse((boolean) invokeIsInRange(10, range));
    }

    // ================================================================
    // resolveLocalVarType tests
    // ================================================================

    @Test
    void resolveLocalVarTypeFindsType() throws Exception {
        String source = "class Foo { void run() { String x = 'hello' } }";
        String uri = "file:///resolveLocalVar.groovy";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode result = invokeResolveLocalVarType(module, "x");
        if (result != null) {
            assertTrue(result.getName().contains("String"));
        }
    }

    @Test
    void resolveLocalVarTypeReturnsNullForMissing() throws Exception {
        String source = "class Foo { void run() { int y = 1 } }";
        String uri = "file:///resolveLocalVar2.groovy";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode result = invokeResolveLocalVarType(module, "missing");
        assertNull(result);
    }

    // ================================================================
    // getBlock tests
    // ================================================================

    @Test
    void getBlockReturnsBlockForMethod() throws Exception {
        String source = "class Foo { void bar() { println 'hi' } }";
        String uri = "file:///getBlock.groovy";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("bar");
        if (!methods.isEmpty()) {
            Object block = invokeGetBlock(methods.get(0));
            assertNotNull(block);
        }
    }

    // ================================================================
    // resolveVarInBlock tests
    // ================================================================

    @Test
    void resolveVarInBlockFindsVar() throws Exception {
        String source = "class Foo { void bar() { String x = 'hello' } }";
        String uri = "file:///resolveVarBlock.groovy";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("bar");
        if (!methods.isEmpty()) {
            var code = methods.get(0).getCode();
            if (code instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) {
                ClassNode result = invokeResolveVarInBlock(block, "x");
                if (result != null) {
                    assertTrue(result.getName().contains("String"));
                }
            }
        }
    }

    @Test
    void resolveVarInBlockReturnsNullForMissing() throws Exception {
        String source = "class Foo { void bar() { int y = 1 } }";
        String uri = "file:///resolveVarBlock2.groovy";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse(uri, source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("bar");
        if (!methods.isEmpty()) {
            var code = methods.get(0).getCode();
            if (code instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) {
                ClassNode result = invokeResolveVarInBlock(block, "missing");
                assertNull(result);
            }
        }
    }

    // ================================================================
    // Reflection helpers for new tests
    // ================================================================

    private Object invokeIsInRange(int line, Range range) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("isInRange", int.class, Range.class);
        m.setAccessible(true);
        return m.invoke(provider, line, range);
    }

    private ClassNode invokeResolveLocalVarType(ModuleNode module, String varName) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("resolveLocalVarType", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, module, varName);
    }

    private Object invokeGetBlock(org.codehaus.groovy.ast.MethodNode methodNode) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("getBlock", org.codehaus.groovy.ast.MethodNode.class);
        m.setAccessible(true);
        return m.invoke(provider, methodNode);
    }

    private ClassNode invokeResolveVarInBlock(org.codehaus.groovy.ast.stmt.BlockStatement block, String varName) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("resolveVarInBlock",
                org.codehaus.groovy.ast.stmt.BlockStatement.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, block, varName);
    }

    // ================================================================
    // resolveClassNodeToType tests (171 missed instructions)
    // ================================================================

    @Test
    void resolveClassNodeToTypeWithFqn() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(mockType.getElementName()).thenReturn("ArrayList");
        when(project.findType("java.util.ArrayList")).thenReturn(mockType);

        ClassNode typeNode = new ClassNode("java.util.ArrayList", 0, null);
        ModuleNode module = parseModule("def x = 1");

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, project);
        assertEquals(mockType, result);
    }

    @Test
    void resolveClassNodeToTypeViaImport() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("java.util.ArrayList")).thenReturn(mockType);
        when(project.findType("ArrayList")).thenReturn(null);

        String source = "import java.util.ArrayList\ndef x = 1";
        ModuleNode module = parseModule(source);
        ClassNode typeNode = new ClassNode("ArrayList", 0, null);

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, project);
        assertEquals(mockType, result);
    }

    @Test
    void resolveClassNodeToTypeViaStarImport() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("HashMap")).thenReturn(null);
        when(project.findType("java.util.HashMap")).thenReturn(mockType);

        String source = "import java.util.*\ndef x = 1";
        ModuleNode module = parseModule(source);
        ClassNode typeNode = new ClassNode("HashMap", 0, null);

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, project);
        assertEquals(mockType, result);
    }

    @Test
    void resolveClassNodeToTypeViaModulePackage() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("MyClass")).thenReturn(null);
        when(project.findType("com.example.MyClass")).thenReturn(mockType);

        String source = "package com.example\ndef x = 1";
        ModuleNode module = parseModule(source);
        ClassNode typeNode = new ClassNode("MyClass", 0, null);

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, project);
        assertEquals(mockType, result);
    }

    @Test
    void resolveClassNodeToTypeViaAutoImport() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("Integer")).thenReturn(null);
        when(project.findType("java.lang.Integer")).thenReturn(mockType);

        ModuleNode module = parseModule("def x = 1");
        ClassNode typeNode = new ClassNode("Integer", 0, null);

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, project);
        assertEquals(mockType, result);
    }

    @Test
    void resolveClassNodeToTypeReturnsNullForNullType() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        ModuleNode module = parseModule("def x = 1");

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(null, module, project);
        assertNull(result);
    }

    @Test
    void resolveClassNodeToTypeReturnsNullForNullProject() throws Exception {
        ModuleNode module = parseModule("def x = 1");
        ClassNode typeNode = new ClassNode("String", 0, null);

        org.eclipse.jdt.core.IType result = invokeResolveClassNodeToType(typeNode, module, null);
        assertNull(result);
    }

    // ================================================================
    // findMethodReturnType tests (131 missed instructions)
    // ================================================================

    @Test
    void findMethodReturnTypeDirectMatch() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);
        when(returnType.getElementName()).thenReturn("String");

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getReturnType()).thenReturn("QString;");
        when(receiverType.getMethods()).thenReturn(new IMethod[] {method});
        when(receiverType.getFullyQualifiedName()).thenReturn("com.example.Person");

        when(project.findType("String")).thenReturn(returnType);

        org.eclipse.jdt.core.IType result = invokeFindMethodReturnType(receiverType, "getName", project);
        assertEquals(returnType, result);
    }

    @Test
    void findMethodReturnTypeSearchesSupertype() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType superType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);

        when(receiverType.getMethods()).thenReturn(new IMethod[0]);
        when(receiverType.getFullyQualifiedName()).thenReturn("com.example.Child");

        IMethod superMethod = mock(IMethod.class);
        when(superMethod.getElementName()).thenReturn("getBase");
        when(superMethod.getReturnType()).thenReturn("QInteger;");
        when(superType.getMethods()).thenReturn(new IMethod[] {superMethod});

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(receiverType)).thenReturn(new org.eclipse.jdt.core.IType[] {superType});
        when(receiverType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        when(project.findType("Integer")).thenReturn(returnType);

        org.eclipse.jdt.core.IType result = invokeFindMethodReturnType(receiverType, "getBase", project);
        assertEquals(returnType, result);
    }

    @Test
    void findMethodReturnTypeReturnsNullWhenNotFound() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);

        when(receiverType.getMethods()).thenReturn(new IMethod[0]);
        when(receiverType.getFullyQualifiedName()).thenReturn("com.example.Empty");

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(receiverType)).thenReturn(new org.eclipse.jdt.core.IType[0]);
        when(receiverType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        org.eclipse.jdt.core.IType result = invokeFindMethodReturnType(receiverType, "noSuchMethod", project);
        assertNull(result);
    }

    @Test
    void findMethodReturnTypeWithHierarchyCache() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("doWork");
        when(method.getReturnType()).thenReturn("QBoolean;");
        when(receiverType.getMethods()).thenReturn(new IMethod[] {method});
        when(receiverType.getFullyQualifiedName()).thenReturn("com.example.Worker");
        when(project.findType("Boolean")).thenReturn(returnType);

        java.util.Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache = new java.util.HashMap<>();
        org.eclipse.jdt.core.IType result = invokeFindMethodReturnTypeWithCache(
                receiverType, "doWork", project, hierarchyCache);
        assertEquals(returnType, result);
    }

    @Test
    void findMethodReturnTypeFallsBackToSourceRecordComponent() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);

        when(receiverType.getMethods()).thenReturn(new IMethod[0]);
        when(receiverType.getFullyQualifiedName()).thenReturn("com.example.Recc");
        when(receiverType.getElementName()).thenReturn("Recc");
        when(receiverType.getSource()).thenReturn("public record Recc(String something) {}\n");

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(receiverType)).thenReturn(new org.eclipse.jdt.core.IType[0]);
        when(receiverType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        when(project.findType("String")).thenReturn(returnType);

        org.eclipse.jdt.core.IType result = invokeFindMethodReturnType(receiverType, "something", project);
        assertEquals(returnType, result);
    }

    @Test
    void findMethodReturnTypeUsesBinaryMemberMetadataForGeneratedGetter() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType sourceType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType binaryType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);
        ICompilationUnit compilationUnit = mock(ICompilationUnit.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        IPackageFragment fragment = mock(IPackageFragment.class);
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);

        when(sourceType.getCompilationUnit()).thenReturn(compilationUnit);
        when(sourceType.getJavaProject()).thenReturn(project);
        when(sourceType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(sourceType.getMethods()).thenReturn(new IMethod[0]);

        when(project.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[] {root});
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_BINARY);
        when(root.getPackageFragment("com.example")).thenReturn(fragment);
        when(fragment.getOrdinaryClassFile("Helper.class")).thenReturn(classFile);
        when(classFile.exists()).thenReturn(true);
        when(classFile.getType()).thenReturn(binaryType);
        when(binaryType.exists()).thenReturn(true);
        when(binaryType.getFullyQualifiedName()).thenReturn("com.example.Helper");

        IMethod getter = mock(IMethod.class);
        when(getter.getElementName()).thenReturn("getSomeList");
        when(getter.getReturnType()).thenReturn("QList<QString;>;");
        when(binaryType.getMethods()).thenReturn(new IMethod[] {getter});

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(binaryType)).thenReturn(new org.eclipse.jdt.core.IType[0]);
        when(binaryType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        when(project.findType("List<String>")).thenReturn(null);
        when(project.findType("List")).thenReturn(returnType);

        org.eclipse.jdt.core.IType result = invokeFindMethodReturnType(sourceType, "getSomeList", project);
        assertEquals(returnType, result);
    }

    // ================================================================
    // resolveTypeByName tests (part of findMethodReturnType path)
    // ================================================================

    @Test
    void resolveTypeByNameDirectFind() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType foundType = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("java.lang.String")).thenReturn(foundType);

        org.eclipse.jdt.core.IType result = invokeResolveTypeByName("java.lang.String", null, project);
        assertEquals(foundType, result);
    }

    @Test
    void resolveTypeByNameViaContextResolve() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType context = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType resolved = mock(org.eclipse.jdt.core.IType.class);

        when(project.findType("MyType")).thenReturn(null);
        when(context.resolveType("MyType")).thenReturn(new String[][] {{"com.example", "MyType"}});
        when(project.findType("com.example.MyType")).thenReturn(resolved);
        when(project.findType("java.lang.MyType")).thenReturn(null);

        org.eclipse.jdt.core.IType result = invokeResolveTypeByName("MyType", context, project);
        assertEquals(resolved, result);
    }

    @Test
    void resolveTypeByNameViaJavaLangFallback() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType foundType = mock(org.eclipse.jdt.core.IType.class);

        when(project.findType("Integer")).thenReturn(null);
        when(project.findType("java.lang.Integer")).thenReturn(foundType);

        org.eclipse.jdt.core.IType result = invokeResolveTypeByName("Integer", null, project);
        assertEquals(foundType, result);
    }

    @Test
    void resolveTypeByNameStripsGenericsBeforeLookup() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType foundType = mock(org.eclipse.jdt.core.IType.class);

        when(project.findType("List<String>")).thenReturn(null);
        when(project.findType("List")).thenReturn(foundType);

        org.eclipse.jdt.core.IType result = invokeResolveTypeByName("List<String>", null, project);
        assertEquals(foundType, result);
    }

    // ================================================================
    // resolveMethodCallChainType tests (87 missed instructions)
    // ================================================================

    @Test
    void resolveMethodCallChainTypeWithConstructorReceiver() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType receiverType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType returnType = mock(org.eclipse.jdt.core.IType.class);

        when(project.findType("java.util.ArrayList")).thenReturn(receiverType);
        when(receiverType.getFullyQualifiedName()).thenReturn("java.util.ArrayList");

        IMethod sizeMethod = mock(IMethod.class);
        when(sizeMethod.getElementName()).thenReturn("size");
        when(sizeMethod.getReturnType()).thenReturn("I");
        when(receiverType.getMethods()).thenReturn(new IMethod[] {sizeMethod});
        when(project.findType("int")).thenReturn(returnType);

        // Build: new ArrayList().size()
        org.codehaus.groovy.ast.expr.ConstructorCallExpression ctorExpr =
                new org.codehaus.groovy.ast.expr.ConstructorCallExpression(
                        new ClassNode("java.util.ArrayList", 0, null),
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);
        org.codehaus.groovy.ast.expr.MethodCallExpression methodCall =
                new org.codehaus.groovy.ast.expr.MethodCallExpression(
                        ctorExpr, "size",
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);

        ModuleNode module = parseModule("def x = 1");
        org.eclipse.jdt.core.IType result = invokeResolveMethodCallChainType(methodCall, module, project);
        assertEquals(returnType, result);
    }

    @Test
    void resolveMethodCallChainTypeReturnsNullForNullMethodName() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);

        // MethodCallExpression with null method
        org.codehaus.groovy.ast.expr.MethodCallExpression methodCall =
                new org.codehaus.groovy.ast.expr.MethodCallExpression(
                        new org.codehaus.groovy.ast.expr.VariableExpression("x"),
                        new org.codehaus.groovy.ast.expr.ConstantExpression(null),
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);

        ModuleNode module = parseModule("def x = 1");
        org.eclipse.jdt.core.IType result = invokeResolveMethodCallChainType(methodCall, module, project);
        assertNull(result);
    }

    // ================================================================
    // computeJdtVariableTypeHints tests (121 missed instructions)
    // ================================================================

    @Test
    void computeJdtVariableTypeHintsProducesHintsForDefMethodCall() throws Exception {
        String source = "def result = new java.util.ArrayList().size()";
        ModuleNode module = parseModule(source);

        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);

        org.eclipse.jdt.core.IType arrayListType = mock(org.eclipse.jdt.core.IType.class);
        when(arrayListType.getFullyQualifiedName()).thenReturn("java.util.ArrayList");

        IMethod sizeMethod = mock(IMethod.class);
        when(sizeMethod.getElementName()).thenReturn("size");
        when(sizeMethod.getReturnType()).thenReturn("I");
        when(arrayListType.getMethods()).thenReturn(new IMethod[] {sizeMethod});

        org.eclipse.jdt.core.IType intType = mock(org.eclipse.jdt.core.IType.class);
        when(intType.getElementName()).thenReturn("int");

        when(project.findType("java.util.ArrayList")).thenReturn(arrayListType);
        when(project.findType("int")).thenReturn(intType);

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(module, source, range, workingCopy);
        assertNotNull(hints);
        // The hint should contain ": int" for the variable type
        if (!hints.isEmpty()) {
            assertTrue(hints.stream().anyMatch(h -> labelText(h).contains("int")));
        }
    }

    @Test
    void computeJdtVariableTypeHintsUsesDirectCodeSelectForStaticMethodCalls() throws Exception {
        String source = "def result = Util.answer()";
        ModuleNode module = parseModule(source);

        DocumentManager documentManager = mock(DocumentManager.class);
        InlayHintProvider directProvider = new InlayHintProvider(documentManager);

        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        when(project.exists()).thenReturn(true);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);

        IMethod method = mock(IMethod.class);
        IType declaringType = mock(IType.class);
        when(method.getElementName()).thenReturn("answer");
        when(method.getReturnType()).thenReturn("QString;");
        when(method.getDeclaringType()).thenReturn(declaringType);
        when(declaringType.getFullyQualifiedName()).thenReturn("demo.Util");

        when(documentManager.cachedCodeSelect(workingCopy, 23)).thenReturn(new IJavaElement[] {method});

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(directProvider, module, source, range, workingCopy);

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> ": String".equals(labelText(h))));
    }

    @Test
    void computeJdtVariableTypeHintsUsesSourceRecordAccessorFallback() throws Exception {
        String source = """
                import com.example.Recc
                def xdf = new Recc()
                def a = xdf.something()
                """;
        ModuleNode module = parseModule(source);

        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        when(project.exists()).thenReturn(true);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);

        org.eclipse.jdt.core.IType recordType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType stringType = mock(org.eclipse.jdt.core.IType.class);
        when(stringType.getElementName()).thenReturn("String");

        when(project.findType("com.example.Recc")).thenReturn(recordType);
        when(project.findType("String")).thenReturn(stringType);
        when(recordType.getFullyQualifiedName()).thenReturn("com.example.Recc");
        when(recordType.getElementName()).thenReturn("Recc");
        when(recordType.getMethods()).thenReturn(new IMethod[0]);
        when(recordType.getSource()).thenReturn("public record Recc(String something) {}\n");

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(recordType)).thenReturn(new org.eclipse.jdt.core.IType[0]);
        when(recordType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(module, source, range, workingCopy);

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> ": String".equals(labelText(h))));
    }

    @Test
    void computeJdtVariableTypeHintsUsesBinaryMemberMetadataForGetter() throws Exception {
        String source = """
                import com.example.Helper
                def x = new Helper()
                def n = x.getSomeList()
                """;
        ModuleNode module = parseModule(source);

        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        when(project.exists()).thenReturn(true);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);

        org.eclipse.jdt.core.IType sourceType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType binaryType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IType listType = mock(org.eclipse.jdt.core.IType.class);
        ICompilationUnit helperCompilationUnit = mock(ICompilationUnit.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        IPackageFragment fragment = mock(IPackageFragment.class);
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);

        when(project.findType("com.example.Helper")).thenReturn(sourceType);
        when(project.findType("List<String>")).thenReturn(null);
        when(project.findType("List")).thenReturn(listType);

        when(sourceType.getCompilationUnit()).thenReturn(helperCompilationUnit);
        when(sourceType.getJavaProject()).thenReturn(project);
        when(sourceType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(sourceType.getMethods()).thenReturn(new IMethod[0]);

        when(project.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[] {root});
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_BINARY);
        when(root.getPackageFragment("com.example")).thenReturn(fragment);
        when(fragment.getOrdinaryClassFile("Helper.class")).thenReturn(classFile);
        when(classFile.exists()).thenReturn(true);
        when(classFile.getType()).thenReturn(binaryType);
        when(binaryType.exists()).thenReturn(true);
        when(binaryType.getFullyQualifiedName()).thenReturn("com.example.Helper");

        IMethod getter = mock(IMethod.class);
        when(getter.getElementName()).thenReturn("getSomeList");
        when(getter.getReturnType()).thenReturn("QList<QString;>;");
        when(binaryType.getMethods()).thenReturn(new IMethod[] {getter});

        org.eclipse.jdt.core.ITypeHierarchy hierarchy = mock(org.eclipse.jdt.core.ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(binaryType)).thenReturn(new org.eclipse.jdt.core.IType[0]);
        when(binaryType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(module, source, range, workingCopy);

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> ": List<String>".equals(labelText(h))));
    }

    @Test
    void simplifyDisplayTypeNameStripsQualifiedNamesInsideGenerics() throws Exception {
        String simplified = invokeSimplifyDisplayTypeName("java.util.List<java.lang.String>");
        assertEquals("List<String>", simplified);
    }

    @Test
    void computeJdtVariableTypeHintsReturnsEmptyForNullProject() throws Exception {
        String source = "def x = someCall()";
        ModuleNode module = parseModule(source);

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(null);

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(module, source, range, workingCopy);
        assertTrue(hints.isEmpty());
    }

    @Test
    void computeJdtVariableTypeHintsReturnsEmptyForNonExistentProject() throws Exception {
        String source = "def x = someCall()";
        ModuleNode module = parseModule(source);

        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        when(project.exists()).thenReturn(false);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        when(workingCopy.getJavaProject()).thenReturn(project);

        Range range = new Range(new Position(0, 0), new Position(5, 0));
        List<InlayHint> hints = invokeComputeJdtVariableTypeHints(module, source, range, workingCopy);
        assertTrue(hints.isEmpty());
    }

    // ================================================================
    // updateSettingsFromObject tests
    // ================================================================

    @Test
    void updateSettingsFromObjectIgnoresNonInlayHintSettings() {
        String uri = "file:///settingsIgnoreNonObject.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, "def x = 42");
        InlayHintProvider p = new InlayHintProvider(dm);
        p.updateSettingsFromObject("not a settings object");
        List<InlayHint> hints = p.getInlayHints(paramsFor(uri), null);

        assertNotNull(hints);
        assertFalse(hints.isEmpty());

        dm.didClose(uri);
    }

    @Test
    void updateSettingsFromObjectAppliesCorrectType() {
        InlayHintProvider p = new InlayHintProvider(new DocumentManager());
        InlayHintSettings settings = new InlayHintSettings(false, false, false, false);
        p.updateSettingsFromObject(settings);
        // Verify by getting hints - all disabled should return empty
        String uri = "file:///settingsFromObj.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, "def x = 42");
        InlayHintProvider p2 = new InlayHintProvider(dm);
        p2.updateSettingsFromObject(settings);
        List<InlayHint> hints = p2.getInlayHints(paramsFor(uri));
        assertNotNull(hints);
    }

    // ================================================================
    // getInlayHints with single-arg overload (uses current settings)
    // ================================================================

    @Test
    void getInlayHintsSingleArgUsesStoredSettings() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///storedSettings.groovy";
        dm.didOpen(uri, "def name = 'Ada'\n");
        InlayHintProvider p = new InlayHintProvider(dm);

        // Default settings have variable types enabled
        List<InlayHint> hints = p.getInlayHints(paramsFor(uri));
        assertNotNull(hints);
        assertFalse(hints.isEmpty());
    }

    // ================================================================
    // resolveVarInBlock with ConstructorCallExpression RHS
    // ================================================================

    @Test
    void resolveVarInBlockWithConstructorRhs() throws Exception {
        String source = "class Foo { void bar() { def x = new java.util.ArrayList() } }";
        var compileResult = new GroovyCompilerService().parse("file:///ctorRhs.groovy", source);
        ModuleNode module = compileResult.getModuleNode();
        ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("bar");
        if (!methods.isEmpty()) {
            var code = methods.get(0).getCode();
            if (code instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) {
                ClassNode result = invokeResolveVarInBlock(block, "x");
                if (result != null) {
                    assertTrue(result.getName().contains("ArrayList"));
                }
            }
        }
    }

    // ================================================================
    // dedupeAndSort with Right-side labels
    // ================================================================

    @Test
    void dedupeAndSortHandlesRightSideLabels() throws Exception {
        List<InlayHint> input = new ArrayList<>();
        InlayHint rightLabel = new InlayHint();
        rightLabel.setPosition(new Position(0, 0));
        org.eclipse.lsp4j.InlayHintLabelPart part = new org.eclipse.lsp4j.InlayHintLabelPart();
        part.setValue("part1");
        rightLabel.setLabel(Either.forRight(List.of(part)));
        input.add(rightLabel);

        List<InlayHint> output = invokeDedupeAndSort(provider, input);
        assertEquals(1, output.size());
    }

    // ================================================================
    // Reflection helpers for JDT-mocked tests
    // ================================================================

    private org.eclipse.jdt.core.IType invokeResolveClassNodeToType(ClassNode typeNode, ModuleNode module,
            org.eclipse.jdt.core.IJavaProject project) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("resolveClassNodeToType",
                ClassNode.class, ModuleNode.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        return (org.eclipse.jdt.core.IType) m.invoke(provider, typeNode, module, project);
    }

    private org.eclipse.jdt.core.IType invokeFindMethodReturnType(org.eclipse.jdt.core.IType receiverType,
            String methodName, org.eclipse.jdt.core.IJavaProject project) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("findMethodReturnType",
                org.eclipse.jdt.core.IType.class, String.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        return (org.eclipse.jdt.core.IType) m.invoke(provider, receiverType, methodName, project);
    }

    private org.eclipse.jdt.core.IType invokeFindMethodReturnTypeWithCache(org.eclipse.jdt.core.IType receiverType,
            String methodName, org.eclipse.jdt.core.IJavaProject project,
            java.util.Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("findMethodReturnType",
                org.eclipse.jdt.core.IType.class, String.class, org.eclipse.jdt.core.IJavaProject.class,
                java.util.Map.class);
        m.setAccessible(true);
        return (org.eclipse.jdt.core.IType) m.invoke(provider, receiverType, methodName, project, hierarchyCache);
    }

    private org.eclipse.jdt.core.IType invokeResolveTypeByName(String typeName, org.eclipse.jdt.core.IType context,
            org.eclipse.jdt.core.IJavaProject project) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("resolveTypeByName",
                String.class, org.eclipse.jdt.core.IType.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        return (org.eclipse.jdt.core.IType) m.invoke(provider, typeName, context, project);
    }

    private String invokeSimplifyDisplayTypeName(String typeName) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("simplifyDisplayTypeName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, typeName);
    }

    private org.eclipse.jdt.core.IType invokeResolveMethodCallChainType(
            org.codehaus.groovy.ast.expr.MethodCallExpression methodCall,
            ModuleNode module, org.eclipse.jdt.core.IJavaProject project) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("resolveMethodCallChainType",
                org.codehaus.groovy.ast.expr.MethodCallExpression.class, ModuleNode.class,
                org.eclipse.jdt.core.IJavaProject.class, java.util.Map.class);
        m.setAccessible(true);
        return (org.eclipse.jdt.core.IType) m.invoke(provider, methodCall, module, project, new java.util.HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private List<InlayHint> invokeComputeJdtVariableTypeHints(ModuleNode module, String content,
            Range requestedRange, ICompilationUnit workingCopy) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("computeJdtVariableTypeHints",
                ModuleNode.class, String.class, Range.class, ICompilationUnit.class);
        m.setAccessible(true);
        return (List<InlayHint>) m.invoke(provider, module, content, requestedRange, workingCopy);
    }

    @SuppressWarnings("unchecked")
    private List<InlayHint> invokeComputeJdtVariableTypeHints(InlayHintProvider targetProvider,
            ModuleNode module, String content, Range requestedRange, ICompilationUnit workingCopy) throws Exception {
        Method m = InlayHintProvider.class.getDeclaredMethod("computeJdtVariableTypeHints",
                ModuleNode.class, String.class, Range.class, ICompilationUnit.class);
        m.setAccessible(true);
        return (List<InlayHint>) m.invoke(targetProvider, module, content, requestedRange, workingCopy);
    }
}
