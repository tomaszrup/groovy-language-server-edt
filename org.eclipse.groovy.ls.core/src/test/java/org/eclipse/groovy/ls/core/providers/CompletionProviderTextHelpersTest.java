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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for pure-text utility methods in {@link CompletionProvider}.
 * All methods tested here operate on strings only — no JDT runtime required.
 */
class CompletionProviderTextHelpersTest {

    private CompletionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CompletionProvider(new DocumentManager());
    }

    // ---- matchesPrefix ----

    @Test
    void matchesPrefixEmptyPrefixMatchesAnything() throws Exception {
        assertTrue(invokeMatchesPrefix("Anything", ""));
    }

    @Test
    void matchesPrefixCaseInsensitive() throws Exception {
        assertTrue(invokeMatchesPrefix("ArrayList", "array"));
        assertTrue(invokeMatchesPrefix("ArrayList", "Array"));
    }

    @Test
    void matchesPrefixNoMatch() throws Exception {
        assertFalse(invokeMatchesPrefix("HashMap", "List"));
    }

    // ---- extractPrefix ----

    @Test
    void extractPrefixAtEndOfIdentifier() throws Exception {
        assertEquals("foo", invokeExtractPrefix("  foo", 5));
    }

    @Test
    void extractPrefixAtMiddleOfLine() throws Exception {
        assertEquals("bar", invokeExtractPrefix("foo.bar baz", 7));
    }

    @Test
    void extractPrefixEmptyWhenAtNonIdentifier() throws Exception {
        assertEquals("", invokeExtractPrefix("foo. ", 5));
    }

    @Test
    void extractPrefixAtStartOfContent() throws Exception {
        assertEquals("x", invokeExtractPrefix("x", 1));
    }

    // ---- parseImportsFromContent ----

    @Test
    void parseImportsFromContentFindsRegularImports() throws Exception {
        String content = """
                package com.example
                import java.time.LocalDate
                import java.util.List
                import static java.lang.Math.PI
                class Foo {}
                """;
        Set<String> imports = invokeParseImportsFromContent(content);
        assertTrue(imports.contains("java.time.LocalDate"));
        assertTrue(imports.contains("java.util.List"));
        // static imports are skipped
        assertFalse(imports.contains("java.lang.Math.PI"));
    }

    @Test
    void parseImportsFromContentSkipsStarAndStaticImports() throws Exception {
        String content = "import java.util.*\nimport static java.lang.Math.*\n";
        Set<String> imports = invokeParseImportsFromContent(content);
        assertTrue(imports.isEmpty());
    }

    @Test
    void parseImportsFromContentHandlesNullAndEmpty() throws Exception {
        assertTrue(invokeParseImportsFromContent(null).isEmpty());
        assertTrue(invokeParseImportsFromContent("").isEmpty());
    }

    @Test
    void parseImportsFromContentStripsTrailingSemicolon() throws Exception {
        Set<String> imports = invokeParseImportsFromContent("import java.time.LocalDate;\n");
        assertTrue(imports.contains("java.time.LocalDate"));
    }

    // ---- shouldAutoImportType ----

    @Test
    void shouldAutoImportTypeReturnsFalseForAlreadyImported() throws Exception {
        Set<String> existing = Set.of("com.example.Foo");
        assertFalse(invokeShouldAutoImport("com.example.Foo", "com.example", "other.pkg", existing));
    }

    @Test
    void shouldAutoImportTypeReturnsFalseForSamePackage() throws Exception {
        assertFalse(invokeShouldAutoImport("com.example.Foo", "com.example", "com.example", Set.of()));
    }

    @Test
    void shouldAutoImportTypeReturnsFalseForAutoImportedPackage() throws Exception {
        assertFalse(invokeShouldAutoImport("java.lang.String", "java.lang", "com.example", Set.of()));
    }

    @Test
    void shouldAutoImportTypeReturnsTrueForNewExternalType() throws Exception {
        assertTrue(invokeShouldAutoImport("org.apache.Foo", "org.apache", "com.example", Set.of()));
    }

    @Test
    void shouldAutoImportTypeReturnsFalseForNullInputs() throws Exception {
        assertFalse(invokeShouldAutoImport(null, "pkg", "other", Set.of()));
        assertFalse(invokeShouldAutoImport("a.B", null, "other", Set.of()));
        assertFalse(invokeShouldAutoImport("a.B", "", "other", Set.of()));
    }

    // ---- isAutoImportedPackage ----

    @Test
    void isAutoImportedPackageRecognizesGroovyDefaults() throws Exception {
        assertTrue(invokeIsAutoImported("java.lang"));
        assertTrue(invokeIsAutoImported("java.util"));
        assertTrue(invokeIsAutoImported("java.io"));
        assertTrue(invokeIsAutoImported("groovy.lang"));
        assertTrue(invokeIsAutoImported("groovy.util"));
        assertTrue(invokeIsAutoImported("java.math"));
    }

    @Test
    void isAutoImportedPackageRejectsNonDefaults() throws Exception {
        assertFalse(invokeIsAutoImported("com.example"));
        assertFalse(invokeIsAutoImported("org.junit"));
    }

    // ---- getCurrentPackageName ----

    @Test
    void getCurrentPackageNameExtractsPackage() throws Exception {
        assertEquals("com.example", invokeGetCurrentPackageName("package com.example\nclass Foo {}"));
    }

    @Test
    void getCurrentPackageNameWithSemicolon() throws Exception {
        assertEquals("com.example", invokeGetCurrentPackageName("package com.example;\nclass Foo {}"));
    }

    @Test
    void getCurrentPackageNameReturnsEmptyWhenNone() throws Exception {
        assertEquals("", invokeGetCurrentPackageName("class Foo {}"));
    }

    @Test
    void getCurrentPackageNameHandlesNullAndEmpty() throws Exception {
        assertEquals("", invokeGetCurrentPackageName(null));
        assertEquals("", invokeGetCurrentPackageName(""));
    }

    // ---- findImportInsertLine ----

    @Test
    void findImportInsertLineAfterLastImport() throws Exception {
        String content = "package com.example\nimport java.util.List\nimport java.io.File\nclass Foo {}";
        assertEquals(3, invokeFindImportInsertLine(content));
    }

    @Test
    void findImportInsertLineAfterPackageWhenNoImports() throws Exception {
        String content = "package com.example\n\nclass Foo {}";
        assertEquals(2, invokeFindImportInsertLine(content));
    }

    @Test
    void findImportInsertLineZeroWhenNoPackageOrImports() throws Exception {
        assertEquals(0, invokeFindImportInsertLine("class Foo {}"));
    }

    @Test
    void findImportInsertLineHandlesNullAndEmpty() throws Exception {
        assertEquals(0, invokeFindImportInsertLine(null));
        assertEquals(0, invokeFindImportInsertLine(""));
    }

    // ---- isAnnotationContext ----

    @Test
    void isAnnotationContextAfterAtSymbol() throws Exception {
        // "  @Over" — prefix starts at index 3, @ at index 2
        assertTrue(invokeIsAnnotationContext("  @Over", 7, 3));
    }

    @Test
    void isAnnotationContextAtSign() throws Exception {
        // cursor right after @
        assertTrue(invokeIsAnnotationContext("@", 1, 1));
    }

    @Test
    void isAnnotationContextNotAnnotation() throws Exception {
        assertFalse(invokeIsAnnotationContext("  Over", 6, 2));
    }

    // ---- positionToOffset ----

    @Test
    void positionToOffsetFirstLine() throws Exception {
        assertEquals(5, invokePositionToOffset("hello\nworld", new Position(0, 5)));
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        // "hello\n" is 6 chars, then "world" at col 3 => offset 9
        assertEquals(9, invokePositionToOffset("hello\nworld", new Position(1, 3)));
    }

    @Test
    void positionToOffsetClampedToLength() throws Exception {
        // Content is "hi" (length=2), requesting beyond end
        assertEquals(2, invokePositionToOffset("hi", new Position(0, 99)));
    }

    // ---- getKeywordCompletions ----

    @Test
    void getKeywordCompletionsEmptyPrefixReturnsAll() throws Exception {
        List<CompletionItem> items = invokeGetKeywordCompletions("");
        assertTrue(items.size() > 40, "Should return many Groovy keywords");
        assertTrue(items.stream().anyMatch(i -> "def".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "class".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "trait".equals(i.getLabel())));
    }

    @Test
    void getKeywordCompletionsFiltersByPrefix() throws Exception {
        List<CompletionItem> items = invokeGetKeywordCompletions("sw");
        assertEquals(1, items.size());
        assertEquals("switch", items.get(0).getLabel());
    }

    @Test
    void getKeywordCompletionsNoMatchReturnsEmpty() throws Exception {
        List<CompletionItem> items = invokeGetKeywordCompletions("zzz");
        assertTrue(items.isEmpty());
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private boolean invokeMatchesPrefix(String name, String prefix) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("matchesPrefix", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, name, prefix);
    }

    private String invokeExtractPrefix(String content, int offset) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("extractPrefix", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, offset);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeParseImportsFromContent(String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("parseImportsFromContent", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(provider, content);
    }

    private boolean invokeShouldAutoImport(String fqn, String packageName,
                                           String currentPackage, Set<String> existing) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("shouldAutoImportType",
                String.class, String.class, String.class, Set.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, fqn, packageName, currentPackage, existing);
    }

    private boolean invokeIsAutoImported(String packageName) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("isAutoImportedPackage", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, packageName);
    }

    private String invokeGetCurrentPackageName(String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("getCurrentPackageName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content);
    }

    private int invokeFindImportInsertLine(String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("findImportInsertLine", String.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content);
    }

    private boolean invokeIsAnnotationContext(String content, int offset, int prefixStart) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationContext",
                String.class, int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, content, offset, prefixStart);
    }

    private int invokePositionToOffset(String content, Position position) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content, position);
    }

    @SuppressWarnings("unchecked")
    private List<CompletionItem> invokeGetKeywordCompletions(String prefix) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("getKeywordCompletions", String.class);
        m.setAccessible(true);
        return (List<CompletionItem>) m.invoke(provider, prefix);
    }
}
