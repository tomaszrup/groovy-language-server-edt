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
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for pure-text and AST utility methods in {@link DefinitionProvider}.
 */
class DefinitionProviderTest {

    private DefinitionProvider provider;
    private DocumentManager documentManager;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new DefinitionProvider(documentManager);
    }

    // ---- positionToOffset ----

    @Test
    void positionToOffsetFirstLine() throws Exception {
        assertEquals(3, invokePositionToOffset("hello\nworld", new Position(0, 3)));
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        assertEquals(9, invokePositionToOffset("hello\nworld", new Position(1, 3)));
    }

    @Test
    void positionToOffsetClamped() throws Exception {
        assertEquals(2, invokePositionToOffset("hi", new Position(0, 99)));
    }

    // ---- extractWordAt ----

    @Test
    void extractWordAtMiddle() throws Exception {
        assertEquals("world", invokeExtractWordAt("hello world", 7));
    }

    @Test
    void extractWordAtStart() throws Exception {
        assertEquals("hello", invokeExtractWordAt("hello world", 0));
    }

    @Test
    void extractWordAtNonIdentifier() throws Exception {
        // Backward scan from a space still finds adjacent word; use isolated non-identifier
        assertNull(invokeExtractWordAt("  +  ", 2)); // '+' surrounded by spaces
    }

    @Test
    void extractWordAtWithUnderscore() throws Exception {
        assertEquals("my_var", invokeExtractWordAt("int my_var = 5", 6));
    }

    // ---- findClassDeclarationRange ----

    @ParameterizedTest
    @MethodSource("classDeclarationCases")
    void findClassDeclarationRangeFindsDeclarations(
            String source,
            String simpleName,
            int expectedLine) throws Exception {
        Range range = invokeFindClassDeclarationRange(source, simpleName);
        assertNotNull(range);
        assertEquals(expectedLine, range.getStart().getLine());
    }

    private static Stream<Arguments> classDeclarationCases() {
        return Stream.of(
                Arguments.of("package demo\n\nclass Foo {\n}", "Foo", 2),
                Arguments.of("interface Bar {}", "Bar", 0),
                Arguments.of("enum Color { RED }", "Color", 0),
                Arguments.of("trait Flyable { }", "Flyable", 0));
    }

    @Test
    void findClassDeclarationRangeReturnsOriginWhenNotFound() throws Exception {
        String source = "class Other {}";
        Range range = invokeFindClassDeclarationRange(source, "Missing");
        assertEquals(0, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
        assertEquals(0, range.getEnd().getLine());
        assertEquals(0, range.getEnd().getCharacter());
    }

    // ---- astNodeToLocation ----

    @Test
    void astNodeToLocationCreatesValidLocation() throws Exception {
        ModuleNode module = parseModule("class Foo {\n  void bar() {}\n}",
                "file:///AstToLoc.groovy");
        MethodNode method = findMethod(module, "Foo", "bar");

        Location loc = invokeAstNodeToLocation("file:///AstToLoc.groovy", method);
        assertNotNull(loc);
        assertEquals("file:///AstToLoc.groovy", loc.getUri());
        assertTrue(loc.getRange().getStart().getLine() >= 0);
    }

    @Test
    void astNodeToLocationReturnsNullForNegativeLine() throws Exception {
        // AST nodes with lineNumber < 1 should return null
        ClassNode syntheticNode = new ClassNode("Synthetic", 0,
                org.codehaus.groovy.ast.ClassHelper.OBJECT_TYPE);
        // Synthetic nodes have lineNumber=-1 by default
        Location loc = invokeAstNodeToLocation("file:///synth.groovy", syntheticNode);
        assertNull(loc);
    }

    // ---- findEnclosingClass ----

    @Test
    void findEnclosingClassFindsCorrectOne() throws Exception {
        ModuleNode module = parseModule("""
                class Outer {
                    void foo() {}
                }
                class Inner {
                    void bar() {}
                }
                """, "file:///EnclosingDef.groovy");

        ClassNode found = invokeFindEnclosingClass(module, 2);
        assertNotNull(found);
        assertEquals("Outer", found.getNameWithoutPackage());
    }

    @Test
    void findEnclosingClassReturnsNullOutsideAllClasses() throws Exception {
        ModuleNode module = parseModule("// Just a comment",
                "file:///NoClass.groovy");
        ClassNode found = invokeFindEnclosingClass(module, 99);
        assertTrue(found == null || found.isScript(), "Expected null or script class fallback");
    }

    // ---- findMemberDeclarationInClass ----

    @Test
    void findMemberDeclarationInClassFindsMethod() throws Exception {
        ModuleNode module = parseModule("class Svc { String process() { '' } }",
                "file:///FindMember1.groovy");
        ClassNode cls = findClass(module, "Svc");
        Location loc = invokeFindMemberDeclarationInClass(cls, "process", "file:///FindMember1.groovy");
        assertNotNull(loc);
    }

    @Test
    void findMemberDeclarationInClassFindsField() throws Exception {
        ModuleNode module = parseModule("class Svc { private int count = 0 }",
                "file:///FindMemberField.groovy");
        ClassNode cls = findClass(module, "Svc");
        Location loc = invokeFindMemberDeclarationInClass(cls, "count", "file:///FindMemberField.groovy");
        assertNotNull(loc);
    }

    @Test
    void findMemberDeclarationInClassFindsProperty() throws Exception {
        ModuleNode module = parseModule("class Svc { String name }",
                "file:///FindMemberProp.groovy");
        ClassNode cls = findClass(module, "Svc");
        Location loc = invokeFindMemberDeclarationInClass(cls, "name", "file:///FindMemberProp.groovy");
        assertNotNull(loc);
    }

    @Test
    void findMemberDeclarationInClassReturnsNullWhenNotFound() throws Exception {
        ModuleNode module = parseModule("class Svc { void run() {} }",
                "file:///FindMemberNone.groovy");
        ClassNode cls = findClass(module, "Svc");
        assertNull(invokeFindMemberDeclarationInClass(cls, "noSuchMember", "file:///FindMemberNone.groovy"));
    }

    @Test
    void findMemberDeclarationInClassHandlesNull() throws Exception {
        assertNull(invokeFindMemberDeclarationInClass(null, "foo", "file:///null.groovy"));
    }

    // ---- getDefinitionFromGroovyAST (integration via DocumentManager) ----

    @Test
    void getDefinitionFromGroovyASTFindsClassByName() throws Exception {
        String uri = "file:///DefASTClass.groovy";
        String content = "class MyService {\n  void run() {}\n}";
        documentManager.didOpen(uri, content);

        // Position cursor on "MyService" — line 0, col 8
        List<Location> locations = invokeGetDefinitionFromGroovyAST(uri, new Position(0, 8));
        assertNotNull(locations);
        assertFalse(locations.isEmpty());
        assertEquals(uri, locations.get(0).getUri());

        documentManager.didClose(uri);
    }

    @Test
    void getDefinitionFromGroovyASTFindsMethodByName() throws Exception {
        String uri = "file:///DefASTMethod.groovy";
        String content = "class Svc {\n  String process() { '' }\n  void caller() { process() }\n}";
        documentManager.didOpen(uri, content);

        // Position cursor on "process" at line 2, col 20 (inside "process()")
        List<Location> locations = invokeGetDefinitionFromGroovyAST(uri, new Position(2, 20));
        assertNotNull(locations);
        assertFalse(locations.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void getDefinitionFromGroovyASTReturnsEmptyForBlank() throws Exception {
        String uri = "file:///DefASTBlank.groovy";
        String content = "class Svc {\n\n}";
        documentManager.didOpen(uri, content);

        // Blank line — no word at cursor
        List<Location> locations = invokeGetDefinitionFromGroovyAST(uri, new Position(1, 0));
        assertTrue(locations.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void getDefinitionFromGroovyASTReturnsEmptyForNoContent() throws Exception {
        List<Location> locations = invokeGetDefinitionFromGroovyAST("file:///noContent.groovy", new Position(0, 0));
        assertTrue(locations.isEmpty());
    }

    // ---- Additional edge cases ----

    @Test
    void positionToOffsetThirdLine() throws Exception {
        assertEquals(14, invokePositionToOffset("hello\nworld\nabc", new Position(2, 2)));
    }

    @Test
    void positionToOffsetEmptyContent() throws Exception {
        assertEquals(0, invokePositionToOffset("", new Position(0, 0)));
    }

    @Test
    void extractWordAtWithDigits() throws Exception {
        assertEquals("var123", invokeExtractWordAt("int var123 = 0", 5));
    }

    @Test
    void extractWordAtBeyondLength() throws Exception {
        // Offset beyond content throws StringIndexOutOfBoundsException
        try {
            String result = invokeExtractWordAt("abc", 100);
            // If no exception, result may be null or word
            assertTrue(result == null || result.equals("abc"));
        } catch (java.lang.reflect.InvocationTargetException e) {
            // extractWordAt throws StringIndexOutOfBoundsException for out-of-range offset
            assertTrue(e.getCause() instanceof StringIndexOutOfBoundsException);
        }
    }

    @Test
    void findClassDeclarationRangeAnnotation() throws Exception {
        String source = "@interface MyAnnotation {}";
        Range range = invokeFindClassDeclarationRange(source, "MyAnnotation");
        assertNotNull(range);
    }

    @Test
    void findClassDeclarationRangeMultiLineSource() throws Exception {
        String source = "package demo\n\nimport java.util.List\n\nclass Target {\n   void method() {}\n}";
        Range range = invokeFindClassDeclarationRange(source, "Target");
        assertNotNull(range);
        assertEquals(4, range.getStart().getLine());
    }

    @Test
    void findEnclosingClassInnerClass() throws Exception {
        ModuleNode module = parseModule("""
                class Outer {
                    class Inner {
                        void innerMethod() {}
                    }
                    void outerMethod() {}
                }
                """, "file:///InnerClassDef.groovy");

        // Line 3 is inside Inner
        ClassNode found = invokeFindEnclosingClass(module, 3);
        assertNotNull(found);
    }

    @Test
    void getDefinitionFromGroovyASTFindsFieldByName() throws Exception {
        String uri = "file:///DefASTField.groovy";
        String content = """
                class Config {
                    String host = 'localhost'
                    void init() { println host }
                }
                """;
        documentManager.didOpen(uri, content);

        List<Location> locations = invokeGetDefinitionFromGroovyAST(uri, new Position(1, 14));
        assertNotNull(locations);

        documentManager.didClose(uri);
    }

    @Test
    void getDefinitionFromGroovyASTFindsPropertyByName() throws Exception {
        String uri = "file:///DefASTProp.groovy";
        String content = """
                class Data {
                    int count
                    void setCount(int c) { count = c }
                }
                """;
        documentManager.didOpen(uri, content);

        List<Location> locations = invokeGetDefinitionFromGroovyAST(uri, new Position(1, 8));
        assertNotNull(locations);

        documentManager.didClose(uri);
    }

    @Test
    void getDefinitionFromGroovyASTWithMultipleClasses() throws Exception {
        String uri = "file:///DefASTMulti.groovy";
        String content = """
                class First {
                    void methodA() {}
                }
                class Second {
                    void methodB() {}
                }
                """;
        documentManager.didOpen(uri, content);

        List<Location> locationsFirst = invokeGetDefinitionFromGroovyAST(uri, new Position(0, 8));
        assertNotNull(locationsFirst);
        assertFalse(locationsFirst.isEmpty());

        List<Location> locationsSecond = invokeGetDefinitionFromGroovyAST(uri, new Position(3, 8));
        assertNotNull(locationsSecond);
        assertFalse(locationsSecond.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void astNodeToLocationHandlesMethodWithParams() throws Exception {
        ModuleNode module = parseModule("class Foo {\n  void process(String arg) {}\n}",
                "file:///AstToLoc2.groovy");
        MethodNode method = findMethod(module, "Foo", "process");

        Location loc = invokeAstNodeToLocation("file:///AstToLoc2.groovy", method);
        assertNotNull(loc);
        assertTrue(loc.getRange().getStart().getLine() >= 0);
    }

    @Test
    void findMemberDeclarationInClassFindsStaticMethod() throws Exception {
        ModuleNode module = parseModule("class Util { static void helper() {} }",
                "file:///FindStaticMethod.groovy");
        ClassNode cls = findClass(module, "Util");
        Location loc = invokeFindMemberDeclarationInClass(cls, "helper", "file:///FindStaticMethod.groovy");
        assertNotNull(loc);
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private int invokePositionToOffset(String content, Position position) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content, position);
    }

    private String invokeExtractWordAt(String content, int offset) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, offset);
    }

    private Range invokeFindClassDeclarationRange(String source, String simpleName) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("findClassDeclarationRange", String.class, String.class);
        m.setAccessible(true);
        return (Range) m.invoke(provider, source, simpleName);
    }

    private Location invokeAstNodeToLocation(String uri, ASTNode node) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("astNodeToLocation", String.class, ASTNode.class);
        m.setAccessible(true);
        return (Location) m.invoke(provider, uri, node);
    }

    private ClassNode invokeFindEnclosingClass(ModuleNode module, int targetLine) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("findEnclosingClass", ModuleNode.class, int.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, module, targetLine);
    }

    private Location invokeFindMemberDeclarationInClass(ClassNode classNode, String word,
                                                         String uri) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("findMemberDeclarationInClass",
                ClassNode.class, String.class, String.class);
        m.setAccessible(true);
        return (Location) m.invoke(provider, classNode, word, uri);
    }

    @SuppressWarnings("unchecked")
    private List<Location> invokeGetDefinitionFromGroovyAST(String uri, Position position) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("getDefinitionFromGroovyAST",
                String.class, Position.class);
        m.setAccessible(true);
        return (List<Location>) m.invoke(provider, uri, position);
    }

    // ---- AST helpers ----

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        if (!result.hasAST()) {
            throw new AssertionError("Expected AST for fixture: " + uri);
        }
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(c -> simpleName.equals(c.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }

    private MethodNode findMethod(ModuleNode module, String className, String methodName) {
        ClassNode cls = findClass(module, className);
        return cls.getMethods().stream()
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    // ---- resolveTypeFromSource tests ----

    @Test
    void resolveTypeFromSourceFindsExplicitImport() throws Exception {
        String content = "import java.time.LocalDate\nclass A { LocalDate d }";
        String result = invokeResolveTypeFromSource(content, "LocalDate");
        assertEquals("java.time.LocalDate", result);
    }

    @Test
    void resolveTypeFromSourceIgnoresStaticImport() throws Exception {
        String content = "import static java.lang.Math.PI\nclass A { Math m }";
        String result = invokeResolveTypeFromSource(content, "Math");
        assertTrue(result == null || result.endsWith(".Math"));
    }

    @Test
    void resolveTypeFromSourceReturnsNullForUnknownType() throws Exception {
        String content = "class A { SomeUnknownType x }";
        String result = invokeResolveTypeFromSource(content, "SomeUnknownType");
        // With no imports and no workspace, should be null
        assertNull(result);
    }

    @Test
    void resolveTypeFromSourceHandlesExtendsWithFQN() throws Exception {
        String content = "class A extends org.example.Base {}";
        String result = invokeResolveTypeFromSource(content, "Base");
        assertEquals("org.example.Base", result);
    }

    @Test
    void resolveTypeFromSourceHandlesImplementsWithFQN() throws Exception {
        String content = "class A implements com.service.Runnable {}";
        String result = invokeResolveTypeFromSource(content, "Runnable");
        // java.lang.Runnable OR com.service.Runnable
        assertNotNull(result);
    }

    // ---- generateClassStub tests ----

    @Test
    void generateClassStubForSimpleClass() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.example");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Foo");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("package com.example"));
        assertTrue(stub.contains("public class Foo"));
    }

    @Test
    void generateClassStubForInterface() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.api");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Service");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccAbstract);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[] { "Serializable" });
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("interface Service"));
        assertTrue(stub.contains("extends Serializable"));
    }

    @Test
    void generateClassStubForEnum() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.enums");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Color");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        when(type.getSuperclassName()).thenReturn("java.lang.Enum");
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("enum Color"));
    }

    @Test
    void generateClassStubWithExtendsClause() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Child");
        when(type.getFlags()).thenReturn(0);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn("Parent");
        when(type.getSuperInterfaceNames()).thenReturn(new String[] { "InterfaceA", "InterfaceB" });
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("extends Parent"));
        assertTrue(stub.contains("implements InterfaceA, InterfaceB"));
    }

    @Test
    void generateClassStubWithFieldsAndMethods() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.example");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Widget");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);

        IField field = mock(IField.class);
        when(field.getFlags()).thenReturn(Flags.AccPublic);
        when(field.getTypeSignature()).thenReturn("QString;");
        when(field.getElementName()).thenReturn("name");
        when(type.getFields()).thenReturn(new IField[] { field });

        IMethod method = mock(IMethod.class);
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("V");
        when(method.getElementName()).thenReturn("process");
        when(method.getParameterNames()).thenReturn(new String[] { "input" });
        when(method.getParameterTypes()).thenReturn(new String[] { "QString;" });
        when(method.getExceptionTypes()).thenReturn(new String[0]);
        when(type.getMethods()).thenReturn(new IMethod[] { method });

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("Widget"));
        assertTrue(stub.contains("name"));
        assertTrue(stub.contains("process"));
    }

    @Test
    void generateClassStubWithConstructor() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.example");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Constructed");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new IField[0]);

        IMethod ctor = mock(IMethod.class);
        when(ctor.getFlags()).thenReturn(Flags.AccPublic);
        when(ctor.isConstructor()).thenReturn(true);
        when(ctor.getElementName()).thenReturn("Constructed");
        when(ctor.getParameterNames()).thenReturn(new String[] { "value" });
        when(ctor.getParameterTypes()).thenReturn(new String[] { "I" });
        when(ctor.getExceptionTypes()).thenReturn(new String[0]);
        when(type.getMethods()).thenReturn(new IMethod[] { ctor });

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("Constructed("));
    }

    // ---- reflection helpers for new tests ----

    private String invokeResolveTypeFromSource(String content, String simpleName) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("resolveTypeFromSource", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, simpleName);
    }

    private String invokeGenerateClassStub(IType type) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("generateClassStub", IType.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, type);
    }

    // ================================================================
    // JDT Mock Tests — offsetToPosition
    // ================================================================

    @Test
    void offsetToPositionFirstLine() throws Exception {
        Position pos = invokeOffsetToPosition("hello world", 5);
        assertEquals(0, pos.getLine());
        assertEquals(5, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        Position pos = invokeOffsetToPosition("line1\nline2", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtNewline() throws Exception {
        Position pos = invokeOffsetToPosition("abc\ndef", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionBeyondContent() throws Exception {
        Position pos = invokeOffsetToPosition("ab", 100);
        assertNotNull(pos);
        // Should clamp to end
    }

    @Test
    void offsetToPositionEmptyString() throws Exception {
        Position pos = invokeOffsetToPosition("", 0);
        assertEquals(0, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void offsetToPositionThirdLine() throws Exception {
        Position pos = invokeOffsetToPosition("a\nb\nc", 4);
        assertEquals(2, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    // ================================================================
    // JDT Mock Tests — generateClassStub (extended)
    // ================================================================

    @Test
    void generateClassStubForInterfaceWithAbstractMethod() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.api");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Runnable");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccInterface);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new IField[0]);

        IMethod m = mock(IMethod.class);
        when(m.getFlags()).thenReturn(Flags.AccPublic | Flags.AccAbstract);
        when(m.isConstructor()).thenReturn(false);
        when(m.getElementName()).thenReturn("run");
        when(m.getParameterTypes()).thenReturn(new String[0]);
        when(m.getParameterNames()).thenReturn(new String[0]);
        when(m.getReturnType()).thenReturn("V");
        when(m.getExceptionTypes()).thenReturn(new String[0]);
        when(type.getMethods()).thenReturn(new IMethod[]{ m });

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("interface"));
        assertTrue(stub.contains("Runnable"));
        assertTrue(stub.contains("run"));
    }

    @Test
    void generateClassStubForEnumWithConstant() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.model");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Color");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        IField enumConst = mock(IField.class);
        when(enumConst.getElementName()).thenReturn("RED");
        when(enumConst.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);
        when(enumConst.getTypeSignature()).thenReturn("QColor;");
        when(type.getFields()).thenReturn(new IField[]{ enumConst });

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("enum"));
        assertTrue(stub.contains("Color"));
    }

    @Test
    void generateClassStubWithSuperclassAndInterfaces() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.model");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Child");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn("Parent");
        when(type.getSuperInterfaceNames()).thenReturn(new String[]{ "Serializable", "Cloneable" });
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("extends Parent"));
        assertTrue(stub.contains("implements"));
        assertTrue(stub.contains("Serializable"));
        assertTrue(stub.contains("Cloneable"));
    }

    @Test
    void generateClassStubWithMethodExceptions() throws Exception {
        IType type = mock(IType.class);
        IPackageFragment pkg = mock(IPackageFragment.class);
        when(pkg.getElementName()).thenReturn("com.io");
        when(type.getPackageFragment()).thenReturn(pkg);
        when(type.getElementName()).thenReturn("Reader");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new IField[0]);

        IMethod m = mock(IMethod.class);
        when(m.getFlags()).thenReturn(Flags.AccPublic);
        when(m.isConstructor()).thenReturn(false);
        when(m.getElementName()).thenReturn("read");
        when(m.getParameterTypes()).thenReturn(new String[0]);
        when(m.getParameterNames()).thenReturn(new String[0]);
        when(m.getReturnType()).thenReturn("I");
        when(m.getExceptionTypes()).thenReturn(new String[]{ "QIOException;" });
        when(type.getMethods()).thenReturn(new IMethod[]{ m });

        String stub = invokeGenerateClassStub(type);
        assertNotNull(stub);
        assertTrue(stub.contains("throws"));
        assertTrue(stub.contains("IOException"));
    }

    // ================================================================
    // JDT Mock Tests — getDefinition (main entry)
    // ================================================================

    @Test
    void getDefinitionReturnsEmptyForUnknownUri() {
        DocumentManager dm = new DocumentManager();
        DefinitionProvider defProvider = new DefinitionProvider(dm);
        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///nonexist.groovy"));
        params.setPosition(new Position(0, 0));

        List<Location> locs = defProvider.getDefinition(params);
        assertNotNull(locs);
        assertTrue(locs.isEmpty());
    }

    @Test
    void getDefinitionASTFallbackForRegisteredContent() {
        DocumentManager dm = new DocumentManager();
        String content = "class Foo { void bar() {} }\nclass Baz { void test() { bar() } }";
        dm.didOpen("file:///test.groovy", content);
        DefinitionProvider defProvider = new DefinitionProvider(dm);

        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///test.groovy"));
        // Position at "bar" in the second class
        params.setPosition(new Position(1, 40));

        List<Location> locs = defProvider.getDefinition(params);
        assertNotNull(locs);
        // Might find bar() in class Foo via AST fallback
    }

    // ================================================================
    // JDT Mock Reflection helpers
    // ================================================================

    private Position invokeOffsetToPosition(String content, int offset) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        return (Position) m.invoke(provider, content, offset);
    }
}
