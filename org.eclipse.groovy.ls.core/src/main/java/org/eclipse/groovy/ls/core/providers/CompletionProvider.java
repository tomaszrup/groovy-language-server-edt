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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;

/**
 * Provides code completion for Groovy documents.
 * <p>
 * JDT's built-in {@code codeComplete()} uses a Java-only parser that cannot
 * handle Groovy syntax (always returns 0 proposals). This provider therefore
 * implements its own completion engine:
 * <ul>
 *   <li><b>Dot completion</b>: Uses {@code codeSelect()} (routed through
 *       {@link MinimalCodeSelectHelper} and the Groovy AST) to resolve the
 *       expression before the dot, then enumerates members of the resolved
 *       type via the JDT Java model and its type hierarchy.</li>
 *   <li><b>Type name completion</b>: Uses {@link SearchEngine#searchAllTypeNames}
 *       to find types matching the prefix (handles {@code @Annotation} too).</li>
 *   <li><b>Keyword completion</b>: Provides Groovy keywords.</li>
 *   <li><b>Fallback</b>: Uses the Groovy AST when JDT is unavailable.</li>
 * </ul>
 */
public class CompletionProvider {

    private static final class TypeSearchContext {
        final boolean annotationOnly;
        final String currentPackage;
        final Set<String> existingImports;
        final int importInsertLine;

        TypeSearchContext(boolean annotationOnly, String currentPackage,
                          Set<String> existingImports, int importInsertLine) {
            this.annotationOnly = annotationOnly;
            this.currentPackage = currentPackage;
            this.existingImports = existingImports;
            this.importInsertLine = importInsertLine;
        }
    }

    private static final class TypeResolutionContext {
        private final Map<String, IType> resolvedTypes = new HashMap<>();
        private final Set<String> missingTypes = new HashSet<>();
    }

    private record ScopedVariable(String name, ClassNode type) {
    }

    private static final class ScopedVariableCollector extends ClassCodeVisitorSupport {
        private final int targetLine;
        private final int targetColumn;
        private final LinkedHashMap<String, ClassNode> variables = new LinkedHashMap<>();

        ScopedVariableCollector(Position position) {
            this.targetLine = position.getLine() + 1;
            this.targetColumn = position.getCharacter() + 1;
        }

        List<ScopedVariable> getVariables() {
            List<ScopedVariable> result = new ArrayList<>();
            for (Map.Entry<String, ClassNode> entry : variables.entrySet()) {
                result.add(new ScopedVariable(entry.getKey(), entry.getValue()));
            }
            return result;
        }

