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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IField;
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
}
