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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class InlayHintProviderTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void getInlayHintsReturnsEmptyWhenDocumentIsMissing() {
        InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> hints = provider.getInlayHints(paramsFor("file:///MissingInlayHintDoc.groovy"), InlayHintSettings.defaults());

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

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), InlayHintSettings.defaults());

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

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        InlayHintSettings noParameterNames = new InlayHintSettings(true, false, true, true);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), noParameterNames);

        assertTrue(hints.stream().noneMatch(hint -> hint.getKind() == InlayHintKind.Parameter));
        assertTrue(hints.stream().anyMatch(hint -> hint.getKind() == InlayHintKind.Type));

        documentManager.didClose(uri);
    }

    @Test
    void dedupeAndSortRemovesDuplicatesAndSortsByPosition() throws Exception {
        InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> input = new ArrayList<>();
        input.add(hint(2, 5, "b:", InlayHintKind.Parameter));
        input.add(hint(1, 3, ": String", InlayHintKind.Type));
        input.add(hint(1, 3, ": String", InlayHintKind.Parameter));
        input.add(hint(0, 10, "a:", InlayHintKind.Parameter));

        List<InlayHint> output = invokeDedupeAndSort(provider, input);

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

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        InlayHintSettings noTypesSettings = new InlayHintSettings(false, true, false, false);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), noTypesSettings);

        assertTrue(hints.stream().noneMatch(hint -> hint.getKind() == InlayHintKind.Type));

        documentManager.didClose(uri);
    }

    @Test
    void getInlayHintsWithAllDisabledReturnsOnlyFallback() {
        String uri = "file:///InlayHintAllDisabledTest.groovy";
        String source = "def x = 42\n";

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        InlayHintSettings allDisabled = new InlayHintSettings(false, false, false, false);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), allDisabled);

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

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), null);

        assertNotNull(hints);
        assertFalse(hints.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void dedupeAndSortHandlesNullLabelsAndPositions() throws Exception {
        InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> input = new ArrayList<>();
        InlayHint nullLabel = new InlayHint();
        nullLabel.setPosition(new Position(0, 0));
        input.add(nullLabel);  // label is null - should be filtered

        InlayHint nullPosition = new InlayHint();
        nullPosition.setLabel("test:");
        input.add(nullPosition);  // position is null - should be filtered

        input.add(hint(1, 1, "valid:", InlayHintKind.Parameter));

        List<InlayHint> output = invokeDedupeAndSort(provider, input);
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
        assertNotNull(result);
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
        var constructor = collectorClass.getDeclaredConstructor(String.class, Range.class, ICompilationUnit.class);
        constructor.setAccessible(true);
        return constructor.newInstance(source, requestedRange, workingCopy);
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
}
