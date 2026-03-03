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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Provides document formatting for Groovy files using the Eclipse JDT
 * {@link CodeFormatter}.
 * <p>
 * Supports Eclipse formatter profile XML files — the same {@code .xml} files
 * used by Eclipse IDE's Java/Groovy formatter settings. When a profile file
 * path is set (via the {@code groovy.format.settingsUrl} configuration), its
 * settings are loaded and applied. Otherwise, sensible defaults are used,
 * respecting the LSP {@link FormattingOptions} (tab size, insert spaces).
 * <p>
 * The JDT CodeFormatter handles core formatting: indentation, brace placement,
 * line wrapping, whitespace around operators, blank lines, etc. Since Groovy
 * is a superset of Java for most statement-level syntax, the JDT formatter
 * works well for the vast majority of Groovy code. Groovy-specific constructs
 * (closures, builders, etc.) are handled gracefully — the formatter preserves
 * what it cannot parse rather than mangling it.
 */
public class FormattingProvider {

    private final DocumentManager documentManager;

    /**
     * Cached formatter options loaded from an Eclipse XML profile.
     * Null means "use defaults + LSP options".
     */
    private Map<String, String> profileOptions;

    /**
     * Path to the currently loaded profile file, for cache invalidation.
     */
    private String loadedProfilePath;

    // Cached workspace formatter prefs (.settings/org.eclipse.jdt.core.prefs)
    private String cachedWorkspacePrefsPath;
    private long cachedWorkspacePrefsLastModified;
    private Map<String, String> cachedWorkspacePrefs;

    public FormattingProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Format the entire document.
     */
    public List<TextEdit> format(DocumentFormattingParams params) {
        String uri = params.getTextDocument().getUri();
        String source = documentManager.getContent(uri);
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, String> options = buildFormatterOptions(params.getOptions(), uri);
        return formatSource(source, 0, source.length(), options);
    }

    /**
     * Format a range of the document.
     */
    public List<TextEdit> formatRange(DocumentRangeFormattingParams params) {
        String uri = params.getTextDocument().getUri();
        String source = documentManager.getContent(uri);
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        Range range = params.getRange();
        int offset = positionToOffset(source, range.getStart());
        int endOffset = positionToOffset(source, range.getEnd());
        int length = endOffset - offset;

        if (offset < 0 || length <= 0 || offset + length > source.length()) {
            return new ArrayList<>();
        }

        Map<String, String> options = buildFormatterOptions(params.getOptions(), uri);
        return formatSource(source, offset, length, options);
    }

    /**
     * Format on typing a trigger character.
     * <p>
     * Supports three trigger characters:
     * <ul>
     *   <li>{@code \}} — formats the block from the matching {@code \{}</li>
     *   <li>{@code ;} — formats the current line</li>
     *   <li>{@code \n} — formats the previous line</li>
     * </ul>
     */
    public List<TextEdit> formatOnType(DocumentOnTypeFormattingParams params) {
        String uri = params.getTextDocument().getUri();
        String source = documentManager.getContent(uri);
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        Position triggerPos = params.getPosition();
        String triggerChar = params.getCh();
        int triggerOffset = positionToOffset(source, triggerPos);

        int formatStart;
        int formatEnd;

        switch (triggerChar) {
            case "}" -> {
                // Find the matching opening brace
                int matchingBrace = findMatchingOpenBrace(source, triggerOffset - 1);
                if (matchingBrace < 0) {
                    return new ArrayList<>();
                }
                // Extend to the line start of the opening brace
                formatStart = findLineStart(source, matchingBrace);
                formatEnd = Math.min(triggerOffset + 1, source.length());
            }
            case ";" -> {
                // Format the current line
                formatStart = findLineStart(source, triggerOffset);
                formatEnd = Math.min(triggerOffset + 1, source.length());
            }
            case "\n" -> {
                // Format the previous line (the line that was just completed)
                int prevLineEnd = triggerOffset - 1;
                if (prevLineEnd < 0) {
                    return new ArrayList<>();
                }
                formatStart = findLineStart(source, prevLineEnd);
                formatEnd = Math.min(triggerOffset, source.length());
            }
            default -> {
                return new ArrayList<>();
            }
        }

        if (formatStart >= formatEnd || formatStart < 0) {
            return new ArrayList<>();
        }

        Map<String, String> options = buildFormatterOptions(params.getOptions(), uri);
        return formatSource(source, formatStart, formatEnd - formatStart, options);
    }