        void addParameters(Parameter[] parameters) {
            if (parameters == null) {
                return;
            }
            for (Parameter parameter : parameters) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isEmpty()) {
                    continue;
                }
                variables.put(parameter.getName(), normalizeType(parameter.getType()));
            }
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expr) {
            if (expr == null || expr.getLineNumber() < 1) {
                return;
            }
            if (containsPosition(expr)) {
                Expression right = expr.getRightExpression();
                if (right != null) {
                    right.visit(this);
                }
                return;
            }
            if (startsBeforeCursor(expr)) {
                Expression left = expr.getLeftExpression();
                if (left instanceof VariableExpression varExpr) {
                    String name = varExpr.getName();
                    if (name != null && !name.isEmpty()) {
                        variables.put(name, resolveVariableType(varExpr, expr.getRightExpression()));
                    }
                }
            }
        }

        @Override
        public void visitClosureExpression(ClosureExpression expr) {
            if (expr == null || !containsPosition(expr)) {
                return;
            }
            addParameters(expr.getParameters());
            if (expr.getCode() != null) {
                expr.getCode().visit(this);
            }
        }

        private ClassNode resolveVariableType(VariableExpression variable, Expression initializer) {
            if (variable == null) {
                return null;
            }
            ClassNode originType = normalizeType(variable.getOriginType());
            if (originType != null) {
                return originType;
            }
            if (initializer instanceof ConstructorCallExpression ctorCall) {
                return normalizeType(ctorCall.getType());
            }
            return initializer != null ? normalizeType(initializer.getType()) : null;
        }

        private ClassNode normalizeType(ClassNode type) {
            if (type == null) {
                return null;
            }
            String name = type.getName();
            if (name == null || name.isEmpty() || "void".equals(name) || "java.lang.Object".equals(name)) {
                return null;
            }
            return type;
        }

        private boolean startsBeforeCursor(ASTNode node) {
            if (node == null || node.getLineNumber() < 1) {
                return false;
            }
            if (node.getLineNumber() < targetLine) {
                return true;
            }
            if (node.getLineNumber() > targetLine) {
                return false;
            }
            int startColumn = node.getColumnNumber() > 0 ? node.getColumnNumber() : 1;
            return startColumn <= targetColumn;
        }

        private boolean containsPosition(ASTNode node) {
            if (node == null || node.getLineNumber() < 1) {
                return false;
            }
            int endLine = node.getLastLineNumber() > 0 ? node.getLastLineNumber() : node.getLineNumber();
            int endColumn = node.getLastColumnNumber() > 0 ? node.getLastColumnNumber() : Integer.MAX_VALUE;
            if (targetLine < node.getLineNumber() || targetLine > endLine) {
                return false;
            }
            if (targetLine == node.getLineNumber()) {
                int startColumn = node.getColumnNumber() > 0 ? node.getColumnNumber() : 1;
                if (targetColumn < startColumn) {
                    return false;
                }
            }
            return targetLine != endLine || targetColumn <= endColumn;
        }
    }

    private static final String JAVA_LANG_PACKAGE = "java.lang.";
    private static final String JAVA_UTIL_PACKAGE = "java.util.";
    private static final String JAVA_IO_PACKAGE = "java.io.";
    private static final String JAVA_NET_PACKAGE = "java.net.";
    private static final String JAVA_MATH_PACKAGE = "java.math.";
    private static final String GROOVY_LANG_PACKAGE = "groovy.lang.";
    private static final String GROOVY_UTIL_PACKAGE = "groovy.util.";
    private static final String TRAIT_DETAIL_SUFFIX = " (trait)";
    private static final String OBJECT_TYPE_NAME = "Object";
    private static final String IMPORT_PREFIX = "import ";
    private static final String PACKAGE_PREFIX = "package ";

    private final DocumentManager documentManager;

    /**
     * Persistent LRU cache for type hierarchy chains. Entries have a 60-second
     * TTL so that structural changes (new supertypes, removed interfaces) are
     * eventually picked up without requiring explicit invalidation on every edit.
     * <p>
     * Key: {@code IType.getFullyQualifiedName()}, Value: hierarchy chain + timestamp.
     */
    private static final int HIERARCHY_CACHE_SIZE = 300;
    private static final long HIERARCHY_TTL_MS = 60_000;

    private record CachedHierarchy(List<IType> chain, long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > HIERARCHY_TTL_MS;
        }
    }

    private final Map<String, CachedHierarchy> hierarchyCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedHierarchy> eldest) {
                    return size() > HIERARCHY_CACHE_SIZE;
                }
            });

    /**
     * Invalidate the hierarchy cache (e.g. after a build or save).
     */
    public void invalidateHierarchyCache() {
        hierarchyCache.clear();
    }

    /**
     * Find a usable IJavaProject. The working copy's own project may be stale/non-existent
     * (e.g. due to URI encoding issues with special characters in paths).
     * Falls back to iterating all open workspace projects.
     */
    private static IJavaProject findWorkingProject(ICompilationUnit workingCopy) {
        IJavaProject project = getProjectFromWorkingCopy(workingCopy);
        return project != null ? project : findOpenWorkspaceJavaProject();
    }

    private static IJavaProject getProjectFromWorkingCopy(ICompilationUnit workingCopy) {
        if (workingCopy == null) {
            return null;
        }
        try {
            IJavaProject project = workingCopy.getJavaProject();
            return project != null && project.exists() ? project : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static IJavaProject findOpenWorkspaceJavaProject() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                IJavaProject javaProject = toJavaProject(project);
                if (javaProject != null) {
                    return javaProject;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static IJavaProject toJavaProject(IProject project) {
        try {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
                return null;
            }
            IJavaProject javaProject = JavaCore.create(project);
            return javaProject != null && javaProject.exists() ? javaProject : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Groovy auto-import packages (always available without explicit import). */
    private static final String[] GROOVY_AUTO_PACKAGES = {
        JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE, JAVA_IO_PACKAGE, JAVA_NET_PACKAGE,
        GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE, JAVA_MATH_PACKAGE
    };

    /** Maximum number of type search results. */
    private static final int MAX_TYPE_RESULTS = 100;

    /** Maximum number of member results per type hierarchy. */
    private static final int MAX_MEMBER_RESULTS = 300;

    // ---- Type-name search result cache (key = projectName:prefix) ----

    private static final int TYPE_NAME_CACHE_SIZE = 200;
    private static final long TYPE_NAME_CACHE_TTL_MS = 30_000;

    private record CachedTypeNames(List<CompletionItem> items, long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > TYPE_NAME_CACHE_TTL_MS;
        }
    }

    private static final int EXACT_TYPE_CACHE_SIZE = 300;
    private static final long EXACT_TYPE_CACHE_TTL_MS = 30_000;

    private record CachedResolvedType(IType type, boolean missing, long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > EXACT_TYPE_CACHE_TTL_MS;
        }
    }

    private static final int DOT_RECONCILE_MISS_CACHE_SIZE = 200;
    private static final long DOT_RECONCILE_MISS_TTL_MS = 30_000;

    private record CachedDotReconcileMiss(long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > DOT_RECONCILE_MISS_TTL_MS;
        }
    }

    private final Map<String, CachedTypeNames> typeNameCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedTypeNames> eldest) {
                    return size() > TYPE_NAME_CACHE_SIZE;
                }
            });

    private final Map<String, CachedResolvedType> exactTypeCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResolvedType> eldest) {
                    return size() > EXACT_TYPE_CACHE_SIZE;
                }
            });

    private final Map<String, CachedDotReconcileMiss> dotReconcileMissCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedDotReconcileMiss> eldest) {
                    return size() > DOT_RECONCILE_MISS_CACHE_SIZE;
                }
            });

    /**
     * Tracks which projects have had their type search index warmed.
     * Once a project is warmed, type-name cache entries for it are valid.
     */
    private final Set<String> warmedProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Invalidate the type-name cache (e.g. after a save or classpath change).
     */
    public void invalidateTypeNameCache() {
        typeNameCache.clear();
        exactTypeCache.clear();
        dotReconcileMissCache.clear();
    }

    /**
     * Warm the JDT search index for a specific project by issuing a dummy
     * {@code searchAllTypeNames("*")} with {@code WAIT_UNTIL_READY_TO_SEARCH}.
     * <p>
     * Should be called on a low-priority background thread — NOT on the LSP
     * request thread. After the warm completes, subsequent type-name completions
     * for this project will return full results via {@code FORCE_IMMEDIATE_SEARCH}.
     *
     * @param project the Java project to warm (typically from the file just opened)
     */
    public void warmTypeIndex(IJavaProject project) {
        if (project == null) return;
        String projectName = project.getElementName();
        if (warmedProjects.contains(projectName)) return;

        try {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Warming type index for project: " + projectName);
            long start = System.currentTimeMillis();

            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                    new IJavaElement[]{project},
                    IJavaSearchScope.SOURCES | IJavaSearchScope.APPLICATION_LIBRARIES);

            JdtSearchSupport.searchAllTypeNames(
                    null, SearchPattern.R_PATTERN_MATCH,
                    "*".toCharArray(), SearchPattern.R_PATTERN_MATCH,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameRequestor() {
                        @Override
                        public void acceptType(int modifiers, char[] packageName,
                                              char[] simpleTypeName,
                                              char[][] enclosingTypeNames, String path) {
                            // Discard results — we just want the index built.
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    null);

            warmedProjects.add(projectName);
            long elapsed = System.currentTimeMillis() - start;
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Type index warm for '" + projectName + "' completed in " + elapsed + " ms");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Type index warm failed for '" + projectName + "': " + e.getMessage());
        }
    }

    public CompletionProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    private static String projectCacheKey(IJavaProject project) {
        if (project == null) {
            return "<null>";
        }
        try {
            String name = project.getElementName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception e) {
            // Ignore and fall back to identity below.
        }
        return Integer.toHexString(System.identityHashCode(project));
    }

    private static String exactTypeCacheKey(IJavaProject project, String lookupKey) {
        return projectCacheKey(project) + ":" + lookupKey;
    }

    private CachedResolvedType getResolvedTypeCacheEntry(String cacheKey) {
        CachedResolvedType cached = exactTypeCache.get(cacheKey);
        if (cached != null && cached.isExpired()) {
            exactTypeCache.remove(cacheKey);
            return null;
        }
        return cached;
    }

    private CachedResolvedType getResolvedTypeFromCaches(TypeResolutionContext context, String cacheKey) {
        if (context != null) {
            if (context.missingTypes.contains(cacheKey)) {
                return new CachedResolvedType(null, true, 0L);
            }
            IType requestResolved = context.resolvedTypes.get(cacheKey);
            if (requestResolved != null) {
                return new CachedResolvedType(requestResolved, false, 0L);
            }
        }

        CachedResolvedType cached = getResolvedTypeCacheEntry(cacheKey);
        if (cached != null && context != null) {
            if (cached.missing()) {
                context.missingTypes.add(cacheKey);
            } else {
                context.resolvedTypes.put(cacheKey, cached.type());
            }
        }
        return cached;
    }

    private void cacheResolvedType(TypeResolutionContext context, String cacheKey, IType resolved) {
        if (context != null) {
            context.resolvedTypes.remove(cacheKey);
            context.missingTypes.remove(cacheKey);
            if (resolved == null) {
                context.missingTypes.add(cacheKey);
            } else {
                context.resolvedTypes.put(cacheKey, resolved);
            }
        }

        exactTypeCache.put(cacheKey,
                new CachedResolvedType(resolved, resolved == null, System.currentTimeMillis()));
    }

    private IType findTypeCached(IJavaProject project,
            String lookupFqn,
            TypeResolutionContext context) throws JavaModelException {
        if (project == null || lookupFqn == null || lookupFqn.isEmpty()) {
            return null;
        }

        String cacheKey = exactTypeCacheKey(project, "find:" + lookupFqn);
        CachedResolvedType cached = getResolvedTypeFromCaches(context, cacheKey);
        if (cached != null) {
            return cached.missing() ? null : cached.type();
        }

        IType resolved = project.findType(lookupFqn);
        cacheResolvedType(context, cacheKey, resolved);
        return resolved;
    }

    private String dotReconcileMissKey(String uri, String content, int dotPos) {
        String safeUri = (uri != null) ? uri : "<null>";
        return safeUri + ":" + dotPos + ":" + content.length() + ":" + content.hashCode();
    }

    private boolean shouldSkipPatchedDotReconcile(String uri, String content, int dotPos) {
        CachedDotReconcileMiss cached = dotReconcileMissCache.get(dotReconcileMissKey(uri, content, dotPos));
        if (cached != null && cached.isExpired()) {
            dotReconcileMissCache.remove(dotReconcileMissKey(uri, content, dotPos));
            return false;
        }
        return cached != null;
    }

    private void rememberPatchedDotReconcileMiss(String uri, String content, int dotPos) {
        dotReconcileMissCache.put(dotReconcileMissKey(uri, content, dotPos),
                new CachedDotReconcileMiss(System.currentTimeMillis()));
    }

    private void clearPatchedDotReconcileMiss(String uri, String content, int dotPos) {
        dotReconcileMissCache.remove(dotReconcileMissKey(uri, content, dotPos));
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Compute completion items at the cursor position.
     */
    public List<CompletionItem> getCompletions(CompletionParams params) {
        List<CompletionItem> items = new ArrayList<>();

        if (Thread.currentThread().isInterrupted()) {
            return items;
        }

        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();
        TypeResolutionContext typeResolutionContext = new TypeResolutionContext();

        GroovyLanguageServerPlugin.logInfo("[completion] Request at " + uri
                + " line=" + position.getLine() + " char=" + position.getCharacter());

        String content = documentManager.getContent(uri);
        if (content == null) {
            GroovyLanguageServerPlugin.logInfo("[completion] No content for URI");
            return items;
        }

        int offset = positionToOffset(content, position);

        // Extract the identifier prefix being typed
        String prefix = extractPrefix(content, offset);
        int prefixStart = offset - prefix.length();

        // Check if there is a dot immediately before the prefix
        boolean isDotCompletion = (prefixStart > 0 && content.charAt(prefixStart - 1) == '.');
        boolean isAnnotationCompletion = isAnnotationContext(content, offset, prefixStart);

        GroovyLanguageServerPlugin.logInfo("[completion] prefix='" + prefix
            + "' isDot=" + isDotCompletion
            + " isAnnotation=" + isAnnotationCompletion
            + " offset=" + offset);

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            GroovyLanguageServerPlugin.logInfo("[completion] No working copy, using fallback");
            return getFallbackCompletions(uri, position);
        }

        try {
            if (isDotCompletion) {
                if (Thread.currentThread().isInterrupted()) {
                    return items;
                }
                int dotPos = prefixStart - 1; // position of the '.'
                items.addAll(getDotCompletions(
                        workingCopy, uri, content, position, dotPos, prefix, typeResolutionContext));
                // Never add keywords/types after a dot — only member completions
            } else if (isAnnotationCompletion) {
                if (Thread.currentThread().isInterrupted()) {
                    return items;
                }
                // After '@', only annotation types are valid.
                items.addAll(getTypeCompletions(
                        workingCopy, uri, content, prefix, true, typeResolutionContext));
            } else {
                // Non-dot context: identifiers + types + keywords
                items.addAll(getIdentifierCompletions(
                        workingCopy, uri, position, prefix, typeResolutionContext));
                if (Thread.currentThread().isInterrupted()) {
                    return items;
                }
                if (!prefix.isEmpty()) {
                    items.addAll(getTypeCompletions(
                            workingCopy, uri, content, prefix, false, typeResolutionContext));
                    if (Thread.currentThread().isInterrupted()) {
                        return items;
                    }
                    items.addAll(getKeywordCompletions(prefix));
                }
            }

            GroovyLanguageServerPlugin.logInfo("[completion] Returning " + items.size() + " items");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Failed", e);
        }

        return items;
    }

    /**
     * Resolve additional details for a completion item.
     */
    public CompletionItem resolveCompletionItem(CompletionItem item) {
        return item;
    }

    // =========================================================================
    // Dot Completion  (expression.member)
    // =========================================================================

    /** Dummy identifier inserted after the dot to make the AST parseable. */
    private static final String DOT_COMPLETION_PLACEHOLDER = "__z__";

    /**
     * Provide completions after a dot by resolving the expression before
     * the dot to a type and listing its members.
     */
    private List<CompletionItem> getDotCompletions(ICompilationUnit workingCopy,
                                                    String lspUri, String content,
                                                    Position position, int dotPos,
                                                    String prefix,
                                                    TypeResolutionContext typeResolutionContext) {
        List<CompletionItem> items = new ArrayList<>();
        if (Thread.currentThread().isInterrupted()) {
            return items;
        }

        // Walk backwards from the dot to find the identifier before it.
        int exprEnd = dotPos; // content[dotPos] == '.'
        int exprStart = exprEnd - 1;
        while (exprStart >= 0 && Character.isJavaIdentifierPart(content.charAt(exprStart))) {
            exprStart--;
        }
        exprStart++;

        if (exprStart >= exprEnd) {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] No identifier before dot at " + dotPos);
            return items;
        }

        String exprName = content.substring(exprStart, exprEnd);
        GroovyLanguageServerPlugin.logInfo("[completion] Dot on '" + exprName + "'");

        // Fast path for "foo.|": try direct member lookup and cached AST
        // first. They are usually enough for locals/fields after typing '.',
        // and they avoid a full working-copy reconcile on the request thread.
        if (prefix.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return items;
            }
            addCheapDotCompletions(
                    workingCopy, lspUri, position, exprName, prefix, items, typeResolutionContext);
            if (!items.isEmpty()) {
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Dot completion resolved without patched reconcile");
                return items;
            }
            if (shouldSkipPatchedDotReconcile(lspUri, content, dotPos)) {
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Skipping repeated patched reconcile for unresolved dot completion");
                return items;
            }
        }

        boolean patched = false;
        try {
            // When the prefix is empty (cursor right after the dot), the source
            // contains an incomplete expression like "foo." which breaks the
            // Groovy parser and causes codeSelect to return 0 elements.
            // Temporarily insert a dummy identifier after the dot so the parser
            // produces a valid AST. Restore only AFTER all fallback attempts,
            // so that AST-based fallbacks also see a valid AST.
            patched = patchContentForDotCompletion(workingCopy, content, dotPos, prefix);

            if (Thread.currentThread().isInterrupted()) {
                return items;
            }
            IJavaElement[] elements = workingCopy.codeSelect(exprStart, exprEnd - exprStart);
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] codeSelect returned " + elements.length + " element(s)");

            if (elements.length > 0) {
                IJavaElement element = elements[0];
                GroovyLanguageServerPlugin.logInfo("[completion]   element: "
                        + element.getClass().getSimpleName()
                        + " '" + element.getElementName() + "'");

                IJavaProject project = findWorkingProject(workingCopy);
                IType type = resolveElementType(element, project, typeResolutionContext);

                if (type != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion]   resolved type: " + type.getFullyQualifiedName());
                    // If the expression starts with lowercase, it's a variable/field reference,
                    // not a type name — don't restrict to static members.
                    boolean staticOnly = (element instanceof IType)
                            && !exprName.isEmpty()
                            && Character.isUpperCase(exprName.charAt(0));
                    addMembersOfType(type, prefix, staticOnly, items);
                } else {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion]   could not resolve element type");
                }
            }

            // If codeSelect failed (e.g. broken AST after typing dot),
            // try direct JDT model lookup for the field
            if (items.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) {
                    return items;
                }
                IJavaProject proj = findWorkingProject(workingCopy);
                IType fieldType = findFieldTypeDirectly(
                        workingCopy, lspUri, exprName, proj, typeResolutionContext);
                if (fieldType != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion] Direct field lookup resolved: "
                            + fieldType.getFullyQualifiedName());
                    addMembersOfType(fieldType, prefix, false, items);
                }
            }

            // AST-based dot completion fallback: resolve the expression type via the AST
            if (items.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) {
                    return items;
                }
                addAstDotCompletions(
                        workingCopy, lspUri, position, exprName, prefix, items, typeResolutionContext);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Dot completion failed", e);
        } finally {
            // Restore original content after ALL resolution attempts are done
            if (patched) {
                restoreOriginalContent(workingCopy, content);
            }
        }

        if (prefix.isEmpty()) {
            if (patched && items.isEmpty()) {
                rememberPatchedDotReconcileMiss(lspUri, content, dotPos);
            } else if (!items.isEmpty()) {
                clearPatchedDotReconcileMiss(lspUri, content, dotPos);
            }
        }

        return items;
    }

    private void addCheapDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                        Position position, String exprName, String prefix,
                                        List<CompletionItem> items,
                                        TypeResolutionContext typeResolutionContext) {
        try {
            IJavaProject project = findWorkingProject(workingCopy);
            if (project != null) {
                IType fieldType = findDirectMemberType(
                        workingCopy, exprName, project, typeResolutionContext);
                if (fieldType != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion] Direct member lookup resolved: "
                            + fieldType.getFullyQualifiedName());
                    addMembersOfType(fieldType, prefix, false, items);
                }
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Cheap direct dot lookup failed: " + e.getMessage());
        }

        if (items.isEmpty()) {
            addCachedAstDotCompletions(
                    workingCopy, lspUri, position, exprName, prefix, items, typeResolutionContext);
        }
    }

    /**
     * Temporarily patch the working copy buffer by inserting a dummy identifier
     * after the dot, so the Groovy parser can produce a valid AST.
     * Returns {@code true} if the patching succeeded and the caller must restore.
     */
    private boolean patchContentForDotCompletion(ICompilationUnit workingCopy,
                                                  String content, int dotPos,
                                                  String prefix) {
        if (!prefix.isEmpty()) {
            return false;
        }
        try {
            String patchedContent = content.substring(0, dotPos + 1)
                    + DOT_COMPLETION_PLACEHOLDER
                    + content.substring(dotPos + 1);
            documentManager.reconcileWithContent(workingCopy, patchedContent);
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Patched content for dot completion");
            return true;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] Failed to patch content for dot completion", e);
            return false;
        }
    }

    /**
     * Restore the original source content in the working copy buffer.
     * Only sets the buffer contents without a full reconcile — the next
     * diagnostics debounce pass (200ms) will reconcile with the real content.
     */
    private void restoreOriginalContent(ICompilationUnit workingCopy,
                                         String originalContent) {
        try {
            workingCopy.getBuffer().setContents(originalContent);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] Failed to restore content after dot completion", e);
        }
    }

    /**
     * Resolve an {@link IJavaElement} (field, local variable, method, type) to
     * the {@link IType} it represents or evaluates to.
     */
    private IType resolveElementType(IJavaElement element, IJavaProject project)
            throws JavaModelException {
        return resolveElementType(element, project, null);
    }

    private IType resolveElementType(IJavaElement element, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {

        if (element instanceof IType typeElement) {
            return typeElement;
        }

        String typeSig = null;
        IType declaringType = null;

        if (element instanceof IField field) {
            typeSig = field.getTypeSignature();
            declaringType = field.getDeclaringType();
        } else if (element instanceof ILocalVariable local) {
            typeSig = local.getTypeSignature();
            IJavaElement parent = local.getParent();
            if (parent instanceof IMember memberParent) {
                declaringType = memberParent.getDeclaringType();
            }
        } else if (element instanceof IMethod method) {
            typeSig = method.getReturnType();
            declaringType = method.getDeclaringType();
        }

        if (typeSig == null) return null;

        String typeName = Signature.toString(typeSig);
        GroovyLanguageServerPlugin.logInfo(
                "[completion]   typeSig='" + typeSig + "' → '" + typeName + "'");

        return resolveTypeName(typeName, declaringType, project, typeResolutionContext);
    }

    /**
     * Resolve a type name (simple or qualified) to an {@link IType}.
     */
    private IType resolveTypeName(String typeName, IType declaringType,
                                   IJavaProject project) throws JavaModelException {
        return resolveTypeName(typeName, declaringType, project, null);
    }

    private IType resolveTypeName(String typeName, IType declaringType,
                                   IJavaProject project,
                                   TypeResolutionContext typeResolutionContext) throws JavaModelException {
        // 1. Direct lookup (works for fully-qualified names)
        IType type = findTypeCached(project, typeName, typeResolutionContext);
        if (type != null) return type;

        // 2. Resolve through declaring type's import context
        if (declaringType != null) {
            String[][] resolved = declaringType.resolveType(typeName);
            if (resolved != null && resolved.length > 0) {
                String fqn = resolved[0][0].isEmpty()
                        ? resolved[0][1]
                        : resolved[0][0] + "." + resolved[0][1];
                type = findTypeCached(project, fqn, typeResolutionContext);
                if (type != null) return type;
            }
        }

        // 3. Try Groovy auto-import packages
        for (String pkg : GROOVY_AUTO_PACKAGES) {
            type = findTypeCached(project, pkg + typeName, typeResolutionContext);
            if (type != null) return type;
        }

        return null;
    }

    /**
     * Add methods and fields of the given type (and its supertypes).
     */
    private void addMembersOfType(IType type, String prefix, boolean staticOnly,
                                   List<CompletionItem> items) throws JavaModelException {
        Set<String> seen = new HashSet<>();
        IType memberSource = JavaBinaryMemberResolver.resolveMemberSource(type);

        List<IType> chain = buildTypeHierarchyChain(memberSource, hierarchyCache);
        for (int typeIndex = 0; typeIndex < chain.size() && items.size() < MAX_MEMBER_RESULTS; typeIndex++) {
            IType currentType = chain.get(typeIndex);
            String sortPrefix = (typeIndex == 0) ? "0" : "1";
            addMethodMembers(currentType, prefix, staticOnly, sortPrefix, seen, items);
            addFieldBackedAccessorPropertyMembers(currentType, prefix, staticOnly, sortPrefix, seen, items);
            addFieldMembers(currentType, prefix, staticOnly, sortPrefix, seen, items);
        }

        GroovyLanguageServerPlugin.logInfo("[completion] Added " + items.size()
        + " members from " + memberSource.getElementName()
                + " hierarchy (" + chain.size() + " types)");
    }

    private List<IType> buildTypeHierarchyChain(IType type,
            Map<String, CachedHierarchy> cache) throws JavaModelException {
        String key = type.getFullyQualifiedName();
        CachedHierarchy cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return cached.chain();

        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(null);
        IType[] supertypes = hierarchy.getAllSupertypes(type);
        List<IType> chain = new ArrayList<>();
        chain.add(type);
        chain.addAll(Arrays.asList(supertypes));
        cache.put(key, new CachedHierarchy(chain, System.currentTimeMillis()));
        return chain;
    }

    private void addMethodMembers(IType ownerType, String prefix, boolean staticOnly,
                                  String sortPrefix, Set<String> seen,
                                  List<CompletionItem> items) throws JavaModelException {
        for (IMethod method : ownerType.getMethods()) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                break;
            }
            if (shouldIncludeMethod(method, prefix, staticOnly, seen)) {
                String name = method.getElementName();
                String key = name + "/" + method.getParameterTypes().length;
                seen.add(key);
                items.add(methodToCompletionItem(method, name, ownerType, sortPrefix));
            }
        }

        addRecordComponentAccessorMembers(ownerType, prefix, staticOnly, sortPrefix, seen, items);
    }

    private void addRecordComponentAccessorMembers(IType ownerType, String prefix, boolean staticOnly,
                                                   String sortPrefix, Set<String> seen,
                                                   List<CompletionItem> items) {
        if (staticOnly) {
            return;
        }

        for (JavaRecordSourceSupport.RecordComponentInfo component : JavaRecordSourceSupport.getRecordComponents(ownerType)) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                return;
            }

            String name = component.name();
            String key = name + "/0";
            boolean includeComponent = matchesPrefix(name, prefix) && !seen.contains(key);
            if (includeComponent) {
                seen.add(key);
                items.add(recordComponentToCompletionItem(component, ownerType, sortPrefix));
            }
        }
    }

    private boolean shouldIncludeMethod(IMethod method, String prefix,
                                        boolean staticOnly, Set<String> seen)
            throws JavaModelException {
        String name = method.getElementName();
        if (method.isConstructor() || name.startsWith("<") || name.contains("$")
                || name.startsWith("__") || !matchesPrefix(name, prefix)) {
            return false;
        }
        if (staticOnly && !Flags.isStatic(method.getFlags())) {
            return false;
        }
        String key = name + "/" + method.getParameterTypes().length;
        return !seen.contains(key);
    }

    private void addFieldMembers(IType ownerType, String prefix, boolean staticOnly,
                                 String sortPrefix, Set<String> seen,
                                 List<CompletionItem> items) throws JavaModelException {
        for (IField field : ownerType.getFields()) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                break;
            }
            if (shouldIncludeField(field, prefix, staticOnly, seen)) {
                String name = field.getElementName();
                seen.add("f:" + name);
                items.add(buildFieldCompletionItem(field, name, ownerType, sortPrefix));
            }
        }
    }

    private void addFieldBackedAccessorPropertyMembers(IType ownerType, String prefix,
                                                       boolean staticOnly, String sortPrefix,
                                                       Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        if (staticOnly) {
            return;
        }

        Map<String, IField> candidateFields = new LinkedHashMap<>();
        for (IField field : ownerType.getFields()) {
            String name = field.getElementName();
            if (!name.startsWith("$") && !name.startsWith("__")) {
                candidateFields.put(name, field);
            }
        }

        for (IMethod method : ownerType.getMethods()) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                break;
            }

            String name = method.getElementName();
            if (!isFieldBackedAccessorPropertyCandidate(method, candidateFields.get(name), prefix)
                    || !seen.add("p:" + name)) {
                continue;
            }

            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Property);
            item.setDetail(resolveFieldBackedAccessorDetail(method, ownerType));
            item.setInsertText(name);
            item.setFilterText(name);
            item.setSortText(sortPrefix + "_" + name);
            items.add(item);
        }
    }

    private boolean isFieldBackedAccessorPropertyCandidate(IMethod method, IField field, String prefix)
            throws JavaModelException {
        if (field == null) {
            return false;
        }

        String name = method.getElementName();
        if (name.startsWith("<") || name.contains("$") || name.startsWith("__")
                || !matchesPrefix(name, prefix) || method.getParameterTypes().length != 0) {
            return false;
        }

        try {
            return field.getTypeSignature().equals(method.getReturnType());
        } catch (JavaModelException e) {
            return false;
        }
    }

    private boolean shouldIncludeField(IField field, String prefix,
                                       boolean staticOnly, Set<String> seen)
            throws JavaModelException {
        String name = field.getElementName();
        if (name.startsWith("$") || name.startsWith("__") || !matchesPrefix(name, prefix)) {
            return false;
        }
        if (staticOnly && !Flags.isStatic(field.getFlags())) {
            return false;
        }
        return !seen.contains("f:" + name);
    }

    private CompletionItem buildFieldCompletionItem(IField field, String name,
                                                    IType ownerType, String sortPrefix)
            throws JavaModelException {
        CompletionItem item = new CompletionItem(name);
        item.setKind(Flags.isEnum(field.getFlags())
                ? CompletionItemKind.EnumMember : CompletionItemKind.Field);
        item.setDetail(resolveFieldDetail(field, ownerType));
        item.setInsertText(name);
        item.setSortText(sortPrefix + "_" + name);
        return item;
    }

    private String resolveFieldDetail(IField field, IType ownerType) {
        try {
            return Signature.toString(field.getTypeSignature()) + " — " + ownerType.getElementName();
        } catch (Exception e) {
            return ownerType.getElementName();
        }
    }

    private CompletionItem recordComponentToCompletionItem(JavaRecordSourceSupport.RecordComponentInfo component,
                                                           IType ownerType, String sortPrefix) {
        CompletionItem item = new CompletionItem();
        item.setLabel(component.name() + "()");
        item.setKind(CompletionItemKind.Method);
        item.setDetail(component.type() + " — " + ownerType.getElementName() + " (record component)");
        item.setInsertText(component.name() + "()");
        item.setFilterText(component.name());
        item.setSortText(sortPrefix + "_" + component.name());
        return item;
    }

    private String resolveFieldBackedAccessorDetail(IMethod method, IType ownerType) {
        try {
            return Signature.toString(method.getReturnType())
                    + " — " + ownerType.getElementName() + " (record-style property)";
        } catch (Exception e) {
            return ownerType.getElementName() + " (record-style property)";
        }
    }

    /**
     * Convert an {@link IMethod} to a {@link CompletionItem}.
     */
    private CompletionItem methodToCompletionItem(IMethod method, String name,
                                                   IType owner, String sortPrefix) {
        CompletionItem item = new CompletionItem();
        try {
            // Label: name(ParamType1 p1, ParamType2 p2)
            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = JdtParameterNameResolver.resolve(method);
            StringBuilder label = new StringBuilder(name).append('(');
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) label.append(", ");
                label.append(Signature.toString(paramTypes[i]));
                if (i < paramNames.length) {
                    label.append(' ').append(paramNames[i]);
                }
            }
            label.append(')');
            item.setLabel(label.toString());

            // Detail: returnType — DeclaringClass
            String returnType = Signature.toString(method.getReturnType());
            item.setDetail(returnType + " — " + owner.getElementName());

            // Kind
            item.setKind(method.isConstructor()
                    ? CompletionItemKind.Constructor : CompletionItemKind.Method);

            // Insert text as snippet with parameter placeholders
            if (paramNames.length > 0) {
                StringBuilder snippet = new StringBuilder(name).append('(');
                for (int i = 0; i < paramNames.length; i++) {
                    if (i > 0) snippet.append(", ");
                    snippet.append("${").append(i + 1).append(':')
                            .append(paramNames[i]).append('}');
                }
                snippet.append(')');
                item.setInsertText(snippet.toString());
                item.setInsertTextFormat(InsertTextFormat.Snippet);
            } else {
                item.setInsertText(name + "()");
                item.setInsertTextFormat(InsertTextFormat.PlainText);
            }

            item.setFilterText(name);
            item.setSortText(sortPrefix + "_" + name);
        } catch (Exception e) {
            item.setLabel(name);
            item.setInsertText(name);
        }
        return item;
    }

    // =========================================================================
    // Direct Field Lookup  (bypass broken AST)
    // =========================================================================

    /**
     * Find the type of a field by directly looking it up in the JDT model.
     * This works even when the Groovy AST is broken (null ModuleNode),
     * e.g. right after typing a dot on an incomplete expression.
     */
    private IType findFieldTypeDirectly(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project) {
        return findFieldTypeDirectly(workingCopy, lspUri, fieldName, project, null);
    }

    private IType findFieldTypeDirectly(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project,
                                         TypeResolutionContext typeResolutionContext) {
        try {
            IType directMemberType = findDirectMemberType(
                    workingCopy, fieldName, project, typeResolutionContext);
            if (directMemberType != null) {
                return directMemberType;
            }

            // Check trait-inherited fields/methods for the field name
            IType traitFieldType = findFieldTypeInTraits(
                    workingCopy, lspUri, fieldName, project, typeResolutionContext);
            if (traitFieldType != null) {
                return traitFieldType;
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("[completion] Direct field lookup failed", e);
        }
        return null;
    }

    private IType findDirectMemberType(ICompilationUnit workingCopy, String memberName,
                                       IJavaProject project) throws JavaModelException {
        return findDirectMemberType(workingCopy, memberName, project, null);
    }

    private IType findDirectMemberType(ICompilationUnit workingCopy, String memberName,
                                       IJavaProject project,
                                       TypeResolutionContext typeResolutionContext) throws JavaModelException {
        if (workingCopy == null || project == null) {
            return null;
        }
        for (IType type : workingCopy.getTypes()) {
            IType fieldType = findDirectFieldType(type, memberName, project, typeResolutionContext);
            if (fieldType != null) {
                return fieldType;
            }

            IType methodType = findDirectMethodReturnType(type, memberName, project, typeResolutionContext);
            if (methodType != null) {
                return methodType;
            }
        }
        return null;
    }

    private IType findDirectFieldType(IType type, String fieldName, IJavaProject project)
            throws JavaModelException {
        return findDirectFieldType(type, fieldName, project, null);
    }

    private IType findDirectFieldType(IType type, String fieldName, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        IField field = type.getField(fieldName);
        if (field == null || !field.exists()) {
            return null;
        }

        String typeSig = field.getTypeSignature();
        String typeName = Signature.toString(typeSig);
        GroovyLanguageServerPlugin.logInfo(
                "[completion] Direct field lookup: " + fieldName + " -> " + typeName);
        return resolveTypeName(typeName, type, project, typeResolutionContext);
    }

    private IType findDirectMethodReturnType(IType type, String methodName, IJavaProject project)
            throws JavaModelException {
        return findDirectMethodReturnType(type, methodName, project, null);
    }

    private IType findDirectMethodReturnType(IType type, String methodName, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(methodName)
                    && method.getParameterTypes().length == 0) {
                String returnSig = method.getReturnType();
                String returnName = Signature.toString(returnSig);
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Direct method lookup: " + methodName + "() -> " + returnName);
                return resolveTypeName(returnName, type, project, typeResolutionContext);
            }
        }
        return null;
    }

    /**
     * Search trait types for a field/property matching the given name.
     * Returns the resolved type of the field, or null if not found.
     * Uses both JDT and Groovy AST for resolution.
     */
    private IType findFieldTypeInTraits(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project) {
        return findFieldTypeInTraits(workingCopy, lspUri, fieldName, project, null);
    }

    private IType findFieldTypeInTraits(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project,
                                         TypeResolutionContext typeResolutionContext) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) return null;

        for (ClassNode classNode : ast.getClasses()) {
            IType resolved = findFieldTypeInClassTraits(
                    classNode, ast, fieldName, project, typeResolutionContext);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private ModuleNode resolveAst(ICompilationUnit workingCopy, String lspUri) {
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        return ast != null ? ast : getModuleFromWorkingCopy(workingCopy);
    }

    private ModuleNode resolveCachedAst(ICompilationUnit workingCopy, String lspUri) {
        ModuleNode ast = (lspUri != null) ? documentManager.getCachedGroovyAST(lspUri) : null;
        return ast != null ? ast : getModuleFromWorkingCopy(workingCopy);
    }

    private IType findFieldTypeInClassTraits(ClassNode classNode, ModuleNode ast,
                                             String fieldName, IJavaProject project) {
        return findFieldTypeInClassTraits(classNode, ast, fieldName, project, null);
    }

    private IType findFieldTypeInClassTraits(ClassNode classNode, ModuleNode ast,
                                             String fieldName, IJavaProject project,
                                             TypeResolutionContext typeResolutionContext) {
        if (classNode.getLineNumber() < 0) {
            return null;
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return null;
        }

        for (ClassNode ifaceRef : interfaces) {
            IType resolved = findFieldTypeInTraitInterface(
                    ifaceRef, classNode, ast, fieldName, project, typeResolutionContext);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private IType findFieldTypeInTraitInterface(ClassNode ifaceRef, ClassNode classNode,
                                                ModuleNode ast, String fieldName,
                                                IJavaProject project) {
        return findFieldTypeInTraitInterface(
                ifaceRef, classNode, ast, fieldName, project, null);
    }

    private IType findFieldTypeInTraitInterface(ClassNode ifaceRef, ClassNode classNode,
                                                ModuleNode ast, String fieldName,
                                                IJavaProject project,
                                                TypeResolutionContext typeResolutionContext) {
        IType jdtResolved = resolveFieldTypeFromTraitJdt(
                ifaceRef, classNode, ast, fieldName, project, typeResolutionContext);
        if (jdtResolved != null) {
            return jdtResolved;
        }
        return resolveFieldTypeFromTraitAst(
                ifaceRef, ast, fieldName, project, typeResolutionContext);
    }

    private IType resolveFieldTypeFromTraitJdt(ClassNode ifaceRef, ClassNode classNode,
                                               ModuleNode ast, String fieldName,
                                               IJavaProject project) {
        return resolveFieldTypeFromTraitJdt(
                ifaceRef, classNode, ast, fieldName, project, null);
    }

    private IType resolveFieldTypeFromTraitJdt(ClassNode ifaceRef, ClassNode classNode,
                                               ModuleNode ast, String fieldName,
                                               IJavaProject project,
                                               TypeResolutionContext typeResolutionContext) {
        IType traitType = resolveTraitType(ifaceRef, classNode, ast, project, typeResolutionContext);
        if (traitType == null) {
            return null;
        }

        try {
            IType directFieldType = resolveTraitDirectFieldType(
                    traitType, fieldName, project, typeResolutionContext);
            if (directFieldType != null) {
                return directFieldType;
            }
            return resolveTraitAccessorType(traitType, fieldName, project, typeResolutionContext);
        } catch (JavaModelException e) {
            return null;
        }
    }

    private IType resolveTraitDirectFieldType(IType traitType, String fieldName,
                                              IJavaProject project)
            throws JavaModelException {
        return resolveTraitDirectFieldType(traitType, fieldName, project, null);
    }

    private IType resolveTraitDirectFieldType(IType traitType, String fieldName,
                                              IJavaProject project,
                                              TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        IField traitField = traitType.getField(fieldName);
        if (traitField == null || !traitField.exists()) {
            return null;
        }

        String typeName = Signature.toString(traitField.getTypeSignature());
        GroovyLanguageServerPlugin.logInfo(
                "[completion] Trait field lookup: " + fieldName + " -> " + typeName);
        return resolveTypeName(typeName, traitType, project, typeResolutionContext);
    }

    private IType resolveTraitAccessorType(IType traitType, String fieldName,
                                           IJavaProject project)
            throws JavaModelException {
        return resolveTraitAccessorType(traitType, fieldName, project, null);
    }

    private IType resolveTraitAccessorType(IType traitType, String fieldName,
                                           IJavaProject project,
                                           TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (IMethod method : traitType.getMethods()) {
            if (isTraitAccessorForField(method, capitalized)) {
                String returnName = Signature.toString(method.getReturnType());
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait property lookup: " + fieldName + " -> " + returnName);
                return resolveTypeName(returnName, traitType, project, typeResolutionContext);
            }
        }
        return null;
    }

    private boolean isTraitAccessorForField(IMethod method, String capitalizedFieldName) {
        String methodName = method.getElementName();
        boolean matchesName = methodName.equals("get" + capitalizedFieldName)
                || methodName.equals("is" + capitalizedFieldName);
        return matchesName && method.getParameterTypes().length == 0;
    }

    private IType resolveFieldTypeFromTraitAst(ClassNode ifaceRef, ModuleNode ast,
                                               String fieldName, IJavaProject project) {
        return resolveFieldTypeFromTraitAst(ifaceRef, ast, fieldName, project, null);
    }

    private IType resolveFieldTypeFromTraitAst(ClassNode ifaceRef, ModuleNode ast,
                                               String fieldName, IJavaProject project,
                                               TypeResolutionContext typeResolutionContext) {
        ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                ifaceRef, ast, documentManager);
        if (resolvedTraitNode == null || resolvedTraitNode.getLineNumber() < 0) {
            return null;
        }

        IType propertyType = resolveTraitAstPropertyType(
                resolvedTraitNode, fieldName, ast, project, typeResolutionContext);
        if (propertyType != null) {
            return propertyType;
        }

        IType fieldType = resolveTraitAstFieldType(
                resolvedTraitNode, fieldName, ast, project, typeResolutionContext);
        if (fieldType != null) {
            return fieldType;
        }

        return resolveTraitFieldHelperType(
                resolvedTraitNode, fieldName, ast, project, typeResolutionContext);
    }

    private IType resolveTraitAstPropertyType(ClassNode traitNode, String fieldName,
                                           ModuleNode ast, IJavaProject project) {
        return resolveTraitAstPropertyType(traitNode, fieldName, ast, project, null);
    }

    private IType resolveTraitAstPropertyType(ClassNode traitNode, String fieldName,
                                           ModuleNode ast, IJavaProject project,
                                           TypeResolutionContext typeResolutionContext) {
        for (PropertyNode prop : traitNode.getProperties()) {
            if (fieldName.equals(prop.getName()) && prop.getType() != null) {
                String typeName = prop.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait AST property: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project, typeResolutionContext);
            }
        }
        return null;
    }

    private IType resolveTraitAstFieldType(ClassNode traitNode, String fieldName,
                                           ModuleNode ast, IJavaProject project) {
        return resolveTraitAstFieldType(traitNode, fieldName, ast, project, null);
    }

    private IType resolveTraitAstFieldType(ClassNode traitNode, String fieldName,
                                           ModuleNode ast, IJavaProject project,
                                           TypeResolutionContext typeResolutionContext) {
        for (FieldNode field : traitNode.getFields()) {
            if (fieldName.equals(field.getName()) && field.getType() != null) {
                String typeName = field.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait AST field: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project, typeResolutionContext);
            }
        }
        return null;
    }

    private IType resolveTraitFieldHelperType(ClassNode traitNode, String fieldName,
                                              ModuleNode ast, IJavaProject project) {
        return resolveTraitFieldHelperType(traitNode, fieldName, ast, project, null);
    }

    private IType resolveTraitFieldHelperType(ClassNode traitNode, String fieldName,
                                              ModuleNode ast, IJavaProject project,
                                              TypeResolutionContext typeResolutionContext) {
        ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(traitNode, ast);
        if (helperNode == null) {
            return null;
        }

        for (FieldNode helperField : helperNode.getFields()) {
            if (TraitMemberResolver.isTraitFieldMatch(helperField.getName(), fieldName)
                    && helperField.getType() != null) {
                String typeName = helperField.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait FieldHelper field: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project, typeResolutionContext);
            }
        }
        return null;
    }

    /**
     * Resolve a type name from the Groovy AST to a JDT IType.
     * Tries FQN, imports, same-package, and auto-imports.
     */
    private IType resolveTypeNameFromAST(String typeName,
                                          ModuleNode module, IJavaProject project) {
        return resolveTypeNameFromAST(typeName, module, project, null);
    }

    private IType resolveTypeNameFromAST(String typeName,
                                          ModuleNode module, IJavaProject project,
                                          TypeResolutionContext typeResolutionContext) {
        try {
            IType resolved = resolveQualifiedType(typeName, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveImportedAstType(typeName, module, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveSamePackageAstType(typeName, module, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveAstAutoImportType(typeName, project, typeResolutionContext);
            if (resolved != null) return resolved;

            return resolveStarImportedAstType(typeName, module, project, typeResolutionContext);
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    private IType resolveQualifiedType(String typeName, IJavaProject project)
            throws JavaModelException {
        return resolveQualifiedType(typeName, project, null);
    }

    private IType resolveQualifiedType(String typeName, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        return typeName.contains(".") ? findTypeCached(project, typeName, typeResolutionContext) : null;
    }

    private IType resolveImportedAstType(String typeName, ModuleNode module,
                                         IJavaProject project) throws JavaModelException {
        return resolveImportedAstType(typeName, module, project, null);
    }

    private IType resolveImportedAstType(String typeName, ModuleNode module,
                                         IJavaProject project,
                                         TypeResolutionContext typeResolutionContext) throws JavaModelException {
        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                IType resolved = findTypeCached(project, impType.getName(), typeResolutionContext);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private IType resolveSamePackageAstType(String typeName, ModuleNode module,
                                            IJavaProject project) throws JavaModelException {
        return resolveSamePackageAstType(typeName, module, project, null);
    }

    private IType resolveSamePackageAstType(String typeName, ModuleNode module,
                                            IJavaProject project,
                                            TypeResolutionContext typeResolutionContext) throws JavaModelException {
        String pkg = normalizePackageName(module.getPackageName());
        return (pkg != null && !pkg.isEmpty())
                ? findTypeCached(project, pkg + "." + typeName, typeResolutionContext)
                : null;
    }

    private IType resolveAstAutoImportType(String typeName, IJavaProject project)
            throws JavaModelException {
        return resolveAstAutoImportType(typeName, project, null);
    }

    private IType resolveAstAutoImportType(String typeName, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        String[] autoPackages = {
            JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE, JAVA_IO_PACKAGE,
            GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE
        };
        for (String autoPkg : autoPackages) {
            IType resolved = findTypeCached(project, autoPkg + typeName, typeResolutionContext);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private IType resolveStarImportedAstType(String typeName, ModuleNode module,
                                             IJavaProject project) throws JavaModelException {
        return resolveStarImportedAstType(typeName, module, project, null);
    }

    private IType resolveStarImportedAstType(String typeName, ModuleNode module,
                                             IJavaProject project,
                                             TypeResolutionContext typeResolutionContext) throws JavaModelException {
        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType resolved = findTypeCached(project, pkgName + typeName, typeResolutionContext);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Identifier Completion  (in-scope fields, methods, properties)
    // =========================================================================

    /**
     * Provide completions for in-scope identifiers (fields, methods) from
     * the current compilation unit. Gives the user access to local
     * declarations when typing without a dot.
     */
    private List<CompletionItem> getIdentifierCompletions(ICompilationUnit workingCopy,
                                                          String lspUri, Position position,
                                                          String prefix) {
        return getIdentifierCompletions(workingCopy, lspUri, position, prefix, null);
    }

    private List<CompletionItem> getIdentifierCompletions(ICompilationUnit workingCopy,
                                                          String lspUri, Position position,
                                                          String prefix,
                                                          TypeResolutionContext typeResolutionContext) {
        List<CompletionItem> items = new ArrayList<>();
        if (prefix.isEmpty()) return items;

        try {
            addTypeIdentifierCompletions(workingCopy, prefix, items);

            // Also add members inherited from traits/interfaces
            addTraitIdentifierCompletions(
                    workingCopy, lspUri, prefix, items, typeResolutionContext);

            addScopedAstIdentifierCompletions(workingCopy, lspUri, position, prefix, items);

            // AST-based fallback: add own-class fields/properties/methods from Groovy AST
            // when JDT model returns empty (e.g., getModuleNode() returns null)
            if (items.isEmpty()) {
                addOwnClassAstCompletions(lspUri, workingCopy, prefix, items);
            }

            GroovyLanguageServerPlugin.logInfo("[completion] Identifier completions for '"
                    + prefix + "': " + items.size() + " results");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Identifier completions failed", e);
        }

        return items;
    }

    private void addTypeIdentifierCompletions(ICompilationUnit workingCopy,
                                              String prefix,
                                              List<CompletionItem> items)
            throws JavaModelException {
        for (IType type : workingCopy.getTypes()) {
            addTypeFieldIdentifierCompletions(type, prefix, items);
            addTypeMethodIdentifierCompletions(type, prefix, items);
        }
    }

    private void addTypeFieldIdentifierCompletions(IType type,
                                                   String prefix,
                                                   List<CompletionItem> items)
            throws JavaModelException {
        for (IField field : type.getFields()) {
            String name = field.getElementName();
            if (isTypeFieldIdentifierCandidate(name, prefix)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(resolveFieldTypeSignature(field));
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private boolean isTypeFieldIdentifierCandidate(String name, String prefix) {
        return !name.startsWith("$") && !name.startsWith("__") && matchesPrefix(name, prefix);
    }

    private String resolveFieldTypeSignature(IField field) {
        try {
            return Signature.toString(field.getTypeSignature());
        } catch (Exception e) {
            return OBJECT_TYPE_NAME;
        }
    }

    private void addTypeMethodIdentifierCompletions(IType type,
                                                    String prefix,
                                                    List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            String name = method.getElementName();
            if (isTypeMethodIdentifierCandidate(name, prefix)) {
                CompletionItem mi = methodToCompletionItem(method, name, type, "2");
                items.add(mi);
            }
        }
    }

    private boolean isTypeMethodIdentifierCandidate(String name, String prefix) {
        return !name.startsWith("<")
                && !name.contains("$")
                && !name.startsWith("__")
                && matchesPrefix(name, prefix);
    }

    /**
     * Add completions for members inherited from traits via the JDT model.
     * Resolves each interface/trait type in the project and lists its members.
     */
    private void addTraitIdentifierCompletions(ICompilationUnit workingCopy,
                                                String lspUri,
                                                String prefix,
                                                List<CompletionItem> items) {
        addTraitIdentifierCompletions(workingCopy, lspUri, prefix, items, null);
    }

    private void addTraitIdentifierCompletions(ICompilationUnit workingCopy,
                                                String lspUri,
                                                String prefix,
                                                List<CompletionItem> items,
                                                TypeResolutionContext typeResolutionContext) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);
        if (project == null) return;

        Set<String> seen = collectSeenCompletionNames(items);

        for (ClassNode classNode : ast.getClasses()) {
            addTraitCompletionsForClass(
                    classNode, ast, prefix, project, seen, items, typeResolutionContext);
        }
    }

    private Set<String> collectSeenCompletionNames(List<CompletionItem> items) {
        Set<String> seen = new HashSet<>();
        for (CompletionItem existing : items) {
            seen.add(existing.getFilterText() != null ? existing.getFilterText() : existing.getLabel());
        }
        return seen;
    }

    private void addScopedAstIdentifierCompletions(ICompilationUnit workingCopy,
                                                   String lspUri,
                                                   Position position,
                                                   String prefix,
                                                   List<CompletionItem> items) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) {
            return;
        }

        Set<String> seen = collectSeenCompletionNames(items);
        for (ScopedVariable variable : findScopedVisibleVariables(ast, position)) {
            String name = variable.name();
            if (!matchesPrefix(name, prefix) || !seen.add(name)) {
                continue;
            }
            CompletionItem item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Variable);
            item.setDetail(variable.type() != null
                    ? variable.type().getNameWithoutPackage() : OBJECT_TYPE_NAME);
            item.setInsertText(name);
            item.setFilterText(name);
            item.setSortText("0_" + name);
            items.add(item);
        }
    }

    private void addTraitCompletionsForClass(ClassNode classNode, ModuleNode ast,
                                             String prefix, IJavaProject project,
                                             Set<String> seen, List<CompletionItem> items) {
        addTraitCompletionsForClass(classNode, ast, prefix, project, seen, items, null);
    }

    private void addTraitCompletionsForClass(ClassNode classNode, ModuleNode ast,
                                             String prefix, IJavaProject project,
                                             Set<String> seen, List<CompletionItem> items,
                                             TypeResolutionContext typeResolutionContext) {
        if (classNode.getLineNumber() < 0) {
            return;
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return;
        }

        for (ClassNode ifaceRef : interfaces) {
            IType traitType = resolveTraitType(
                    ifaceRef, classNode, ast, project, typeResolutionContext);
            addTraitJdtIdentifierCompletions(traitType, prefix, seen, items);
            addTraitAstIdentifierCompletions(ifaceRef, ast, prefix, seen, items);
        }
    }

    private void addTraitJdtIdentifierCompletions(IType traitType, String prefix,
                                                  Set<String> seen, List<CompletionItem> items) {
        if (traitType == null) {
            return;
        }
        try {
            addTraitJdtFieldCompletions(traitType, prefix, seen, items);
            addTraitJdtMethodCompletions(traitType, prefix, seen, items);
            addTraitJdtPropertyCompletions(traitType, prefix, seen, items);
        } catch (JavaModelException e) {
            // ignore
        }
    }

    private void addTraitJdtFieldCompletions(IType traitType, String prefix,
                                             Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IField field : traitType.getFields()) {
            String name = field.getElementName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(resolveFieldTypeSignature(field) + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitJdtMethodCompletions(IType traitType, String prefix,
                                              Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : traitType.getMethods()) {
            String name = method.getElementName();
            String key = name + "/" + method.getParameterTypes().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem completion = methodToCompletionItem(method, name, traitType, "2");
                items.add(completion);
            }
        }
    }

    private void addTraitJdtPropertyCompletions(IType traitType, String prefix,
                                                Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : traitType.getMethods()) {
            String methodName = method.getElementName();
            if (methodName.startsWith("get") && methodName.length() > 3
                    && method.getParameterTypes().length == 0) {
                String propertyName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                if (matchesPrefix(propertyName, prefix) && seen.add(propertyName)) {
                    CompletionItem item = new CompletionItem(propertyName);
                    item.setKind(CompletionItemKind.Property);
                    item.setDetail(resolveMethodReturnType(method) + " (trait property)");
                    item.setInsertText(propertyName);
                    item.setFilterText(propertyName);
                    item.setSortText("1_" + propertyName);
                    items.add(item);
                }
            }
        }
    }

    private String resolveMethodReturnType(IMethod method) {
        try {
            return Signature.toString(method.getReturnType());
        } catch (Exception e) {
            return OBJECT_TYPE_NAME;
        }
    }

    private void addTraitAstIdentifierCompletions(ClassNode ifaceRef, ModuleNode ast,
                                                  String prefix, Set<String> seen,
                                                  List<CompletionItem> items) {
        ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                ifaceRef, ast, documentManager);
        if (resolvedTraitNode == null || resolvedTraitNode.getLineNumber() < 0) {
            return;
        }

        addTraitAstPropertyCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitAstFieldCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitAstMethodCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitFieldHelperCompletions(resolvedTraitNode, ast, prefix, seen, items);
    }

    private void addTraitAstPropertyCompletions(ClassNode traitNode, String prefix,
                                                Set<String> seen, List<CompletionItem> items) {
        for (PropertyNode prop : traitNode.getProperties()) {
            String name = prop.getName();
            if (matchesPrefix(name, prefix) && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                String typeName = prop.getType() != null
                        ? prop.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + " (trait property)");
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitAstFieldCompletions(ClassNode traitNode, String prefix,
                                             Set<String> seen, List<CompletionItem> items) {
        for (FieldNode field : traitNode.getFields()) {
            String name = field.getName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                String typeName = field.getType() != null
                        ? field.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitAstMethodCompletions(ClassNode traitNode, String prefix,
                                              Set<String> seen, List<CompletionItem> items) {
        for (MethodNode method : traitNode.getMethods()) {
            String name = method.getName();
            String key = name + "/" + method.getParameters().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && method.getLineNumber() >= 0
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                String returnType = method.getReturnType() != null
                        ? method.getReturnType().getNameWithoutPackage() : "void";
                item.setDetail(returnType + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(buildAstMethodInvocation(name, method.getParameters()));
                item.setFilterText(name);
                item.setSortText("2_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitFieldHelperCompletions(ClassNode traitNode, ModuleNode ast,
                                                String prefix, Set<String> seen,
                                                List<CompletionItem> items) {
        ClassNode helperNode = findTraitFieldHelperNode(traitNode, ast);
        if (helperNode == null) {
            return;
        }
        for (FieldNode helperField : helperNode.getFields()) {
            String rawName = helperField.getName();
            String demangledName = TraitMemberResolver.demangleTraitFieldName(rawName);
            if (!demangledName.equals(rawName)
                    && matchesPrefix(demangledName, prefix)
                    && seen.add(demangledName)) {
                CompletionItem item = new CompletionItem(demangledName);
                item.setKind(CompletionItemKind.Field);
                String typeName = helperField.getType() != null
                        ? helperField.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(demangledName);
                item.setFilterText(demangledName);
                item.setSortText("1_" + demangledName);
                items.add(item);
            }
        }
    }

    private String buildAstMethodInvocation(String name, Parameter[] parameters) {
        StringBuilder invocation = new StringBuilder(name).append("(");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                invocation.append(", ");
            }
            invocation.append(parameters[i].getName());
        }
        invocation.append(")");
        return invocation.toString();
    }

    /**
     * Find a trait FieldHelper node, searching in:
     * 1) current module, 2) trait's own module, 3) other open documents.
     */
    private ClassNode findTraitFieldHelperNode(ClassNode traitNode, ModuleNode currentModule) {
        if (traitNode == null) return null;

        ClassNode helper = TraitMemberResolver.findFieldHelperNode(traitNode, currentModule);
        if (helper != null) {
            return helper;
        }

        ModuleNode traitModule = traitNode.getModule();
        if (traitModule != null && traitModule != currentModule) {
            helper = TraitMemberResolver.findFieldHelperNode(traitNode, traitModule);
            if (helper != null) {
                return helper;
            }
        }

        for (String uri : documentManager.getOpenDocumentUris()) {
            ModuleNode otherModule = documentManager.getGroovyAST(uri);
            if (otherModule == null || otherModule == currentModule || otherModule == traitModule) {
                continue;
            }
            helper = TraitMemberResolver.findFieldHelperNode(traitNode, otherModule);
            if (helper != null) {
                return helper;
            }
        }

        return null;
    }

    /**
     * Add own-class fields, properties, and methods from the Groovy AST
     * when the JDT model returns empty (e.g., getModuleNode() is null).
     */
    private void addOwnClassAstCompletions(String lspUri, ICompilationUnit workingCopy,
                                            String prefix, List<CompletionItem> items) {
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        if (ast == null) {
            ast = getModuleFromWorkingCopy(workingCopy);
        }
        if (ast == null) return;

        Set<String> seen = new HashSet<>();
        for (CompletionItem existing : items) {
            seen.add(existing.getFilterText() != null ? existing.getFilterText() : existing.getLabel());
        }

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() >= 0) {
                addOwnClassAstCompletionsForClass(classNode, prefix, seen, items);
            }
        }
    }

    private void addOwnClassAstCompletionsForClass(ClassNode classNode,
                                                    String prefix,
                                                    Set<String> seen,
                                                    List<CompletionItem> items) {
        addOwnAstPropertyCompletions(classNode, prefix, seen, items);
        addOwnAstFieldCompletions(classNode, prefix, seen, items);
        addOwnAstMethodCompletions(classNode, prefix, seen, items);
    }

    private void addOwnAstPropertyCompletions(ClassNode classNode,
                                              String prefix,
                                              Set<String> seen,
                                              List<CompletionItem> items) {
        for (PropertyNode prop : classNode.getProperties()) {
            String name = prop.getName();
            if (matchesPrefix(name, prefix) && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                String typeName = prop.getType() != null
                    ? prop.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addOwnAstFieldCompletions(ClassNode classNode,
                                           String prefix,
                                           Set<String> seen,
                                           List<CompletionItem> items) {
        for (FieldNode field : classNode.getFields()) {
            String name = field.getName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                String typeName = field.getType() != null
                    ? field.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addOwnAstMethodCompletions(ClassNode classNode,
                                            String prefix,
                                            Set<String> seen,
                                            List<CompletionItem> items) {
        for (MethodNode method : classNode.getMethods()) {
            String name = method.getName();
            String key = name + "/" + method.getParameters().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && method.getLineNumber() >= 0
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem mi = new CompletionItem(name);
                mi.setKind(CompletionItemKind.Method);
                String retType = method.getReturnType() != null
                        ? method.getReturnType().getNameWithoutPackage() : "void";
                mi.setDetail(retType);
                applyAstMethodInsertText(mi, name, method.getParameters());
                mi.setFilterText(name);
                mi.setSortText("2_" + name);
                items.add(mi);
            }
        }
    }

    private void applyAstMethodInsertText(CompletionItem item, String name, Parameter[] params) {
        if (params != null && params.length > 0) {
            StringBuilder snippet = new StringBuilder(name).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) snippet.append(", ");
                snippet.append("${").append(i + 1).append(':')
                        .append(params[i].getName()).append('}');
            }
            snippet.append(')');
            item.setInsertText(snippet.toString());
            item.setInsertTextFormat(InsertTextFormat.Snippet);
        } else {
            item.setInsertText(name + "()");
        }
    }

    /**
     * AST-based dot completion fallback: resolve the expression type via the
     * Groovy AST and list members. Handles fields/properties from traits and
     * own class when JDT codeSelect and direct model lookup both fail.
     */
    private void addAstDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                       Position position, String exprName, String prefix,
                                       List<CompletionItem> items) {
        addAstDotCompletions(workingCopy, lspUri, position, exprName, prefix, items, null);
    }

    private void addAstDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                       Position position, String exprName, String prefix,
                                       List<CompletionItem> items,
                                       TypeResolutionContext typeResolutionContext) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        addAstDotCompletions(
                workingCopy, position, exprName, prefix, items, ast, typeResolutionContext);
    }

    private void addCachedAstDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                            Position position, String exprName, String prefix,
                                            List<CompletionItem> items) {
        addCachedAstDotCompletions(workingCopy, lspUri, position, exprName, prefix, items, null);
    }

    private void addCachedAstDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                            Position position, String exprName, String prefix,
                                            List<CompletionItem> items,
                                            TypeResolutionContext typeResolutionContext) {
        ModuleNode ast = resolveCachedAst(workingCopy, lspUri);
        addAstDotCompletions(
                workingCopy, position, exprName, prefix, items, ast, typeResolutionContext);
    }

    private void addAstDotCompletions(ICompilationUnit workingCopy, Position position,
                                      String exprName, String prefix,
                                      List<CompletionItem> items, ModuleNode ast) {
        addAstDotCompletions(workingCopy, position, exprName, prefix, items, ast, null);
    }

    private void addAstDotCompletions(ICompilationUnit workingCopy, Position position,
                                      String exprName, String prefix,
                                      List<CompletionItem> items, ModuleNode ast,
                                      TypeResolutionContext typeResolutionContext) {
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() >= 0) {
                ClassNode exprType = resolveAstExpressionType(classNode, ast, position, exprName);
                addAstResolvedTypeMembers(exprType, project, prefix, items, typeResolutionContext);
                if (!items.isEmpty()) return;
            }
        }

        // Script-level local variables: check the module statement block directly
        ClassNode scriptType = resolveLocalVariableTypeInBlock(
                ast.getStatementBlock(), exprName);
        addAstResolvedTypeMembers(scriptType, project, prefix, items, typeResolutionContext);
    }

    private ClassNode resolveAstExpressionType(ClassNode classNode, ModuleNode ast,
                                               Position position, String exprName) {
        ClassNode exprType = resolveClassMemberExpressionType(classNode, exprName);
        if (exprType != null) return exprType;

        exprType = resolveTraitMemberExpressionType(classNode, ast, exprName);
        if (exprType != null) return exprType;

        exprType = resolveScopedVariableType(ast, position, exprName);
        if (exprType != null) return exprType;

        // Check local variables inside method bodies
        return resolveLocalVariableTypeInClass(classNode, exprName);
    }

    private ClassNode resolveScopedVariableType(ModuleNode ast, Position position, String varName) {
        for (ScopedVariable variable : findScopedVisibleVariables(ast, position)) {
            if (varName.equals(variable.name())) {
                return variable.type();
            }
        }
        return null;
    }

    private List<ScopedVariable> findScopedVisibleVariables(ModuleNode ast, Position position) {
        if (ast == null || position == null) {
            return List.of();
        }

        for (ClassNode classNode : ast.getClasses()) {
            List<ScopedVariable> variables = findScopedVisibleVariables(classNode, position);
            if (!variables.isEmpty()) {
                return variables;
            }
        }

        BlockStatement statementBlock = ast.getStatementBlock();
        if (containsPosition(statementBlock, position)) {
            ScopedVariableCollector collector = new ScopedVariableCollector(position);
            statementBlock.visit(collector);
            return collector.getVariables();
        }
        return List.of();
    }

    private List<ScopedVariable> findScopedVisibleVariables(ClassNode classNode, Position position) {
        if (classNode == null || position == null) {
            return List.of();
        }

        for (MethodNode method : classNode.getMethods()) {
            List<ScopedVariable> variables = collectScopedVariablesFromCode(method, methodBodyBlock(method), position);
            if (!variables.isEmpty()) {
                return variables;
            }
        }
        for (ConstructorNode ctor : classNode.getDeclaredConstructors()) {
            List<ScopedVariable> variables = collectScopedVariablesFromCode(ctor, methodBodyBlock(ctor), position);
            if (!variables.isEmpty()) {
                return variables;
            }
        }
        return List.of();
    }

    private List<ScopedVariable> collectScopedVariablesFromCode(MethodNode method,
                                                                BlockStatement block,
                                                                Position position) {
        if (!containsPosition(method, position) && !containsPosition(block, position)) {
            return List.of();
        }
        ScopedVariableCollector collector = new ScopedVariableCollector(position);
        collector.addParameters(method.getParameters());
        if (block != null) {
            block.visit(collector);
        }
        return collector.getVariables();
    }

    /**
     * Walk all methods (including the synthetic {@code run()} in script classes)
     * looking for a local variable declaration matching {@code varName}, and
     * return the declared/inferred type.
     */
    private ClassNode resolveLocalVariableTypeInClass(ClassNode classNode, String varName) {
        for (MethodNode method : classNode.getMethods()) {
            ClassNode type = resolveLocalVariableTypeInBlock(
                    methodBodyBlock(method), varName);
            if (type != null) return type;
        }
        // Also check constructors
        for (var ctor : classNode.getDeclaredConstructors()) {
            ClassNode type = resolveLocalVariableTypeInBlock(
                    methodBodyBlock(ctor), varName);
            if (type != null) return type;
        }
        return null;
    }

    /**
     * Extract the block statement from a method's code.
     */
    private BlockStatement methodBodyBlock(MethodNode method) {
        Statement code = method.getCode();
        return (code instanceof BlockStatement block) ? block : null;
    }

    private boolean containsPosition(ASTNode node, Position position) {
        if (node == null || position == null || node.getLineNumber() < 1) {
            return false;
        }
        int line = position.getLine() + 1;
        int column = position.getCharacter() + 1;
        int startLine = node.getLineNumber();
        int endLine = node.getLastLineNumber() > 0 ? node.getLastLineNumber() : startLine;
        if (line < startLine || line > endLine) {
            return false;
        }
        if (line == startLine) {
            int startColumn = node.getColumnNumber() > 0 ? node.getColumnNumber() : 1;
            if (column < startColumn) {
                return false;
            }
        }
        if (line == endLine) {
            int endColumn = node.getLastColumnNumber() > 0 ? node.getLastColumnNumber() : Integer.MAX_VALUE;
            if (column > endColumn) {
                return false;
            }
        }
        return true;
    }

    /**
     * Search a {@link BlockStatement} for a {@code DeclarationExpression} whose
     * variable name matches {@code varName} and return the resolved type.
     */
    private ClassNode resolveLocalVariableTypeInBlock(BlockStatement block, String varName) {
        if (block == null) return null;

        for (Statement stmt : block.getStatements()) {
            if (!(stmt instanceof ExpressionStatement exprStmt)) continue;
            if (!(exprStmt.getExpression() instanceof DeclarationExpression decl)) continue;

            Expression left = decl.getLeftExpression();
            if (!(left instanceof VariableExpression varExpr)) continue;
            if (!varName.equals(varExpr.getName())) continue;

            // Found the declaration — determine the type
            Expression initializer = decl.getRightExpression();

            // "def x = new Foo()" → use the constructor call type directly
            if (initializer instanceof ConstructorCallExpression ctorCall) {
                return ctorCall.getType();
            }

            // Typed declaration (e.g. "String x = ...") → use the declared type
            ClassNode originType = varExpr.getOriginType();
            if (originType != null
                    && !"java.lang.Object".equals(originType.getName())) {
                return originType;
            }

            // Fallback: use the initializer expression's inferred type
            if (initializer != null) {
                return initializer.getType();
            }
        }
        return null;
    }

    private ClassNode resolveClassMemberExpressionType(ClassNode classNode, String exprName) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (exprName.equals(prop.getName())) {
                return prop.getType();
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (exprName.equals(field.getName())) {
                return field.getType();
            }
        }
        return null;
    }

    private ClassNode resolveTraitMemberExpressionType(ClassNode classNode, ModuleNode ast,
                                                       String exprName) {
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return null;
        }

        for (ClassNode ifaceRef : interfaces) {
            ClassNode traitNode = TraitMemberResolver.resolveTraitClassNode(ifaceRef, ast, documentManager);
            ClassNode resolvedType = resolveClassMemberExpressionType(traitNode, exprName);
            if (resolvedType != null) {
                return resolvedType;
            }
        }
        return null;
    }

    private void addAstResolvedTypeMembers(ClassNode exprType, IJavaProject project,
                                           String prefix, List<CompletionItem> items) {
        addAstResolvedTypeMembers(exprType, project, prefix, items, null);
    }

    private void addAstResolvedTypeMembers(ClassNode exprType, IJavaProject project,
                                           String prefix, List<CompletionItem> items,
                                           TypeResolutionContext typeResolutionContext) {
        if (exprType == null || project == null) {
            return;
        }
        try {
            IType jdtType = resolveAstTypeInProject(project, exprType, typeResolutionContext);
            if (jdtType != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] AST dot fallback resolved: " + jdtType.getFullyQualifiedName());
                addMembersOfType(jdtType, prefix, false, items);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] AST dot fallback type lookup failed", e);
        }
    }

    private IType resolveAstTypeInProject(IJavaProject project, ClassNode exprType)
            throws JavaModelException {
        return resolveAstTypeInProject(project, exprType, null);
    }

    private IType resolveAstTypeInProject(IJavaProject project, ClassNode exprType,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        IType jdtType = findTypeCached(project, exprType.getName(), typeResolutionContext);
        if (jdtType != null) {
            return jdtType;
        }
        String simpleName = exprType.getNameWithoutPackage();
        for (String pkg : GROOVY_AUTO_PACKAGES) {
            IType autoImported = findTypeCached(project, pkg + simpleName, typeResolutionContext);
            if (autoImported != null) {
                return autoImported;
            }
        }
        return null;
    }

    /**
     * Get the Groovy ModuleNode directly from a working copy via reflection.
     * Works even when the URI-based lookup in DocumentManager fails due to URI format mismatch.
     */
    private ModuleNode getModuleFromWorkingCopy(ICompilationUnit workingCopy) {
        if (workingCopy == null) return null;
        try {
            return ReflectionCache.getModuleNode(workingCopy);
        } catch (Exception e) {
            // Not a GroovyCompilationUnit or reflection failed
        }
        return null;
    }

    /**
     * Resolve an interface/trait ClassNode reference to a JDT IType in the project.
     */
    private IType resolveTraitType(ClassNode ifaceRef, ClassNode owner,
                                    ModuleNode module, IJavaProject project) {
        return resolveTraitType(ifaceRef, owner, module, project, null);
    }

    private IType resolveTraitType(ClassNode ifaceRef, ClassNode owner,
                                    ModuleNode module, IJavaProject project,
                                    TypeResolutionContext typeResolutionContext) {
        String ifaceSimple = ifaceRef.getNameWithoutPackage();
        if (ifaceSimple == null || ifaceSimple.isEmpty()) return null;

        String ownerPkg = normalizePackageName(owner != null ? owner.getPackageName() : null);
        if ((ownerPkg == null || ownerPkg.isEmpty()) && module != null) {
            ownerPkg = normalizePackageName(module.getPackageName());
        }

        try {
            IType resolved = resolveTraitTypeByFqn(ifaceRef, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByOwnerPackage(
                    ifaceSimple, ownerPkg, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByImports(
                    ifaceSimple, module, project, typeResolutionContext);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByAutoImports(
                    ifaceSimple, project, typeResolutionContext);
            if (resolved != null) return resolved;

            return searchTypeBySimpleName(project, ifaceSimple, ownerPkg, typeResolutionContext);
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    private IType resolveTraitTypeByFqn(ClassNode ifaceRef, IJavaProject project)
            throws JavaModelException {
        return resolveTraitTypeByFqn(ifaceRef, project, null);
    }

    private IType resolveTraitTypeByFqn(ClassNode ifaceRef, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        String fqn = ifaceRef.getName();
        return (fqn != null && fqn.contains("."))
                ? findTypeCached(project, fqn, typeResolutionContext)
                : null;
    }

    private IType resolveTraitTypeByOwnerPackage(String ifaceSimple, String ownerPkg,
                                                 IJavaProject project)
            throws JavaModelException {
        return resolveTraitTypeByOwnerPackage(ifaceSimple, ownerPkg, project, null);
    }

    private IType resolveTraitTypeByOwnerPackage(String ifaceSimple, String ownerPkg,
                                                 IJavaProject project,
                                                 TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        return (ownerPkg != null && !ownerPkg.isEmpty())
                ? findTypeCached(project, ownerPkg + "." + ifaceSimple, typeResolutionContext)
                : null;
    }

    private IType resolveTraitTypeByImports(String ifaceSimple, ModuleNode module,
                                            IJavaProject project) throws JavaModelException {
        return resolveTraitTypeByImports(ifaceSimple, module, project, null);
    }

    private IType resolveTraitTypeByImports(String ifaceSimple, ModuleNode module,
                                            IJavaProject project,
                                            TypeResolutionContext typeResolutionContext) throws JavaModelException {
        if (module == null) {
            return null;
        }
        for (org.codehaus.groovy.ast.ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && ifaceSimple.equals(impType.getNameWithoutPackage())) {
                IType resolved = findTypeCached(project, impType.getName(), typeResolutionContext);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        for (org.codehaus.groovy.ast.ImportNode starImport : module.getStarImports()) {
            String pkgName = normalizePackageName(starImport.getPackageName());
            if (pkgName != null && !pkgName.isEmpty()) {
                IType resolved = findTypeCached(
                        project, pkgName + "." + ifaceSimple, typeResolutionContext);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private IType resolveTraitTypeByAutoImports(String ifaceSimple, IJavaProject project)
            throws JavaModelException {
        return resolveTraitTypeByAutoImports(ifaceSimple, project, null);
    }

    private IType resolveTraitTypeByAutoImports(String ifaceSimple, IJavaProject project,
            TypeResolutionContext typeResolutionContext)
            throws JavaModelException {
        String[] autoPackages = {
            GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE, JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE
        };
        for (String pkg : autoPackages) {
            IType resolved = findTypeCached(project, pkg + ifaceSimple, typeResolutionContext);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String normalizePackageName(String packageName) {
        if (packageName == null) return null;
        String normalized = packageName.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private IType searchTypeBySimpleName(IJavaProject project,
                                          String simpleName,
                                          String preferredPackage) {
        return searchTypeBySimpleName(project, simpleName, preferredPackage, null);
    }

    private IType searchTypeBySimpleName(IJavaProject project,
                                          String simpleName,
                                          String preferredPackage,
                                          TypeResolutionContext typeResolutionContext) {
        if (simpleName == null || simpleName.isEmpty()) return null;

        final String normalizedPreferredPackage = normalizePackageName(preferredPackage);
        String cacheKey = exactTypeCacheKey(project,
                "search:" + normalizedPreferredPackage + ":" + simpleName);
        CachedResolvedType cached = getResolvedTypeFromCaches(typeResolutionContext, cacheKey);
        if (cached != null) {
            return cached.missing() ? null : cached.type();
        }
        final String[] preferredFqn = new String[1];
        final String[] firstFqn = new String[1];

        try {
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                    new IJavaElement[] { project },
                    IJavaSearchScope.SOURCES
                            | IJavaSearchScope.APPLICATION_LIBRARIES
                            | IJavaSearchScope.REFERENCED_PROJECTS);

            JdtSearchSupport.searchAllTypeNames(
                    null,
                    SearchPattern.R_PATTERN_MATCH,
                    simpleName.toCharArray(),
                    SearchPattern.R_EXACT_MATCH,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameRequestor() {
                        @Override
                        public void acceptType(int modifiers, char[] packageName,
                                               char[] simpleTypeName,
                                               char[][] enclosingTypeNames, String path) {
                            String foundSimpleName = new String(simpleTypeName);
                            if (!simpleName.equals(foundSimpleName)) {
                                return;
                            }

                            String pkg = new String(packageName);
                            String fqn = pkg.isEmpty() ? foundSimpleName : pkg + "." + foundSimpleName;

                            if (firstFqn[0] == null) {
                                firstFqn[0] = fqn;
                            }

                            if (normalizedPreferredPackage != null
                                    && !normalizedPreferredPackage.isEmpty()
                                    && normalizedPreferredPackage.equals(pkg)) {
                                preferredFqn[0] = fqn;
                            }
                        }
                    },
                    IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH,
                    null);

            String chosen = (preferredFqn[0] != null) ? preferredFqn[0] : firstFqn[0];
            IType resolved = (chosen != null)
                    ? findTypeCached(project, chosen, typeResolutionContext)
                    : null;
            cacheResolvedType(typeResolutionContext, cacheKey, resolved);
            return resolved;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Trait type search fallback failed for '"
                            + simpleName + "': " + e.getMessage());
        }

        cacheResolvedType(typeResolutionContext, cacheKey, null);
        return null;
    }

    // =========================================================================
    // Type Search Completion  (type names, annotations)
    // =========================================================================

    /**
     * Search for types matching the prefix using the JDT search engine.
     * <p>
     * Uses {@code FORCE_IMMEDIATE_SEARCH} so this never blocks waiting for
     * the JDT indexer. Results are cached (30s TTL) once the project's index
     * has been warmed by {@link #warmTypeIndex(IJavaProject)}.
     */
    private List<CompletionItem> getTypeCompletions(ICompilationUnit workingCopy,
                                 String uri,
                                 String content,
                                                     String prefix,
                                                     boolean annotationOnly) {
        return getTypeCompletions(workingCopy, uri, content, prefix, annotationOnly, null);
    }

    private List<CompletionItem> getTypeCompletions(ICompilationUnit workingCopy,
                                 String uri,
                                 String content,
                                                     String prefix,
                                                     boolean annotationOnly,
                                                     TypeResolutionContext typeResolutionContext) {
        List<CompletionItem> items = new ArrayList<>();
        if (prefix.isEmpty() && !annotationOnly) return items;
        if (Thread.currentThread().isInterrupted()) {
            return items;
        }

        try {
            IJavaProject project = findWorkingProject(workingCopy);
            if (project == null) return items;

            // ---- Cache lookup (only valid for warmed projects) ----
            String projectName = project.getElementName();
            String cacheKey = projectName + ":" + (annotationOnly ? "@" : "") + prefix;
            if (warmedProjects.contains(projectName)) {
                CachedTypeNames cached = typeNameCache.get(cacheKey);
                if (cached != null && !cached.isExpired()) {
                    return new ArrayList<>(cached.items());
                }
            }

            Set<String> existingImports = getExistingImports(uri, content);
            String currentPackage = getCurrentPackageName(content);
            int importInsertLine = findImportInsertLine(content);
            Set<String> seenSimpleNames = new HashSet<>();
                TypeSearchContext searchContext = new TypeSearchContext(
                    annotationOnly, currentPackage, existingImports, importInsertLine);

            if (annotationOnly) {
                addImportedAnnotationCompletions(
                        project, existingImports, prefix, items, seenSimpleNames, typeResolutionContext);
            }

            if (Thread.currentThread().isInterrupted()) {
                return items;
            }
            searchTypeNames(project, prefix, annotationOnly, seenSimpleNames,
                    searchContext, items, IJavaSearchScope.SOURCES, "5_", typeResolutionContext);
            if (Thread.currentThread().isInterrupted()) {
                return items;
            }
            searchTypeNames(project, prefix, annotationOnly, seenSimpleNames,
                    searchContext, items, IJavaSearchScope.APPLICATION_LIBRARIES, "6_",
                    typeResolutionContext);

            GroovyLanguageServerPlugin.logInfo("[completion] Type search for '"
                    + prefix + "': " + items.size() + " results");

            // Cache results if this project's index is fully warmed
            if (warmedProjects.contains(projectName)) {
                typeNameCache.put(cacheKey,
                        new CachedTypeNames(new ArrayList<>(items), System.currentTimeMillis()));
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Type search failed", e);
        }

        return items;
    }

    private boolean isSearchResultEligible(String simpleName, String fqn, int modifiers,
                                           boolean annotationOnly, IJavaProject project,
                                           Set<String> seenSimpleNames) {
        return isSearchResultEligible(
                simpleName, fqn, modifiers, annotationOnly, project, seenSimpleNames, null);
    }

    private boolean isSearchResultEligible(String simpleName, String fqn, int modifiers,
                                           boolean annotationOnly, IJavaProject project,
                                           Set<String> seenSimpleNames,
                                           TypeResolutionContext typeResolutionContext) {
        if (annotationOnly && !isAnnotationTypeCandidate(project, fqn, modifiers, typeResolutionContext)) {
            return false;
        }
        if (seenSimpleNames.contains(simpleName)) {
            return false;
        }
        seenSimpleNames.add(simpleName);
        return true;
    }

    private void searchTypeNames(IJavaProject project, String prefix, boolean annotationOnly,
                                 Set<String> seenSimpleNames, TypeSearchContext searchContext,
                                 List<CompletionItem> items,
                                 int scopeMask, String sortPrefix) throws org.eclipse.core.runtime.CoreException {
        searchTypeNames(project, prefix, annotationOnly, seenSimpleNames,
                searchContext, items, scopeMask, sortPrefix, null);
    }

    private void searchTypeNames(IJavaProject project, String prefix, boolean annotationOnly,
                                 Set<String> seenSimpleNames, TypeSearchContext searchContext,
                                 List<CompletionItem> items,
                                 int scopeMask, String sortPrefix,
                                 TypeResolutionContext typeResolutionContext)
            throws org.eclipse.core.runtime.CoreException {
        if (items.size() >= MAX_TYPE_RESULTS || Thread.currentThread().isInterrupted()) {
            return;
        }

        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                new IJavaElement[]{project}, scopeMask);
        IProgressMonitor searchMonitor = createSearchMonitor(() -> items.size() >= MAX_TYPE_RESULTS);
        JdtSearchSupport.searchAllTypeNames(
                null,
                SearchPattern.R_PATTERN_MATCH,
                (prefix.isEmpty() ? "*" : prefix).toCharArray(),
                prefix.isEmpty() ? SearchPattern.R_PATTERN_MATCH : SearchPattern.R_PREFIX_MATCH,
                IJavaSearchConstants.TYPE,
                scope,
                new TypeNameRequestor() {
                    @Override
                    public void acceptType(int modifiers, char[] packageName,
                                           char[] simpleTypeName,
                                           char[][] enclosingTypeNames, String path) {
                        if (items.size() >= MAX_TYPE_RESULTS || Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        String simpleName = new String(simpleTypeName);
                        String pkg = new String(packageName);
                        String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;

                        if (isSearchResultEligible(simpleName, fqn, modifiers, annotationOnly,
                                project, seenSimpleNames, typeResolutionContext)) {
                            items.add(buildTypeSearchItem(simpleName, pkg, modifiers,
                                    fqn, searchContext, sortPrefix));
                        }
                    }
                },
                IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH,
                searchMonitor);
    }

    private IProgressMonitor createSearchMonitor(java.util.function.BooleanSupplier additionalCancelCondition) {
        return new IProgressMonitor() {
            private volatile boolean cancelled = false;

            @Override public void beginTask(String name, int totalWork) {}
            @Override public void done() {}
            @Override public void internalWorked(double work) {}
            @Override public void setTaskName(String name) {}
            @Override public void subTask(String name) {}
            @Override public void worked(int work) {}

            @Override
            public boolean isCanceled() {
                return cancelled
                        || Thread.currentThread().isInterrupted()
                        || (additionalCancelCondition != null && additionalCancelCondition.getAsBoolean());
            }

            @Override
            public void setCanceled(boolean value) {
                cancelled = value;
            }
        };
    }

    private CompletionItem buildTypeSearchItem(String simpleName, String pkg, int modifiers,
                                               String fqn,
                                               TypeSearchContext searchContext) {
        return buildTypeSearchItem(simpleName, pkg, modifiers, fqn, searchContext,
                (searchContext.annotationOnly ? "4_" : "5_"));
    }

    private CompletionItem buildTypeSearchItem(String simpleName, String pkg, int modifiers,
                                               String fqn,
                                               TypeSearchContext searchContext,
                                               String sortPrefix) {
        CompletionItem item = new CompletionItem(simpleName);
        item.setKind(resolveTypeKind(modifiers));
        item.setDetail(pkg.isEmpty() ? simpleName : pkg + "." + simpleName);
        item.setInsertText(simpleName);
        item.setFilterText(simpleName);
        if (shouldAutoImportType(fqn, pkg, searchContext.currentPackage,
                searchContext.existingImports)) {
            item.setAdditionalTextEdits(
                    java.util.Collections.singletonList(createImportEdit(searchContext.importInsertLine, fqn)));
        }
        item.setSortText(sortPrefix + simpleName);
        return item;
    }

    private CompletionItemKind resolveTypeKind(int modifiers) {
        if (Flags.isInterface(modifiers)) {
            return CompletionItemKind.Interface;
        }
        if (Flags.isEnum(modifiers)) {
            return CompletionItemKind.Enum;
        }
        return CompletionItemKind.Class;
    }

    private TextEdit createImportEdit(int importInsertLine, String fqn) {
        return new TextEdit(
                new org.eclipse.lsp4j.Range(
                        new Position(importInsertLine, 0),
                        new Position(importInsertLine, 0)),
                IMPORT_PREFIX + fqn + "\n");
    }

    private void addImportedAnnotationCompletions(IJavaProject project,
                                                  Set<String> existingImports,
                                                  String prefix,
                                                  List<CompletionItem> items,
                                                  Set<String> seenSimpleNames) {
        addImportedAnnotationCompletions(project, existingImports, prefix, items, seenSimpleNames, null);
    }

    private void addImportedAnnotationCompletions(IJavaProject project,
                                                  Set<String> existingImports,
                                                  String prefix,
                                                  List<CompletionItem> items,
                                                  Set<String> seenSimpleNames,
                                                  TypeResolutionContext typeResolutionContext) {
        for (String fqn : existingImports) {
            addImportedAnnotationCompletion(
                    project, fqn, prefix, items, seenSimpleNames, typeResolutionContext);
        }
    }

    private void addImportedAnnotationCompletion(IJavaProject project,
                                                 String fqn,
                                                 String prefix,
                                                 List<CompletionItem> items,
                                                 Set<String> seenSimpleNames) {
        addImportedAnnotationCompletion(project, fqn, prefix, items, seenSimpleNames, null);
    }

    private void addImportedAnnotationCompletion(IJavaProject project,
                                                 String fqn,
                                                 String prefix,
                                                 List<CompletionItem> items,
                                                 Set<String> seenSimpleNames,
                                                 TypeResolutionContext typeResolutionContext) {
        int lastDot = fqn.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        if (!matchesPrefix(simpleName, prefix) || !isAnnotationImport(project, fqn, typeResolutionContext)) {
            return;
        }
        if (!seenSimpleNames.add(simpleName)) {
            return;
        }

        CompletionItem item = new CompletionItem(simpleName);
        item.setKind(CompletionItemKind.Class);
        item.setDetail(fqn);
        item.setInsertText(simpleName);
        item.setFilterText(simpleName);
        item.setSortText("3_" + simpleName);
        items.add(item);
    }

    private boolean isAnnotationImport(IJavaProject project, String fqn) {
        return isAnnotationImport(project, fqn, null);
    }

    private boolean isAnnotationImport(IJavaProject project, String fqn,
            TypeResolutionContext typeResolutionContext) {
        try {
            IType type = findTypeCached(project, fqn, typeResolutionContext);
            return type != null && type.exists() && type.isAnnotation();
        } catch (JavaModelException e) {
            return false;
        }
    }

    private boolean isAnnotationTypeCandidate(IJavaProject project, String fqn, int modifiers) {
        return isAnnotationTypeCandidate(project, fqn, modifiers, null);
    }

    private boolean isAnnotationTypeCandidate(IJavaProject project, String fqn, int modifiers,
            TypeResolutionContext typeResolutionContext) {
        if (Flags.isAnnotation(modifiers)) {
            return true;
        }

        try {
            IType type = findTypeCached(project, fqn, typeResolutionContext);
            return type != null && type.exists() && type.isAnnotation();
        } catch (JavaModelException e) {
            return false;
        }
    }

    // =========================================================================
    // Keyword Completion
    // =========================================================================

    /** Groovy keywords for completion. */
    private static final String[] GROOVY_KEYWORDS = {
        "abstract", "as", "assert", "boolean", "break", "byte",
        "case", "catch", "char", "class", "const", "continue",
        "def", "default", "do", "double", "else", "enum", "extends",
        "false", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "in", "instanceof", "int",
        "interface", "long", "native", "new", "null",
        "package", "private", "protected", "public", "return",
        "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "trait", "transient", "true", "try",
        "var", "void", "volatile", "while",
    };

    private List<CompletionItem> getKeywordCompletions(String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        for (String keyword : GROOVY_KEYWORDS) {
            if (prefix.isEmpty() || keyword.startsWith(prefix)) {
                CompletionItem item = new CompletionItem(keyword);
                item.setKind(CompletionItemKind.Keyword);
                item.setInsertText(keyword);
                item.setSortText("9_" + keyword);
                items.add(item);
            }
        }
        return items;
    }

    // =========================================================================
    // Fallback Completion  (no JDT working copy)
    // =========================================================================

    /**
     * Provide fallback completions using Groovy keywords and the Groovy AST
     * when no JDT working copy is available.
     */
    private List<CompletionItem> getFallbackCompletions(String uri, Position position) {
        List<CompletionItem> items = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) return items;

        int offset = positionToOffset(content, position);
        String prefix = extractPrefix(content, offset);
        int prefixStart = offset - prefix.length();
        boolean isAnnotationCompletion = isAnnotationContext(content, offset, prefixStart);

        if (isAnnotationCompletion) {
            // No JDT available: avoid suggesting non-annotation identifiers/keywords in '@' context.
            return items;
        }

        // Keywords
        if (!prefix.isEmpty()) {
            items.addAll(getKeywordCompletions(prefix));
        }

        // Identifiers from the Groovy AST
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast != null) {
            for (ScopedVariable variable : findScopedVisibleVariables(ast, position)) {
                String name = variable.name();
                if (!matchesPrefix(name, prefix)) {
                    continue;
                }
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Variable);
                item.setDetail(variable.type() != null
                        ? variable.type().getNameWithoutPackage() : OBJECT_TYPE_NAME);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("0_" + name);
                items.add(item);
            }
            for (ClassNode classNode : ast.getClasses()) {
                addClassCompletions(classNode, prefix, items);
            }
        }

        return items;
    }

    /**
     * Add completion items for members of a Groovy class node (fallback mode).
     */
    private void addClassCompletions(ClassNode classNode, String prefix,
                                      List<CompletionItem> items) {
        addFallbackClassNameCompletion(classNode, prefix, items);
        addFallbackMethodCompletions(classNode, prefix, items);
        addFallbackFieldCompletions(classNode, prefix, items);
        addFallbackPropertyCompletions(classNode, prefix, items);
        addTraitMemberCompletions(classNode, prefix, items);
    }

    private void addFallbackClassNameCompletion(ClassNode classNode, String prefix,
                                                List<CompletionItem> items) {
        String className = classNode.getNameWithoutPackage();
        if (className != null && (prefix.isEmpty() || className.startsWith(prefix))) {
            CompletionItem item = new CompletionItem(className);
            item.setKind(CompletionItemKind.Class);
            item.setDetail(classNode.getName());
            item.setSortText("2_" + className);
            items.add(item);
        }
    }

    private void addFallbackMethodCompletions(ClassNode classNode, String prefix,
                                              List<CompletionItem> items) {
        for (MethodNode method : classNode.getMethods()) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && !name.contains("$") && !name.startsWith("__")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage());
                applyAstMethodInsertText(item, name, method.getParameters());
                item.setSortText("3_" + name);
                items.add(item);
            }
        }
    }

    private void addFallbackFieldCompletions(ClassNode classNode, String prefix,
                                             List<CompletionItem> items) {
        for (FieldNode field : classNode.getFields()) {
            String name = field.getName();
            if (name != null && !name.startsWith("$")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(field.getType().getNameWithoutPackage());
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    private void addFallbackPropertyCompletions(ClassNode classNode, String prefix,
                                                List<CompletionItem> items) {
        for (PropertyNode prop : classNode.getProperties()) {
            String name = prop.getName();
            if (name != null && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                item.setDetail(prop.getType().getNameWithoutPackage());
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    /**
     * Add completion items for members inherited from traits/interfaces.
     */
    private void addTraitMemberCompletions(ClassNode classNode, String prefix,
                                            List<CompletionItem> items) {
        ModuleNode module = classNode.getModule();
        addTraitMethodCompletions(classNode, module, prefix, items);
        addTraitFieldCompletions(classNode, module, prefix, items);
        addTraitPropertyCompletions(classNode, module, prefix, items);
    }

    private void addTraitMethodCompletions(ClassNode classNode,
                                           ModuleNode module,
                                           String prefix,
                                           List<CompletionItem> items) {
        for (MethodNode method : TraitMemberResolver.collectTraitMethods(classNode, module, documentManager)) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                applyAstMethodInsertText(item, name, method.getParameters());
                item.setSortText("3_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitFieldCompletions(ClassNode classNode,
                                          ModuleNode module,
                                          String prefix,
                                          List<CompletionItem> items) {
        for (FieldNode field : TraitMemberResolver.collectTraitFields(classNode, module, documentManager)) {
            String name = field.getName();
            if (name != null && !name.startsWith("$")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(field.getType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitPropertyCompletions(ClassNode classNode,
                                             ModuleNode module,
                                             String prefix,
                                             List<CompletionItem> items) {
        for (PropertyNode prop : TraitMemberResolver.collectTraitProperties(classNode, module, documentManager)) {
            String name = prop.getName();
            if (name != null && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                item.setDetail(prop.getType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Case-insensitive prefix match. */
    private boolean matchesPrefix(String name, String prefix) {
        if (prefix.isEmpty()) return true;
        return name.toLowerCase().startsWith(prefix.toLowerCase());
    }

    /**
     * Extract the identifier prefix being typed at the given offset.
     */
    private String extractPrefix(String content, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        return content.substring(start, offset);
    }

    private Set<String> getExistingImports(String uri, String content) {
        Set<String> imports = new HashSet<>();
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast != null) {
            for (ImportNode imp : ast.getImports()) {
                if (imp.getType() != null) {
                    imports.add(imp.getType().getName());
                }
            }
        }

        imports.addAll(parseImportsFromContent(content));

        return imports;
    }

    private Set<String> parseImportsFromContent(String content) {
        Set<String> imports = new HashSet<>();
        if (content == null || content.isEmpty()) {
            return imports;
        }

        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            String extractedImport = extractNonStaticImport(line.trim());
            if (extractedImport != null) {
                imports.add(extractedImport);
            }
        }

        return imports;
    }

    private String extractNonStaticImport(String trimmedLine) {
        if (!trimmedLine.startsWith(IMPORT_PREFIX)) {
            return null;
        }

        String importTarget = trimmedLine.substring(IMPORT_PREFIX.length()).trim();
        if (importTarget.startsWith("static ")) {
            return null;
        }
        if (importTarget.endsWith(";")) {
            importTarget = importTarget.substring(0, importTarget.length() - 1).trim();
        }
        if (importTarget.isEmpty() || importTarget.indexOf('.') < 0 || importTarget.endsWith(".*")) {
            return null;
        }
        return importTarget;
    }

    private boolean shouldAutoImportType(String fqn, String packageName,
                                         String currentPackage, Set<String> existingImports) {
        return fqn != null
                && !fqn.isEmpty()
                && packageName != null
                && !packageName.isEmpty()
                && !existingImports.contains(fqn)
                && !packageName.equals(currentPackage)
                && !isAutoImportedPackage(packageName);
    }

    private boolean isAutoImportedPackage(String packageName) {
        for (String autoPkg : GROOVY_AUTO_PACKAGES) {
            String normalized = autoPkg.endsWith(".")
                    ? autoPkg.substring(0, autoPkg.length() - 1)
                    : autoPkg;
            if (normalized.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private String getCurrentPackageName(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(PACKAGE_PREFIX)) {
                String pkg = trimmed.substring(PACKAGE_PREFIX.length()).trim();
                if (pkg.endsWith(";")) {
                    pkg = pkg.substring(0, pkg.length() - 1).trim();
                }
                return pkg;
            }
            if (trimmed.startsWith(IMPORT_PREFIX) || trimmed.startsWith("class ")
                    || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")
                    || trimmed.startsWith("trait ")) {
                break;
            }
        }

        return "";
    }

    private int findImportInsertLine(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        String[] lines = content.split("\n", -1);
        int lastImportLine = -1;
        int packageLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(PACKAGE_PREFIX)) {
                packageLine = i;
            }
            if (trimmed.startsWith(IMPORT_PREFIX)) {
                lastImportLine = i;
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("interface ")
                    || trimmed.startsWith("enum ") || trimmed.startsWith("trait ")
                    || trimmed.startsWith("@") || trimmed.startsWith("def ")
                    || trimmed.startsWith("public ") || trimmed.startsWith("abstract ")
                    || trimmed.startsWith("final ")) {
                break;
            }
        }

        if (lastImportLine >= 0) {
            return lastImportLine + 1;
        }
        if (packageLine >= 0) {
            return packageLine + 2;
        }
        return 0;
    }

    /**
     * Detect whether completion is requested in annotation context (around '@').
     * Handles cursor positions both after and on the '@' character.
     */
    private boolean isAnnotationContext(String content, int offset, int prefixStart) {
        return (prefixStart > 0 && content.charAt(prefixStart - 1) == '@')
                || (offset > 0 && content.charAt(offset - 1) == '@')
                || (prefixStart >= 0 && prefixStart < content.length()
                        && content.charAt(prefixStart) == '@');
    }

    /**
     * Convert an LSP position (line/column) to a character offset.
     */
    private int positionToOffset(String content, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < content.length() && line < position.getLine()) {
            if (content.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), content.length());
    }
}
