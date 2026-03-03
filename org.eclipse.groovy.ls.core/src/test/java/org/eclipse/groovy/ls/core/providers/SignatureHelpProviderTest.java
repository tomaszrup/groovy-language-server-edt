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

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class SignatureHelpProviderTest {

    @Test
    void getSignatureHelpReturnsAstSignaturesWithoutWorkingCopy() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpProviderAstMethod.groovy";
        manager.didOpen(uri, """
                class Calc {
                    int add(int left, int right) { left + right }
                }
                def calc = new Calc()
                calc.add(1, 2)
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(4, 12)); // inside second argument

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        assertNotNull(help.getSignatures());
        assertFalse(help.getSignatures().isEmpty());
        assertTrue(help.getSignatures().get(0).getLabel().contains("add("));
        assertEquals(0, help.getActiveParameter());

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpReturnsConstructorSignatureFromAst() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpProviderCtor.groovy";
        manager.didOpen(uri, """
                class Person {
                    Person(String name, int age) {}
                }
                new Person('Ada', 37)
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(3, 19));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        assertFalse(help.getSignatures().isEmpty());
        assertTrue(help.getSignatures().stream().anyMatch(sig -> sig.getLabel().contains("name")));
        assertEquals(0, help.getActiveParameter());

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpReturnsNullForUnknownDocument() {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///MissingSignatureDoc.groovy"));
        params.setPosition(new Position(0, 0));

        assertNull(provider.getSignatureHelp(params));
    }

    @Test
    void helperMethodsHandleNestedCallsAndBounds() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        String content = "fn(a, b, c)";
        int offset = content.length() - 1;

        int methodNameEnd = invokeFindMethodNameEnd(provider, content, offset);
        assertEquals(content.indexOf("fn") + 1, methodNameEnd);

        int commas = invokeCountCommas(provider, content, methodNameEnd + 1, offset);
        assertEquals(0, commas);

        assertEquals("fn", invokeExtractWordAt(provider, content, content.indexOf("fn")));
        assertEquals(6, invokePositionToOffset(provider, "hello\nworld", new Position(1, 0)));
    }

    // ---- Additional resolution tests ----

    @Test
    void getSignatureHelpResolvesOverloadedMethods() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpOverloaded.groovy";
        manager.didOpen(uri, """
                class Util {
                    String format(String s) { s }
                    String format(String s, int width) { s.padLeft(width) }
                    String format(String s, int width, char pad) { s.padLeft(width, pad as String) }
                }
                def u = new Util()
                u.format('hello', 10)
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(6, 18));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        assertTrue(help.getSignatures().size() >= 3);

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpDefaultConstructorWhenNoneExplicit() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpDefaultCtor.groovy";
        manager.didOpen(uri, """
                class Simple {
                    String name
                }
                new Simple()
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(3, 11));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        assertFalse(help.getSignatures().isEmpty());
        // Default constructor has empty params
        assertTrue(help.getSignatures().get(0).getLabel().contains("Simple()"));

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpReturnsNullOutsideMethodCall() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpNoCall.groovy";
        manager.didOpen(uri, """
                class Foo {
                    void bar() {}
                }
                def x = 42
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(3, 10));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNull(help);

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpMethodWithNoParams() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpNoParams.groovy";
        manager.didOpen(uri, """
                class Svc {
                    void start() {}
                }
                def svc = new Svc()
                svc.start()
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(4, 10));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        assertFalse(help.getSignatures().isEmpty());
        assertTrue(help.getSignatures().get(0).getParameters().isEmpty());

        manager.didClose(uri);
    }

    @Test
    void getSignatureHelpWithReturnType() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpReturnType.groovy";
        manager.didOpen(uri, """
                class MathUtil {
                    double sqrt(double value) { Math.sqrt(value) }
                }
                def m = new MathUtil()
                m.sqrt(4.0)
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(4, 8));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        SignatureInformation sig = help.getSignatures().get(0);
        assertTrue(sig.getLabel().contains("double"));
        assertTrue(sig.getLabel().contains("value"));
        assertEquals(1, sig.getParameters().size());

        manager.didClose(uri);
    }

    // ---- findMethodNameEnd edge cases ----

    @Test
    void findMethodNameEndHandlesNestedParens() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        // fn(inner(a), b) — cursor at the end before ')'
        String content = "fn(inner(a), b)";
        int offset = content.length() - 1; // just before final ')'

        int nameEnd = invokeFindMethodNameEnd(provider, content, offset);
        assertTrue(nameEnd >= 0);
        // The method name "fn" should be found
        String word = invokeExtractWordAt(provider, content, nameEnd);
        assertEquals("fn", word);
    }

    @Test
    void findMethodNameEndReturnsNegativeForNoCall() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        assertEquals(-1, invokeFindMethodNameEnd(provider, "hello world", 5));
    }

    // ---- countCommas edge cases ----

    @Test
    void countCommasSkipsNestedCommaSeparators() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        // fn(a, [1,2,3], b) — commas in the list shouldn't count
        String content = "fn(a, [1,2,3], b)";
        int start = 3; // after '('
        int end = content.length() - 1; // before ')'

        int commas = invokeCountCommas(provider, content, start, end);
        assertEquals(2, commas); // only two top-level commas
    }

    @Test
    void countCommasHandlesEmptyRange() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        assertEquals(0, invokeCountCommas(provider, "fn()", 3, 3));
    }

    // ---- extractWordAt edge cases ----

    @Test
    void extractWordAtReturnsNullForNonIdentifier() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        assertNull(invokeExtractWordAt(provider, "  +  ", 2));
    }

    @Test
    void extractWordAtHandlesUnderscore() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        assertEquals("my_func", invokeExtractWordAt(provider, "my_func(x)", 3));
    }

    // ---- positionToOffset edge cases ----

    @Test
    void positionToOffsetClampsToLength() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        assertEquals(3, invokePositionToOffset(provider, "abc", new Position(0, 99)));
    }

    @Test
    void positionToOffsetMultiLine() throws Exception {
        SignatureHelpProvider provider = new SignatureHelpProvider(new DocumentManager());
        String content = "first\nsecond\nthird";
        assertEquals(13, invokePositionToOffset(provider, content, new Position(2, 0)));
    }

    // ---- getSignatureHelpFromGroovyAST integration ----

    @Test
    void getSignatureHelpFromGroovyASTMultipleParameterTypes() {
        DocumentManager manager = new DocumentManager();
        String uri = "file:///SignatureHelpMultiTypes.groovy";
        manager.didOpen(uri, """
                class Converter {
                    Map convert(List items, String format, boolean strict) {
                        [:]
                    }
                }
                def c = new Converter()
                c.convert([], 'json', true)
                """);

        SignatureHelpProvider provider = new SignatureHelpProvider(manager);
        SignatureHelpParams params = new SignatureHelpParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(6, 20));

        SignatureHelp help = provider.getSignatureHelp(params);

        assertNotNull(help);
        SignatureInformation sig = help.getSignatures().get(0);
        assertTrue(sig.getLabel().contains("List"));
        assertTrue(sig.getLabel().contains("String"));
        assertTrue(sig.getLabel().contains("boolean"));
        assertEquals(3, sig.getParameters().size());

        manager.didClose(uri);
    }

    private int invokeFindMethodNameEnd(SignatureHelpProvider provider, String content, int offset) throws Exception {
        Method method = SignatureHelpProvider.class.getDeclaredMethod("findMethodNameEnd", String.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(provider, content, offset);
    }

    private int invokeCountCommas(SignatureHelpProvider provider, String content, int start, int end) throws Exception {
        Method method = SignatureHelpProvider.class.getDeclaredMethod("countCommas", String.class, int.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(provider, content, start, end);
    }

    private String invokeExtractWordAt(SignatureHelpProvider provider, String content, int offset) throws Exception {
        Method method = SignatureHelpProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, content, offset);
    }

    private int invokePositionToOffset(SignatureHelpProvider provider, String content, Position position) throws Exception {
        Method method = SignatureHelpProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        method.setAccessible(true);
        return (int) method.invoke(provider, content, position);
    }

    // ================================================================
    // toSignatureInformation tests (108 missed instructions)
    // ================================================================

    @Test
    void toSignatureInformationWithMethodParams() throws Exception {
        DocumentManager dm = new DocumentManager();
        SignatureHelpProvider provider = new SignatureHelpProvider(dm);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("greet");
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "I"});
        when(method.getParameterNames()).thenReturn(new String[] {"name", "age"});
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("V");

        SignatureInformation sig = invokeToSignatureInformation(provider, method);
        assertNotNull(sig);
        assertTrue(sig.getLabel().contains("greet"));
        assertTrue(sig.getLabel().contains("String"));
        assertTrue(sig.getLabel().contains("name"));
        assertTrue(sig.getLabel().contains("age"));
        assertEquals(2, sig.getParameters().size());
    }

    @Test
    void toSignatureInformationConstructor() throws Exception {
        DocumentManager dm = new DocumentManager();
        SignatureHelpProvider provider = new SignatureHelpProvider(dm);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("Person");
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(method.getParameterNames()).thenReturn(new String[] {"name"});
        when(method.isConstructor()).thenReturn(true);
        when(method.getReturnType()).thenReturn("V");

        SignatureInformation sig = invokeToSignatureInformation(provider, method);
        assertNotNull(sig);
        assertTrue(sig.getLabel().contains("Person"));
        assertTrue(sig.getLabel().contains("String"));
        // constructors don't show return type
        assertFalse(sig.getLabel().contains(": void"));
        assertEquals(1, sig.getParameters().size());
    }

    @Test
    void toSignatureInformationNoParams() throws Exception {
        DocumentManager dm = new DocumentManager();
        SignatureHelpProvider provider = new SignatureHelpProvider(dm);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("doWork");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("QString;");

        SignatureInformation sig = invokeToSignatureInformation(provider, method);
        assertNotNull(sig);
        assertTrue(sig.getLabel().startsWith("doWork()"));
        assertTrue(sig.getLabel().contains("String"));
        assertTrue(sig.getParameters().isEmpty());
    }

    @Test
    void toSignatureInformationFallbackParamNames() throws Exception {
        DocumentManager dm = new DocumentManager();
        SignatureHelpProvider provider = new SignatureHelpProvider(dm);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("fn");
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(method.getParameterNames()).thenReturn(null);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("V");

        SignatureInformation sig = invokeToSignatureInformation(provider, method);
        assertNotNull(sig);
        assertTrue(sig.getLabel().contains("arg0"));
    }

    @Test
    void toSignatureInformationExceptionReturnsNull() throws Exception {
        DocumentManager dm = new DocumentManager();
        SignatureHelpProvider provider = new SignatureHelpProvider(dm);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenThrow(new RuntimeException("broken"));

        SignatureInformation sig = invokeToSignatureInformation(provider, method);
        assertNull(sig);
    }

    private SignatureInformation invokeToSignatureInformation(SignatureHelpProvider provider, IMethod method) throws Exception {
        Method m = SignatureHelpProvider.class.getDeclaredMethod("toSignatureInformation", IMethod.class);
        m.setAccessible(true);
        return (SignatureInformation) m.invoke(provider, method);
    }

}
