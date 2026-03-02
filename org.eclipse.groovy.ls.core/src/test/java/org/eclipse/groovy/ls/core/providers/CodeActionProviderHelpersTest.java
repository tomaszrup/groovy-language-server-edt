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

import java.lang.reflect.Method;
import java.util.List;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for pure-text and AST utility methods in {@link CodeActionProvider}.
 */
class CodeActionProviderHelpersTest {

    private CodeActionProvider provider;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        provider = new CodeActionProvider(new DocumentManager(), new DiagnosticsProvider(new DocumentManager()));
    }

    // ---- packagePriority ----

    @Test
    void packagePriorityRanksJavaLangFirst() throws Exception {
        assertTrue(invokePackagePriority("java.lang.String") < invokePackagePriority("java.util.List"));
        assertTrue(invokePackagePriority("java.util.List") < invokePackagePriority("java.io.File"));
    }

    @Test
    void packagePriorityRanksExternalLast() throws Exception {
        int external = invokePackagePriority("org.apache.commons.Foo");
        int javaLang = invokePackagePriority("java.lang.String");
        assertTrue(external > javaLang);
        assertEquals(10, external);
    }

    @Test
    void packagePriorityRanksGroovyBetweenJavaAndExternal() throws Exception {
        int groovy = invokePackagePriority("groovy.transform.ToString");
        int javaUtil = invokePackagePriority("java.util.Map");
        int jackson = invokePackagePriority("com.fasterxml.jackson.Mapper");
        assertTrue(groovy > javaUtil);
        assertTrue(groovy < jackson);
    }

    @Test
    void packagePriorityRanksJavaxAndJakarta() throws Exception {
        assertEquals(7, invokePackagePriority("javax.inject.Inject"));
        assertEquals(7, invokePackagePriority("jakarta.annotation.Nullable"));
    }

    // ---- extractTypeNameFromMessage ----

    @Test
    void extractTypeNameFromMessageSimple() throws Exception {
        assertEquals("Foo", invokeExtractTypeName("unable to resolve class Foo"));
    }

    @Test
    void extractTypeNameFromMessageQualified() throws Exception {
        assertEquals("Bar", invokeExtractTypeName("unable to resolve class com.example.Bar"));
    }

    @Test
    void extractTypeNameFromMessageWithGroovyPrefix() throws Exception {
        assertEquals("Baz", invokeExtractTypeName("Groovy:unable to resolve class Baz"));
    }

    @Test
    void extractTypeNameFromMessageNull() throws Exception {
        assertNull(invokeExtractTypeName(null));
        assertNull(invokeExtractTypeName("some other error"));
    }

    // ---- getDiagnosticMessage ----

    @Test
    void getDiagnosticMessageExtractsText() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("test message");
        String result = invokeGetDiagnosticMessage(d);
        // LSP4j 1.0 may wrap getMessage() in Either; toString() should contain the text
        assertTrue(result.contains("test message"));
    }

    // ---- isUnresolvedTypeDiagnostic ----

    @Test
    void isUnresolvedTypeDiagnosticByCode() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setCode("groovy.unresolvedType.Foo");
        d.setMessage("");
        assertTrue(invokeIsUnresolvedType(d));
    }

    @Test
    void isUnresolvedTypeDiagnosticByMessage() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("unable to resolve class Foo");
        assertTrue(invokeIsUnresolvedType(d));
    }

    @Test
    void isUnresolvedTypeDiagnosticFalseForUnrelated() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("unexpected token");
        assertFalse(invokeIsUnresolvedType(d));
    }

    // ---- isMissingInterfaceMemberDiagnostic ----

    @Test
    void isMissingInterfaceMemberDiagnosticTrueForDeclareAbstract() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("The class 'Impl' must be declared abstract or the method 'foo' must be implemented");
        assertTrue(invokeIsMissingInterface(d));
    }

    @Test
    void isMissingInterfaceMemberDiagnosticTrueForInherited() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("Impl must implement the inherited abstract method bar()");
        assertTrue(invokeIsMissingInterface(d));
    }

    @Test
    void isMissingInterfaceMemberDiagnosticFalse() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("syntax error");
        assertFalse(invokeIsMissingInterface(d));
    }

    // ---- sanitizeParameterName ----

    @Test
    void sanitizeParameterNameKeepsValid() throws Exception {
        assertEquals("name", invokeSanitize("name", 1));
    }

    @Test
    void sanitizeParameterNameFallsBackOnNull() throws Exception {
        assertEquals("arg1", invokeSanitize(null, 1));
        assertEquals("arg2", invokeSanitize("", 2));
    }

    @Test
    void sanitizeParameterNameFallsBackOnInvalidStart() throws Exception {
        assertEquals("arg3", invokeSanitize("123abc", 3));
    }

    @Test
    void sanitizeParameterNameFallsBackOnInvalidChars() throws Exception {
        assertEquals("arg1", invokeSanitize("na-me", 1));
    }

    // ---- inferIndentUnit ----

    @Test
    void inferIndentUnitDetectsFourSpaces() throws Exception {
        String[] lines = { "class Foo {", "    int x", "}" };
        assertEquals("    ", invokeInferIndentUnit(lines, "", 0, 2));
    }

    @Test
    void inferIndentUnitDetectsTab() throws Exception {
        String[] lines = { "class Foo {", "\tint x", "}" };
        assertEquals("\t", invokeInferIndentUnit(lines, "", 0, 2));
    }

    @Test
    void inferIndentUnitDefaultsToFourSpaces() throws Exception {
        String[] lines = { "class Foo {", "}" };
        assertEquals("    ", invokeInferIndentUnit(lines, "", 0, 1));
    }

    // ---- leadingWhitespace ----

    @Test
    void leadingWhitespaceExtractsSpaces() throws Exception {
        assertEquals("    ", invokeLeadingWhitespace("    int x = 5"));
    }

    @Test
    void leadingWhitespaceExtractsTabs() throws Exception {
        assertEquals("\t\t", invokeLeadingWhitespace("\t\tint x = 5"));
    }

    @Test
    void leadingWhitespaceEmptyForNoIndent() throws Exception {
        assertEquals("", invokeLeadingWhitespace("int x = 5"));
    }

    // ---- renderType ----

    @Test
    void renderTypeSimple() throws Exception {
        assertEquals("String", invokeRenderType(ClassHelper.STRING_TYPE));
    }

    @Test
    void renderTypeNull() throws Exception {
        assertEquals("def", invokeRenderType(null));
    }

    @Test
    void renderTypeArray() throws Exception {
        assertEquals("String[]", invokeRenderType(ClassHelper.STRING_TYPE.makeArray()));
    }

    // ---- renderParameters ----

    @Test
    void renderParametersEmpty() throws Exception {
        assertEquals("", invokeRenderParameters(new Parameter[0]));
    }

    @Test
    void renderParametersSingle() throws Exception {
        Parameter p = new Parameter(ClassHelper.STRING_TYPE, "name");
        assertEquals("String name", invokeRenderParameters(new Parameter[] { p }));
    }

    @Test
    void renderParametersMultiple() throws Exception {
        Parameter p1 = new Parameter(ClassHelper.STRING_TYPE, "name");
        Parameter p2 = new Parameter(ClassHelper.int_TYPE, "count");
        assertEquals("String name, int count", invokeRenderParameters(new Parameter[] { p1, p2 }));
    }

    // ---- normalizeTypeName ----

    @Test
    void normalizeTypeNameSimple() throws Exception {
        assertEquals("java.lang.String", invokeNormalizeTypeName(ClassHelper.STRING_TYPE));
    }

    @Test
    void normalizeTypeNameNull() throws Exception {
        assertEquals("def", invokeNormalizeTypeName(null));
    }

    // ---- methodSignatureKey ----

    @Test
    void methodSignatureKeyNoParams() throws Exception {
        ModuleNode module = parseModule("interface I { void foo() }", "file:///SigTest1.groovy");
        var method = findClass(module, "I").getMethods().stream()
                .filter(m -> "foo".equals(m.getName())).findFirst().orElseThrow();
        assertEquals("foo()", invokeMethodSignatureKey(method));
    }

    @Test
    void methodSignatureKeyWithParams() throws Exception {
        ModuleNode module = parseModule(
                "interface I { void bar(String s, int n) }",
                "file:///SigTest2.groovy");
        var method = findClass(module, "I").getMethods().stream()
                .filter(m -> "bar".equals(m.getName())).findFirst().orElseThrow();
        String key = invokeMethodSignatureKey(method);
        assertTrue(key.startsWith("bar("));
        assertTrue(key.contains("String") || key.contains("java.lang.String"));
    }

    // ---- findImportInsertLine (CodeActionProvider's version) ----

    @Test
    void findImportInsertLineAfterImports() throws Exception {
        String content = "package demo\nimport java.util.List\nimport java.io.File\nclass Foo {}";
        assertEquals(3, invokeCAFindImportInsertLine(content));
    }

    @Test
    void findImportInsertLineAfterPackage() throws Exception {
        assertEquals(2, invokeCAFindImportInsertLine("package demo\n\nclass Foo {}"));
    }

    @Test
    void findImportInsertLineZeroWhenEmpty() throws Exception {
        assertEquals(0, invokeCAFindImportInsertLine("class Foo {}"));
    }

    // ---- wantsKind ----

    @Test
    void wantsKindNullMatchesAll() throws Exception {
        assertTrue(invokeWantsKind(null, "quickfix"));
    }

    @Test
    void wantsKindMatchesPresent() throws Exception {
        assertTrue(invokeWantsKind(java.util.List.of("quickfix", "source"), "quickfix"));
    }

    @Test
    void wantsKindNoMatch() throws Exception {
        assertFalse(invokeWantsKind(java.util.List.of("source"), "quickfix"));
    }

    // ---- findEnclosingClass / findClassBySimpleName (AST) ----

    @Test
    void findEnclosingClassFindsCorrectClass() throws Exception {
        ModuleNode module = parseModule("""
                class Outer {
                    void foo() {}
                }
                class Second {
                    void bar() {}
                }
                """, "file:///EnclosingTest.groovy");

        ClassNode found = invokeFindEnclosingClass(module, 2);
        assertNotNull(found);
        assertEquals("Outer", found.getNameWithoutPackage());
    }

    @Test
    void findClassBySimpleNameFinds() throws Exception {
        ModuleNode module = parseModule("class Alpha {}\nclass Beta {}", "file:///FindByNameTest.groovy");
        ClassNode found = invokeFindClassBySimpleName(module, "Beta");
        assertNotNull(found);
        assertEquals("Beta", found.getNameWithoutPackage());
    }

    @Test
    void findClassBySimpleNameNullForMissing() throws Exception {
        ModuleNode module = parseModule("class Alpha {}", "file:///FindByNameNull.groovy");
        assertNull(invokeFindClassBySimpleName(module, "Gamma"));
        assertNull(invokeFindClassBySimpleName(module, null));
        assertNull(invokeFindClassBySimpleName(module, ""));
    }

    // ---- extractClassSimpleNameFromMessage ----

    @Test
    void extractClassSimpleNameFromQuotedMessage() throws Exception {
        String msg = "The class 'com.example.Impl' must be declared abstract";
        assertEquals("Impl", invokeExtractClassName(msg));
    }

    @Test
    void extractClassSimpleNameFromTypeMessage() throws Exception {
        String msg = "The type com.example.Other must implement the inherited abstract method";
        assertEquals("Other", invokeExtractClassName(msg));
    }

    @Test
    void extractClassSimpleNameReturnsNullForUnrelated() throws Exception {
        assertNull(invokeExtractClassName("Random error message"));
        assertNull(invokeExtractClassName(null));
        assertNull(invokeExtractClassName(""));
    }

    // ---- getDiagnosticCode ----

    @Test
    void getDiagnosticCodeExtractsStringCode() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setCode("unused-import");
        String code = invokeGetDiagnosticCode(d);
        assertEquals("unused-import", code);
    }

    @Test
    void getDiagnosticCodeReturnsNullForMissing() throws Exception {
        Diagnostic d = new Diagnostic();
        String code = invokeGetDiagnosticCode(d);
        assertNull(code);
    }

    // ---- findMissingInterfaceMethods ----

    @Test
    void findMissingInterfaceMethodsFindsAll() throws Exception {
        String source = """
                interface Doer {
                    void doWork()
                    String status()
                }
                class Worker implements Doer {}
                """;
        ModuleNode module = parseModule(source, "file:///CAMissing.groovy");
        ClassNode worker = findClass(module, "Worker");

        List<MethodNode> missing = invokeFindMissingInterfaceMethods(worker, module);
        assertNotNull(missing);
        assertTrue(missing.size() >= 2,
                "Expected at least 2 missing methods but got " + missing.size());
    }

    @Test
    void findMissingInterfaceMethodsEmptyWhenImplemented() throws Exception {
        String source = """
                interface Doer { void doWork() }
                class Worker implements Doer {
                    void doWork() { }
                }
                """;
        ModuleNode module = parseModule(source, "file:///CANoMissing.groovy");
        ClassNode worker = findClass(module, "Worker");

        List<MethodNode> missing = invokeFindMissingInterfaceMethods(worker, module);
        assertNotNull(missing);
        assertTrue(missing.isEmpty());
    }

    // ---- isMethodRequiredForImplementation ----

    @Test
    void isMethodRequiredForImplementationTrueForAbstract() throws Exception {
        String source = """
                interface Doer { void doWork() }
                """;
        ModuleNode module = parseModule(source, "file:///CARequired.groovy");
        MethodNode method = findClass(module, "Doer").getMethods().stream()
                .filter(m -> "doWork".equals(m.getName()))
                .findFirst().orElse(null);
        assertNotNull(method);

        boolean required = invokeIsMethodRequired(method);
        assertTrue(required);
    }

    // ---- hasSameParameterTypes ----

    @Test
    void hasSameParameterTypesReturnsTrueForMatching() throws Exception {
        String source = """
                interface A { void foo(String a, int b) }
                interface B { void foo(String x, int y) }
                """;
        ModuleNode module = parseModule(source, "file:///CASameParams.groovy");
        MethodNode methodA = findClass(module, "A").getMethods().stream()
                .filter(m -> "foo".equals(m.getName())).findFirst().orElse(null);
        MethodNode methodB = findClass(module, "B").getMethods().stream()
                .filter(m -> "foo".equals(m.getName())).findFirst().orElse(null);
        assertNotNull(methodA);
        assertNotNull(methodB);

        assertTrue(invokeHasSameParameterTypes(methodA, methodB));
    }

    @Test
    void hasSameParameterTypesReturnsFalseForDifferent() throws Exception {
        String source = """
                interface A { void foo(String a) }
                interface B { void foo(int a) }
                """;
        ModuleNode module = parseModule(source, "file:///CADiffParams.groovy");
        MethodNode methodA = findClass(module, "A").getMethods().stream()
                .filter(m -> "foo".equals(m.getName())).findFirst().orElse(null);
        MethodNode methodB = findClass(module, "B").getMethods().stream()
                .filter(m -> "foo".equals(m.getName())).findFirst().orElse(null);
        assertNotNull(methodA);
        assertNotNull(methodB);

        assertFalse(invokeHasSameParameterTypes(methodA, methodB));
    }

    // ---- hasConcreteMethodInHierarchy ----

    @Test
    void hasConcreteMethodInHierarchyFalseForNoImpl() throws Exception {
        String source = """
                interface Doer { void doWork() }
                class Worker implements Doer {}
                """;
        ModuleNode module = parseModule(source, "file:///CAConcreteNo.groovy");
        ClassNode worker = findClass(module, "Worker");
        MethodNode method = findClass(module, "Doer").getMethods().stream()
                .filter(m -> "doWork".equals(m.getName())).findFirst().orElse(null);
        assertNotNull(method);

        assertFalse(invokeHasConcreteMethodInHierarchy(worker, method));
    }

    @Test
    void hasConcreteMethodInHierarchyTrueWhenImplemented() throws Exception {
        String source = """
                interface Doer { void doWork() }
                class Worker implements Doer {
                    void doWork() { println 'working' }
                }
                """;
        ModuleNode module = parseModule(source, "file:///CAConcreteYes.groovy");
        ClassNode worker = findClass(module, "Worker");
        MethodNode method = findClass(module, "Doer").getMethods().stream()
                .filter(m -> "doWork".equals(m.getName())).findFirst().orElse(null);
        assertNotNull(method);

        assertTrue(invokeHasConcreteMethodInHierarchy(worker, method));
    }

    // ---- methodSignatureKey ----

    @Test
    void methodSignatureKeyIncludesNameAndParams() throws Exception {
        String source = """
                interface API { String process(String input, int count) }
                """;
        ModuleNode module = parseModule(source, "file:///CASigKey.groovy");
        MethodNode method = findClass(module, "API").getMethods().stream()
                .filter(m -> "process".equals(m.getName())).findFirst().orElse(null);
        assertNotNull(method);

        String key = invokeMethodSignatureKey(method);
        assertNotNull(key);
        assertTrue(key.contains("process"));
    }

    // ---- findClassInsertLine ----

    @Test
    void findClassInsertLineFindsClosingBrace() throws Exception {
        String source = "class A {\n    void foo() {}\n}\n";
        ModuleNode module = parseModule(source, "file:///CAInsertLine.groovy");
        ClassNode clazz = findClass(module, "A");

        int line = invokeFindClassInsertLine(clazz, source);
        assertTrue(line >= 1, "Insert line should be at least 1 but got " + line);
    }

    // ---- buildMissingMethodStubsText ----

    @Test
    void buildMissingMethodStubsTextGeneratesStubs() throws Exception {
        String source = """
                interface Doer {
                    void doWork()
                    String status()
                }
                class Worker implements Doer {
                }
                """;
        ModuleNode module = parseModule(source, "file:///CAStubs.groovy");
        ClassNode worker = findClass(module, "Worker");

        List<MethodNode> missing = invokeFindMissingInterfaceMethods(worker, module);
        if (!missing.isEmpty()) {
            String stubs = invokeBuildMissingMethodStubsText(worker, missing, source, 5);
            assertNotNull(stubs);
            assertTrue(stubs.contains("doWork") || stubs.contains("status"));
        }
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private int invokePackagePriority(String fqn) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("packagePriority", String.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, fqn);
    }

    private String invokeExtractTypeName(String message) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("extractTypeNameFromMessage", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, message);
    }

    private String invokeGetDiagnosticMessage(Diagnostic d) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("getDiagnosticMessage", Diagnostic.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, d);
    }

    private boolean invokeIsUnresolvedType(Diagnostic d) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("isUnresolvedTypeDiagnostic", Diagnostic.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, d);
    }

    private boolean invokeIsMissingInterface(Diagnostic d) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("isMissingInterfaceMemberDiagnostic", Diagnostic.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, d);
    }

    private String invokeSanitize(String name, int position) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("sanitizeParameterName", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, name, position);
    }

    private String invokeInferIndentUnit(String[] lines, String classIndent,
                                         int classLine, int insertLine) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("inferIndentUnit",
                String[].class, String.class, int.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, lines, classIndent, classLine, insertLine);
    }

    private String invokeLeadingWhitespace(String line) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("leadingWhitespace", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, line);
    }

    private String invokeRenderType(ClassNode type) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("renderType", ClassNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, type);
    }

    private String invokeRenderParameters(Parameter[] params) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("renderParameters", Parameter[].class);
        m.setAccessible(true);
        return (String) m.invoke(provider, (Object) params);
    }

    private String invokeNormalizeTypeName(ClassNode type) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("normalizeTypeName", ClassNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, type);
    }

    private String invokeMethodSignatureKey(org.codehaus.groovy.ast.MethodNode method) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("methodSignatureKey",
                org.codehaus.groovy.ast.MethodNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, method);
    }

    private int invokeCAFindImportInsertLine(String content) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("findImportInsertLine", String.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content);
    }

    private boolean invokeWantsKind(java.util.List<String> onlyKinds, String... expected) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("wantsKind",
                java.util.List.class, String[].class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, onlyKinds, expected);
    }

    private ClassNode invokeFindEnclosingClass(ModuleNode module, int targetLine) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("findEnclosingClass",
                ModuleNode.class, int.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, module, targetLine);
    }

    private ClassNode invokeFindClassBySimpleName(ModuleNode module, String simpleName) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("findClassBySimpleName",
                ModuleNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, module, simpleName);
    }

    private String invokeExtractClassName(String message) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("extractClassSimpleNameFromMessage", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, message);
    }

    private String invokeGetDiagnosticCode(Diagnostic d) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("getDiagnosticCode", Diagnostic.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, d);
    }

    @SuppressWarnings("unchecked")
    private List<MethodNode> invokeFindMissingInterfaceMethods(ClassNode targetClass, ModuleNode module) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("findMissingInterfaceMethods", ClassNode.class, ModuleNode.class);
        m.setAccessible(true);
        return (List<MethodNode>) m.invoke(provider, targetClass, module);
    }

    private boolean invokeIsMethodRequired(MethodNode method) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("isMethodRequiredForImplementation", MethodNode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, method);
    }

    private boolean invokeHasSameParameterTypes(MethodNode left, MethodNode right) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("hasSameParameterTypes", MethodNode.class, MethodNode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, left, right);
    }

    private boolean invokeHasConcreteMethodInHierarchy(ClassNode cls, MethodNode required) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("hasConcreteMethodInHierarchy", ClassNode.class, MethodNode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, cls, required);
    }

    private int invokeFindClassInsertLine(ClassNode cls, String content) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("findClassInsertLine", ClassNode.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, cls, content);
    }

    private String invokeBuildMissingMethodStubsText(ClassNode cls, List<MethodNode> missing, String content, int insertLine) throws Exception {
        Method m = CodeActionProvider.class.getDeclaredMethod("buildMissingMethodStubsText", ClassNode.class, List.class, String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, cls, missing, content, insertLine);
    }

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        if (!result.hasAST()) {
            throw new AssertionError("Expected AST for fixture");
        }
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(c -> simpleName.equals(c.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }
}
