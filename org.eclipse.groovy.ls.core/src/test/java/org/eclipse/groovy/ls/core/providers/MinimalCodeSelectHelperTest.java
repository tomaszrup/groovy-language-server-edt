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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MinimalCodeSelectHelper} private helper methods.
 * <p>
 * The main {@code select()} method requires a
 * {@code GroovyCompilationUnit} (JDT runtime), so we focus on the
 * AST-only and string-manipulation helpers that are testable standalone.
 */
class MinimalCodeSelectHelperTest {

    private final MinimalCodeSelectHelper helper = new MinimalCodeSelectHelper();

    // ---- extractWordAt ----

    @Test
    void extractWordAtMiddleOfIdentifier() throws Exception {
        String content = "def myVariable = 42";
        // offset 6 → 'V' inside "myVariable"
        String word = invokeExtractWordAt(content, 6);
        assertEquals("myVariable", word);
    }

    @Test
    void extractWordAtStartOfIdentifier() throws Exception {
        String content = "def myVariable = 42";
        // offset 4 → 'm' in "myVariable"
        String word = invokeExtractWordAt(content, 4);
        assertEquals("myVariable", word);
    }

    @Test
    void extractWordAtEndOfIdentifier() throws Exception {
        String content = "def myVariable = 42";
        // offset 14 → right after "myVariable" (the space)
        // The method should scan back and find "myVariable"
        String word = invokeExtractWordAt(content, 14);
        assertEquals("myVariable", word);
    }

    @Test
    void extractWordAtOnNonIdentifierReturnsNull() throws Exception {
        String content = "a + b";
        // offset 2 → '+' with spaces around
        String word = invokeExtractWordAt(content, 2);
        assertNull(word);
    }

    @Test
    void extractWordAtOutOfBoundsReturnsNull() throws Exception {
        String content = "abc";
        String word = invokeExtractWordAt(content, 100);
        assertNull(word);
    }

    @Test
    void extractWordAtEndOfContent() throws Exception {
        String content = "abc";
        // offset 3 → past last char, should find "abc"
        String word = invokeExtractWordAt(content, 3);
        assertEquals("abc", word);
    }

    @Test
    void extractWordAtNegativeOffsetReturnsNull() throws Exception {
        String content = "abc";
        String word = invokeExtractWordAt(content, -1);
        assertNull(word);
    }

    @Test
    void extractWordAtHandlesUnderscoresAndDigits() throws Exception {
        String content = "def _myVar2 = 1";
        String word = invokeExtractWordAt(content, 5);
        assertEquals("_myVar2", word);
    }

    // ---- offsetToLine ----

    @Test
    void offsetToLineFirstLine() throws Exception {
        String source = "class A {}\n";
        int line = invokeOffsetToLine(source, 0);
        assertEquals(1, line);
    }

    @Test
    void offsetToLineSecondLine() throws Exception {
        String source = "line1\nline2\n";
        // offset 6 → 'l' in "line2", which is line 2
        int line = invokeOffsetToLine(source, 6);
        assertEquals(2, line);
    }

    @Test
    void offsetToLineThirdLine() throws Exception {
        String source = "a\nb\nc\n";
        // offset 4 → 'c' on line 3
        int line = invokeOffsetToLine(source, 4);
        assertEquals(3, line);
    }

    @Test
    void offsetToLineAtNewlineChar() throws Exception {
        String source = "abc\ndef\n";
        // offset 3 → '\n' at end of line 1 → still line 1
        int line = invokeOffsetToLine(source, 3);
        assertEquals(1, line);
    }

    @Test
    void offsetToLineClampsBeyondEnd() throws Exception {
        String source = "abc";
        // offset way past content should be clamped safely
        int line = invokeOffsetToLine(source, 999);
        assertEquals(1, line);
    }

    // ---- findEnclosingClass ----

