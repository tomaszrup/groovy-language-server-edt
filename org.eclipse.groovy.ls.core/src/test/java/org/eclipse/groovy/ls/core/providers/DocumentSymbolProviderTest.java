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
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Groovy AST fallback path and helpers in {@link DocumentSymbolProvider}.
 */
class DocumentSymbolProviderTest {

    private DocumentSymbolProvider provider;
    private DocumentManager documentManager;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new DocumentSymbolProvider(documentManager);
    }

    // ---- offsetToPosition ----

    @Test
    void offsetToPositionFirstLine() throws Exception {
        Position pos = invokeOffsetToPosition("hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        Position pos = invokeOffsetToPosition("hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionClamped() throws Exception {
        Position pos = invokeOffsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    // ---- toDocumentSymbolFromAST ----

    @Test
    void toDocumentSymbolFromASTClassWithMembers() throws Exception {
        ModuleNode module = parseModule("""
                class Foo {
                    String name
                    int count
                    void greet(String msg) {}
                }
                """, "file:///DocSymClass.groovy");

        ClassNode cls = findClass(module, "Foo");
        String content = "class Foo {\n    String name\n    int count\n    void greet(String msg) {}\n}\n";
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, content);

        assertNotNull(symbol);
        assertEquals("Foo", symbol.getName());
        assertEquals(SymbolKind.Class, symbol.getKind());

        List<DocumentSymbol> children = symbol.getChildren();
        assertNotNull(children);
        // At least: name (property), count (property), greet (method)
        assertTrue(children.size() >= 3, "Expected at least 3 children, got " + children.size());
        assertTrue(children.stream().anyMatch(c -> "name".equals(c.getName())));
        assertTrue(children.stream().anyMatch(c -> "count".equals(c.getName())));
        assertTrue(children.stream().anyMatch(c -> "greet".equals(c.getName())));
    }

    @Test
    void toDocumentSymbolFromASTInterface() throws Exception {
        ModuleNode module = parseModule("interface Greeter { void greet() }",
                "file:///DocSymInterface.groovy");
        ClassNode cls = findClass(module, "Greeter");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, "interface Greeter { void greet() }");

        assertEquals("Greeter", symbol.getName());
        assertEquals(SymbolKind.Interface, symbol.getKind());
    }

    @Test
    void toDocumentSymbolFromASTEnum() throws Exception {
        ModuleNode module = parseModule("enum Color { RED, GREEN, BLUE }",
                "file:///DocSymEnum.groovy");
        ClassNode cls = findClass(module, "Color");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, "enum Color { RED, GREEN, BLUE }");

        assertEquals("Color", symbol.getName());
        assertEquals(SymbolKind.Enum, symbol.getKind());
        // Enum constants should appear as children
        assertTrue(symbol.getChildren().stream().anyMatch(c -> "RED".equals(c.getName())));
    }

    @Test
    void toDocumentSymbolFromASTWithSuper() throws Exception {
        ModuleNode module = parseModule("""
                class Base {}
                class Child extends Base {}
                """, "file:///DocSymExtends.groovy");
        ClassNode child = findClass(module, "Child");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(child,
                "class Base {}\nclass Child extends Base {}\n");

        assertNotNull(symbol.getDetail());
        assertTrue(symbol.getDetail().contains("extends"));
        assertTrue(symbol.getDetail().contains("Base"));
    }

    // ---- setRangesFromAST ----

    @Test
    void setRangesFromASTSetsValidRanges() throws Exception {
        ModuleNode module = parseModule("class Foo { void bar() {} }",
                "file:///SetRanges.groovy");
        ClassNode cls = findClass(module, "Foo");

        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("Foo");
        invokeSetRangesFromAST(symbol, cls, "class Foo { void bar() {} }");

        assertNotNull(symbol.getRange());
        assertNotNull(symbol.getSelectionRange());
        assertTrue(symbol.getRange().getStart().getLine() >= 0);
    }

    @Test
    void setRangesFromASTHandlesNullNode() throws Exception {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("Test");
        invokeSetRangesFromAST(symbol, null, "class Test {}");

        assertEquals(0, symbol.getRange().getStart().getLine());
        assertEquals(0, symbol.getRange().getStart().getCharacter());
    }

    // ---- getDocumentSymbolsFromGroovyAST (via DocumentManager) ----

    @Test
    void getDocumentSymbolsFromGroovyASTReturnsClassesAndMembers() throws Exception {
        String uri = "file:///DocSymAST.groovy";
        String content = """
                class Foo {
                    String name
                    void doStuff() {}
                }
                """;

        documentManager.didOpen(uri, content);
        List<Either<SymbolInformation, DocumentSymbol>> symbols = invokeGetDocumentSymbolsFromGroovyAST(uri, content);

        assertNotNull(symbols);
        assertFalse(symbols.isEmpty());
        DocumentSymbol fooSymbol = symbols.stream()
                .filter(Either::isRight)
                .map(Either::getRight)
                .filter(s -> "Foo".equals(s.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(fooSymbol, "Expected 'Foo' symbol");
        assertTrue(fooSymbol.getChildren().stream().anyMatch(c -> "name".equals(c.getName())));
        assertTrue(fooSymbol.getChildren().stream().anyMatch(c -> "doStuff".equals(c.getName())));

        documentManager.didClose(uri);
    }

    @Test
    void getDocumentSymbolsFromGroovyASTReturnsEmptyForNoAST() throws Exception {
        String uri = "file:///DocSymNoAST.groovy";
        // Don't open the document, so there's no AST
        List<Either<SymbolInformation, DocumentSymbol>> symbols =
                invokeGetDocumentSymbolsFromGroovyAST(uri, "class Foo {}");
        assertTrue(symbols.isEmpty());
    }

    // ---- Full getDocumentSymbols (public API, fallback path) ----

    @Test
    void getDocumentSymbolsFallsBackToAST() {
        String uri = "file:///DocSymPublic.groovy";
        String content = """
                class Alpha {
                    int value
                    void compute() {}
                }
                """;

        documentManager.didOpen(uri, content);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
        List<Either<SymbolInformation, DocumentSymbol>> result = provider.getDocumentSymbols(params);

        // Since there's no JDT working copy, the fallback AST path should kick in
        assertNotNull(result);
        assertFalse(result.isEmpty());

        documentManager.didClose(uri);
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    // ---- Additional tests ----

    @Test
    void offsetToPositionAtNewline() throws Exception {
        Position pos = invokeOffsetToPosition("ab\ncd\nef", 2);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtStartOfSecondLine() throws Exception {
        Position pos = invokeOffsetToPosition("ab\ncd\nef", 3);
        assertEquals(1, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void toDocumentSymbolFromASTTrait() throws Exception {
        ModuleNode module = parseModule("trait Flyable { void fly() {} }",
                "file:///DocSymTrait.groovy");
        ClassNode cls = findClass(module, "Flyable");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, "trait Flyable { void fly() {} }");

        assertEquals("Flyable", symbol.getName());
    }

    @Test
    void toDocumentSymbolFromASTWithMultipleMembers() throws Exception {
        ModuleNode module = parseModule("""
                class Container {
                    String name
                    int size
                    void init() {}
                    String toString() { name }
                }
                """, "file:///DocSymMulti.groovy");

        ClassNode cls = findClass(module, "Container");
        String content = "class Container {\n    String name\n    int size\n    void init() {}\n    String toString() { name }\n}\n";
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, content);

        assertNotNull(symbol);
        assertTrue(symbol.getChildren().size() >= 4);
    }

    @Test
    void getDocumentSymbolsFromGroovyASTWithMultipleClasses() throws Exception {
        String uri = "file:///DocSymMultiClass.groovy";
        String content = """
                class First {
                    void doFirst() {}
                }
                class Second {
                    void doSecond() {}
                }
                """;

        documentManager.didOpen(uri, content);
        List<Either<SymbolInformation, DocumentSymbol>> symbols = invokeGetDocumentSymbolsFromGroovyAST(uri, content);

        assertNotNull(symbols);
        assertTrue(symbols.size() >= 2);

        documentManager.didClose(uri);
    }

    @Test
    void getDocumentSymbolsFallsBackToASTForEnum() {
        String uri = "file:///DocSymEnum2.groovy";
        String content = "enum Direction { NORTH, SOUTH, EAST, WEST }";

        documentManager.didOpen(uri, content);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
        List<Either<SymbolInformation, DocumentSymbol>> result = provider.getDocumentSymbols(params);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void getDocumentSymbolsReturnsEmptyForMissingDocument() {
        DocumentSymbolParams params = new DocumentSymbolParams(
                new TextDocumentIdentifier("file:///MissingDocSym.groovy"));
        List<Either<SymbolInformation, DocumentSymbol>> result = provider.getDocumentSymbols(params);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void setRangesFromASTWithMethodNode() throws Exception {
        ModuleNode module = parseModule("class Foo {\n  void method() {}\n}",
                "file:///SetRangesMethod.groovy");
        ClassNode cls = findClass(module, "Foo");
        org.codehaus.groovy.ast.MethodNode method = cls.getMethods().stream()
                .filter(m -> "method".equals(m.getName()))
                .findFirst()
                .orElseThrow();

        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("method");
        invokeSetRangesFromAST(symbol, method, "class Foo {\n  void method() {}\n}");

        assertNotNull(symbol.getRange());
        assertNotNull(symbol.getSelectionRange());
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private Position invokeOffsetToPosition(String content, int offset) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        return (Position) m.invoke(provider, content, offset);
    }

    private DocumentSymbol invokeToDocumentSymbolFromAST(ClassNode classNode, String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("toDocumentSymbolFromAST",
                ClassNode.class);
        m.setAccessible(true);
        return (DocumentSymbol) m.invoke(provider, classNode);
    }

    private void invokeSetRangesFromAST(DocumentSymbol symbol,
                                         org.codehaus.groovy.ast.ASTNode node,
                                         String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("setRangesFromAST",
                DocumentSymbol.class, org.codehaus.groovy.ast.ASTNode.class);
        m.setAccessible(true);
        m.invoke(provider, symbol, node);
    }

    @SuppressWarnings("unchecked")
    private List<Either<SymbolInformation, DocumentSymbol>> invokeGetDocumentSymbolsFromGroovyAST(
            String uri, String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("getDocumentSymbolsFromGroovyAST",
                String.class);
        m.setAccessible(true);
        return (List<Either<SymbolInformation, DocumentSymbol>>) m.invoke(provider, uri);
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

    // ================================================================
    // JDT Mock Tests — getTypeKind
    // ================================================================

    @Test
    void getTypeKindReturnsInterfaceForMockedInterface() throws Exception {
        IType type = mock(IType.class);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        SymbolKind kind = invokeGetTypeKind(type);
        assertEquals(SymbolKind.Interface, kind);
    }

    @Test
    void getTypeKindReturnsEnumForMockedEnum() throws Exception {
        IType type = mock(IType.class);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        SymbolKind kind = invokeGetTypeKind(type);
        assertEquals(SymbolKind.Enum, kind);
    }

    @Test
    void getTypeKindReturnsClassForMockedClass() throws Exception {
        IType type = mock(IType.class);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        SymbolKind kind = invokeGetTypeKind(type);
        assertEquals(SymbolKind.Class, kind);
    }

    @Test
    void getTypeKindReturnsStructForMockedTrait() throws Exception {
        IType type = mock(IType.class);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        IAnnotation ann = mock(IAnnotation.class);
        when(ann.getElementName()).thenReturn("Trait");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{ ann });

        SymbolKind kind = invokeGetTypeKind(type);
        assertEquals(SymbolKind.Struct, kind);
    }

    // ================================================================
    // JDT Mock Tests — isTrait
    // ================================================================

    @Test
    void isTraitReturnsTrueForTraitAnnotation() throws Exception {
        IType type = mock(IType.class);
        IAnnotation ann = mock(IAnnotation.class);
        when(ann.getElementName()).thenReturn("groovy.transform.Trait");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{ ann });

        boolean result = invokeIsTrait(type);
        assertTrue(result);
    }

    @Test
    void isTraitReturnsFalseForNoAnnotations() throws Exception {
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        boolean result = invokeIsTrait(type);
        assertFalse(result);
    }

    @Test
    void isTraitReturnsFalseForOtherAnnotation() throws Exception {
        IType type = mock(IType.class);
        IAnnotation ann = mock(IAnnotation.class);
        when(ann.getElementName()).thenReturn("Override");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{ ann });

        boolean result = invokeIsTrait(type);
        assertFalse(result);
    }

    // ================================================================
    // JDT Mock Tests — toDocumentSymbol(IType, String)
    // ================================================================

    @Test
    void toDocumentSymbolForMockedType() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Foo");
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn("Bar");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getTypes()).thenReturn(new IType[0]);

        // Create mock source range
        ISourceRange sourceRange = mock(ISourceRange.class);
        when(sourceRange.getOffset()).thenReturn(0);
        when(sourceRange.getLength()).thenReturn(10);
        // IType doesn't directly implement ISourceReference in the mock
        // so just test that it doesn't crash

        DocumentSymbol symbol = invokeToDocumentSymbol(type, "class Foo extends Bar {}");
        assertNotNull(symbol);
        assertEquals("Foo", symbol.getName());
        assertEquals(SymbolKind.Class, symbol.getKind());
        assertNotNull(symbol.getDetail());
        assertTrue(symbol.getDetail().contains("extends"));
    }

    @Test
    void toDocumentSymbolForMockedTypeWithFields() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Config");
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getFlags()).thenReturn(Flags.AccPublic);

        IField field1 = mock(IField.class);
        when(field1.getElementName()).thenReturn("name");
        when(field1.getFlags()).thenReturn(Flags.AccPrivate);
        when(field1.getTypeSignature()).thenReturn("QString;");

        IField field2 = mock(IField.class);
        when(field2.getElementName()).thenReturn("count");
        when(field2.getFlags()).thenReturn(Flags.AccPrivate);
        when(field2.getTypeSignature()).thenReturn("I");

        when(type.getFields()).thenReturn(new IField[]{ field1, field2 });
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getTypes()).thenReturn(new IType[0]);

        DocumentSymbol symbol = invokeToDocumentSymbol(type, "class Config { String name; int count; }");
        assertNotNull(symbol);
        assertEquals("Config", symbol.getName());
        List<DocumentSymbol> children = symbol.getChildren();
        assertNotNull(children);
        assertTrue(children.size() >= 2);
        assertTrue(children.stream().anyMatch(c -> "name".equals(c.getName())));
        assertTrue(children.stream().anyMatch(c -> "count".equals(c.getName())));
    }

    @Test
    void toDocumentSymbolForMockedTypeWithMethods() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Service");
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.getFields()).thenReturn(new IField[0]);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("process");
        when(method.isConstructor()).thenReturn(false);
        when(method.getParameterTypes()).thenReturn(new String[]{"QString;"});
        when(method.getParameterNames()).thenReturn(new String[]{"input"});
        when(method.getReturnType()).thenReturn("V");

        IMethod constructor = mock(IMethod.class);
        when(constructor.getElementName()).thenReturn("Service");
        when(constructor.isConstructor()).thenReturn(true);
        when(constructor.getParameterTypes()).thenReturn(new String[0]);
        when(constructor.getParameterNames()).thenReturn(new String[0]);
        when(constructor.getReturnType()).thenReturn("V");

        when(type.getMethods()).thenReturn(new IMethod[]{ method, constructor });
        when(type.getTypes()).thenReturn(new IType[0]);

        DocumentSymbol symbol = invokeToDocumentSymbol(type, "class Service { void process(String input) {} }");
        assertNotNull(symbol);
        List<DocumentSymbol> children = symbol.getChildren();
        assertNotNull(children);
        assertTrue(children.stream().anyMatch(c -> "process".equals(c.getName()) && c.getKind() == SymbolKind.Method));
        assertTrue(children.stream().anyMatch(c -> "Service".equals(c.getName()) && c.getKind() == SymbolKind.Constructor));
    }

    @Test
    void toDocumentSymbolForMockedEnum() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Color");
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);

        IField enumConst = mock(IField.class);
        when(enumConst.getElementName()).thenReturn("RED");
        when(enumConst.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);
        when(enumConst.getTypeSignature()).thenReturn("QColor;");

        when(type.getFields()).thenReturn(new IField[]{ enumConst });
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getTypes()).thenReturn(new IType[0]);

        DocumentSymbol symbol = invokeToDocumentSymbol(type, "enum Color { RED }");
        assertNotNull(symbol);
        assertEquals(SymbolKind.Enum, symbol.getKind());
        assertTrue(symbol.getChildren().stream().anyMatch(
                c -> "RED".equals(c.getName()) && c.getKind() == SymbolKind.EnumMember));
    }

    @Test
    void toDocumentSymbolForMockedInterface() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Greeter");
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccInterface);
        when(type.getFields()).thenReturn(new IField[0]);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("greet");
        when(method.isConstructor()).thenReturn(false);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.getReturnType()).thenReturn("V");

        when(type.getMethods()).thenReturn(new IMethod[]{ method });
        when(type.getTypes()).thenReturn(new IType[0]);

        DocumentSymbol symbol = invokeToDocumentSymbol(type, "interface Greeter { void greet() }");
        assertNotNull(symbol);
        assertEquals(SymbolKind.Interface, symbol.getKind());
    }

    @Test
    void toDocumentSymbolForMockedTypeWithInnerType() throws Exception {
        IType outer = mock(IType.class);
        when(outer.getElementName()).thenReturn("Outer");
        when(outer.isInterface()).thenReturn(false);
        when(outer.isEnum()).thenReturn(false);
        when(outer.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(outer.getSuperclassName()).thenReturn(null);
        when(outer.getFlags()).thenReturn(Flags.AccPublic);
        when(outer.getFields()).thenReturn(new IField[0]);
        when(outer.getMethods()).thenReturn(new IMethod[0]);

        IType inner = mock(IType.class);
        when(inner.getElementName()).thenReturn("Inner");
        when(inner.isInterface()).thenReturn(false);
        when(inner.isEnum()).thenReturn(false);
        when(inner.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(inner.getSuperclassName()).thenReturn(null);
        when(inner.getFlags()).thenReturn(Flags.AccPublic | Flags.AccStatic);
        when(inner.getFields()).thenReturn(new IField[0]);
        when(inner.getMethods()).thenReturn(new IMethod[0]);
        when(inner.getTypes()).thenReturn(new IType[0]);

        when(outer.getTypes()).thenReturn(new IType[]{ inner });

        DocumentSymbol symbol = invokeToDocumentSymbol(outer, "class Outer { static class Inner {} }");
        assertNotNull(symbol);
        assertTrue(symbol.getChildren().stream().anyMatch(c -> "Inner".equals(c.getName())));
    }

    // ================================================================
    // JDT Mock Reflection helpers
    // ================================================================

    private SymbolKind invokeGetTypeKind(IType type) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("getTypeKind", IType.class);
        m.setAccessible(true);
        return (SymbolKind) m.invoke(provider, type);
    }

    private boolean invokeIsTrait(IType type) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("isTrait", IType.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, type);
    }

    private DocumentSymbol invokeToDocumentSymbol(IType type, String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("toDocumentSymbol", IType.class, String.class);
        m.setAccessible(true);
        return (DocumentSymbol) m.invoke(provider, type, content);
    }
}