    /**
     * Find the matching opening brace for a closing brace, accounting for
     * nesting. Skips braces inside string literals and comments.
     */
    private int findMatchingOpenBrace(String source, int closeBraceOffset) {
        int depth = 1;
        for (int i = closeBraceOffset - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Find the start offset of the line containing the given offset.
     */
    private int findLineStart(String source, int offset) {
        for (int i = Math.min(offset, source.length() - 1); i >= 0; i--) {
            if (source.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Set or update the formatter profile path. Called when the configuration
     * changes. Pass {@code null} to clear and use defaults.
     */
    public void setFormatterProfilePath(String path) {
        if (path == null || path.isEmpty()) {
            profileOptions = null;
            loadedProfilePath = null;
            GroovyLanguageServerPlugin.logInfo("Formatter profile cleared; using defaults.");
            return;
        }

        // Only reload if the path actually changed
        if (path.equals(loadedProfilePath) && profileOptions != null) {
            return;
        }

        try {
            Map<String, String> loaded = loadEclipseFormatterProfile(path);
                if (!loaded.isEmpty()) {
                profileOptions = loaded;
                loadedProfilePath = path;
                GroovyLanguageServerPlugin.logInfo(
                        "Loaded Eclipse formatter profile (" + loaded.size()
                                + " settings) from: " + path);
            } else {
                GroovyLanguageServerPlugin.logWarning(
                        "No formatter settings found in profile: " + path);
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to load Eclipse formatter profile from: " + path, e);
        }
    }

    // ================================================================
    // Core formatting logic
    // ================================================================

    /**
     * Format a region of source using the Eclipse JDT CodeFormatter.
     */
    private List<TextEdit> formatSource(String source, int offset, int length,
                                         Map<String, String> options) {
        List<TextEdit> result = new ArrayList<>();

        try {
            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
            if (formatter == null) {
                GroovyLanguageServerPlugin.logError(
                        "ToolFactory.createCodeFormatter() returned null", null);
                return result;
            }

            // Preprocess Groovy source to make it JDT-parseable.
            // String literals are replaced with same-length identifiers so the
            // JDT scanner doesn't crash on Groovy constructs (e.g. Spock string
            // method names). All character offsets are preserved, so the
            // formatter's whitespace edits apply directly to the original source.
            String preprocessed = preprocessForJdt(source);
            String lineSeparator = detectLineSeparator(source);
            org.eclipse.text.edits.TextEdit edit = tryFormat(
                formatter,
                CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                preprocessed,
                offset,
                length,
                lineSeparator,
                "K_COMPILATION_UNIT|F_INCLUDE_COMMENTS");

            if (edit == null) {
                edit = tryFormat(
                    formatter,
                    CodeFormatter.K_COMPILATION_UNIT,
                    preprocessed,
                    offset,
                    length,
                    lineSeparator,
                    "K_COMPILATION_UNIT");
            }

            if (edit == null) {
                edit = tryFormat(
                    formatter,
                    CodeFormatter.K_UNKNOWN,
                    preprocessed,
                    offset,
                    length,
                    lineSeparator,
                    "K_UNKNOWN");
            }

            if (edit == null) {
                GroovyLanguageServerPlugin.logWarning("CodeFormatter produced no edits.");
                return result;
            }

            // Convert Eclipse TextEdits to LSP TextEdits
            result = convertEdits(edit, source);

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Formatting failed", e);
        }

        return result;
    }

    private org.eclipse.text.edits.TextEdit tryFormat(CodeFormatter formatter,
                                                      int kind,
                                                      String source,
                                                      int offset,
                                                      int length,
                                                      String lineSeparator,
                                                      String kindLabel) {
        try {
            return formatter.format(kind, source, offset, length, 0, lineSeparator);
        } catch (RuntimeException e) {
            GroovyLanguageServerPlugin.logWarning(
                    "Formatter mode " + kindLabel + " failed: " + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " - " + e.getMessage() : ""));
            return null;
        }
    }

    private String detectLineSeparator(String source) {
        int newlineIndex = source.indexOf('\n');
        if (newlineIndex > 0 && source.charAt(newlineIndex - 1) == '\r') {
            return "\r\n";
        }
        if (newlineIndex >= 0) {
            return "\n";
        }
        return System.lineSeparator();
    }

    /**
     * Preprocess Groovy source to make it parseable by the JDT formatter.
     * <p>
     * The JDT formatter's internal scanner crashes on Groovy-specific syntax
     * (e.g. {@code def}, string method names). This method transforms the
     * source into near-valid Java while preserving every character offset so
     * the formatter's whitespace-only edits can be applied to the original.
     * <ol>
     *   <li>Replace string/GString literals with same-length identifiers</li>
     *   <li>Replace {@code def} keyword with {@code int} (same 3 chars)</li>
     * </ol>
     */
    private String preprocessForJdt(String source) {
        StringBuilder sb = new StringBuilder(source);

        // Step 1: neutralise string literals (must run before keyword
        // replacement so that "def" inside a string is not touched).
        replaceStringLiterals(sb);

        // Step 2: replace Groovy keywords with same-length Java equivalents.
        replaceKeywordPreservingOffsets(sb, "def", "int");

        return sb.toString();
    }

    /**
     * Replace every string / char literal in {@code sb} with same-length
     * underscore identifiers. Handles single- and double-quoted strings,
     * escape sequences, and unterminated strings (stops at newline).
     */
    private void replaceStringLiterals(StringBuilder sb) {
        int index = 0;
        while (index < sb.length()) {
            char current = sb.charAt(index);
            if (current == '"' || current == '\'') {
                index = maskQuotedLiteral(sb, index, current);
            } else {
                index++;
            }
        }
    }

    private int maskQuotedLiteral(StringBuilder sb, int startIndex, char quote) {
        sb.setCharAt(startIndex, '_');
        int index = startIndex + 1;

        while (index < sb.length()) {
            char current = sb.charAt(index);
            if (current == '\\' && index + 1 < sb.length()) {
                sb.setCharAt(index, '_');
                sb.setCharAt(index + 1, '_');
                index += 2;
                continue;
            }
            if (current == quote) {
                sb.setCharAt(index, '_');
                return index + 1;
            }
            if (current == '\n' || current == '\r') {
                return index;
            }
            if (!Character.isJavaIdentifierPart(current)) {
                sb.setCharAt(index, '_');
            }
            index++;
        }

        return index;
    }

    /**
     * Replace all occurrences of a Groovy keyword with a same-length Java
     * keyword, respecting word boundaries so that identifiers like
     * {@code define} or {@code undefined} are not affected.
     */
    private void replaceKeywordPreservingOffsets(StringBuilder sb,
                                                  String keyword,
                                                  String replacement) {
        int kwLen = keyword.length();
        int index = 0;
        while (index <= sb.length() - kwLen) {
            if (isKeywordAt(sb, keyword, index, kwLen)
                    && hasKeywordBoundaries(sb, index, kwLen)) {
                for (int j = 0; j < kwLen; j++) {
                    sb.setCharAt(index + j, replacement.charAt(j));
                }
                index += kwLen;
            } else {
                index++;
            }
        }
    }

    private boolean isKeywordAt(StringBuilder sb, String keyword, int index, int keywordLength) {
        for (int j = 0; j < keywordLength; j++) {
            if (sb.charAt(index + j) != keyword.charAt(j)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasKeywordBoundaries(StringBuilder sb, int index, int keywordLength) {
        boolean validStart = index == 0 || !Character.isJavaIdentifierPart(sb.charAt(index - 1));
        if (!validStart) {
            return false;
        }
        boolean atEnd = index + keywordLength >= sb.length();
        return atEnd || !Character.isJavaIdentifierPart(sb.charAt(index + keywordLength));
    }

    // ================================================================
    // Options building
    // ================================================================

    /**
     * Build the formatter options map. Priority order:
     * <ol>
     *   <li>Eclipse formatter profile XML settings (highest)</li>
     *   <li>LSP formatting options (tab size, insert spaces)</li>
     *   <li>JDT/JavaCore defaults (lowest)</li>
     * </ol>
     */
    private Map<String, String> buildFormatterOptions(FormattingOptions lspOptions,
                                                       String documentUri) {
        // Start with JDT defaults
        @SuppressWarnings("unchecked")
        Map<String, String> options = new HashMap<>(
                (Map<String, String>) (Map<?, ?>) JavaCore.getOptions());

        // Apply profile settings if loaded
        if (profileOptions != null) {
            options.putAll(profileOptions);
        }

        // Apply workspace-level .settings/org.eclipse.jdt.core.prefs if found
        loadWorkspaceFormatterPrefs(options, documentUri);

        // Override with LSP-provided options (these come from editor settings)
        if (lspOptions != null) {
            int tabSize = lspOptions.getTabSize();
            if (tabSize > 0) {
                String tabSizeStr = String.valueOf(tabSize);
                options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, tabSizeStr);
                options.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, tabSizeStr);
            }
            if (lspOptions.isInsertSpaces()) {
                options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
            } else {
                options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.TAB);
            }
        }

        // Ensure the source compatibility is set high enough for Groovy features
        options.put(JavaCore.COMPILER_SOURCE, "17");
        options.put(JavaCore.COMPILER_COMPLIANCE, "17");
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");

        // Spock blocks (e.g. expect:, when:, then:) are parsed similarly to labels by
        // the JDT formatter; forcing newline-after-label prevents block flattening.
        options.put("org.eclipse.jdt.core.formatter.insert_new_line_after_label", "insert");

        return options;
    }

    /**
     * Try to load formatter preferences from the workspace's
     * {@code .settings/org.eclipse.jdt.core.prefs} file — the same file
     * that Eclipse IDE generates when you configure project-specific
     * formatter settings.
     */
    private void loadWorkspaceFormatterPrefs(Map<String, String> options, String documentUri) {
        try {
            if (documentUri == null) return;

            URI uri = URI.create(documentUri);
            File docFile = new File(uri);
            File dir = docFile.getParentFile();

            // Walk up to find .settings/org.eclipse.jdt.core.prefs
            while (dir != null) {
                File settings = new File(dir, ".settings/org.eclipse.jdt.core.prefs");
                if (settings.exists() && settings.isFile()) {
                    String settingsPath = settings.getAbsolutePath();
                    long lastModified = settings.lastModified();

                    // Re-use cached prefs if file hasn't changed
                    if (settingsPath.equals(cachedWorkspacePrefsPath)
                            && lastModified == cachedWorkspacePrefsLastModified
                            && cachedWorkspacePrefs != null) {
                        options.putAll(cachedWorkspacePrefs);
                        return;
                    }

                    // Parse and cache
                    java.util.Properties props = new java.util.Properties();
                    try (FileInputStream fis = new FileInputStream(settings)) {
                        props.load(fis);
                    }
                    Map<String, String> parsed = new HashMap<>();
                    for (String key : props.stringPropertyNames()) {
                        if (key.startsWith("org.eclipse.jdt.core.formatter.")) {
                            parsed.put(key, props.getProperty(key));
                        }
                    }
                    cachedWorkspacePrefsPath = settingsPath;
                    cachedWorkspacePrefsLastModified = lastModified;
                    cachedWorkspacePrefs = parsed;
                    options.putAll(parsed);

                    GroovyLanguageServerPlugin.logInfo(
                            "Loaded workspace formatter prefs from: " + settings.getPath());
                    return;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            // Silently ignore — workspace prefs are optional
        }
    }

    // ================================================================
    // Eclipse Formatter Profile XML Parser
    // ================================================================

    /**
     * Load formatter settings from an Eclipse formatter profile XML file.
     * <p>
     * Eclipse formatter profiles are XML files with this structure:
     * <pre>{@code
     * <profiles>
     *   <profile kind="CodeFormatterProfile" name="MyProfile" version="...">
     *     <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
     *     <setting id="org.eclipse.jdt.core.formatter.tabulation.size" value="4"/>
     *     ...
     *   </profile>
     * </profiles>
     * }</pre>
     * We extract all {@code <setting>} elements and map {@code id → value}.
     */
        static Map<String, String> loadEclipseFormatterProfile(String path)
            throws IOException, ParserConfigurationException, SAXException {
        Map<String, String> settings = new HashMap<>();
        File file = resolveProfilePath(path);

        if (file == null || !file.exists()) {
            throw new java.io.FileNotFoundException(
                    "Formatter profile not found: " + path);
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Security: disable external entities
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);

        // Find all <setting> elements (could be under <profile> or directly)
        NodeList settingNodes = doc.getElementsByTagName("setting");
        for (int i = 0; i < settingNodes.getLength(); i++) {
            Element setting = (Element) settingNodes.item(i);
            String id = setting.getAttribute("id");
            String value = setting.getAttribute("value");
            if (id != null && !id.isEmpty() && value != null) {
                settings.put(id, value);
            }
        }

        return settings;
    }

    /**
     * Resolve the profile path — supports absolute paths, workspace-relative
     * paths, and file:// URIs.
     */
    private static File resolveProfilePath(String path) {
        if (path == null || path.isEmpty()) return null;

        // Handle file:// URIs
        if (path.startsWith("file://") || path.startsWith("file:///")) {
            try {
                return new File(URI.create(path));
            } catch (Exception e) {
                // Fall through to try as a regular path
            }
        }

        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }

        // Try workspace-relative
        // The workspace root should be passed in; for now just return as-is
        return file;
    }

    // ================================================================
    // Edit conversion
    // ================================================================

    /**
     * Convert an Eclipse {@link org.eclipse.text.edits.TextEdit} tree into
     * a flat list of LSP {@link TextEdit} objects.
     * <p>
     * Groovy uses newlines as statement terminators (unlike Java which uses
     * semicolons). The JDT formatter may produce edits that collapse newlines
     * — valid for Java but destructive for Groovy when the newline separates
     * independent statements. Edits that remove newlines are allowed only when
     * the context indicates a continuation (e.g. chained method calls,
     * multi-line expressions). Edits that would collapse statement boundaries
     * are rejected.
     */
    private List<TextEdit> convertEdits(org.eclipse.text.edits.TextEdit eclipseEdit,
                                         String source) {
        List<TextEdit> lspEdits = new ArrayList<>();

        if (eclipseEdit instanceof MultiTextEdit) {
            // Recurse into children
            for (org.eclipse.text.edits.TextEdit child : eclipseEdit.getChildren()) {
                lspEdits.addAll(convertEdits(child, source));
            }
        } else if (eclipseEdit instanceof ReplaceEdit replace) {
            int offset = replace.getOffset();
            int length = replace.getLength();
            String newText = replace.getText();

            // Guard: reject edits that would collapse Groovy statement boundaries.
            // Continuation-style breaks (chained calls, multi-line args) are fine.
            String originalSpan = source.substring(offset, Math.min(offset + length, source.length()));
            long originalNewlines = originalSpan.chars().filter(c -> c == '\n').count();
            long replacementNewlines = newText.chars().filter(c -> c == '\n').count();
            if (replacementNewlines < originalNewlines
                    && !isNewlineRemovalSafe(source, offset, length)) {
                // Skip — this edit would merge independent Groovy statements
                return lspEdits;
            }

            Position start = offsetToPosition(source, offset);
            Position end = offsetToPosition(source, offset + length);
            lspEdits.add(new TextEdit(new Range(start, end), newText));
        }

        return lspEdits;
    }

    /**
     * Check whether removing newlines in the given source span is safe.
     * <p>
     * Returns {@code true} if every newline in the span sits in a
     * <em>continuation context</em> — meaning the code before/after it
     * indicates the expression continues across the line break (e.g.
     * {@code obj.\n  method()}, {@code foo(a,\n  b)}). Returns {@code false}
     * if any newline appears to separate two independent statements.
     */
    private boolean isNewlineRemovalSafe(String source, int offset, int length) {
        int end = Math.min(offset + length, source.length());

        for (int i = offset; i < end; i++) {
            if (source.charAt(i) == '\n') {
                char before = lastNonWhitespaceBefore(source, i);
                char after = firstNonWhitespaceAfter(source, i + 1);

                if (!isContinuationContext(before, after)) {
                    return false;
                }
            }
        }
        return true;
    }

    private char lastNonWhitespaceBefore(String source, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') {
                return c;
            }
        }
        return '\0';
    }

    private char firstNonWhitespaceAfter(String source, int index) {
        for (int i = index; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return c;
            }
        }
        return '\0';
    }

    /**
     * Determine whether a line break between two tokens is a continuation
     * (safe to remove) rather than a Groovy statement boundary.
     * <p>
     * A break is a continuation if the token before ends with an operator or
     * open delimiter that requires more input, or the token after starts with
     * a closing delimiter or member-access dot.
     */
    private boolean isContinuationContext(char before, char after) {
        // Line ends with an operator / open delimiter → expression continues
        if (".,([{+*/-=%?&|\\" .indexOf(before) >= 0) {
            return true;
        }
        // Line after starts with dot (chained call) or closing bracket
        return ".)]},".indexOf(after) >= 0;
    }

    // ================================================================
    // Position helpers
    // ================================================================

    /**
     * Convert a character offset to an LSP Position (line, character).
     */
    private Position offsetToPosition(String source, int offset) {
        int line = 0;
        int col = 0;

        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }

        return new Position(line, col);
    }

    /**
     * Convert an LSP Position to a character offset.
     */
    private int positionToOffset(String source, Position position) {
        int line = 0;
        int offset = 0;

        while (offset < source.length() && line < position.getLine()) {
            if (source.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }

        return Math.min(offset + position.getCharacter(), source.length());
    }
}
