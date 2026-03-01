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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Provides diagnostics (errors/warnings) for Groovy documents.
 * <p>
 * Uses JDT's compilation infrastructure. When groovy-eclipse's patched JDT compiles
 * a {@code .groovy} file, it reports problems as {@link IProblem} instances.
 * We convert these to LSP {@link Diagnostic} objects and publish them to the client.
 */
public class DiagnosticsProvider {

    private static final int TYPE_COLLISION_PROBLEM_ID = 16777539;
    private static final String NO_SUCH_CLASS_PREFIX = "No such class: ";
    private static final String TRANSFORM_LOADER_FRAGMENT =
            "JDTClassNode.getTypeClass() cannot locate it using transform loader";
        private static final Pattern UNABLE_TO_RESOLVE_CLASS_PATTERN =
            Pattern.compile("(?i)unable to resolve class\\s+([\\w.$]+)");

    private final DocumentManager documentManager;
    private LanguageClient client;
        private final java.util.Map<String, List<Diagnostic>> latestDiagnosticsByUri =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Simple debounce: track last scheduled publish per URI
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pendingPublish =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "groovy-ls-diagnostics");
                t.setDaemon(true);
                return t;
            });

    public DiagnosticsProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    public void connect(LanguageClient client) {
        this.client = client;
    }

    /**
     * Publish diagnostics for a document immediately.
     */
    public void publishDiagnostics(String uri) {
        String normalizedUri = DocumentManager.normalizeUri(uri);
        if (client == null) {
            GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnostics skipped (no client) uri=" + uri);
            return;
        }

        List<Diagnostic> diagnostics = collectDiagnostics(uri);
        latestDiagnosticsByUri.put(normalizedUri, diagnostics);
        GroovyLanguageServerPlugin.logInfo(
                "[diag-trace] publishDiagnostics uri=" + uri + " count=" + diagnostics.size());
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    /**
     * Get the latest cached diagnostics for a URI.
     */
    public List<Diagnostic> getLatestDiagnostics(String uri) {
        List<Diagnostic> diagnostics = latestDiagnosticsByUri.get(DocumentManager.normalizeUri(uri));
        return diagnostics == null ? new ArrayList<>() : new ArrayList<>(diagnostics);
    }

    /**
     * Collect diagnostics on demand (for code actions) and refresh cache.
     */
    public List<Diagnostic> collectDiagnosticsForCodeActions(String uri) {
        List<Diagnostic> diagnostics = collectDiagnostics(uri);
        latestDiagnosticsByUri.put(DocumentManager.normalizeUri(uri), diagnostics);
        return diagnostics;
    }

    /**
     * Clear cached diagnostics for a URI.
     */
    public void clearDiagnostics(String uri) {
        latestDiagnosticsByUri.remove(DocumentManager.normalizeUri(uri));
    }

    /**
     * Publish diagnostics after a short delay (debounced).
     * Subsequent calls for the same URI cancel the previous scheduled publish.
     */
    public void publishDiagnosticsDebounced(String uri) {
        GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnosticsDebounced schedule uri=" + uri);
        String normalizedUri = DocumentManager.normalizeUri(uri);
        java.util.concurrent.ScheduledFuture<?> existing = pendingPublish.remove(normalizedUri);
        if (existing != null) {
            existing.cancel(false);
            GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnosticsDebounced cancel previous uri=" + uri);
        }

        java.util.concurrent.ScheduledFuture<?> future = scheduler.schedule(
                () -> publishDiagnostics(uri),
                500, java.util.concurrent.TimeUnit.MILLISECONDS);
        pendingPublish.put(normalizedUri, future);
    }

    /**
     * Collect diagnostics from JDT compilation problems.
     */
    private List<Diagnostic> collectDiagnostics(String uri) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String content = documentManager.getContent(uri);

        try {
            // Approach 1: Get problems from the working copy reconciliation
            ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
            if (workingCopy != null) {
                collectFromWorkingCopy(workingCopy, diagnostics, content);

                // Also get problems from resource markers (for saved files)
                IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                        .findFilesForLocationURI(URI.create(uri));
                if (files.length > 0) {
                collectFromMarkers(files[0], diagnostics, content);
                }
            } else {
                // Fallback: use standalone Groovy compiler for syntax diagnostics
                collectFromGroovyCompiler(uri, diagnostics);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to collect diagnostics for " + uri, e);
        }

        // Always check for unused imports via AST analysis
        try {
            ModuleNode ast = documentManager.getGroovyAST(uri);
            if (ast != null) {
                if (content != null) {
                    List<Diagnostic> unusedImports = UnusedImportDetector.detectUnusedImports(ast, content);
                    diagnostics.addAll(unusedImports);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Unused import detection failed for " + uri, e);
        }

        return diagnostics;
    }

    /**
     * Collect problems from a JDT working copy that has been reconciled.
     */
    private void collectFromWorkingCopy(ICompilationUnit workingCopy, List<Diagnostic> diagnostics, String content) {
        try {
            // Reconcile to get fresh problems
            org.eclipse.jdt.core.dom.CompilationUnit ast = workingCopy.reconcile(
                    org.eclipse.jdt.core.dom.AST.getJLSLatest(),
                    true, // force problem detection
                    true, // enable statements recovery
                    documentManager.getWorkingCopyOwner(),
                    null);

            if (ast != null) {
                IProblem[] problems = ast.getProblems();
                IJavaProject javaProject = workingCopy.getJavaProject();
                for (IProblem problem : problems) {
                    if (shouldSkipDiagnostic(
                            problem.getID(),
                            problem.getMessage(),
                            javaProject,
                            workingCopy)) {
                        continue;
                    }
                    diagnostics.add(toDiagnostic(problem, content));
                }
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("Failed to reconcile working copy", e);
        }
    }

    /**
     * Collect problems from Eclipse resource markers.
     */
    private void collectFromMarkers(IFile file, List<Diagnostic> diagnostics, String content) {
        try {
            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
            IJavaProject javaProject = JavaCore.create(file.getProject());
            ICompilationUnit contextUnit = null;
            org.eclipse.jdt.core.IJavaElement element = JavaCore.create(file);
            if (element instanceof ICompilationUnit) {
                contextUnit = (ICompilationUnit) element;
            }
            for (IMarker marker : markers) {
                // Filter out the same Groovy-Eclipse false positive from markers
                String msg = marker.getAttribute(IMarker.MESSAGE, "");
                if (shouldSkipDiagnostic(-1, msg, javaProject, contextUnit)) {
                    continue;
                }
                Diagnostic diagnostic = toDiagnostic(marker, content);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to collect markers", e);
        }
    }

    private boolean shouldSkipDiagnostic(
            int problemId,
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        // Filter out Groovy-Eclipse false positive: "The type X is already defined"
        // (IProblem.TypeCollision = 16777539). When a .groovy file has errors,
        // the Groovy compiler wraps the file body in a synthetic script class
        // with the same name as the declared class, causing a spurious duplicate.
        if (problemId == TYPE_COLLISION_PROBLEM_ID
                || (message != null
                        && message.startsWith("The type ")
                        && message.endsWith(" is already defined"))) {
            return true;
        }

        // Filter a known transient Groovy-Eclipse issue: a source type exists in the
        // project but JDTClassNode.getTypeClass() cannot load it through transform loader.
        if (isExistingTypeTransformLoaderFailure(message, javaProject, contextUnit)) {
            return true;
        }

        // Filter transient unresolved-class diagnostics when the target type
        // is actually present in the project and resolvable from context.
        return isResolvableUnableToResolveClassFailure(message, javaProject, contextUnit);
    }

    private boolean isExistingTypeTransformLoaderFailure(
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        if (message == null || javaProject == null || !message.contains(TRANSFORM_LOADER_FRAGMENT)) {
            return false;
        }

        String missingType = extractMissingTypeName(message);
        if (missingType == null || missingType.isBlank()) {
            return false;
        }

        return typeExistsInProjectContext(missingType, javaProject, contextUnit);
    }

    private boolean isResolvableUnableToResolveClassFailure(
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        if (message == null || javaProject == null) {
            return false;
        }

        Matcher matcher = UNABLE_TO_RESOLVE_CLASS_PATTERN.matcher(message);
        if (!matcher.find()) {
            return false;
        }

        String missingType = matcher.group(1);
        if (missingType == null || missingType.isBlank()) {
            return false;
        }

        return typeExistsInProjectContext(missingType, javaProject, contextUnit);
    }

    private boolean typeExistsInProjectContext(
            String missingType,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(missingType.trim());

        try {
            String simpleName = missingType;
            int lastDot = missingType.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < missingType.length() - 1) {
                simpleName = missingType.substring(lastDot + 1);
            }

            if (!missingType.contains(".")) {
                if (contextUnit != null) {
                    try {
                        for (var pkg : contextUnit.getPackageDeclarations()) {
                            String packageName = pkg.getElementName();
                            if (packageName != null && !packageName.isBlank()) {
                                candidates.add(packageName + "." + simpleName);
                            }
                        }
                    } catch (JavaModelException e) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[diagnostics] Failed to read package declarations: " + e.getMessage());
                    }

                    try {
                        for (IImportDeclaration imp : contextUnit.getImports()) {
                            String importedName = imp.getElementName();
                            if (importedName == null || importedName.isBlank()) {
                                continue;
                            }
                            if (imp.isOnDemand()) {
                                candidates.add(importedName + "." + simpleName);
                            } else if (importedName.endsWith("." + simpleName)) {
                                candidates.add(importedName);
                            }
                        }
                    } catch (JavaModelException e) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[diagnostics] Failed to read import declarations: " + e.getMessage());
                    }
                }

                candidates.add("java.lang." + simpleName);
            }

            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                IType type = javaProject.findType(candidate);
                if (type != null && type.exists()) {
                    return true;
                }
            }

            return false;
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diagnostics] Failed to validate missing type '" + missingType
                    + "' with candidates=" + candidates + ": " + e.getMessage());
            return false;
        }
    }

    private String extractMissingTypeName(String message) {
        int start = message.indexOf(NO_SUCH_CLASS_PREFIX);
        if (start < 0) {
            return null;
        }
        start += NO_SUCH_CLASS_PREFIX.length();

        int end = message.indexOf(" --", start);
        if (end < 0) {
            end = message.length();
        }

        return message.substring(start, end).trim();
    }

    /**
     * Collect diagnostics from the standalone Groovy compiler (fallback).
     * Used when no JDT working copy is available.
     */
    private void collectFromGroovyCompiler(String uri, List<Diagnostic> diagnostics) {
        String content = documentManager.getContent(uri);
        if (content == null) {
            return;
        }

        GroovyCompilerService compilerService = documentManager.getCompilerService();
        GroovyCompilerService.ParseResult result = compilerService.getCachedResult(uri);
        if (result == null) {
            result = compilerService.parse(uri, content);
        }

        for (org.codehaus.groovy.syntax.SyntaxException error : result.getErrors()) {
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setSeverity(DiagnosticSeverity.Error);

            int startLine = Math.max(0, error.getStartLine() - 1);
            int startCol = Math.max(0, error.getStartColumn() - 1);
            int endLine = Math.max(startLine, error.getEndLine() - 1);
            int endCol = Math.max(startCol, error.getEndColumn() - 1);

            diagnostic.setRange(new Range(
                    new Position(startLine, startCol),
                    new Position(endLine, endCol)));
            diagnostic.setMessage(error.getMessage());
            diagnostic.setSource("groovy");

            diagnostics.add(diagnostic);
        }
    }

    /**
     * Convert a JDT {@link IProblem} to an LSP {@link Diagnostic}.
     *
     * @param problem the JDT problem
     * @param content the document source text (for offset-to-line/column conversion)
     */
    private Diagnostic toDiagnostic(IProblem problem, String content) {
        Diagnostic diagnostic = new Diagnostic();

        // Map severity
        if (problem.isError()) {
            diagnostic.setSeverity(DiagnosticSeverity.Error);
        } else if (problem.isWarning()) {
            diagnostic.setSeverity(DiagnosticSeverity.Warning);
        } else {
            diagnostic.setSeverity(DiagnosticSeverity.Information);
        }

        Range range;
        int sourceStart = problem.getSourceStart();
        int sourceEnd = problem.getSourceEnd();

        if (sourceStart >= 0 && sourceEnd >= 0 && content != null) {
            // Convert absolute character offsets to line:column positions
            range = offsetRangeToLspRange(content, sourceStart, sourceEnd + 1);
        } else {
            // Fallback: use the line number (1-based from JDT, 0-based for LSP)
            int line = Math.max(0, problem.getSourceLineNumber() - 1);
            range = new Range(
                    new Position(line, 0),
                    new Position(line, Integer.MAX_VALUE));
        }

        diagnostic.setRange(range);
        diagnostic.setMessage(problem.getMessage());
        diagnostic.setSource("groovy");
        diagnostic.setCode(String.valueOf(problem.getID()));

        return diagnostic;
    }

    /**
     * Convert an absolute character offset range to an LSP {@link Range}.
     *
     * @param content the full document text
     * @param start   the start offset (inclusive)
     * @param end     the end offset (exclusive)
     * @return the corresponding LSP range with correct line/column positions
     */
    private Range offsetRangeToLspRange(String content, int start, int end) {
        int line = 0;
        int col = 0;
        int startLine = 0, startCol = 0;
        int endLine = 0, endCol = 0;
        boolean foundStart = false, foundEnd = false;

        for (int i = 0; i < content.length() && !foundEnd; i++) {
            if (i == start) {
                startLine = line;
                startCol = col;
                foundStart = true;
            }
            if (i == end) {
                endLine = line;
                endCol = col;
                foundEnd = true;
            }
            if (content.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }

        // Handle end-of-file edge case
        if (!foundEnd) {
            endLine = line;
            endCol = col;
        }
        if (!foundStart) {
            startLine = line;
            startCol = 0;
        }

        return new Range(
                new Position(startLine, startCol),
                new Position(endLine, endCol));
    }

    /**
     * Convert an Eclipse {@link IMarker} to an LSP {@link Diagnostic}.
     */
    private Diagnostic toDiagnostic(IMarker marker, String content) {
        try {
            Diagnostic diagnostic = new Diagnostic();

            // Severity
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            switch (severity) {
                case IMarker.SEVERITY_ERROR:
                    diagnostic.setSeverity(DiagnosticSeverity.Error);
                    break;
                case IMarker.SEVERITY_WARNING:
                    diagnostic.setSeverity(DiagnosticSeverity.Warning);
                    break;
                default:
                    diagnostic.setSeverity(DiagnosticSeverity.Information);
            }

            // Range — convert absolute character offsets to line:column
            int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
            int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);

            Range range;
            if (charStart >= 0 && charEnd >= 0 && content != null) {
                range = offsetRangeToLspRange(content, charStart, charEnd);
            } else {
                int line = Math.max(0, marker.getAttribute(IMarker.LINE_NUMBER, 1) - 1);
                range = new Range(
                        new Position(line, 0),
                        new Position(line, Integer.MAX_VALUE));
            }

            diagnostic.setRange(range);
            diagnostic.setMessage(marker.getAttribute(IMarker.MESSAGE, "Unknown error"));
            diagnostic.setSource("groovy");

            return diagnostic;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to convert marker to diagnostic", e);
            return null;
        }
    }
}
