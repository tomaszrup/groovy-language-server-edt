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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Provides code lens with reference counts for Groovy documents.
 * <p>
 * Displays the number of references above each type and method declaration.
 * Uses JDT's {@link SearchEngine} to count references. Code lenses are
 * resolved lazily — the initial {@code codeLens} request returns positions
 * only, and {@code resolveCodeLens} performs the actual reference count.
 */
public class CodeLensProvider {

    private static final String HANDLE_ID_KEY = "handleId";
    private static final String URI_KEY = "uri";
    private static final String SHOW_REFERENCES_COMMAND = "groovy.showReferences";
    private static final String ZERO_REFERENCES_COMMAND = "groovy.codeLensNoop";
    private static final String SHOW_OUTPUT_COMMAND = "groovy.showOutputChannel";
    private static final String ZERO_REFERENCES_TITLE = "0 references";
    private static final String REFERENCES_UNAVAILABLE_TITLE = "References unavailable";
    private static final Set<String> SPOCK_LIFECYCLE_METHODS =
            Set.of("setup", "cleanup", "setupSpec", "cleanupSpec");
    private static final Set<String> SPOCK_LABELS =
            Set.of("given", "when", "then", "expect", "where", "and");
    private static final int RESOLVE_CACHE_SIZE = 300;

    private final DocumentManager documentManager;

