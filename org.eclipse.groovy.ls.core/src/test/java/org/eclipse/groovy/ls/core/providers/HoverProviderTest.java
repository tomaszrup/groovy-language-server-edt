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

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for pure-text and AST utility methods in {@link HoverProvider}.
 */
class HoverProviderTest {

    private HoverProvider provider;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        provider = new HoverProvider(new DocumentManager());
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

    // ---- simpleName ----

    @Test
    void simpleNameFromFqn() throws Exception {
        assertEquals("String", invokeSimpleName("java.lang.String"));
    }

    @Test
    void simpleNameAlreadySimple() throws Exception {
        assertEquals("Foo", invokeSimpleName("Foo"));
    }

    @Test
    void simpleNameNull() throws Exception {
        assertNull(invokeSimpleName(null));
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
        // Backward scan from a space finds adjacent word; use isolated non-identifier
        assertNull(invokeExtractWordAt("  +  ", 2)); // '+' surrounded by spaces
    }

    @Test
    void extractWordAtEndOfContent() throws Exception {
        assertEquals("foo", invokeExtractWordAt("foo", 2));
    }

    // ---- isInRange ----

    @Test
    void isInRangeTrue() throws Exception {
        ModuleNode module = parseModule("class Foo {\n  void bar() {}\n}", "file:///IsInRange.groovy");
        ClassNode cls = findClass(module, "Foo");
        assertTrue(invokeIsInRange(cls, 1)); // line 1 is within class Foo
    }

    @Test
    void isInRangeFalse() throws Exception {
        ModuleNode module = parseModule("class Foo {\n}\nclass Bar {}", "file:///IsInRange2.groovy");
        ClassNode foo = findClass(module, "Foo");
        // Line 3 (where Bar is) should be outside Foo if Foo ends at line 2
        int barLine = findClass(module, "Bar").getLineNumber();
        // Use a line that's clearly outside Foo
        boolean result = invokeIsInRange(foo, barLine + 10);
        // If Foo's last line < barLine + 10, should be false
        assertTrue(!result || foo.getLastLineNumber() >= barLine + 10);
    }

    // ---- buildClassHover ----

    @Test
    void buildClassHoverSimpleClass() throws Exception {
        ModuleNode module = parseModule("class Foo {}", "file:///ClassHover.groovy");
        ClassNode cls = findClass(module, "Foo");
        String hover = invokeBuildClassHover(cls);
        assertNotNull(hover);
        assertTrue(hover.contains("class"));
        assertTrue(hover.contains("Foo"));
    }

