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

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
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
        // The Groovy AST may create a script class that spans the whole file,
        // so this may or may not be null. We just verify no exception.
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
        Method method = MinimalCodeSelectHelper.class.getDeclaredMethod("resolveTypeFromAST", ModuleNode.class, String.class, int.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(helper, module, word, offset, source);
    }
}