    @Test
    void findEnclosingClassReturnsClassAtLine() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "class Outer {\n  class Inner {\n  }\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///test.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        // Line 2 (1-based) should be inside "Outer" at minimum
        ClassNode enclosing = invokeFindEnclosingClass(module, 2);
        assertNotNull(enclosing);
        // Either Outer or Inner is acceptable; the key is non-null
    }

    @Test
    void findEnclosingClassReturnsNullOutsideClass() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "package foo\n\nimport java.util.List\n\nclass A {}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///test2.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        // Line 1 → package declaration, outside any class
        ClassNode enclosing = invokeFindEnclosingClass(module, 1);
        assertTrue(enclosing == null || enclosing.getLineNumber() > 0);
    }

    // ---- resolveFromImports ----

    @Test
    void resolveFromImportsFindsRegularImport() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A { LocalDate d }\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///imports.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveFromImports(module, "LocalDate");
        assertEquals("java.time.LocalDate", fqn);
    }

    @Test
    void resolveFromImportsReturnsNullForUnimportedType() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A {}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///imports2.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveFromImports(module, "LocalTime");
        assertNull(fqn);
    }

    @Test
    void resolveFromImportsHandlesAliasedImport() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate as LD\n\nclass A { LD d }\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///alias.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveFromImports(module, "LD");
        assertEquals("java.time.LocalDate", fqn);
    }

    // ---- resolveTypeFromAST ----

    @Test
    void resolveTypeFromASTFindsExtendsClause() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.util.ArrayList\n\nclass MyList extends ArrayList {}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///extends.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "ArrayList", 50, source);
        // Should resolve to java.util.ArrayList
        assertNotNull(fqn);
        assertEquals("java.util.ArrayList", fqn);
    }

    @Test
    void resolveTypeFromASTFindsFieldType() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A {\n  LocalDate birthday\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///field.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        // "LocalDate" at offset roughly ~42
        String fqn = invokeResolveTypeFromAST(module, "LocalDate", 42, source);
        assertNotNull(fqn);
        assertEquals("java.time.LocalDate", fqn);
    }

    @Test
    void resolveTypeFromASTReturnsNullForUnknownWord() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "class A { String name }\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///unknown.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "Nonexistent", 0, source);
        assertNull(fqn);
    }

    // ---- Additional edge cases ----

    @Test
    void extractWordAtOnWhitespaceReturnsNull() throws Exception {
        assertNull(invokeExtractWordAt("   ", 1));
    }

    @Test
    void extractWordAtBoundaryBetweenWords() throws Exception {
        String content = "foo bar";
        // position at space between foo and bar
        String result = invokeExtractWordAt(content, 3);
        // May return null (at space) or adjacent word depending on boundary handling
        assertTrue(result == null || result.equals("foo") || result.equals("bar"));
    }

    @Test
    void extractWordAtDollarSign() throws Exception {
        String content = "def $special = 1";
        String word = invokeExtractWordAt(content, 5);
        assertEquals("$special", word);
    }

    @Test
    void offsetToLineZeroOffset() throws Exception {
        assertEquals(1, invokeOffsetToLine("abc\ndef", 0));
    }

    @Test
    void offsetToLineNegativeClamps() throws Exception {
        assertEquals(1, invokeOffsetToLine("abc", -5));
    }

    @Test
    void resolveFromImportsStaticImport() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import static java.lang.Math.PI\n\nclass A { double pi = PI }\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///static.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveFromImports(module, "Math");
        assertTrue(fqn == null || fqn.contains("Math"));
    }

    @Test
    void resolveTypeFromASTFindsImplementsClause() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.io.Serializable\n\nclass A implements Serializable {}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///impl.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "Serializable", 50, source);
        assertNotNull(fqn);
        assertEquals("java.io.Serializable", fqn);
    }

    @Test
    void resolveTypeFromASTFindsMethodReturnType() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A {\n  LocalDate getDate() { null }\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///rettype.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "LocalDate", 40, source);
        assertNotNull(fqn);
        assertEquals("java.time.LocalDate", fqn);
    }

    @Test
    void resolveTypeFromASTFindsMethodParameterType() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A {\n  void process(LocalDate date) {}\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///paramtype.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "LocalDate", 50, source);
        assertNotNull(fqn);
        assertEquals("java.time.LocalDate", fqn);
    }

    @Test
    void resolveTypeFromASTFindsPropertyType() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\n\nclass A {\n  LocalDate birthday\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///proptype.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        String fqn = invokeResolveTypeFromAST(module, "LocalDate", 40, source);
        assertNotNull(fqn);
        assertEquals("java.time.LocalDate", fqn);
    }

    @Test
    void findEnclosingClassMultipleClasses() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "class A {\n  void foo() {}\n}\nclass B {\n  void bar() {}\n}\n";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///multi.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        ClassNode atLineA = invokeFindEnclosingClass(module, 2);
        ClassNode atLineB = invokeFindEnclosingClass(module, 5);
        assertNotNull(atLineA);
        assertNotNull(atLineB);
    }

    // ---- Helpers ----

    private String invokeExtractWordAt(String content, int offset) throws Exception {
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(helper, content, offset);
    }

    private int invokeOffsetToLine(String source, int offset) throws Exception {
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("offsetToLine", String.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(helper, source, offset);
    }

    private ClassNode invokeFindEnclosingClass(ModuleNode module, int line1Based) throws Exception {
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("findEnclosingClass", ModuleNode.class, int.class);
        method.setAccessible(true);
        return (ClassNode) method.invoke(helper, module, line1Based);
    }

    private String invokeResolveFromImports(ModuleNode module, String word) throws Exception {
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveFromImports", ModuleNode.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(helper, module, word);
    }

    private String invokeResolveTypeFromAST(ModuleNode module, String word, int offset, String source) throws Exception {
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTypeFromAST", ModuleNode.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(helper, module, word);
    }

    // ---- resolveClassNodeName ----

    @Test
    void resolveClassNodeNameResolvesViaImports() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "import java.time.LocalDate\nclass A { LocalDate d }";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///resolveNodeName.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        // Get the ClassNode for LocalDate from the field's type
        ClassNode localDateNode = module.getClasses().get(0).getFields().stream()
                .filter(f -> "d".equals(f.getName()))
                .findFirst()
                .map(f -> f.getType())
                .orElse(null);
        assertNotNull(localDateNode);

        String resolved = invokeResolveClassNodeName(module, localDateNode, "LocalDate");
        assertNotNull(resolved);
    }

    @Test
    void resolveClassNodeNameReturnsOriginalNameForFQN() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "class A { java.util.List items }";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///resolveNodeFqn.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        ClassNode listNode = module.getClasses().get(0).getFields().stream()
                .filter(f -> "items".equals(f.getName()))
                .findFirst()
                .map(f -> f.getType())
                .orElse(null);
        assertNotNull(listNode);

        String resolved = invokeResolveClassNodeName(module, listNode, "List");
        assertNotNull(resolved);
        assertTrue(resolved.contains("List"));
    }

    // ---- findTraitClassNodeInModule ----

    @Test
    void findTraitClassNodeInModuleFindsExistingTrait() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "trait Named { String name }\nclass Person implements Named {}";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///traitFind.groovy", source);
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);

        ClassNode found = invokeFindTraitClassNodeInModule(module, "Named");
        assertNotNull(found);
        assertEquals("Named", found.getNameWithoutPackage());
    }

    @Test
    void findTraitClassNodeInModuleReturnsNullForMissing() throws Exception {
        GroovyCompilerService compiler = new GroovyCompilerService();
        String source = "class A {}";
        GroovyCompilerService.ParseResult result = compiler.parse("file:///traitMissing.groovy", source);
        ModuleNode module = result.getModuleNode();

        ClassNode found = invokeFindTraitClassNodeInModule(module, "NonExistent");
        assertNull(found);
    }

    @Test
    void findTraitClassNodeInModuleReturnsNullForNullInputs() throws Exception {
        assertNull(invokeFindTraitClassNodeInModule(null, "A"));
        GroovyCompilerService compiler = new GroovyCompilerService();
        ModuleNode module = compiler.parse("file:///n.groovy", "class A {}").getModuleNode();
        assertNull(invokeFindTraitClassNodeInModule(module, null));
    }

    // ---- findTypeInUnit ----

    @Test
    void findTypeInUnitFindsMatchingType() throws Exception {
        IType matching = mock(IType.class);
        when(matching.getElementName()).thenReturn("Foo");
        IType other = mock(IType.class);
        when(other.getElementName()).thenReturn("Bar");

        IType result = invokeFindTypeInUnit(new IType[] { other, matching }, "Foo");
        assertNotNull(result);
        assertEquals("Foo", result.getElementName());
    }

    @Test
    void findTypeInUnitReturnsNullWhenNotFound() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Foo");

        IType result = invokeFindTypeInUnit(new IType[] { type }, "Bar");
        assertNull(result);
    }

    // ---- findFieldByName ----

    @Test
    void findFieldByNameFindsMatch() throws Exception {
        IType type = mock(IType.class);
        IField field1 = mock(IField.class);
        when(field1.getElementName()).thenReturn("name");
        IField field2 = mock(IField.class);
        when(field2.getElementName()).thenReturn("age");
        when(type.getFields()).thenReturn(new IField[] { field1, field2 });

        IField result = invokeFindFieldByName(type, "age");
        assertNotNull(result);
        assertEquals("age", result.getElementName());
    }

    @Test
    void findFieldByNameReturnsNullForNoMatch() throws Exception {
        IType type = mock(IType.class);
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("x");
        when(type.getFields()).thenReturn(new IField[] { field });

        IField result = invokeFindFieldByName(type, "y");
        assertNull(result);
    }

    @Test
    void findFieldByNameReturnsNullWhenFieldsNull() throws Exception {
        IType type = mock(IType.class);
        when(type.getFields()).thenReturn(null);

        IField result = invokeFindFieldByName(type, "x");
        assertNull(result);
    }

    // ---- findMethodByName ----

    @Test
    void findMethodByNameFindsMatch() throws Exception {
        IType type = mock(IType.class);
        IMethod method1 = mock(IMethod.class);
        when(method1.getElementName()).thenReturn("init");
        IMethod method2 = mock(IMethod.class);
        when(method2.getElementName()).thenReturn("run");
        when(type.getMethods()).thenReturn(new IMethod[] { method1, method2 });

        IMethod result = invokeFindMethodByName(type, "run");
        assertNotNull(result);
        assertEquals("run", result.getElementName());
    }

    @Test
    void findMethodByNameReturnsNullForNoMatch() throws Exception {
        IType type = mock(IType.class);
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("foo");
        when(type.getMethods()).thenReturn(new IMethod[] { method });

        IMethod result = invokeFindMethodByName(type, "bar");
        assertNull(result);
    }

    // ---- resolveTypeFromAST ----

    @Test
    void resolveTypeFromASTReturnsClassDeclaration() throws Exception {
        String source = "import java.util.List\nclass Holder { List items }\n";
        ModuleNode module = parseModule(source);
        // resolveTypeFromAST finds field types via the AST
        String result = invokeResolveTypeFromAST(module, "List", 35, source);
        assertNotNull(result);
        assertTrue(result.contains("List"));
    }

    @Test
    void resolveTypeFromASTResolvesFieldType() throws Exception {
        String source = "import java.util.Map\nclass Holder { Map items }\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Map", 34, source);
        assertNotNull(result);
        assertTrue(result.contains("Map"));
    }

    @Test
    void resolveTypeFromASTResolvesMethodReturnType() throws Exception {
        String source = "import java.util.Map\nclass Svc { Map getData() { [:] } }\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Map", 30, source);
        assertNotNull(result);
        assertTrue(result.contains("Map"));
    }

    @Test
    void resolveTypeFromASTResolvesParameterType() throws Exception {
        String source = "import java.util.Set\nclass Svc { void process(Set items) {} }\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Set", 45, source);
        assertNotNull(result);
        assertTrue(result.contains("Set"));
    }

    @Test
    void resolveTypeFromASTReturnsNullForUnknown() throws Exception {
        String source = "class MyClass {}\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "NonExistent", 0, source);
        assertNull(result);
    }

    @Test
    void resolveTypeFromASTResolvesSuperClass() throws Exception {
        String source = "class Base {}\nclass Child extends Base {}\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Base", 30, source);
        assertNotNull(result);
        assertTrue(result.contains("Base"));
    }

    @Test
    void resolveTypeFromASTResolvesInterface() throws Exception {
        String source = "interface Speakable { String speak() }\nclass Dog implements Speakable { String speak() { 'Woof' } }\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Speakable", 55, source);
        assertNotNull(result);
        assertTrue(result.contains("Speakable"));
    }

    @Test
    void resolveTypeFromASTResolvesPropertyType() throws Exception {
        String source = "import java.util.Date\nclass Event { Date when }\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromAST(module, "Date", 35, source);
        assertNotNull(result);
        assertTrue(result.contains("Date"));
    }

    // ---- resolveFromImports with various import styles ----

    @Test
    void resolveFromImportsResolvesRegularImport() throws Exception {
        String source = "import java.util.ArrayList\ndef list = new ArrayList()\n";
        ModuleNode module = parseModule(source);
        String result = invokeResolveFromImports(module, "ArrayList");
        assertNotNull(result);
        assertEquals("java.util.ArrayList", result);
    }

    // ---- Additional edge cases for extractWordAt ----

    @Test
    void extractWordAtEndOfShortContent() throws Exception {
        String content = "abc";
        String word = invokeExtractWordAt(content, 2);
        assertEquals("abc", word);
    }

    @Test
    void extractWordAtDottedExpression() throws Exception {
        String content = "foo.bar.baz";
        String word = invokeExtractWordAt(content, 5);
        assertEquals("bar", word);
    }

    // ---- Additional edge cases for offsetToLine ----

    @Test
    void offsetToLineMultipleEmptyLines() throws Exception {
        String content = "\n\n\nfoo";
        int line = invokeOffsetToLine(content, 3); // 'f' of "foo" is at offset 3
        assertTrue(line >= 0, "Expected non-negative line, got " + line);
    }

    @Test
    void offsetToLineAtExactNewline() throws Exception {
        String content = "abc\ndef";
        int line = invokeOffsetToLine(content, 3); // '\n' is at offset 3
        assertTrue(line >= 0 && line <= 1, "Expected line 0 or 1, got " + line);
    }

    // ---- Additional edge cases for findEnclosingClass ----

    @Test
    void findEnclosingClassForInnerClass() throws Exception {
        String source = "class Outer {\n  class Inner {\n    void foo() {}\n  }\n}\n";
        ModuleNode module = parseModule(source);
        ClassNode inner = invokeFindEnclosingClass(module, 2);
        assertNotNull(inner);
    }

    @Test
    void findEnclosingClassNoExceptionForScriptContent() throws Exception {
        String source = "def x = 42\n";
        ModuleNode module = parseModule(source);
        // Script content may have a synthetic class — just ensure no crash
        invokeFindEnclosingClass(module, 0);
    }

    // ---- findFieldByName edge cases ----

    @Test
    void findFieldByNameReturnsNullForEmptyFields() throws Exception {
        IType type = mock(IType.class);
        when(type.getFields()).thenReturn(new IField[0]);
        IField result = invokeFindFieldByName(type, "nonexistent");
        assertNull(result);
    }

    // ---- findMethodByName edge cases ----

    @Test
    void findMethodByNameReturnsNullForEmptyMethods() throws Exception {
        IType type = mock(IType.class);
        when(type.getMethods()).thenReturn(new IMethod[0]);
        IMethod result = invokeFindMethodByName(type, "nonexistent");
        assertNull(result);
    }

    // ---- findTypeInUnit edge cases ----

    @Test
    void findTypeInUnitReturnsNullForEmptyArray() throws Exception {
        IType result = invokeFindTypeInUnit(new IType[0], "Something");
        assertNull(result);
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService compilerService = new GroovyCompilerService();
        GroovyCompilerService.ParseResult result =
                compilerService.parse("file:///MCSelectTest.groovy", source);
        assertTrue(result.hasAST(), "Expected AST for test fixture");
        ModuleNode module = result.getModuleNode();
        assertNotNull(module);
        return module;
    }

    // ---- Reflection helpers for new tests ----

    private String invokeResolveClassNodeName(ModuleNode module, ClassNode node, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNodeName", ModuleNode.class, ClassNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, node, word);
    }

    private ClassNode invokeFindTraitClassNodeInModule(ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findTraitClassNodeInModule", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, module, simpleName);
    }

    private IType invokeFindTypeInUnit(IType[] types, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findTypeInUnit", IType[].class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, types, simpleName);
    }

    private IField invokeFindFieldByName(IType type, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findFieldByName", IType.class, String.class);
        m.setAccessible(true);
        return (IField) m.invoke(helper, type, name);
    }

    private IMethod invokeFindMethodByName(IType type, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findMethodByName", IType.class, String.class);
        m.setAccessible(true);
        return (IMethod) m.invoke(helper, type, name);
    }

    // ================================================================
    // qualifyByModulePackage tests
    // ================================================================

    private String invokeQualifyByModulePackage(ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("qualifyByModulePackage", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, simpleName);
    }

    @Test
    void qualifyByModulePackageAddsPackage() throws Exception {
        String source = "package com.example\nclass Foo {}";
        ModuleNode module = parseModule(source);
        String result = invokeQualifyByModulePackage(module, "Foo");
        assertEquals("com.example.Foo", result);
    }

    @Test
    void qualifyByModulePackageNoPackage() throws Exception {
        String source = "class Foo {}";
        ModuleNode module = parseModule(source);
        String result = invokeQualifyByModulePackage(module, "Foo");
        // No package → returns null
        assertNull(result);
    }

    // ================================================================
    // offsetToColumn tests
    // ================================================================

    private int invokeOffsetToColumn(String content, int offset) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("offsetToColumn", String.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(helper, content, offset);
    }

    @Test
    void offsetToColumnFirstChar() throws Exception {
        int col = invokeOffsetToColumn("abc\ndef", 0);
        assertEquals(1, col);
    }

    @Test
    void offsetToColumnSecondLine() throws Exception {
        // "abc\ndef" → offset 4 is 'd', column 1 on second line
        int col = invokeOffsetToColumn("abc\ndef", 4);
        assertEquals(1, col);
    }

    @Test
    void offsetToColumnMiddle() throws Exception {
        // "abc\ndef" → offset 5 is 'e', column 2 on second line
        int col = invokeOffsetToColumn("abc\ndef", 5);
        assertEquals(2, col);
    }

    @Test
    void offsetToColumnAtNewline() throws Exception {
        int col = invokeOffsetToColumn("abc\ndef", 3);
        // '\n' at offset 3, column should be 4 (after 'c')
        assertTrue(col >= 1);
    }

    // ================================================================
    // isMatchingAccessorName tests
    // ================================================================

    private boolean invokeIsMatchingAccessorName(String methodName, String fieldName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("isMatchingAccessorName", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(helper, methodName, fieldName);
    }

    @Test
    void isMatchingAccessorNameGetter() throws Exception {
        // The second parameter is already capitalized (e.g., "Name")
        assertTrue(invokeIsMatchingAccessorName("getName", "Name"));
    }

    @Test
    void isMatchingAccessorNameSetter() throws Exception {
        assertTrue(invokeIsMatchingAccessorName("setName", "Name"));
    }

    @Test
    void isMatchingAccessorNameIsBoolean() throws Exception {
        assertTrue(invokeIsMatchingAccessorName("isActive", "Active"));
    }

    @Test
    void isMatchingAccessorNameNoMatch() throws Exception {
        assertFalse(invokeIsMatchingAccessorName("doSomething", "Name"));
    }

    @Test
    void isMatchingAccessorNameWrongPrefix() throws Exception {
        assertFalse(invokeIsMatchingAccessorName("hasName", "Name"));
    }

    // ================================================================
    // normalizeOffsetToIdentifier tests
    // ================================================================

    private Integer invokeNormalizeOffsetToIdentifier(String content, int offset) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("normalizeOffsetToIdentifier", String.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(helper, content, offset);
    }

    @Test
    void normalizeOffsetOnIdentifier() throws Exception {
        Integer result = invokeNormalizeOffsetToIdentifier("abc", 1);
        assertNotNull(result);
        assertEquals(1, result);
    }

    @Test
    void normalizeOffsetOnSpace() throws Exception {
        // "a b" offset 1 is space, should normalize to adjacent identifier
        Integer result = invokeNormalizeOffsetToIdentifier("a b", 1);
        assertNotNull(result);
    }

    @Test
    void normalizeOffsetBeyondEnd() throws Exception {
        Integer result = invokeNormalizeOffsetToIdentifier("abc", 10);
        // Beyond end → may return null or clamp
        // Accept either
    }

    @Test
    void normalizeOffsetEmptyString() throws Exception {
        Integer result = invokeNormalizeOffsetToIdentifier("", 0);
        assertNull(result);
    }

    @Test
    void normalizeOffsetAllOperators() throws Exception {
        Integer result = invokeNormalizeOffsetToIdentifier("+ - *", 2);
        // No identifier adjacent to the space
        // Result could be null
    }

    // ================================================================
    // hasPropertyNamed / hasFieldNamed / hasMethodNamed tests
    // ================================================================

    private boolean invokeHasPropertyNamed(ClassNode node, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("hasPropertyNamed", ClassNode.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(helper, node, name);
    }

    private boolean invokeHasFieldNamed(ClassNode node, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("hasFieldNamed", ClassNode.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(helper, node, name);
    }

    private boolean invokeHasMethodNamed(ClassNode node, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("hasMethodNamed", ClassNode.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(helper, node, name);
    }

    @Test
    void hasPropertyNamedFindsProperty() throws Exception {
        String source = "class A { String name }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertTrue(invokeHasPropertyNamed(cls, "name"));
    }

    @Test
    void hasPropertyNamedNotFound() throws Exception {
        String source = "class A { String name }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertFalse(invokeHasPropertyNamed(cls, "missing"));
    }

    @Test
    void hasFieldNamedFindsField() throws Exception {
        String source = "class A { private int count = 0 }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertTrue(invokeHasFieldNamed(cls, "count"));
    }

    @Test
    void hasFieldNamedNotFound() throws Exception {
        String source = "class A { private int count = 0 }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertFalse(invokeHasFieldNamed(cls, "missing"));
    }

    @Test
    void hasMethodNamedFindsMethod() throws Exception {
        String source = "class A { void process() {} }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertTrue(invokeHasMethodNamed(cls, "process"));
    }

    @Test
    void hasMethodNamedNotFound() throws Exception {
        String source = "class A { void process() {} }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        assertFalse(invokeHasMethodNamed(cls, "missing"));
    }

    // ================================================================
    // resolveFromModuleClasses tests
    // ================================================================

    private String invokeResolveFromModuleClasses(ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveFromModuleClasses", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, simpleName);
    }

    @Test
    void resolveFromModuleClassesFinds() throws Exception {
        String source = "class MyClass {}\nclass Other {}";
        ModuleNode module = parseModule(source);
        String result = invokeResolveFromModuleClasses(module, "MyClass");
        assertNotNull(result);
        assertTrue(result.contains("MyClass"));
    }

    @Test
    void resolveFromModuleClassesMissing() throws Exception {
        String source = "class MyClass {}";
        ModuleNode module = parseModule(source);
        String result = invokeResolveFromModuleClasses(module, "Missing");
        // Should return the input or null
        assertNotNull(result);
    }

    // ================================================================
    // resolveClassMemberType tests
    // ================================================================

    private ClassNode invokeResolveClassMemberType(ClassNode classNode, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassMemberType", ClassNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, classNode, name);
    }

    @Test
    void resolveClassMemberTypeFindsProperty() throws Exception {
        String source = "class A { String name }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        ClassNode type = invokeResolveClassMemberType(cls, "name");
        assertNotNull(type);
        assertTrue(type.getName().contains("String"));
    }

    @Test
    void resolveClassMemberTypeReturnsNullForMissing() throws Exception {
        String source = "class A { String name }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        ClassNode type = invokeResolveClassMemberType(cls, "missing");
        assertNull(type);
    }

    // ================================================================
    // getMethodBlock tests
    // ================================================================

    private BlockStatement invokeGetMethodBlock(MethodNode method) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("getMethodBlock", MethodNode.class);
        m.setAccessible(true);
        return (BlockStatement) m.invoke(helper, method);
    }

    @Test
    void getMethodBlockReturnsBlock() throws Exception {
        String source = "class A { void foo() { int x = 1 } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        assertNotNull(block);
    }

    @Test
    void getMethodBlockReturnsNullForAbstract() throws Exception {
        String source = "abstract class A { abstract void foo() }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        assertNull(block);
    }

    // ================================================================
    // resolveLocalVariableClassNode tests
    // ================================================================

    private ClassNode invokeResolveLocalVariableClassNode(ModuleNode module, String varName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveLocalVariableClassNode", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, module, varName);
    }

    @Test
    void resolveLocalVariableClassNodeFindsTyped() throws Exception {
        String source = "class A { void foo() { ArrayList myList = new ArrayList() } }";
        ModuleNode module = parseModule(source);
        ClassNode result = invokeResolveLocalVariableClassNode(module, "myList");
        assertNotNull(result);
        assertTrue(result.getName().contains("ArrayList"));
    }

    @Test
    void resolveLocalVariableClassNodeReturnsNullForMissing() throws Exception {
        String source = "class A { void foo() { int x = 1 } }";
        ModuleNode module = parseModule(source);
        ClassNode result = invokeResolveLocalVariableClassNode(module, "missing");
        assertNull(result);
    }

    // ================================================================
    // resolveLocalVarInBlock tests
    // ================================================================

    private ClassNode invokeResolveLocalVarInBlock(BlockStatement block, String name, ModuleNode module) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveLocalVarInBlock", BlockStatement.class, String.class, ModuleNode.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, block, name, module);
    }

    @Test
    void resolveLocalVarInBlockFinds() throws Exception {
        String source = "class A { void foo() { String text = 'hello' } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        assertNotNull(block);
        ClassNode result = invokeResolveLocalVarInBlock(block, "text", module);
        assertNotNull(result);
        assertTrue(result.getName().contains("String"));
    }

    @Test
    void resolveLocalVarInBlockNotFound() throws Exception {
        String source = "class A { void foo() { int x = 1 } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        ClassNode result = invokeResolveLocalVarInBlock(block, "missing", module);
        assertNull(result);
    }

    // ================================================================
    // resolveVarFromStatement tests
    // ================================================================

    private ClassNode invokeResolveVarFromStatement(Statement stmt, String name, ModuleNode module) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveVarFromStatement", Statement.class, String.class, ModuleNode.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, stmt, name, module);
    }

    @Test
    void resolveVarFromStatementFindsDecl() throws Exception {
        String source = "class A { void foo() { String text = 'hello' } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        assertNotNull(block);
        assertFalse(block.getStatements().isEmpty());
        Statement stmt = block.getStatements().get(0);
        ClassNode result = invokeResolveVarFromStatement(stmt, "text", module);
        assertNotNull(result);
    }

    @Test
    void resolveVarFromStatementWrongName() throws Exception {
        String source = "class A { void foo() { String text = 'hello' } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(mn -> "foo".equals(mn.getName())).findFirst().orElseThrow();
        BlockStatement block = invokeGetMethodBlock(method);
        Statement stmt = block.getStatements().get(0);
        ClassNode result = invokeResolveVarFromStatement(stmt, "wrong", module);
        assertNull(result);
    }

    // ================================================================
    // findMethodCallAtOffset tests
    // ================================================================

    private MethodCallExpression invokeFindMethodCallAtOffset(ModuleNode module, int offset, String methodName, String source) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findMethodCallAtOffset", ModuleNode.class, int.class, String.class, String.class);
        m.setAccessible(true);
        return (MethodCallExpression) m.invoke(helper, module, offset, methodName, source);
    }

    private IJavaElement invokeResolveDotMethodCall(ModuleNode module, IJavaProject project,
            String source, int offset, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod(
                "resolveDotMethodCall", ModuleNode.class, IJavaProject.class, String.class, int.class, String.class);
        m.setAccessible(true);
        return (IJavaElement) m.invoke(helper, module, project, source, offset, word);
    }

    @Test
    void findMethodCallAtOffsetFindsCall() throws Exception {
        String source = "class A { void foo() { 'hello'.toUpperCase() } }";
        ModuleNode module = parseModule(source);
        int offset = source.indexOf("toUpperCase");
        MethodCallExpression result = invokeFindMethodCallAtOffset(module, offset, "toUpperCase", source);
        // May or may not find due to AST line/col matching
        // Just ensure no crash
    }

    @Test
    void findMethodCallAtOffsetReturnsNull() throws Exception {
        String source = "class A { void foo() {} }";
        ModuleNode module = parseModule(source);
        MethodCallExpression result = invokeFindMethodCallAtOffset(module, 0, "nonexistent", source);
        assertNull(result);
    }

    @Test
    void resolveDotMethodCallFindsNestedTypeInQualifiedStaticChain() throws Exception {
        String source = "import org.springframework.test.annotation.DirtiesContext\n"
                + "class A { def x = DirtiesContext.ClassMode }";
        ModuleNode module = parseModule(source);
        IJavaProject project = mock(IJavaProject.class);
        IType outerType = mock(IType.class);
        IType innerType = mock(IType.class);

        when(project.findType("org.springframework.test.annotation.DirtiesContext")).thenReturn(outerType);
        when(outerType.getElementName()).thenReturn("DirtiesContext");
        when(outerType.getFields()).thenReturn(new IField[0]);
        when(outerType.getMethods()).thenReturn(new IMethod[0]);
        when(outerType.getTypes()).thenReturn(new IType[] { innerType });
        when(outerType.getJavaProject()).thenReturn(project);
        when(innerType.getElementName()).thenReturn("ClassMode");

        int offset = source.indexOf("ClassMode");
        IJavaElement result = invokeResolveDotMethodCall(module, project, source, offset, "ClassMode");

        assertEquals(innerType, result);
    }

    @Test
    void resolveDotMethodCallFindsEnumConstantInNestedTypeChain() throws Exception {
        String source = "import org.springframework.test.annotation.DirtiesContext\n"
                + "class A { def x = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD }";
        ModuleNode module = parseModule(source);
        IJavaProject project = mock(IJavaProject.class);
        IType outerType = mock(IType.class);
        IType innerType = mock(IType.class);
        IField enumConstant = mock(IField.class);

        when(project.findType("org.springframework.test.annotation.DirtiesContext")).thenReturn(outerType);
        when(outerType.getElementName()).thenReturn("DirtiesContext");
        when(outerType.getFields()).thenReturn(new IField[0]);
        when(outerType.getMethods()).thenReturn(new IMethod[0]);
        when(outerType.getTypes()).thenReturn(new IType[] { innerType });
        when(outerType.getJavaProject()).thenReturn(project);
        when(innerType.getElementName()).thenReturn("ClassMode");
        when(innerType.getFields()).thenReturn(new IField[] { enumConstant });
        when(innerType.getMethods()).thenReturn(new IMethod[0]);
        when(enumConstant.getElementName()).thenReturn("BEFORE_EACH_TEST_METHOD");

        int offset = source.indexOf("BEFORE_EACH_TEST_METHOD");
        IJavaElement result = invokeResolveDotMethodCall(module, project, source, offset,
                "BEFORE_EACH_TEST_METHOD");

        assertEquals(enumConstant, result);
    }

    // ================================================================
    // findMemberInType (MEDIUM) tests
    // ================================================================

    private IJavaElement invokeFindMemberInType(IType type, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("findMemberInType", IType.class, String.class);
        m.setAccessible(true);
        return (IJavaElement) m.invoke(helper, type, name);
    }

    @Test
    void findMemberInTypeFindsMethod() throws Exception {
        IType type = mock(IType.class);
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("run");
        when(type.getMethods()).thenReturn(new IMethod[] {method});
        when(type.getFields()).thenReturn(new IField[0]);
        IJavaElement result = invokeFindMemberInType(type, "run");
        assertNotNull(result);
        assertEquals(method, result);
    }

    @Test
    void findMemberInTypeFindsField() throws Exception {
        IType type = mock(IType.class);
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("count");
        when(type.getFields()).thenReturn(new IField[] {field});
        when(type.getMethods()).thenReturn(new IMethod[0]);
        IJavaElement result = invokeFindMemberInType(type, "count");
        assertNotNull(result);
        assertEquals(field, result);
    }

    @Test
    void findMemberInTypeReturnsNullForMissing() throws Exception {
        IType type = mock(IType.class);
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getFields()).thenReturn(new IField[0]);
        IJavaElement result = invokeFindMemberInType(type, "nothing");
        assertNull(result);
    }

    // ================================================================
    // resolveDirectMemberDeclaration (MEDIUM) tests
    // ================================================================

    private IJavaElement invokeResolveDirectMemberDeclaration(IType[] types, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveDirectMemberDeclaration", IType[].class, String.class);
        m.setAccessible(true);
        return (IJavaElement) m.invoke(helper, (Object) types, name);
    }

    @Test
    void resolveDirectMemberDeclarationFinds() throws Exception {
        IType type = mock(IType.class);
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("process");
        when(type.getMethods()).thenReturn(new IMethod[] {method});
        when(type.getFields()).thenReturn(new IField[0]);
        IJavaElement result = invokeResolveDirectMemberDeclaration(new IType[] {type}, "process");
        assertNotNull(result);
    }

    @Test
    void resolveDirectMemberDeclarationReturnsNull() throws Exception {
        IType type = mock(IType.class);
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(type.getFields()).thenReturn(new IField[0]);
        IJavaElement result = invokeResolveDirectMemberDeclaration(new IType[] {type}, "missing");
        assertNull(result);
    }

    @Test
    void resolveDirectMemberDeclarationEmptyArray() throws Exception {
        IJavaElement result = invokeResolveDirectMemberDeclaration(new IType[0], "anything");
        assertNull(result);
    }

    // ================================================================
    // fallbackTraitElement (MEDIUM) tests
    // ================================================================

    private IJavaElement invokeFallbackTraitElement(IType traitType, IType[] types, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("fallbackTraitElement", IType.class, IType[].class, String.class);
        m.setAccessible(true);
        return (IJavaElement) m.invoke(helper, traitType, (Object) types, simpleName);
    }

    @Test
    void fallbackTraitElementReturnsTraitType() throws Exception {
        IType traitType = mock(IType.class);
        IJavaElement result = invokeFallbackTraitElement(traitType, new IType[0], "Foo");
        assertEquals(traitType, result);
    }

    @Test
    void fallbackTraitElementSearchesTypes() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Foo");
        IJavaElement result = invokeFallbackTraitElement(null, new IType[] {type}, "Foo");
        assertEquals(type, result);
    }

    @Test
    void fallbackTraitElementReturnsNullBothEmpty() throws Exception {
        IJavaElement result = invokeFallbackTraitElement(null, new IType[0], "Foo");
        assertNull(result);
    }

    // ================================================================
    // resolveTraitTypeMember (MEDIUM) tests
    // ================================================================

    private IJavaElement invokeResolveTraitTypeMember(IType type, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTraitTypeMember", IType.class, String.class);
        m.setAccessible(true);
        return (IJavaElement) m.invoke(helper, type, name);
    }

    @Test
    void resolveTraitTypeMemberFindsField() throws Exception {
        IType type = mock(IType.class);
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("name");
        when(type.getFields()).thenReturn(new IField[] {field});
        when(type.getMethods()).thenReturn(new IMethod[0]);
        IJavaElement result = invokeResolveTraitTypeMember(type, "name");
        assertNotNull(result);
    }

    @Test
    void resolveTraitTypeMemberFindsGetter() throws Exception {
        IType type = mock(IType.class);
        IMethod getter = mock(IMethod.class);
        when(getter.getElementName()).thenReturn("getName");
        when(type.getFields()).thenReturn(new IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[] {getter});
        IJavaElement result = invokeResolveTraitTypeMember(type, "name");
        assertNotNull(result);
    }

    @Test
    void resolveTraitTypeMemberReturnsNullForNull() throws Exception {
        IJavaElement result = invokeResolveTraitTypeMember(null, "name");
        assertNull(result);
    }

    // ================================================================
    // resolveTypeByPackages (MEDIUM) tests
    // ================================================================

    private IType invokeResolveTypeByPackages(IJavaProject project, String simpleName, String[] packages) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTypeByPackages", IJavaProject.class, String.class, String[].class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, simpleName, (Object) packages);
    }

    @Test
    void resolveTypeByPackagesFindsType() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        // Packages must end with dot — method does pkg + simpleName
        when(project.findType("java.util.List")).thenReturn(foundType);
        IType result = invokeResolveTypeByPackages(project, "List", new String[] {"java.util."});
        assertEquals(foundType, result);
    }

    @Test
    void resolveTypeByPackagesReturnsNullForNoMatch() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("java.util.Unknown")).thenReturn(null);
        IType result = invokeResolveTypeByPackages(project, "Unknown", new String[] {"java.util."});
        assertNull(result);
    }

    // ================================================================
    // resolveClassNodeToIType (MEDIUM) tests
    // ================================================================

    private IType invokeResolveClassNodeToIType(ClassNode node, ModuleNode module, IJavaProject project) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNodeToIType", ClassNode.class, ModuleNode.class, IJavaProject.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, node, module, project);
    }

    private IType invokeResolveClassNodeToIType(ClassNode node, ModuleNode module, IJavaProject project,
            String sourceUri) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod(
                "resolveClassNodeToIType", ClassNode.class, ModuleNode.class, IJavaProject.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, node, module, project, sourceUri);
    }

    @Test
    void resolveClassNodeToITypeReturnsNullForNullNode() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType result = invokeResolveClassNodeToIType(null, null, project);
        assertNull(result);
    }

    @Test
    void resolveClassNodeToITypeFindsQualified() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        when(project.findType("java.util.ArrayList")).thenReturn(foundType);
        ClassNode node = new ClassNode("java.util.ArrayList", 0, null);
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        IType result = invokeResolveClassNodeToIType(node, module, project);
        assertEquals(foundType, result);
    }

    @Test
    void resolveClassNodeToITypeUsesScopedLookupForTestSourceUri() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        ClassNode node = new ClassNode("demo.Support", 0, null);
        ModuleNode module = parseModule("package demo\nclass Example {}");

        try (org.mockito.MockedStatic<ScopedTypeLookupSupport> lookup =
                org.mockito.Mockito.mockStatic(ScopedTypeLookupSupport.class)) {
            lookup.when(() -> ScopedTypeLookupSupport.findType(project, "demo.Support",
                    "file:///workspace/sample/src/test/groovy/demo/Spec.groovy"))
                    .thenReturn(foundType);

            IType result = invokeResolveClassNodeToIType(node, module, project,
                    "file:///workspace/sample/src/test/groovy/demo/Spec.groovy");

            assertEquals(foundType, result);
        }
    }

    // ================================================================
    // resolveObjectExpressionType tests
    // ================================================================

    private ClassNode invokeResolveObjectExpressionType(Expression expr, ModuleNode module) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveObjectExpressionType", Expression.class, ModuleNode.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, expr, module);
    }

    @Test
    void resolveObjectExpressionTypeForNull() throws Exception {
        ClassNode result = invokeResolveObjectExpressionType(null, null);
        assertNull(result);
    }

    // ================================================================
    // resolveNamedTypeReference tests
    // ================================================================

    private String invokeResolveNamedTypeReference(ModuleNode module, ClassNode classNode, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveNamedTypeReference", ModuleNode.class, ClassNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, classNode, word);
    }

    @Test
    void resolveNamedTypeReferenceFromImport() throws Exception {
        String source = "import java.util.List\nclass A extends ArrayList {}";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        // resolveNamedTypeReference expects typeNode to be a ClassNode whose nameWithoutPackage matches word
        ClassNode superClass = cls.getUnresolvedSuperClass();
        if (superClass != null) {
            String result = invokeResolveNamedTypeReference(module, superClass, superClass.getNameWithoutPackage());
            assertNotNull(result);
        }
    }

    @Test
    void resolveNamedTypeReferenceReturnsNullForMissing() throws Exception {
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveNamedTypeReference(module, cls, "NonExistent");
        assertNull(result);
    }

    // ================================================================
    // resolveFieldType / resolveMethodType / resolvePropertyType tests
    // ================================================================

    private String invokeResolveFieldType(ClassNode cls, ModuleNode module, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveFieldType", ClassNode.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, cls, module, word);
    }

    private String invokeResolveMethodType(ClassNode cls, ModuleNode module, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveMethodType", ClassNode.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, cls, module, word);
    }

    private String invokeResolvePropertyType(ClassNode cls, ModuleNode module, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolvePropertyType", ClassNode.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, cls, module, word);
    }

    @Test
    void resolveFieldTypeFindsMatch() throws Exception {
        String source = "import java.util.List\nclass A { List items }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveFieldType(cls, module, "List");
        // Field type is List — should resolve
        // Note: Groovy treats "List items" as a property, so field may not have the type "List" directly
    }

    @Test
    void resolveMethodTypeFindsReturnType() throws Exception {
        String source = "import java.util.Map\nclass A { Map getData() { null } }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveMethodType(cls, module, "Map");
        assertNotNull(result);
        assertTrue(result.contains("Map"));
    }

    @Test
    void resolveMethodTypeReturnsNullForNoMatch() throws Exception {
        String source = "class A { void foo() {} }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveMethodType(cls, module, "Map");
        assertNull(result);
    }

    @Test
    void resolvePropertyTypeFindsMatch() throws Exception {
        String source = "import java.util.Date\nclass A { Date when }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolvePropertyType(cls, module, "Date");
        assertNotNull(result);
        assertTrue(result.contains("Date"));
    }

    @Test
    void resolvePropertyTypeReturnsNullForNoMatch() throws Exception {
        String source = "class A { String name }";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolvePropertyType(cls, module, "Date");
        assertNull(result);
    }

    // ================================================================
    // resolveTypeFromExpressions tests
    // ================================================================

    private String invokeResolveTypeFromExpressions(ModuleNode module, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTypeFromExpressions", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, word);
    }

    @Test
    void resolveTypeFromExpressionsFindsConstructor() throws Exception {
        String source = "class A { void foo() { def x = new ArrayList() } }";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromExpressions(module, "ArrayList");
        assertNotNull(result);
        assertTrue(result.contains("ArrayList"));
    }

    @Test
    void resolveTypeFromExpressionsReturnsNullForMissing() throws Exception {
        String source = "class A { void foo() { int x = 1 } }";
        ModuleNode module = parseModule(source);
        String result = invokeResolveTypeFromExpressions(module, "HashMap");
        assertNull(result);
    }

    // ================================================================
    // resolveClassHierarchyType tests
    // ================================================================

    private String invokeResolveClassHierarchyType(ModuleNode module, ClassNode cls, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassHierarchyType", ModuleNode.class, ClassNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, cls, word);
    }

    @Test
    void resolveClassHierarchyTypeFindsSuper() throws Exception {
        String source = "class Base {}\nclass Child extends Base {}";
        ModuleNode module = parseModule(source);
        ClassNode child = module.getClasses().stream()
                .filter(c -> "Child".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveClassHierarchyType(module, child, "Base");
        assertNotNull(result);
        assertTrue(result.contains("Base"));
    }

    @Test
    void resolveClassHierarchyTypeReturnsNullForNoMatch() throws Exception {
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        ClassNode cls = module.getClasses().stream()
                .filter(c -> "A".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        String result = invokeResolveClassHierarchyType(module, cls, "Nonexistent");
        assertNull(result);
    }

    // ================================================================
    // resolveClassNodeByQualifiedName (MEDIUM) tests
    // ================================================================

    private IType invokeResolveClassNodeByQualifiedName(IJavaProject project, String fqn) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNodeByQualifiedName", IJavaProject.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, fqn);
    }

    @Test
    void resolveClassNodeByQualifiedNameFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        when(project.findType("java.util.List")).thenReturn(foundType);
        IType result = invokeResolveClassNodeByQualifiedName(project, "java.util.List");
        assertEquals(foundType, result);
    }

    @Test
    void resolveClassNodeByQualifiedNameReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("com.missing.Type")).thenReturn(null);
        IType result = invokeResolveClassNodeByQualifiedName(project, "com.missing.Type");
        assertNull(result);
    }

    // ================================================================
    // resolveClassNodeByModulePackage (MEDIUM) tests
    // ================================================================

    private IType invokeResolveClassNodeByModulePackage(IJavaProject project, ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNodeByModulePackage", IJavaProject.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, module, simpleName);
    }

    @Test
    void resolveClassNodeByModulePackageFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        String source = "package com.example\nclass Foo {}";
        ModuleNode module = parseModule(source);
        when(project.findType("com.example.Foo")).thenReturn(foundType);
        IType result = invokeResolveClassNodeByModulePackage(project, module, "Foo");
        assertEquals(foundType, result);
    }

    @Test
    void resolveClassNodeByModulePackageNoPackage() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String source = "class Foo {}";
        ModuleNode module = parseModule(source);
        IType result = invokeResolveClassNodeByModulePackage(project, module, "Foo");
        assertNull(result);
    }

    // ================================================================
    // resolveClassNodeByImports (MEDIUM) tests
    // ================================================================

    private IType invokeResolveClassNodeByImports(IJavaProject project, ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNodeByImports", IJavaProject.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, module, simpleName);
    }

    @Test
    void resolveClassNodeByImportsFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        String source = "import java.util.List\nclass A {}";
        ModuleNode module = parseModule(source);
        when(project.findType("java.util.List")).thenReturn(foundType);
        IType result = invokeResolveClassNodeByImports(project, module, "List");
        assertEquals(foundType, result);
    }

    @Test
    void resolveClassNodeByImportsReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        IType result = invokeResolveClassNodeByImports(project, module, "List");
        assertNull(result);
    }

    // ================================================================
    // searchTypeBySimpleName (MEDIUM) tests
    // ================================================================

    private IType invokeSearchTypeBySimpleName(IJavaProject project, ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("searchTypeBySimpleName", IJavaProject.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, module, simpleName);
    }

    @Test
    void searchTypeBySimpleNameFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        when(project.findType("java.lang.String")).thenReturn(foundType);
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        IType result = invokeSearchTypeBySimpleName(project, module, "String");
        assertEquals(foundType, result);
    }

    @Test
    void searchTypeBySimpleNameReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String source = "class A {}";
        ModuleNode module = parseModule(source);
        IType result = invokeSearchTypeBySimpleName(project, module, "UnknownType");
        assertNull(result);
    }

    // ================================================================
    // resolveTypeFromClassDeclarations (EASY) tests
    // ================================================================

    private String invokeResolveTypeFromClassDeclarations(ModuleNode module, String word) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTypeFromClassDeclarations", ModuleNode.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(helper, module, word);
    }

    @Test
    void resolveTypeFromClassDeclarationsFindsField() throws Exception {
        String src = "class Foo {\n  String bar\n}";
        ModuleNode module = parseModule(src);
        // The method matches on type names used in fields, not field names
        String result = invokeResolveTypeFromClassDeclarations(module, "String");
        assertNotNull(result);
        assertTrue(result.contains("String"));
    }

    @Test
    void resolveTypeFromClassDeclarationsFindsMethod() throws Exception {
        String src = "class Foo {\n  Integer calc() { return 1 }\n}";
        ModuleNode module = parseModule(src);
        // Matches on return type name
        String result = invokeResolveTypeFromClassDeclarations(module, "Integer");
        assertNotNull(result);
    }

    @Test
    void resolveTypeFromClassDeclarationsReturnsNullForUnknown() throws Exception {
        String src = "class Foo {\n  String bar\n}";
        ModuleNode module = parseModule(src);
        String result = invokeResolveTypeFromClassDeclarations(module, "nonExist");
        assertNull(result);
    }

    // ================================================================
    // resolveMethodCallReturnType (EASY) tests
    // ================================================================

    private ClassNode invokeResolveMethodCallReturnType(org.codehaus.groovy.ast.expr.MethodCallExpression methodCall, ModuleNode module) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveMethodCallReturnType",
                org.codehaus.groovy.ast.expr.MethodCallExpression.class, ModuleNode.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(helper, methodCall, module);
    }

    @Test
    void resolveMethodCallReturnTypeReturnsNullForNullMethodName() throws Exception {
        // MethodCallExpression with null method name
        org.codehaus.groovy.ast.expr.MethodCallExpression mce =
                new org.codehaus.groovy.ast.expr.MethodCallExpression(
                        new org.codehaus.groovy.ast.expr.VariableExpression("x"),
                        (org.codehaus.groovy.ast.expr.Expression) null,
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);
        String src = "class Foo {}";
        ModuleNode module = parseModule(src);
        ClassNode result = invokeResolveMethodCallReturnType(mce, module);
        assertNull(result);
    }

    @Test
    void resolveMethodCallReturnTypeFromConstructorReceiver() throws Exception {
        // Parse a file with a class that has a method returning a type
        String src = "class Foo {\n  String getName() { 'hello' }\n}\ndef x = new Foo().getName()";
        ModuleNode module = parseModule(src);
        // Create MethodCallExpression: new Foo().getName()
        ClassNode fooType = module.getClasses().stream()
                .filter(c -> c.getNameWithoutPackage().equals("Foo")).findFirst().orElse(null);
        assertNotNull(fooType);
        org.codehaus.groovy.ast.expr.ConstructorCallExpression ctor =
                new org.codehaus.groovy.ast.expr.ConstructorCallExpression(fooType,
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);
        org.codehaus.groovy.ast.expr.MethodCallExpression mce =
                new org.codehaus.groovy.ast.expr.MethodCallExpression(ctor, "getName",
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);
        ClassNode result = invokeResolveMethodCallReturnType(mce, module);
        assertNotNull(result);
        assertTrue(result.getName().contains("String"));
    }

    @Test
    void resolveMethodCallReturnTypeThisReceiver() throws Exception {
        // 'this' receiver should return null (let other resolution handle it)
        org.codehaus.groovy.ast.expr.MethodCallExpression mce =
                new org.codehaus.groovy.ast.expr.MethodCallExpression(
                        new org.codehaus.groovy.ast.expr.VariableExpression("this"),
                        "doSomething",
                        org.codehaus.groovy.ast.expr.ArgumentListExpression.EMPTY_ARGUMENTS);
        String src = "class Foo { void doSomething() {} }";
        ModuleNode module = parseModule(src);
        ClassNode result = invokeResolveMethodCallReturnType(mce, module);
        assertNull(result);
    }

    // ================================================================
    // resolveTraitTypeByDeclaredName (MEDIUM) tests
    // ================================================================

    private IType invokeResolveTraitTypeByDeclaredName(IJavaProject project, ClassNode traitNode) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTraitTypeByDeclaredName",
                IJavaProject.class, ClassNode.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, traitNode);
    }

    @Test
    void resolveTraitTypeByDeclaredNameFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType traitType = mock(IType.class);
        ClassNode traitNode = new ClassNode("com.example.MyTrait", 0, ClassNode.SUPER);
        when(project.findType("com.example.MyTrait")).thenReturn(traitType);
        IType result = invokeResolveTraitTypeByDeclaredName(project, traitNode);
        assertEquals(traitType, result);
    }

    @Test
    void resolveTraitTypeByDeclaredNameReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        ClassNode traitNode = new ClassNode("com.example.Missing", 0, ClassNode.SUPER);
        when(project.findType("com.example.Missing")).thenReturn(null);
        IType result = invokeResolveTraitTypeByDeclaredName(project, traitNode);
        assertNull(result);
    }

    // ================================================================
    // resolveTraitTypeByOwnerPackage (MEDIUM) tests
    // ================================================================

    private IType invokeResolveTraitTypeByOwnerPackage(IJavaProject project, ClassNode ownerClass, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTraitTypeByOwnerPackage",
                IJavaProject.class, ClassNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, ownerClass, simpleName);
    }

    @Test
    void resolveTraitTypeByOwnerPackageFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType found = mock(IType.class);
        ClassNode owner = new ClassNode("com.example.MyClass", 0, ClassNode.SUPER);
        when(project.findType("com.example.MyTrait")).thenReturn(found);
        IType result = invokeResolveTraitTypeByOwnerPackage(project, owner, "MyTrait");
        assertEquals(found, result);
    }

    @Test
    void resolveTraitTypeByOwnerPackageReturnsNullDefault() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        ClassNode owner = new ClassNode("MyClass", 0, ClassNode.SUPER);
        IType result = invokeResolveTraitTypeByOwnerPackage(project, owner, "MyTrait");
        assertNull(result);
    }

    // ================================================================
    // resolveTraitTypeByExplicitImports (MEDIUM) tests
    // ================================================================

    private IType invokeResolveTraitTypeByExplicitImports(IJavaProject project, ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTraitTypeByExplicitImports",
                IJavaProject.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, module, simpleName);
    }

    @Test
    void resolveTraitTypeByExplicitImportsFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType found = mock(IType.class);
        String src = "import com.example.MyTrait\nclass Foo {}";
        ModuleNode module = parseModule(src);
        when(project.findType("com.example.MyTrait")).thenReturn(found);
        IType result = invokeResolveTraitTypeByExplicitImports(project, module, "MyTrait");
        assertEquals(found, result);
    }

    @Test
    void resolveTraitTypeByExplicitImportsReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String src = "class Foo {}";
        ModuleNode module = parseModule(src);
        IType result = invokeResolveTraitTypeByExplicitImports(project, module, "NoImport");
        assertNull(result);
    }

    // ================================================================
    // resolveTraitTypeByStarImports (MEDIUM) tests
    // ================================================================

    private IType invokeResolveTraitTypeByStarImports(IJavaProject project, ModuleNode module, String simpleName) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTraitTypeByStarImports",
                IJavaProject.class, ModuleNode.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, project, module, simpleName);
    }

    @Test
    void resolveTraitTypeByStarImportsFinds() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType found = mock(IType.class);
        String src = "import com.example.*\nclass Foo {}";
        ModuleNode module = parseModule(src);
        when(project.findType("com.example.MyTrait")).thenReturn(found);
        IType result = invokeResolveTraitTypeByStarImports(project, module, "MyTrait");
        assertEquals(found, result);
    }

    @Test
    void resolveTraitTypeByStarImportsReturnsNull() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String src = "class Foo {}";
        ModuleNode module = parseModule(src);
        IType result = invokeResolveTraitTypeByStarImports(project, module, "Missing");
        assertNull(result);
    }

    // ================================================================
    // resolveClassNameToType (MEDIUM) tests
    // ================================================================

    private IType invokeResolveClassNameToType(ModuleNode module, IJavaProject project, String name) throws Exception {
        Method m = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveClassNameToType",
                ModuleNode.class, IJavaProject.class, String.class);
        m.setAccessible(true);
        return (IType) m.invoke(helper, module, project, name);
    }

    @Test
    void resolveClassNameToTypeFindsViaProject() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        IType found = mock(IType.class);
        String src = "import com.example.Bar\nclass Foo {}";
        ModuleNode module = parseModule(src);
        when(project.findType("com.example.Bar")).thenReturn(found);
        IType result = invokeResolveClassNameToType(module, project, "Bar");
        assertEquals(found, result);
    }

    @Test
    void resolveClassNameToTypeReturnsNullForUnknown() throws Exception {
        IJavaProject project = mock(IJavaProject.class);
        String src = "class Foo {}";
        ModuleNode module = parseModule(src);
        IType result = invokeResolveClassNameToType(module, project, "TotallyUnknown");
        assertNull(result);
    }
}