    @Test
    void buildClassHoverInterface() throws Exception {
        ModuleNode module = parseModule("interface Greeter { void greet() }",
                "file:///InterfaceHover.groovy");
        ClassNode cls = findClass(module, "Greeter");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("interface"));
        assertTrue(hover.contains("Greeter"));
    }

    @Test
    void buildClassHoverEnum() throws Exception {
        ModuleNode module = parseModule("enum Color { RED, GREEN, BLUE }",
                "file:///EnumHover.groovy");
        ClassNode cls = findClass(module, "Color");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("enum"));
        assertTrue(hover.contains("Color"));
    }

    @Test
    void buildClassHoverWithExtends() throws Exception {
        ModuleNode module = parseModule("""
                class Base {}
                class Child extends Base {}
                """, "file:///ExtendsHover.groovy");
        ClassNode cls = findClass(module, "Child");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("extends"));
        assertTrue(hover.contains("Base"));
    }

    @Test
    void buildClassHoverWithImplements() throws Exception {
        ModuleNode module = parseModule("""
                interface Greeter { void greet() }
                class Impl implements Greeter { void greet() {} }
                """, "file:///ImplHover.groovy");
        ClassNode cls = findClass(module, "Impl");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("implements"));
        assertTrue(hover.contains("Greeter"));
    }

    // ---- buildMethodHover ----

    @Test
    void buildMethodHoverNoParams() throws Exception {
        ModuleNode module = parseModule("class Foo { String getName() { 'hi' } }",
                "file:///MethodHover1.groovy");
        MethodNode method = findMethod(module, "Foo", "getName");
        String hover = invokeBuildMethodHover(method);
        assertNotNull(hover);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("getName"));
    }

    @Test
    void buildMethodHoverWithParams() throws Exception {
        ModuleNode module = parseModule("class Foo { void greet(String name, int count) {} }",
                "file:///MethodHover2.groovy");
        MethodNode method = findMethod(module, "Foo", "greet");
        String hover = invokeBuildMethodHover(method);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("name"));
        assertTrue(hover.contains("int"));
        assertTrue(hover.contains("count"));
    }

    // ---- buildFieldHover ----

    @Test
    void buildFieldHover() throws Exception {
        ModuleNode module = parseModule("class Foo { private int count = 0 }",
                "file:///FieldHover.groovy");
        FieldNode field = findField(module, "Foo", "count");
        String hover = invokeBuildFieldHover(field);
        assertNotNull(hover);
        assertTrue(hover.contains("int"));
        assertTrue(hover.contains("count"));
    }

    // ---- buildPropertyHover ----

    @Test
    void buildPropertyHover() throws Exception {
        ModuleNode module = parseModule("class Foo { String name }",
                "file:///PropHover.groovy");
        PropertyNode prop = findProperty(module, "Foo", "name");
        String hover = invokeBuildPropertyHover(prop);
        assertNotNull(hover);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("name"));
        assertTrue(hover.contains("(property)"));
    }

    // ---- buildASTHover ----

    @Test
    void buildASTHoverReturnsHoverForValidText() throws Exception {
        Hover hover = invokeBuildASTHover("```groovy\nclass Foo\n```");
        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());
        assertTrue(hover.getContents().getRight().getValue().contains("Foo"));
    }

    @Test
    void buildASTHoverReturnsNullForEmpty() throws Exception {
        assertNull(invokeBuildASTHover(""));
        assertNull(invokeBuildASTHover(null));
    }

    // ---- getHoverFromGroovyAST (integration-level via DocumentManager) ----

    @Test
    void getHoverFromGroovyASTFindsClassAtCursorPosition() throws Exception {
        String uri = "file:///HoverASTClass.groovy";
        String content = "class MyService {\n  void run() {}\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        // Position at "MyService" — line 0, col 6 (inside "MyService")
        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(0, 8));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("MyService"));

        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTFindsMethodAtCursorPosition() throws Exception {
        String uri = "file:///HoverASTMethod.groovy";
        String content = "class Svc {\n  String process(int n) { '' }\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 12));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("process"));

        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTReturnsNullForBlankArea() throws Exception {
        String uri = "file:///HoverASTBlank.groovy";
        String content = "class Svc {\n\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        // Line 1 is blank; no word at cursor
        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 0));
        assertNull(hover);

        dm.didClose(uri);
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private int invokePositionToOffset(String content, Position position) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content, position);
    }

    private String invokeSimpleName(String fqn) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("simpleName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, fqn);
    }

    private String invokeExtractWordAt(String content, int offset) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, offset);
    }

    private boolean invokeIsInRange(org.codehaus.groovy.ast.ASTNode node, int line) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("isInRange",
                org.codehaus.groovy.ast.ASTNode.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, node, line);
    }

    private String invokeBuildClassHover(ClassNode cls) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildClassHover", ClassNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, cls);
    }

    private String invokeBuildMethodHover(MethodNode method) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildMethodHover", MethodNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, method);
    }

    private String invokeBuildFieldHover(FieldNode field) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildFieldHover", FieldNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, field);
    }

    private String invokeBuildPropertyHover(PropertyNode prop) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildPropertyHover", PropertyNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, prop);
    }

    private Hover invokeBuildASTHover(String text) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildASTHover", String.class);
        m.setAccessible(true);
        return (Hover) m.invoke(provider, text);
    }

    private Hover invokeGetHoverFromGroovyAST(HoverProvider hp, String uri, Position position) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("getHoverFromGroovyAST", String.class, Position.class);
        m.setAccessible(true);
        return (Hover) m.invoke(hp, uri, position);
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

    private FieldNode findField(ModuleNode module, String className, String fieldName) {
        ClassNode cls = findClass(module, className);
        return cls.getFields().stream()
                .filter(f -> fieldName.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + fieldName));
    }

    private PropertyNode findProperty(ModuleNode module, String className, String propName) {
        ClassNode cls = findClass(module, className);
        return cls.getProperties().stream()
                .filter(p -> propName.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propName));
    }
}
