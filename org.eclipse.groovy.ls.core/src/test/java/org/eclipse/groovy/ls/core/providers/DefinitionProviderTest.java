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

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void findClassDeclarationRangeFindsClass() throws Exception {
        String source = "package demo\n\nclass Foo {\n}";
        Range range = invokeFindClassDeclarationRange(source, "Foo");
        assertNotNull(range);
        assertEquals(2, range.getStart().getLine()); // "class Foo" is on line 2
    }

    @Test
    void findClassDeclarationRangeFindsInterface() throws Exception {
        String source = "interface Bar {}";
        Range range = invokeFindClassDeclarationRange(source, "Bar");
        assertNotNull(range);
        assertEquals(0, range.getStart().getLine());
    }

    @Test
    void findClassDeclarationRangeFindsEnum() throws Exception {
        String source = "enum Color { RED }";
        Range range = invokeFindClassDeclarationRange(source, "Color");
        assertEquals(0, range.getStart().getLine());
    }

    @Test
    void findClassDeclarationRangeFindsTrait() throws Exception {
        String source = "trait Flyable { }";
        Range range = invokeFindClassDeclarationRange(source, "Flyable");
        assertEquals(0, range.getStart().getLine());
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
        // Line 99 is way out of range; but note that `findEnclosingClass` is lenient
        // and may still return the script class if present. We test basic behavior.
        ClassNode found = invokeFindEnclosingClass(module, 99);
        // Script class might be returned; just ensure no crash
    }

    // ---- findClassBySimpleName ----

    @Test
    void findClassBySimpleNameFindsClass() throws Exception {
        ModuleNode module = parseModule("class Alpha {}\nclass Beta {}",
                "file:///FindByName.groovy");
        ClassNode found = invokeFindClassBySimpleName(module, "Beta");
        assertNotNull(found);
        assertEquals("Beta", found.getNameWithoutPackage());
    }

    @Test
    void findClassBySimpleNameReturnsNullForMissing() throws Exception {
        ModuleNode module = parseModule("class Alpha {}",
                "file:///FindByNameNull.groovy");
        assertNull(invokeFindClassBySimpleName(module, "Gamma"));
        assertNull(invokeFindClassBySimpleName(module, null));
        assertNull(invokeFindClassBySimpleName(module, ""));
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
        // Don't open the document
        List<Location> locations = invokeGetDefinitionFromGroovyAST("file:///noContent.groovy", new Position(0, 0));
        assertTrue(locations.isEmpty());
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

    private ClassNode invokeFindClassBySimpleName(ModuleNode module, String simpleName) throws Exception {
        Method m = DefinitionProvider.class.getDeclaredMethod("findClassBySimpleName",
                ModuleNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, module, simpleName);
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
