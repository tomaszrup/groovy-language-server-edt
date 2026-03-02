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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.codehaus.groovy.ast.ModuleNode;

/**
 * Provides LSP Semantic Tokens for Groovy documents.
 * <p>
 * Walks the Groovy AST via {@link SemanticTokensVisitor} and encodes each
 * token into the delta-encoded integer array required by the LSP protocol.
 * This enables rich, type-aware syntax highlighting that goes far beyond
 * what the TextMate grammar can achieve — classes, methods, parameters,
 * local variables, annotations, and more are all accurately classified.
 * <p>
 * The VS Code client automatically picks up semantic tokens when the server
 * advertises the capability — no client-side code changes are needed.
 */
public class SemanticTokensProvider {

    // ---- Standard LSP Semantic Token Types ----
    // Order matters — indices into this list are used as the token type ID.
    public static final List<String> TOKEN_TYPES = Collections.unmodifiableList(Arrays.asList(
            "namespace",      // 0  — package declarations, import paths
            "type",           // 1  — type references (variable decls, casts, generics)
            "class",          // 2  — class declarations
            "enum",           // 3  — enum declarations
            "interface",      // 4  — interface declarations
            "struct",         // 5  — trait declarations (closest standard type)
            "typeParameter",  // 6  — generic type parameters <T>
            "parameter",      // 7  — method/closure parameters
            "variable",       // 8  — local variables
            "property",       // 9  — class fields/properties
            "enumMember",     // 10 — enum constants
            "decorator",      // 11 — annotations (@Override, etc.)
            "function",       // 12 — script-level function declarations
            "method",         // 13 — method declarations and calls
            "keyword",        // 14 — contextual keywords (reserved)
            "comment",        // 15 — doc comment tags (reserved)
            "string",         // 16 — string interpolation expressions (reserved)
            "number",         // 17 — (reserved)
            "regexp",         // 18 — (reserved)
            "operator",       // 19 — (reserved)
            "typeKeyword"     // 20 — declaration keywords (class/trait/etc.) coloured as storage.type
    ));

    // ---- Standard LSP Semantic Token Modifiers ----
    // Each modifier is a bit flag — index N corresponds to bit (1 << N).
    public static final List<String> TOKEN_MODIFIERS = Collections.unmodifiableList(Arrays.asList(
            "declaration",    // bit 0 — defining occurrence
            "definition",     // bit 1 — definition site
            "readonly",       // bit 2 — final fields/variables
            "static",         // bit 3 — static members
            "deprecated",     // bit 4 — @Deprecated elements
            "abstract",       // bit 5 — abstract classes/methods
            "modification",   // bit 6 — write access to variable
            "documentation",  // bit 7 — doc comments
            "defaultLibrary"  // bit 8 — JDK/Groovy standard library types
    ));

    // Modifier bit constants for convenience
    public static final int MOD_DECLARATION    = 1 << 0;
    public static final int MOD_DEFINITION     = 1 << 1;
    public static final int MOD_READONLY       = 1 << 2;
    public static final int MOD_STATIC         = 1 << 3;
    public static final int MOD_DEPRECATED     = 1 << 4;
    public static final int MOD_ABSTRACT       = 1 << 5;
    public static final int MOD_DEFAULT_LIB    = 1 << 8;

    // Token type indices for convenience
    public static final int TYPE_NAMESPACE      = 0;
    public static final int TYPE_TYPE           = 1;
    public static final int TYPE_CLASS          = 2;
    public static final int TYPE_ENUM           = 3;
    public static final int TYPE_INTERFACE      = 4;
    public static final int TYPE_STRUCT         = 5;
    public static final int TYPE_TYPE_PARAMETER = 6;
    public static final int TYPE_PARAMETER      = 7;
    public static final int TYPE_VARIABLE       = 8;
    public static final int TYPE_PROPERTY       = 9;
    public static final int TYPE_ENUM_MEMBER    = 10;
    public static final int TYPE_DECORATOR      = 11;
    public static final int TYPE_FUNCTION       = 12;
    public static final int TYPE_METHOD         = 13;
    public static final int TYPE_KEYWORD        = 14;
    public static final int TYPE_TYPE_KEYWORD    = 20;

    private final DocumentManager documentManager;