    /**
     * LRU cache for resolved reference locations, keyed by JDT handle ID.
     * Avoids redundant {@link SearchEngine} searches when the user scrolls
     * away and back.  Invalidated per-URI via {@link #invalidateCodeLensCache(String)}.
     */
    @SuppressWarnings("serial")
    private final Map<String, List<Location>> resolveCache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Location>> eldest) {
                    return size() > RESOLVE_CACHE_SIZE;
                }
            });

    public CodeLensProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Invalidate cached resolve results for the given document URI.
     * Called from {@code didChange} so that stale counts are never served.
     */
    public void invalidateCodeLensCache(String uri) {
        if (uri == null) return;
        // Handle IDs produced by JDT contain the project-relative path;
        // a simple URI suffix check covers the common case.
        resolveCache.entrySet().removeIf(
                entry -> entry.getKey().contains(uri) || uri.contains(entry.getKey()));
    }

    /**
     * Invalidate the entire resolve cache.  Called when a file change may
     * affect reference counts in <em>other</em> files (e.g. editing file B
     * changes the count shown in file A's code lens).
     */
    public void invalidateAllResolveCache() {
        resolveCache.clear();
    }

    /**
     * Generate code lenses for all types and methods in the document.
     * <p>
     * Lenses are returned <em>unresolved</em> — only positions and handle
     * identifiers are populated.  The expensive reference count is deferred
     * to {@link #resolveCodeLens(CodeLens)} which VS Code calls lazily as
     * each lens scrolls into view.  We intentionally do not pre-filter by
     * reference count here: probing each declaration up front turned a single
     * code-lens refresh into O(n) search requests for the whole file.
     */
    public List<CodeLens> getCodeLenses(CodeLensParams params) {
        String uri = params.getTextDocument().getUri();
        List<CodeLens> lenses = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return lenses;
        }

        String content = documentManager.getContent(uri);
        if (content == null) {
            return lenses;
        }

        try {
            IType[] types = workingCopy.getTypes();
            for (IType type : types) {
                addCodeLensesForType(type, content, uri, lenses);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("CodeLens generation failed for " + uri, e);
        }

        return lenses;
    }

    /**
     * Resolve a code lens by performing the (expensive) reference count.
     * Called lazily by VS Code when the lens becomes visible in the viewport.
     */
    public CodeLens resolveCodeLens(CodeLens codeLens) {
        // Already resolved?
        if (codeLens.getCommand() != null) {
            return codeLens;
        }

        if (codeLens.getData() == null) {
            codeLens.setCommand(fallbackCommand());
            return codeLens;
        }

        try {
            JsonElement dataElement = (JsonElement) codeLens.getData();
            String handleId = null;
            String uri = null;
            if (dataElement.isJsonObject()) {
                JsonObject obj = dataElement.getAsJsonObject();
                if (obj.has(HANDLE_ID_KEY)) {
                    handleId = obj.get(HANDLE_ID_KEY).getAsString();
                }
                if (obj.has(URI_KEY)) {
                    uri = obj.get(URI_KEY).getAsString();
                }
            }

            if (handleId == null) {
                codeLens.setCommand(fallbackCommand());
                return codeLens;
            }

            IJavaElement element = JavaCore.create(handleId);
            if (element == null || !element.exists()) {
                codeLens.setCommand(fallbackCommand());
                return codeLens;
            }

            // Check cache first to avoid redundant searches
            List<Location> cached = resolveCache.get(handleId);
            List<Location> locations;
            if (cached != null) {
                locations = cached;
            } else {
                locations = findReferenceLocations(element, uri);
                resolveCache.put(handleId, locations);
            }
            int count = locations.size();

            if (count == 0) {
                codeLens.setCommand(zeroReferencesCommand());
                return codeLens;
            }

            String label = count == 1 ? "1 reference" : count + " references";

            Command cmd = new Command(label, SHOW_REFERENCES_COMMAND);
            if (uri != null) {
                cmd.setArguments(List.of(uri, codeLens.getRange().getStart(), locations));
            }
            codeLens.setCommand(cmd);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("CodeLens resolve failed", e);
            if (codeLens.getCommand() == null) {
                codeLens.setCommand(fallbackCommand());
            }
        }

        return codeLens;
    }

    private static Command fallbackCommand() {
        return new Command(REFERENCES_UNAVAILABLE_TITLE, SHOW_OUTPUT_COMMAND);
    }

    private static Command zeroReferencesCommand() {
        return new Command(ZERO_REFERENCES_TITLE, ZERO_REFERENCES_COMMAND);
    }

    // ---- Helpers ----

    private void addCodeLensesForType(IType type, String content, String uri, List<CodeLens> lenses)
            throws JavaModelException {
        addCodeLensesForType(type, content, uri, lenses, documentManager.getCachedGroovyAST(uri));
    }

    private void addCodeLensesForType(
            IType type,
            String content,
            String uri,
            List<CodeLens> lenses,
            ModuleNode cachedAst) throws JavaModelException {
        CodeLens typeLens = createUnresolvedCodeLens(type, content);
        if (typeLens != null) {
            lenses.add(typeLens);
        }

        for (IMethod method : type.getMethods()) {
            if (shouldSkipMethodCodeLens(method, content, uri, cachedAst)) {
                continue;
            }
            CodeLens methodLens = createUnresolvedCodeLens(method, content);
            if (methodLens != null) {
                lenses.add(methodLens);
            }
        }

        for (IType innerType : type.getTypes()) {
            addCodeLensesForType(innerType, content, uri, lenses, cachedAst);
        }
    }

    boolean shouldSkipMethodCodeLens(IMethod method, String uri) {
        return shouldSkipMethodCodeLens(method, documentManager.getContent(uri), uri,
                documentManager.getCachedGroovyAST(uri));
    }

    private boolean shouldSkipMethodCodeLens(
            IMethod method, String content, String uri, ModuleNode cachedAst) {
        try {
            if (method == null || uri == null || !isSpockSpecification(method.getDeclaringType())) {
                return false;
            }

            String methodName = method.getElementName();
            if (isSpockLifecycleMethod(methodName) || !isValidIdentifier(methodName)) {
                return true;
            }

            return isIdentifierNamedSpockFeatureMethod(method, content, cachedAst);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isSpockSpecification(IType type) {
        try {
            if (type == null) {
                return false;
            }
            String superclassName = type.getSuperclassName();
            return "Specification".equals(superclassName)
                    || "spock.lang.Specification".equals(superclassName);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isSpockLifecycleMethod(String methodName) {
        return methodName != null && SPOCK_LIFECYCLE_METHODS.contains(methodName);
    }

    boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isIdentifierNamedSpockFeatureMethod(
            IMethod method, String content, ModuleNode cachedAst) {
        if (cachedAst == null || content == null) {
            return false;
        }

        MethodNode astMethod = findMatchingAstMethod(cachedAst, method, content);
        if (astMethod == null || astMethod.getCode() == null) {
            return false;
        }

        return containsSpockLabels(astMethod, cachedAst);
    }

    private MethodNode findMatchingAstMethod(ModuleNode module, IMethod method, String content) {
        if (module == null || method == null || content == null) {
            return null;
        }

        ClassNode declaringClass = findMatchingClassNode(module, safeTypeName(method));
        if (declaringClass == null) {
            return null;
        }

        int targetLine = methodNameLine(method, content);
        if (targetLine < 0) {
            return null;
        }

        for (MethodNode candidate : declaringClass.getDeclaredMethods(method.getElementName())) {
            if (candidate.getLineNumber() == targetLine) {
                return candidate;
            }
        }
        return null;
    }

    private ClassNode findMatchingClassNode(ModuleNode module, String typeName) {
        if (module == null || typeName == null || typeName.isBlank()) {
            return null;
        }

        for (ClassNode classNode : module.getClasses()) {
            ClassNode match = findMatchingClassNode(classNode, typeName);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private ClassNode findMatchingClassNode(ClassNode classNode, String typeName) {
        if (classNode == null) {
            return null;
        }
        if (matchesTypeName(classNode, typeName)) {
            return classNode;
        }

        java.util.Iterator<InnerClassNode> innerIter = classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode match = findMatchingClassNode(innerIter.next(), typeName);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean matchesTypeName(ClassNode classNode, String typeName) {
        String astName = classNode.getName();
        if (typeName.equals(astName)) {
            return true;
        }

        String astNameWithoutPackage = classNode.getNameWithoutPackage();
        if (typeName.equals(astNameWithoutPackage)) {
            return true;
        }

        int dollarIdx = astNameWithoutPackage.lastIndexOf('$');
        return dollarIdx >= 0 && dollarIdx < astNameWithoutPackage.length() - 1
                && typeName.equals(astNameWithoutPackage.substring(dollarIdx + 1));
    }

    private String safeTypeName(IMethod method) {
        try {
            IType declaringType = method.getDeclaringType();
            if (declaringType == null) {
                return null;
            }

            String fullName = declaringType.getFullyQualifiedName('$');
            if (fullName != null && !fullName.isBlank()) {
                return fullName;
            }
            return declaringType.getElementName();
        } catch (Exception e) {
            return null;
        }
    }

    private int methodNameLine(IMethod method, String content) {
        try {
            ISourceRange nameRange = method.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return -1;
            }
            return offsetToPosition(content, nameRange.getOffset()).getLine() + 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean containsSpockLabels(MethodNode methodNode, ModuleNode module) {
        if (methodNode == null || methodNode.getCode() == null) {
            return false;
        }

        boolean[] found = {false};
        methodNode.getCode().visit(new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module != null ? module.getContext() : null;
            }

            @Override
            public void visitBlockStatement(BlockStatement block) {
                if (found[0] || block == null) {
                    return;
                }

                for (Statement stmt : block.getStatements()) {
                    List<String> labels = stmt.getStatementLabels();
                    if (labels == null) {
                        continue;
                    }
                    for (String label : labels) {
                        if (SPOCK_LABELS.contains(label)) {
                            found[0] = true;
                            return;
                        }
                    }
                }
                super.visitBlockStatement(block);
            }
        });
        return found[0];
    }

    CodeLens createCodeLensIfReferenced(IJavaElement element, String content, String uri) {
        if (!hasReferences(element, uri)) {
            return null;
        }
        return createUnresolvedCodeLens(element, content);
    }

    boolean hasReferences(IJavaElement element, String uri) {
        return ReferenceSearchHelper.hasReferences(element, uri, documentManager);
    }

    /**
     * Create an unresolved code lens containing only the position and
     * a handle identifier for later resolution.  No workspace search
     * is performed here — resolution is deferred to
     * {@link #resolveCodeLens(CodeLens)}.
     */
    CodeLens createUnresolvedCodeLens(IJavaElement element, String content) {
        try {
            if (!(element instanceof ISourceReference sourceRef)) {
                return null;
            }

            ISourceRange nameRange = sourceRef.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return null;
            }

            Position start = offsetToPosition(content, nameRange.getOffset());
            Range range = new Range(start, start);

            CodeLens lens = new CodeLens();
            lens.setRange(range);
            // Command left null — resolved lazily via resolveCodeLens

            JsonObject data = new JsonObject();
            data.addProperty(HANDLE_ID_KEY, element.getHandleIdentifier());
            data.addProperty(URI_KEY, element.getResource() != null
                    ? element.getResource().getLocationURI().toString() : "");
            lens.setData(data);

            return lens;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to create code lens for " + element.getElementName(), e);
            return null;
        }
    }

    /**
     * Find reference locations for an element using JDT SearchEngine.
     * Returns a list of LSP {@link Location} objects for the peek view.
     *
     * @param element the element to search references for
     * @param uri     the URI of the document containing the element (used for
     *                test-scope narrowing)
     */
    private List<Location> findReferenceLocations(IJavaElement element, String uri) {
        return ReferenceSearchHelper.findReferenceLocations(element, uri, documentManager);
    }

    Position offsetToPosition(String content, int offset) {
        return PositionUtils.offsetToPosition(content, offset);
    }
}
