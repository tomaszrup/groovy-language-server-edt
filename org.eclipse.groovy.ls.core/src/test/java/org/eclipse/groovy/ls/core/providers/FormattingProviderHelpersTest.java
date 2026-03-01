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
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for pure-text utility methods in {@link FormattingProvider}.
 */
class FormattingProviderHelpersTest {

    @TempDir
    Path tempDir;

    private FormattingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FormattingProvider(new DocumentManager());
    }

    // ---- replaceStringLiterals ----

    @Test
    void replaceStringLiteralsNeutralizeDoubleQuoted() throws Exception {
        StringBuilder sb = new StringBuilder("String s = \"hello world\"");
        invokeReplaceStringLiterals(sb);
        assertEquals(sb.length(), "String s = \"hello world\"".length()); // same length
        assertFalse(sb.toString().contains("hello world"));
    }

    @Test
    void replaceStringLiteralsSingleQuoted() throws Exception {
        StringBuilder sb = new StringBuilder("String s = 'hi'");
        invokeReplaceStringLiterals(sb);
        // Quotes are replaced with underscores; identifier chars inside are preserved
        assertFalse(sb.toString().contains("'"));
        assertEquals(15, sb.length());
    }

    @Test
    void replaceStringLiteralsPreservesEscapes() throws Exception {
        StringBuilder sb = new StringBuilder("String s = \"a\\nb\"");
        invokeReplaceStringLiterals(sb);
        // Should still be same length; escape chars replaced with underscores
        assertEquals("String s = \"a\\nb\"".length(), sb.length());
    }

    @Test
    void replaceStringLiteralsNoStrings() throws Exception {
        StringBuilder sb = new StringBuilder("int x = 42");
        invokeReplaceStringLiterals(sb);
        assertEquals("int x = 42", sb.toString());
    }

    // ---- replaceKeywordPreservingOffsets ----

    @Test
    void replaceKeywordPreservingOffsetsReplacesStandalone() throws Exception {
        StringBuilder sb = new StringBuilder("def value = 5");
        invokeReplaceKeyword(sb, "def", "int");
        assertEquals("int value = 5", sb.toString());
    }

    @Test
    void replaceKeywordPreservingOffsetsSkipsInIdentifiers() throws Exception {
        StringBuilder sb = new StringBuilder("String define = 5");
        invokeReplaceKeyword(sb, "def", "int");
        assertEquals("String define = 5", sb.toString()); // "define" not touched
    }

    @Test
    void replaceKeywordPreservingOffsetsSkipsPartialMatch() throws Exception {
        StringBuilder sb = new StringBuilder("undefined = 5");
        invokeReplaceKeyword(sb, "def", "int");
        assertEquals("undefined = 5", sb.toString()); // "def" inside "undefined" not touched
    }

    @Test
    void replaceKeywordPreservingOffsetsMultipleOccurrences() throws Exception {
        StringBuilder sb = new StringBuilder("def a = 1\ndef b = 2");
        invokeReplaceKeyword(sb, "def", "int");
        assertEquals("int a = 1\nint b = 2", sb.toString());
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
    void offsetToPositionAtNewline() throws Exception {
        // offset 5 is the '\n' character; after it col resets
        Position pos = invokeOffsetToPosition("hello\nworld", 5);
        assertEquals(0, pos.getLine());
        assertEquals(5, pos.getCharacter());
    }

    @Test
    void offsetToPositionBeyondLength() throws Exception {
        Position pos = invokeOffsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
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
        int offset = invokePositionToOffset("hi", new Position(0, 99));
        assertEquals(2, offset);
    }

    // ---- resolveProfilePath ----

    @Test
    void resolveProfilePathAbsolute() throws Exception {
        Path file = tempDir.resolve("test.xml");
        Files.writeString(file, "<profiles/>");
        java.io.File resolved = invokeResolveProfilePath(file.toAbsolutePath().toString());
        assertNotNull(resolved);
        assertTrue(resolved.exists());
    }

    @Test
    void resolveProfilePathNullReturnsNull() throws Exception {
        assertNull(invokeResolveProfilePath(null));
        assertNull(invokeResolveProfilePath(""));
    }

    @Test
    void resolveProfilePathFileUri() throws Exception {
        Path file = tempDir.resolve("profile.xml");
        Files.writeString(file, "<profiles/>");
        String uri = file.toUri().toString();
        java.io.File resolved = invokeResolveProfilePath(uri);
        assertNotNull(resolved);
        assertTrue(resolved.exists());
    }

    // ---- detectLineSeparator ----

    @Test
    void detectLineSeparatorCrlf() throws Exception {
        assertEquals("\r\n", invokeDetectLineSeparator("a\r\nb\r\n"));
    }

    @Test
    void detectLineSeparatorLf() throws Exception {
        assertEquals("\n", invokeDetectLineSeparator("a\nb\n"));
    }

    // ---- preprocessForJdt ----

    @Test
    void preprocessForJdtReplacesDefAndStrings() throws Exception {
        String source = "def value = \"def in string\"";
        String result = invokePreprocessForJdt(source);
        assertEquals(source.length(), result.length());
        assertTrue(result.contains("int value"));
        // "def in string" should be neutralized, so the literal 'def' inside it is gone
        assertFalse(result.contains("\"def in string\""));
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private void invokeReplaceStringLiterals(StringBuilder sb) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("replaceStringLiterals", StringBuilder.class);
        m.setAccessible(true);
        m.invoke(provider, sb);
    }

    private void invokeReplaceKeyword(StringBuilder sb, String keyword, String replacement) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("replaceKeywordPreservingOffsets",
                StringBuilder.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, sb, keyword, replacement);
    }

    private Position invokeOffsetToPosition(String source, int offset) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        return (Position) m.invoke(provider, source, offset);
    }

    private int invokePositionToOffset(String source, Position position) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, source, position);
    }

    private java.io.File invokeResolveProfilePath(String path) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("resolveProfilePath", String.class);
        m.setAccessible(true);
        return (java.io.File) m.invoke(null, path);
    }

    private String invokeDetectLineSeparator(String source) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("detectLineSeparator", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, source);
    }

    private String invokePreprocessForJdt(String source) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("preprocessForJdt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, source);
    }
}
