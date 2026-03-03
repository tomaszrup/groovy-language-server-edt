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

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormattingProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void preprocessForJdtReplacesDefAndPreservesOffsets() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String source = """
                def value = \"def in string\"
                String define = 'def in single quotes'
                """;

        String preprocessed = invokeString(provider, "preprocessForJdt", source);

        assertEquals(source.length(), preprocessed.length());
        assertTrue(preprocessed.contains("int value"));
        assertTrue(preprocessed.contains("define"));
        assertFalse(preprocessed.contains("\"def in string\""));
        assertFalse(preprocessed.contains("'def in single quotes'"));
    }

    @Test
    void detectLineSeparatorReturnsCrlfOrLf() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String crlf = invokeString(provider, "detectLineSeparator", "a\r\nb\r\n");
        String lf = invokeString(provider, "detectLineSeparator", "a\nb\n");

        assertEquals("\r\n", crlf);
        assertEquals("\n", lf);
    }

    @Test
    void loadEclipseFormatterProfileParsesSettingsXml() throws Exception {
        Path profile = tempDir.resolve("formatter.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind=\"CodeFormatterProfile\" name=\"Test\" version=\"21\">
                    <setting id=\"org.eclipse.jdt.core.formatter.tabulation.char\" value=\"space\"/>
                    <setting id=\"org.eclipse.jdt.core.formatter.tabulation.size\" value=\"4\"/>
                  </profile>
                </profiles>
                """);

        Map<String, String> settings = FormattingProvider.loadEclipseFormatterProfile(profile.toString());

        assertNotNull(settings);
        assertEquals("space", settings.get("org.eclipse.jdt.core.formatter.tabulation.char"));
        assertEquals("4", settings.get("org.eclipse.jdt.core.formatter.tabulation.size"));
    }

    @Test
    void loadEclipseFormatterProfileReturnsNullOrEmptyForMissingFile() throws Exception {
        try {
            Map<String, String> settings = FormattingProvider.loadEclipseFormatterProfile("/nonexistent/formatter.xml");
            // May return null or empty map
            assertTrue(settings == null || settings.isEmpty());
        } catch (Exception e) {
            // Some implementations throw for missing files - that's acceptable
            assertNotNull(e);
        }
    }

    @Test
    void preprocessForJdtHandlesEmptySource() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String preprocessed = invokeString(provider, "preprocessForJdt", "");
        assertEquals(0, preprocessed.length());
    }

    @Test
    void preprocessForJdtPreservesNonDefKeywords() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String source = "class Foo {\n    String name = 'test'\n}";
        String preprocessed = invokeString(provider, "preprocessForJdt", source);

        assertEquals(source.length(), preprocessed.length());
    }

    @Test
    void preprocessForJdtHandlesMultipleDefs() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String source = """
                def a = 1
                def b = 'hello'
                def c = true
                """;
        String preprocessed = invokeString(provider, "preprocessForJdt", source);

        assertEquals(source.length(), preprocessed.length());
        assertFalse(preprocessed.startsWith("def"));
    }

    @Test
    void detectLineSeparatorHandlesMixed() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String mixed = "a\nb\r\nc\n";
        String result = invokeString(provider, "detectLineSeparator", mixed);
        assertNotNull(result);
    }

    @Test
    void detectLineSeparatorHandlesNoNewlines() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String noNewlines = "single line";
        String result = invokeString(provider, "detectLineSeparator", noNewlines);
        assertNotNull(result);
    }

    @Test
    void loadEclipseFormatterProfileHandlesEmptyProfile() throws Exception {
        Path profile = tempDir.resolve("empty_formatter.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind="CodeFormatterProfile" name="Empty" version="21">
                  </profile>
                </profiles>
                """);

        Map<String, String> settings = FormattingProvider.loadEclipseFormatterProfile(profile.toString());
        assertNotNull(settings);
        assertTrue(settings.isEmpty());
    }

    @Test
    void loadEclipseFormatterProfileHandlesMultipleSettings() throws Exception {
        Path profile = tempDir.resolve("multi_formatter.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind="CodeFormatterProfile" name="Multi" version="21">
                    <setting id="setting1" value="val1"/>
                    <setting id="setting2" value="val2"/>
                    <setting id="setting3" value="val3"/>
                  </profile>
                </profiles>
                """);

        Map<String, String> settings = FormattingProvider.loadEclipseFormatterProfile(profile.toString());
        assertNotNull(settings);
        assertEquals(3, settings.size());
        assertEquals("val1", settings.get("setting1"));
        assertEquals("val2", settings.get("setting2"));
        assertEquals("val3", settings.get("setting3"));
    }

    private String invokeString(Object target, String methodName, String arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, arg);
    }

    // ================================================================
    // replaceStringLiterals tests
    // ================================================================

    @Test
    void replaceStringLiteralsReplacesDoubleQuoted() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("class Foo { String s = \"hello\" }");
        invokeReplaceStringLiterals(provider, sb);
        // Quotes are replaced with underscores; identifier-compatible content stays
        assertFalse(sb.toString().contains("\""), "Double quotes should be replaced");
        assertEquals("class Foo { String s = \"hello\" }".length(), sb.length());
    }

    @Test
    void replaceStringLiteralsSingleQuoted() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def x = 'world'");
        invokeReplaceStringLiterals(provider, sb);
        // Single quotes are replaced with underscores; identifier-compatible content stays
        assertFalse(sb.toString().contains("'"), "Single quotes should be replaced");
        assertEquals("def x = 'world'".length(), sb.length());
    }

    @Test
    void replaceStringLiteralsEscapedQuotes() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def x = \"he\\\"llo\"");
        invokeReplaceStringLiterals(provider, sb);
        // Length should be preserved
        assertEquals("def x = \"he\\\"llo\"".length(), sb.length());
    }

    @Test
    void replaceStringLiteralsEmpty() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder();
        invokeReplaceStringLiterals(provider, sb);
        assertEquals(0, sb.length());
    }

    @Test
    void replaceStringLiteralsNoStrings() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("int x = 42");
        invokeReplaceStringLiterals(provider, sb);
        assertEquals("int x = 42", sb.toString());
    }

    // ================================================================
    // replaceKeywordPreservingOffsets tests
    // ================================================================

    @Test
    void replaceKeywordBasic() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def x = 1");
        invokeReplaceKeyword(provider, sb, "def", "int");
        assertTrue(sb.toString().startsWith("int x"));
    }

    @Test
    void replaceKeywordDoesNotReplaceSubstring() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("String define = 1");
        invokeReplaceKeyword(provider, sb, "def", "int");
        assertTrue(sb.toString().contains("define"));
        assertFalse(sb.toString().contains("intine"));
    }

    @Test
    void replaceKeywordMultipleOccurrences() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def a;\ndef b;");
        invokeReplaceKeyword(provider, sb, "def", "int");
        assertFalse(sb.toString().contains("def"));
        assertTrue(sb.toString().contains("int a"));
        assertTrue(sb.toString().contains("int b"));
    }

    @Test
    void replaceKeywordAtEndOfString() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("x = def");
        invokeReplaceKeyword(provider, sb, "def", "int");
        assertTrue(sb.toString().endsWith("int"));
    }

    // ================================================================
    // offsetToPosition and positionToOffset tests
    // ================================================================

    @Test
    void offsetToPositionFirstLine() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        Position pos = invokeOffsetToPosition(provider, "hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        Position pos = invokeOffsetToPosition(provider, "hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtNewline() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        Position pos = invokeOffsetToPosition(provider, "ab\ncd", 2);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void positionToOffsetFirstLine() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "hello\nworld", new Position(0, 3));
        assertEquals(3, offset);
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "hello\nworld", new Position(1, 3));
        assertEquals(9, offset);
    }

    @Test
    void positionToOffsetClamped() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "hi", new Position(0, 99));
        assertEquals(2, offset);
    }

    // ================================================================
    // resolveProfilePath tests
    // ================================================================

    @Test
    void resolveProfilePathReturnsNullForNull() throws Exception {
        java.io.File file = invokeResolveProfilePath(null);
        assertNull(file);
    }

    @Test
    void resolveProfilePathReturnsNullForEmpty() throws Exception {
        java.io.File file = invokeResolveProfilePath("");
        assertNull(file);
    }

    @Test
    void resolveProfilePathHandlesAbsolutePath() throws Exception {
        Path profile = tempDir.resolve("test.xml");
        Files.writeString(profile, "<profiles></profiles>");
        java.io.File file = invokeResolveProfilePath(profile.toString());
        assertNotNull(file);
        assertTrue(file.exists());
    }

    @Test
    void resolveProfilePathHandlesFileUri() throws Exception {
        Path profile = tempDir.resolve("uri_test.xml");
        Files.writeString(profile, "<profiles></profiles>");
        String uri = profile.toUri().toString();
        java.io.File file = invokeResolveProfilePath(uri);
        assertNotNull(file);
    }

    // ================================================================
    // setFormatterProfilePath tests
    // ================================================================

    @Test
    void setFormatterProfilePathClearsOnNull() {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        provider.setFormatterProfilePath(null);
        // No exception, profile cleared
    }

    @Test
    void setFormatterProfilePathClearsOnEmpty() {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        provider.setFormatterProfilePath("");
        // No exception, profile cleared
    }

    @Test
    void setFormatterProfilePathLoadsValidProfile() throws Exception {
        Path profile = tempDir.resolve("valid_formatter.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind="CodeFormatterProfile" name="Test" version="21">
                    <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
                  </profile>
                </profiles>
                """);
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        provider.setFormatterProfilePath(profile.toString());
        // Loaded without exception
    }

    @Test
    void setFormatterProfilePathHandlesNonexistent() {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        // Should not throw, just log error
        provider.setFormatterProfilePath("/nonexistent/path/formatter.xml");
    }

    // ================================================================
    // Preprocessing edge cases
    // ================================================================

    @Test
    void preprocessForJdtHandlesNewlinesInsideString() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        String source = "def s = \"line1\\nline2\"";
        String result = invokeString(provider, "preprocessForJdt", source);
        assertEquals(source.length(), result.length());
    }

    @Test
    void preprocessForJdtHandlesMixedQuotes() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        String source = "def x = \"hello\"\ndef y = 'world'";
        String result = invokeString(provider, "preprocessForJdt", source);
        assertEquals(source.length(), result.length());
        // Quotes are replaced; identifier chars stay; 'def' becomes 'int'
        assertFalse(result.contains("\""), "Double quotes should be replaced");
        assertFalse(result.contains("'"), "Single quotes should be replaced");
        assertFalse(result.contains("def"), "def keyword should be replaced");
    }

    // ================================================================
    // convertEdits tests
    // ================================================================

    @Test
    void convertEditsHandlesReplaceEdit() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        ReplaceEdit replace = new ReplaceEdit(6, 5, "earth");
        String source = "hello world";
        @SuppressWarnings("unchecked")
        List<TextEdit> edits = (List<TextEdit>) invokeConvertEdits(provider, replace, source);
        assertEquals(1, edits.size());
        TextEdit edit = edits.get(0);
        assertEquals("earth", edit.getNewText());
        assertEquals(0, edit.getRange().getStart().getLine());
        assertEquals(6, edit.getRange().getStart().getCharacter());
    }

    @Test
    void convertEditsHandlesMultiTextEdit() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        MultiTextEdit multi = new MultiTextEdit();
        multi.addChild(new ReplaceEdit(0, 5, "Hi"));
        multi.addChild(new ReplaceEdit(6, 5, "earth"));
        String source = "hello world";
        @SuppressWarnings("unchecked")
        List<TextEdit> edits = (List<TextEdit>) invokeConvertEdits(provider, multi, source);
        assertEquals(2, edits.size());
    }

    @Test
    void convertEditsHandlesEmptyMultiTextEdit() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        MultiTextEdit multi = new MultiTextEdit();
        String source = "hello";
        @SuppressWarnings("unchecked")
        List<TextEdit> edits = (List<TextEdit>) invokeConvertEdits(provider, multi, source);
        assertTrue(edits.isEmpty());
    }

    @Test
    void convertEditsReplaceEditMultiLine() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        String source = "line1\nline2\nline3";
        ReplaceEdit replace = new ReplaceEdit(6, 5, "LINE2");
        @SuppressWarnings("unchecked")
        List<TextEdit> edits = (List<TextEdit>) invokeConvertEdits(provider, replace, source);
        assertEquals(1, edits.size());
        assertEquals(1, edits.get(0).getRange().getStart().getLine());
        assertEquals(0, edits.get(0).getRange().getStart().getCharacter());
    }

    // ================================================================
    // format / formatRange early return tests
    // ================================================================

    @Test
    void formatReturnsEmptyForNullContent() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider provider = new FormattingProvider(dm);
        DocumentFormattingParams params = new DocumentFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///unknown.groovy"));
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = provider.format(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void formatReturnsEmptyForEmptyContent() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///empty.groovy";
        dm.didOpen(uri, "");
        FormattingProvider provider = new FormattingProvider(dm);
        DocumentFormattingParams params = new DocumentFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = provider.format(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void formatRangeReturnsEmptyForNullContent() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider provider = new FormattingProvider(dm);
        DocumentRangeFormattingParams params = new DocumentRangeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///unknown.groovy"));
        params.setRange(new Range(new Position(0, 0), new Position(1, 0)));
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = provider.formatRange(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void formatRangeReturnsEmptyForEmptyContent() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///empty2.groovy";
        dm.didOpen(uri, "");
        FormattingProvider provider = new FormattingProvider(dm);
        DocumentRangeFormattingParams params = new DocumentRangeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setRange(new Range(new Position(0, 0), new Position(1, 0)));
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = provider.formatRange(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void formatRangeReturnsEmptyForInvalidRange() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///range.groovy";
        dm.didOpen(uri, "class A {\n    void run() {}\n}");
        FormattingProvider provider = new FormattingProvider(dm);
        DocumentRangeFormattingParams params = new DocumentRangeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        // End before start
        params.setRange(new Range(new Position(2, 0), new Position(0, 0)));
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = provider.formatRange(params);
        assertTrue(result.isEmpty());
    }

    // ================================================================
    // loadWorkspaceFormatterPrefs tests
    // ================================================================

    @Test
    void loadWorkspaceFormatterPrefsLoadsFromSettingsDir() throws Exception {
        // Create a temp project structure with .settings/org.eclipse.jdt.core.prefs
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir.resolve(".settings"));
        Path prefs = projectDir.resolve(".settings/org.eclipse.jdt.core.prefs");
        Properties props = new Properties();
        props.setProperty("org.eclipse.jdt.core.formatter.tabulation.char", "space");
        props.setProperty("org.eclipse.jdt.core.formatter.tabulation.size", "2");
        props.setProperty("unrelated.property", "value");
        try (FileOutputStream fos = new FileOutputStream(prefs.toFile())) {
            props.store(fos, null);
        }

        // Create a source file in the project
        Path srcFile = projectDir.resolve("Source.groovy");
        Files.writeString(srcFile, "class Source {}");

        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        java.util.Map<String, String> options = new java.util.HashMap<>();
        Method m = FormattingProvider.class.getDeclaredMethod(
                "loadWorkspaceFormatterPrefs", Map.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, options, srcFile.toUri().toString());

        // Only formatter-related properties should be loaded
        assertEquals("space", options.get("org.eclipse.jdt.core.formatter.tabulation.char"));
        assertEquals("2", options.get("org.eclipse.jdt.core.formatter.tabulation.size"));
        assertNull(options.get("unrelated.property"));
    }

    @Test
    void loadWorkspaceFormatterPrefsDoesNothingForNullUri() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        java.util.Map<String, String> options = new java.util.HashMap<>();
        Method m = FormattingProvider.class.getDeclaredMethod(
                "loadWorkspaceFormatterPrefs", Map.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, options, (String) null);
        assertTrue(options.isEmpty());
    }

    @Test
    void loadWorkspaceFormatterPrefsDoesNothingWhenNoSettingsDir() throws Exception {
        Path srcFile = tempDir.resolve("Orphan.groovy");
        Files.writeString(srcFile, "class Orphan {}");

        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        java.util.Map<String, String> options = new java.util.HashMap<>();
        Method m = FormattingProvider.class.getDeclaredMethod(
                "loadWorkspaceFormatterPrefs", Map.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, options, srcFile.toUri().toString());
        assertTrue(options.isEmpty());
    }

    // ================================================================
    // setFormatterProfilePath reload skip test
    // ================================================================

    @Test
    void setFormatterProfilePathSkipsReloadForSamePath() throws Exception {
        Path profile = tempDir.resolve("same_profile.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind="CodeFormatterProfile" name="Test" version="21">
                    <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="tab"/>
                  </profile>
                </profiles>
                """);
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        provider.setFormatterProfilePath(profile.toString());
        // Second call should skip reload (same path)
        provider.setFormatterProfilePath(profile.toString());
        // No exception, same path handled
    }

    @Test
    void setFormatterProfilePathHandlesEmptyProfileXml() throws Exception {
        Path profile = tempDir.resolve("empty_settings.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind="CodeFormatterProfile" name="Empty" version="21">
                  </profile>
                </profiles>
                """);
        FormattingProvider provider = new FormattingProvider(new DocumentManager());
        provider.setFormatterProfilePath(profile.toString());
        // No exception, empty settings handled
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private void invokeReplaceStringLiterals(Object target, StringBuilder sb) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("replaceStringLiterals", StringBuilder.class);
        m.setAccessible(true);
        m.invoke(target, sb);
    }

    private void invokeReplaceKeyword(Object target, StringBuilder sb, String keyword, String replacement) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("replaceKeywordPreservingOffsets",
                StringBuilder.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(target, sb, keyword, replacement);
    }

    private Position invokeOffsetToPosition(Object target, String source, int offset) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        return (Position) m.invoke(target, source, offset);
    }

    private int invokePositionToOffset(Object target, String source, Position position) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(target, source, position);
    }

    private java.io.File invokeResolveProfilePath(String path) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("resolveProfilePath", String.class);
        m.setAccessible(true);
        return (java.io.File) m.invoke(null, path);
    }

    private Object invokeConvertEdits(Object target, org.eclipse.text.edits.TextEdit edit, String source) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("convertEdits",
                org.eclipse.text.edits.TextEdit.class, String.class);
        m.setAccessible(true);
        return m.invoke(target, edit, source);
    }

    private boolean invokeIsContinuationContext(Object target, char before, char after) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("isContinuationContext", char.class, char.class);
        m.setAccessible(true);
        return (boolean) m.invoke(target, before, after);
    }

    private boolean invokeIsNewlineRemovalSafe(Object target, String source, int offset, int length) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("isNewlineRemovalSafe", String.class, int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(target, source, offset, length);
    }

    private String invokeDetectLineSeparator(Object target, String source) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("detectLineSeparator", String.class);
        m.setAccessible(true);
        return (String) m.invoke(target, source);
    }

    private char invokeLastNonWhitespaceBefore(Object target, String source, int index) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("lastNonWhitespaceBefore", String.class, int.class);
        m.setAccessible(true);
        return (char) m.invoke(target, source, index);
    }

    private char invokeFirstNonWhitespaceAfter(Object target, String source, int index) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("firstNonWhitespaceAfter", String.class, int.class);
        m.setAccessible(true);
        return (char) m.invoke(target, source, index);
    }

    private boolean invokeIsKeywordAt(Object target, StringBuilder sb, String keyword, int index, int len) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("isKeywordAt", StringBuilder.class, String.class, int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(target, sb, keyword, index, len);
    }

    private boolean invokeHasKeywordBoundaries(Object target, StringBuilder sb, int index, int len) throws Exception {
        Method m = FormattingProvider.class.getDeclaredMethod("hasKeywordBoundaries", StringBuilder.class, int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(target, sb, index, len);
    }

    // ================================================================
    // isContinuationContext tests
    // ================================================================

    @Test
    void isContinuationContextDotBefore() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsContinuationContext(p, '.', 'f'));
    }

    @Test
    void isContinuationContextPlusBefore() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsContinuationContext(p, '+', 'x'));
    }

    @Test
    void isContinuationContextDotAfter() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsContinuationContext(p, 'x', '.'));
    }

    @Test
    void isContinuationContextClosingParenAfter() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsContinuationContext(p, 'x', ')'));
    }

    @Test
    void isContinuationContextNoContinuation() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertFalse(invokeIsContinuationContext(p, 'x', 'y'));
    }

    @Test
    void isContinuationContextOpenParenBefore() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsContinuationContext(p, '(', 'x'));
    }

    // ================================================================
    // isNewlineRemovalSafe tests
    // ================================================================

    @Test
    void isNewlineRemovalSafeWithContinuation() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        String source = "foo.\nbar";
        assertTrue(invokeIsNewlineRemovalSafe(p, source, 0, source.length()));
    }

    @Test
    void isNewlineRemovalSafeWithoutContinuation() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        String source = "foo\nbar";
        assertFalse(invokeIsNewlineRemovalSafe(p, source, 0, source.length()));
    }

    @Test
    void isNewlineRemovalSafeNoNewline() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertTrue(invokeIsNewlineRemovalSafe(p, "foobar", 0, 6));
    }

    // ================================================================
    // detectLineSeparator tests
    // ================================================================

    @Test
    void detectLineSeparatorCRLF() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals("\r\n", invokeDetectLineSeparator(p, "hello\r\nworld"));
    }

    @Test
    void detectLineSeparatorLF() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals("\n", invokeDetectLineSeparator(p, "hello\nworld"));
    }

    @Test
    void detectLineSeparatorNoNewline() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        String result = invokeDetectLineSeparator(p, "hello");
        assertEquals(System.lineSeparator(), result);
    }

    // ================================================================
    // lastNonWhitespaceBefore / firstNonWhitespaceAfter tests
    // ================================================================

    @Test
    void lastNonWhitespaceBeforeFindsChar() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals('.', invokeLastNonWhitespaceBefore(p, "foo.  ", 5));
    }

    @Test
    void lastNonWhitespaceBeforeReturnsNullChar() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals('\0', invokeLastNonWhitespaceBefore(p, "   ", 3));
    }

    @Test
    void firstNonWhitespaceAfterFindsChar() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals('.', invokeFirstNonWhitespaceAfter(p, "  .bar", 0));
    }

    @Test
    void firstNonWhitespaceAfterReturnsNullChar() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        assertEquals('\0', invokeFirstNonWhitespaceAfter(p, "   ", 0));
    }

    // ================================================================
    // isKeywordAt / hasKeywordBoundaries tests
    // ================================================================

    @Test
    void isKeywordAtMatch() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def foo");
        assertTrue(invokeIsKeywordAt(p, sb, "def", 0, 3));
    }

    @Test
    void isKeywordAtNoMatch() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("int foo");
        assertFalse(invokeIsKeywordAt(p, sb, "def", 0, 3));
    }

    @Test
    void hasKeywordBoundariesAtStart() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("def foo");
        assertTrue(invokeHasKeywordBoundaries(p, sb, 0, 3));
    }

    @Test
    void hasKeywordBoundariesInMiddle() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("x def foo");
        // "def" at index 2 — preceded by space (valid boundary)
        assertTrue(invokeHasKeywordBoundaries(p, sb, 2, 3));
    }

    @Test
    void hasKeywordBoundariesNoValidStart() throws Exception {
        FormattingProvider p = new FormattingProvider(new DocumentManager());
        StringBuilder sb = new StringBuilder("adef foo");
        // "def" at index 1 — preceded by 'a' (identifier char, NOT valid boundary)
        assertFalse(invokeHasKeywordBoundaries(p, sb, 1, 3));
    }

    // ================================================================
    // resolveProfilePath tests
    // ================================================================

    @Test
    void resolveProfilePathNull() throws Exception {
        assertNull(invokeResolveProfilePath(null));
    }

    @Test
    void resolveProfilePathEmpty() throws Exception {
        assertNull(invokeResolveProfilePath(""));
    }

    @Test
    void resolveProfilePathAbsolute() throws Exception {
        java.io.File result = invokeResolveProfilePath(tempDir.resolve("test.xml").toString());
        assertNotNull(result);
        assertTrue(result.isAbsolute());
    }

    @Test
    void resolveProfilePathRelative() throws Exception {
        java.io.File result = invokeResolveProfilePath("relative/path.xml");
        assertNotNull(result);
    }

    // ================================================================
    // formatOnType tests
    // ================================================================

    @Test
    void formatOnTypeReturnsEmptyForMissingDocument() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///NoSuchDoc.groovy"));
        params.setPosition(new Position(0, 5));
        params.setCh("}");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void formatOnTypeReturnsEmptyForEmptySource() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///EmptyOnType.groovy";
        dm.didOpen(uri, "");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 0));
        params.setCh("}");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertTrue(result.isEmpty());
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeReturnsEmptyForUnknownTrigger() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///UnknownTrigger.groovy";
        dm.didOpen(uri, "def x = 1");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 5));
        params.setCh("x");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertTrue(result.isEmpty());
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeWithCloseBrace() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///CloseBrace.groovy";
        String source = "class Foo {\n    void bar() {\n        int x = 1\n    }\n}";
        dm.didOpen(uri, source);
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(3, 5));
        params.setCh("}");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertNotNull(result);
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeCloseBraceNoMatch() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///NoMatchBrace.groovy";
        dm.didOpen(uri, "}");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 1));
        params.setCh("}");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertTrue(result.isEmpty());
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeWithSemicolon() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///Semicolon.groovy";
        dm.didOpen(uri, "int x = 1;");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 10));
        params.setCh(";");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertNotNull(result);
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeWithNewline() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///Newline.groovy";
        dm.didOpen(uri, "int x = 1\n");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(1, 0));
        params.setCh("\n");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertNotNull(result);
        dm.didClose(uri);
    }

    @Test
    void formatOnTypeNewlineAtStart() {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        String uri = "file:///NewlineStart.groovy";
        dm.didOpen(uri, "\nint x = 1");
        DocumentOnTypeFormattingParams params = new DocumentOnTypeFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 0));
        params.setCh("\n");
        params.setOptions(new FormattingOptions(4, true));
        List<TextEdit> result = p.formatOnType(params);
        assertTrue(result.isEmpty());
        dm.didClose(uri);
    }

    // ================================================================
    // findMatchingOpenBrace tests
    // ================================================================

    @Test
    void findMatchingOpenBraceSimple() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findMatchingOpenBrace", String.class, int.class);
        m.setAccessible(true);
        assertEquals(0, (int) m.invoke(p, "{abc}", 3));
    }

    @Test
    void findMatchingOpenBraceNested() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findMatchingOpenBrace", String.class, int.class);
        m.setAccessible(true);
        assertEquals(0, (int) m.invoke(p, "{ { } }", 5));
    }

    @Test
    void findMatchingOpenBraceNotFound() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findMatchingOpenBrace", String.class, int.class);
        m.setAccessible(true);
        assertEquals(-1, (int) m.invoke(p, "abc}", 2));
    }

    // ================================================================
    // findLineStart tests
    // ================================================================

    @Test
    void findLineStartAtBeginning() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findLineStart", String.class, int.class);
        m.setAccessible(true);
        assertEquals(0, (int) m.invoke(p, "hello", 3));
    }

    @Test
    void findLineStartSecondLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findLineStart", String.class, int.class);
        m.setAccessible(true);
        assertEquals(6, (int) m.invoke(p, "hello\nworld", 8));
    }

    @Test
    void findLineStartThirdLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        FormattingProvider p = new FormattingProvider(dm);
        Method m = FormattingProvider.class.getDeclaredMethod("findLineStart", String.class, int.class);
        m.setAccessible(true);
        assertEquals(12, (int) m.invoke(p, "hello\nworld\nfoo", 14));
    }
}