    /**
     * Cache of the last successfully computed (non-empty) semantic tokens per URI.
     * When a transient syntax error causes the AST to have zero classes, we return
     * the cached tokens instead of wiping all highlighting.
     */
    private final Map<String, SemanticTokens> lastGoodTokensCache = new ConcurrentHashMap<>();

    public SemanticTokensProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Removes cached semantic tokens for a document (e.g. on didClose).
     */
    public void invalidate(String uri) {
        if (uri != null) {
            lastGoodTokensCache.remove(DocumentManager.normalizeUri(uri));
        }
    }

    /**
     * Returns the semantic tokens legend to register as a server capability.
     */
    public static SemanticTokensLegend getLegend() {
        return new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);
    }

    /**
     * Compute semantic tokens for the full document.
     */
    public SemanticTokens getSemanticTokensFull(SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        return computeTokens(uri, null);
    }

    /**
     * Compute semantic tokens for a range of the document.
     */
    public SemanticTokens getSemanticTokensRange(SemanticTokensRangeParams params) {
        String uri = params.getTextDocument().getUri();
        return computeTokens(uri, params.getRange());
    }

    /**
     * Core implementation: get the Groovy AST and walk it to produce tokens.
     *
     * @param uri   the document URI
     * @param range optional LSP range to restrict tokens to (null = full document)
     */
    private SemanticTokens computeTokens(String uri, org.eclipse.lsp4j.Range range) {
        String normalizedUri = DocumentManager.normalizeUri(uri);
        String content = documentManager.getContent(uri);
        if (content == null) {
            return new SemanticTokens(Collections.emptyList());
        }

        // Try to get the Groovy AST — first from JDT, then from standalone compiler
        ModuleNode moduleNode = null;

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            // Try via groovy-eclipse's GroovyCompilationUnit
            moduleNode = getModuleNode(workingCopy);
        }

        if (moduleNode == null) {
            // Fallback: use standalone Groovy compiler AST
            moduleNode = documentManager.getGroovyAST(uri);
        }

        // If we have no module at all, or a module with 0 classes, try the
        // standalone compiler with error-line-blanking to recover structure.
        boolean needsFallback = moduleNode == null
                || moduleNode.getClasses() == null
                || moduleNode.getClasses().isEmpty();

        if (needsFallback) {
            GroovyLanguageServerPlugin.logInfo(
                    "[semantic] AST is " + (moduleNode == null ? "null" : "empty (0 classes)")
                            + " for " + uri + ", trying standalone compiler fallback");
            ModuleNode fallback = getStandaloneAST(uri, content);
            if (fallback != null
                    && fallback.getClasses() != null
                    && !fallback.getClasses().isEmpty()) {
                moduleNode = fallback;
            }
        }

        if (moduleNode == null
                || moduleNode.getClasses() == null
                || moduleNode.getClasses().isEmpty()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[semantic] No usable AST for " + uri + ", returning cached tokens");
            return returnCachedOrEmpty(normalizedUri);
        }

        try {
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(content, range);
            visitor.visitModule(moduleNode);

            List<Integer> encodedTokens = visitor.getEncodedTokens();
            SemanticTokens result = new SemanticTokens(encodedTokens);

            // Cache successful non-empty full-document results
            if (!encodedTokens.isEmpty() && range == null) {
                lastGoodTokensCache.put(normalizedUri, result);
            }

            return result;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Semantic tokens computation failed for " + uri, e);
            return returnCachedOrEmpty(normalizedUri);
        }
    }

    /**
     * Returns the last known good cached tokens for the URI, or empty tokens
     * if no cached result is available.
     */
    private SemanticTokens returnCachedOrEmpty(String normalizedUri) {
        SemanticTokens cached = lastGoodTokensCache.get(normalizedUri);
        if (cached != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[semantic] Returning cached tokens for " + normalizedUri);
            return cached;
        }
        return new SemanticTokens(Collections.emptyList());
    }

    /**
     * Extract the Groovy {@link ModuleNode} from a working copy.
     * <p>
     * The groovy-eclipse plugin provides a {@code GroovyCompilationUnit} that extends
     * the standard JDT {@link ICompilationUnit}. We use reflection to call
     * {@code getModuleNode()} so that we don't require a compile-time dependency
     * on the groovy-eclipse internal API — it's available at runtime via the
     * OSGi bundle.
     */
    private ModuleNode getModuleNode(ICompilationUnit unit) {
        try {
            // GroovyCompilationUnit.getModuleNode() returns the parsed AST
            java.lang.reflect.Method getModuleNode = unit.getClass().getMethod("getModuleNode");
            Object result = getModuleNode.invoke(unit);
            if (result instanceof ModuleNode moduleNode) {
                return moduleNode;
            }
        } catch (NoSuchMethodException e) {
            // Not a GroovyCompilationUnit — e.g. a plain .java file
            GroovyLanguageServerPlugin.logInfo(
                    "Semantic tokens: compilation unit is not a GroovyCompilationUnit");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Semantic tokens: failed to get ModuleNode via reflection", e);
        }
        return null;
    }

    /**
     * Get a Groovy AST directly from the standalone compiler, bypassing JDT.
     * This is used when JDT returns a broken module (0 classes) — the standalone
     * compiler with error tolerance may still produce a partial AST that has
     * the class structure intact.
     * <p>
     * If the first parse also fails (0 classes), we use the reported error
     * locations to blank out the offending lines and re-parse. This is a general
     * strategy that works for any kind of syntax error (trailing dots, incomplete
     * expressions, missing tokens, etc.) — not just specific patterns.
     */
    private ModuleNode getStandaloneAST(String uri, String content) {
        try {
            GroovyCompilerService compilerService = documentManager.getCompilerService();

            // First try: parse the content as-is
            GroovyCompilerService.ParseResult result = compilerService.parse(uri, content);
            if (result.hasAST()) {
                ModuleNode module = result.getModuleNode();
                int classCount = module.getClasses() != null ? module.getClasses().size() : 0;
                GroovyLanguageServerPlugin.logInfo(
                        "[semantic] Standalone compiler fallback for " + uri + " classes=" + classCount);
                if (classCount > 0) {
                    return module;
                }
            }

            // Second try: blank out lines that have errors and re-parse.
            // The compiler tells us where the errors are — by removing those lines
            // we often recover the surrounding class/method structure.
            if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                String patched = blankErrorLines(content, result.getErrors());
                if (patched != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[semantic] Trying error-line-blanked content for " + uri);
                    // Use a synthetic URI so we don't pollute the cache for the real file
                    GroovyCompilerService.ParseResult patchedResult =
                            compilerService.parse(uri + "#semantic-patched", patched);
                    if (patchedResult.hasAST()) {
                        ModuleNode module = patchedResult.getModuleNode();
                        int classCount = module.getClasses() != null ? module.getClasses().size() : 0;
                        GroovyLanguageServerPlugin.logInfo(
                                "[semantic] Error-line-blanked content for " + uri + " classes=" + classCount);
                        if (classCount > 0) {
                            return module;
                        }
                    }
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[semantic] Standalone compiler fallback failed for " + uri, e);
        }
        return null;
    }

    /**
     * Blank out lines that contain syntax errors, preserving line count so that
     * the remaining AST nodes retain correct line numbers for token mapping.
     *
     * @param content the original source text
     * @param errors  syntax errors with line information from a failed parse
     * @return patched content with error lines blanked, or {@code null} if
     *         nothing could be blanked
     */
    private String blankErrorLines(String content,
            List<org.codehaus.groovy.syntax.SyntaxException> errors) {
        String[] lines = content.split("\n", -1);
        Set<Integer> errorLineIndices = new HashSet<>();

        for (org.codehaus.groovy.syntax.SyntaxException error : errors) {
            // SyntaxException lines are 1-based
            int startLine = error.getStartLine() - 1;
            int endLine = error.getEndLine() - 1;
            if (endLine < startLine) {
                endLine = startLine;
            }
            for (int i = Math.max(0, startLine); i <= Math.min(endLine, lines.length - 1); i++) {
                errorLineIndices.add(i);
            }
        }

        if (errorLineIndices.isEmpty()) {
            return null;
        }

        // Blank error lines (replace with empty strings to preserve line numbering)
        for (int idx : errorLineIndices) {
            lines[idx] = "";
        }

        return String.join("\n", lines);
    }
}
