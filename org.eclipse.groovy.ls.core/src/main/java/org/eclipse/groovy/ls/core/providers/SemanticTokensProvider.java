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
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

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
    private final SemanticTokenEncodingSupport encodingSupport;
    private final SemanticTokensFallbackSupport fallbackSupport;

    public SemanticTokensProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
        this.encodingSupport = new SemanticTokenEncodingSupport();
        this.fallbackSupport = new SemanticTokensFallbackSupport();
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
        return computeTokens(uri, null, true);
    }

    /**
     * Compute semantic tokens for the full document without using the JDT
     * working copy. This avoids workspace-lock contention during builds while
     * still allowing cached AST or standalone parsing to provide tokens.
     */
    public SemanticTokens getSemanticTokensFullBestEffort(SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        return computeTokens(uri, null, false);
    }

    /**
     * Compute semantic tokens for a range of the document.
     */
    public SemanticTokens getSemanticTokensRange(SemanticTokensRangeParams params) {
        String uri = params.getTextDocument().getUri();
        return computeTokens(uri, params.getRange(), true);
    }

    /**
     * Compute semantic tokens for a range without consulting the JDT working
     * copy. Used while the workspace is building.
     */
    public SemanticTokens getSemanticTokensRangeBestEffort(SemanticTokensRangeParams params) {
        String uri = params.getTextDocument().getUri();
        return computeTokens(uri, params.getRange(), false);
    }

    /**
     * Core implementation: get the Groovy AST and walk it to produce tokens.
     *
     * @param uri   the document URI
     * @param range optional LSP range to restrict tokens to (null = full document)
     */
    private SemanticTokens computeTokens(String uri, org.eclipse.lsp4j.Range range, boolean allowWorkingCopy) {
        if (Thread.currentThread().isInterrupted()) {
            return new SemanticTokens(Collections.emptyList());
        }

        String content = documentManager.getContent(uri);
        if (content == null) {
            return new SemanticTokens(Collections.emptyList());
        }

        ICompilationUnit workingCopy = allowWorkingCopy ? documentManager.getWorkingCopy(uri) : null;
        ModuleNode moduleNode = resolvePrimaryModule(uri, workingCopy);
        List<Integer> primaryTokens = tryVisitModule(moduleNode, content, range, uri);
        if (!primaryTokens.isEmpty()) {
            return new SemanticTokens(supplementTokensIfNeeded(primaryTokens, workingCopy, uri, content, range));
        }

        return computeFallbackTokens(uri, range, content);
    }

    private ModuleNode resolvePrimaryModule(String uri, ICompilationUnit workingCopy) {
        if (workingCopy != null) {
            ModuleNode moduleNode = getModuleNode(workingCopy);
            if (moduleNode != null) {
                return moduleNode;
            }
        }
        return documentManager.getCachedGroovyAST(uri);
    }

    private List<Integer> supplementTokensIfNeeded(List<Integer> primaryTokens,
            ICompilationUnit workingCopy,
            String uri,
            String content,
            org.eclipse.lsp4j.Range range) {
        if (Thread.currentThread().isInterrupted() || workingCopy == null || !shouldSupplementTraitTokens(content)) {
            return primaryTokens;
        }

        ModuleNode fallback = getStandaloneAST(uri, content);
        List<Integer> supplemental = tryVisitModule(fallback, content, range, uri + " [standalone]");
        return supplemental.isEmpty() ? primaryTokens : mergeTokenData(primaryTokens, supplemental);
    }

    private SemanticTokens computeFallbackTokens(String uri, org.eclipse.lsp4j.Range range, String content) {
        GroovyLanguageServerPlugin.logInfo(
                "[semantic] AST produced no tokens for " + uri + ", trying standalone compiler fallback");
        if (Thread.currentThread().isInterrupted()) {
            return new SemanticTokens(Collections.emptyList());
        }

        ModuleNode fallback = getStandaloneAST(uri, content);
        List<Integer> tokens = tryVisitModule(fallback, content, range, uri);
        if (!tokens.isEmpty()) {
            return new SemanticTokens(tokens);
        }

        GroovyLanguageServerPlugin.logInfo(
                "[semantic] No usable AST for " + uri + ", returning empty tokens");
        return new SemanticTokens(Collections.emptyList());
    }

    private boolean shouldSupplementTraitTokens(String content) {
        return content.contains("trait ") || content.contains("implements ");
    }

    private List<Integer> mergeTokenData(List<Integer> primary, List<Integer> supplemental) {
        return encodingSupport.mergeTokenData(primary, supplemental);
    }

    /**
     * Try to visit a module and return encoded tokens.
     * Returns null or empty list if the module is unusable.
     */
    private List<Integer> tryVisitModule(ModuleNode moduleNode, String content,
                                          org.eclipse.lsp4j.Range range, String uri) {
        if (moduleNode == null
                || moduleNode.getClasses() == null
                || moduleNode.getClasses().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            SemanticTokensVisitor visitor = new SemanticTokensVisitor(content, range, documentManager);
            visitor.visitModule(moduleNode);
            return visitor.getEncodedTokens();
        } catch (SemanticTokensVisitor.VisitorCancelled ignored) {
            return Collections.emptyList();
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Semantic tokens visitor failed for " + uri, e);
            return Collections.emptyList();
        }
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
            ModuleNode module = ReflectionCache.getModuleNode(unit);
            if (module != null) {
                return module;
            }
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
     *
     */
    private ModuleNode getStandaloneAST(String uri, String content) {
        GroovyCompilerService compilerService = documentManager.getCompilerService();
        return fallbackSupport.getStandaloneAST(compilerService, uri, content);
    }

    private static final class SemanticTokenEncodingSupport {

        private List<Integer> mergeTokenData(List<Integer> primary, List<Integer> supplemental) {
            Map<String, AbsoluteToken> merged = new HashMap<>();
            for (AbsoluteToken token : decodeTokenData(primary)) {
                merged.put(spanKey(token), token);
            }
            for (AbsoluteToken token : decodeTokenData(supplemental)) {
                merged.putIfAbsent(spanKey(token), token);
            }

            List<AbsoluteToken> ordered = new ArrayList<>(merged.values());
            ordered.sort(Comparator
                    .comparingInt((AbsoluteToken token) -> token.line)
                    .thenComparingInt(token -> token.column)
                    .thenComparingInt(token -> token.length)
                    .thenComparingInt(token -> token.tokenType)
                    .thenComparingInt(token -> token.modifiers));
            return encodeTokenData(ordered);
        }

        private String spanKey(AbsoluteToken token) {
            return token.line + ":" + token.column + ":" + token.length;
        }

        private List<AbsoluteToken> decodeTokenData(List<Integer> encoded) {
            List<AbsoluteToken> decoded = new ArrayList<>();
            int previousLine = 0;
            int previousColumn = 0;
            for (int index = 0; index + 4 < encoded.size(); index += 5) {
                int deltaLine = encoded.get(index);
                int deltaColumn = encoded.get(index + 1);
                int length = encoded.get(index + 2);
                int tokenType = encoded.get(index + 3);
                int modifiers = encoded.get(index + 4);

                int line = previousLine + deltaLine;
                int column = deltaLine == 0 ? previousColumn + deltaColumn : deltaColumn;
                decoded.add(new AbsoluteToken(line, column, length, tokenType, modifiers));
                previousLine = line;
                previousColumn = column;
            }
            return decoded;
        }

        private List<Integer> encodeTokenData(List<AbsoluteToken> tokens) {
            List<Integer> encoded = new ArrayList<>(tokens.size() * 5);
            int previousLine = 0;
            int previousColumn = 0;
            boolean first = true;
            for (AbsoluteToken token : tokens) {
                int deltaLine = first ? token.line : token.line - previousLine;
                int deltaColumn = first || deltaLine != 0 ? token.column : token.column - previousColumn;
                encoded.add(deltaLine);
                encoded.add(deltaColumn);
                encoded.add(token.length);
                encoded.add(token.tokenType);
                encoded.add(token.modifiers);
                previousLine = token.line;
                previousColumn = token.column;
                first = false;
            }
            return encoded;
        }

        private static final class AbsoluteToken {
            private final int line;
            private final int column;
            private final int length;
            private final int tokenType;
            private final int modifiers;

            private AbsoluteToken(int line, int column, int length, int tokenType, int modifiers) {
                this.line = line;
                this.column = column;
                this.length = length;
                this.tokenType = tokenType;
                this.modifiers = modifiers;
            }
        }
    }

}
