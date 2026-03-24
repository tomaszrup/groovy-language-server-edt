/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * Standalone Groovy compiler service that parses Groovy source code
 * directly using the Groovy compiler, without depending on JDT or
 * the Eclipse workspace infrastructure.
 * <p>
 * This provides a fallback for language features when JDT working copies
 * cannot be created (e.g., when the file is outside the Eclipse project
 * or when groovy-eclipse's JDT integration is unavailable).
 */
public class GroovyCompilerService {

    /**
     * Result of parsing a Groovy source file.
     */
    public static class ParseResult {
        private final ModuleNode moduleNode;
        private final List<SyntaxException> errors;

        public ParseResult(ModuleNode moduleNode, List<SyntaxException> errors) {
            this.moduleNode = moduleNode;
            this.errors = errors;
        }

        /**
         * Returns the parsed AST, or {@code null} if parsing failed completely.
         */
        public ModuleNode getModuleNode() {
            return moduleNode;
        }

        /**
         * Returns syntax errors encountered during parsing (may be empty).
         */
        public List<SyntaxException> getErrors() {
            return errors;
        }

        /**
         * Returns {@code true} if an AST was produced (even if it has errors).
         */
        public boolean hasAST() {
            return moduleNode != null;
        }
    }

    /**
     * Maximum number of cached parse results.  When the cache exceeds this
     * size the least-recently-used entry is evicted automatically.  This
     * prevents unbounded memory growth when hundreds of files are parsed
     * over a long session.
     */
    private static final int MAX_CACHE_SIZE = 500;

    /**
     * Bounded LRU cache of parse results keyed by document URI.
     * Wrapped in {@link Collections#synchronizedMap} for thread safety.
     */
    private final Map<String, ParseResult> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ParseResult> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    /**
     * Reusable compiler configuration.  Creating a new
     * {@code CompilerConfiguration} on every parse is wasteful — the settings
     * (tolerance, source encoding, etc.) do not change between invocations.
     */
    private final CompilerConfiguration sharedConfig;

    public GroovyCompilerService() {
        sharedConfig = new CompilerConfiguration();
        sharedConfig.setTolerance(10); // collect up to 10 errors before giving up
    }

    /**
     * Parse Groovy source code and return the AST and any errors.
     * <p>
     * Attempts to parse through the CONVERSION phase, which produces
     * a full AST with class/method/field structure. This is sufficient
     * for document symbols, semantic tokens, and basic diagnostics.
     *
     * @param uri      the document URI (used for caching and source naming)
     * @param source   the Groovy source text
     * @return a {@link ParseResult} with the AST and/or errors
     */
    public ParseResult parse(String uri, String source) {
        uri = DocumentManager.normalizeUri(uri);
        List<SyntaxException> errors = new ArrayList<>();
        ModuleNode moduleNode = null;

        try {
            CompilationUnit compilationUnit = new CompilationUnit(sharedConfig);
            String fileName = extractFileName(uri);
            compilationUnit.addSource(fileName, source);

            compileThroughConversion(compilationUnit, errors);
            moduleNode = extractFirstModuleNode(compilationUnit, uri);

        } catch (Exception e) {
            // Catch everything — we must not crash the language server
            GroovyLanguageServerPlugin.logError(
                    "Groovy compiler service failed for " + uri, e);
            errors.add(new SyntaxException(
                    "Internal parse error: " + e.getMessage(), 1, 1));
        }

        ParseResult result = new ParseResult(moduleNode, errors);
        cache.put(uri, result);
        return result;
    }

    private void compileThroughConversion(CompilationUnit compilationUnit, List<SyntaxException> errors) {
        try {
            compilationUnit.compile(Phases.CONVERSION);
        } catch (MultipleCompilationErrorsException e) {
            collectErrors(e.getErrorCollector(), errors);
        } catch (Exception e) {
            errors.add(new SyntaxException("Parse error: " + e.getMessage(), 1, 1));
        }
    }

    private ModuleNode extractFirstModuleNode(CompilationUnit compilationUnit, String uri) {
        try {
            java.util.Iterator<SourceUnit> iter = compilationUnit.iterator();
            while (iter.hasNext()) {
                SourceUnit sourceUnit = iter.next();
                if (sourceUnit.getAST() != null) {
                    return sourceUnit.getAST();
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to extract AST from compilation unit for " + uri, e);
        }
        return null;
    }

    /**
     * Returns the cached parse result for a URI, or {@code null} if not cached.
     */
    public ParseResult getCachedResult(String uri) {
        return cache.get(DocumentManager.normalizeUri(uri));
    }

    /**
     * Removes the cached parse result for a URI.
     */
    public void invalidate(String uri) {
        cache.remove(DocumentManager.normalizeUri(uri));
    }

    /**
     * Remove the primary cached parse result for a document together with any
     * derived semantic-token recovery entries for the same source.
     */
    public void invalidateDocumentFamily(String uri) {
        String normalized = DocumentManager.normalizeUri(uri);
        String rawPatchedPrefix = normalized + "#semantic-patched";
        String encodedPatchedPrefix = normalized + "%23semantic-patched";

        synchronized (cache) {
            cache.remove(normalized);
            cache.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                return key.startsWith(rawPatchedPrefix) || key.startsWith(encodedPatchedPrefix);
            });
        }
    }

    /**
     * Collect syntax errors from the Groovy error collector.
     */
    private void collectErrors(ErrorCollector collector, List<SyntaxException> errors) {
        if (collector == null) {
            return;
        }
        List<? extends Message> messages = collector.getErrors();
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            if (message instanceof SyntaxErrorMessage syntaxErrorMessage) {
                SyntaxException cause = syntaxErrorMessage.getCause();
                if (cause != null) {
                    errors.add(cause);
                }
            }
        }
    }

    /**
     * Extract a simple file name from a URI string for error messages.
     */
    private String extractFileName(String uri) {
        if (uri == null) {
            return "Script.groovy";
        }
        int lastSlash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < uri.length() - 1) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }
}
