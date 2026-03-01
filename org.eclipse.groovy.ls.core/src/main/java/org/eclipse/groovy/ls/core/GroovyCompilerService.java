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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Cache of parse results keyed by document URI. */
    private final Map<String, ParseResult> cache = new ConcurrentHashMap<>();

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
            CompilerConfiguration config = new CompilerConfiguration();
            config.setTolerance(10); // collect up to 10 errors before giving up

            CompilationUnit compilationUnit = new CompilationUnit(config);

            // Derive a simple file name from the URI for error reporting
            String fileName = extractFileName(uri);

            compilationUnit.addSource(fileName, source);

            try {
                // Parse through CONVERSION to get class/method structure.
                // SEMANTIC_ANALYSIS would give type info but requires classpath.
                compilationUnit.compile(Phases.CONVERSION);
            } catch (MultipleCompilationErrorsException e) {
                collectErrors(e.getErrorCollector(), errors);
            } catch (Exception e) {
                // Some parse errors throw other exceptions — treat as syntax error
                errors.add(new SyntaxException(
                        "Parse error: " + e.getMessage(), 1, 1));
            }

            // Try to extract the module node even if there were errors
            try {
                java.util.Iterator<SourceUnit> iter = compilationUnit.iterator();
                while (iter.hasNext()) {
                    SourceUnit su = iter.next();
                    if (su.getAST() != null) {
                        moduleNode = su.getAST();
                        break;
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                        "Failed to extract AST from compilation unit for " + uri, e);
            }

        } catch (Throwable e) {
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
            if (message instanceof SyntaxErrorMessage) {
                SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
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
