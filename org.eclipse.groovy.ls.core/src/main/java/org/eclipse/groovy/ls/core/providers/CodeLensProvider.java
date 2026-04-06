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

import org.codehaus.groovy.ast.ModuleNode;
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
    private final CodeLensSpockSupport spockSupport;

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
        this.spockSupport = new CodeLensSpockSupport(documentManager, SPOCK_LIFECYCLE_METHODS, SPOCK_LABELS);
    }

    /**
     * Invalidate cached resolve results for the given document URI.
     * Called from {@code didChange} so that stale counts are never served.
     */
    public void invalidateCodeLensCache(String uri) {
        if (uri == null) return;
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
        if (codeLens.getCommand() != null) {
            return codeLens;
        }

        if (codeLens.getData() == null) {
            codeLens.setCommand(fallbackCommand());
            return codeLens;
        }

        try {
            ResolvedLensData resolved = parseLensData(codeLens);
            if (resolved == null) {
                codeLens.setCommand(fallbackCommand());
                return codeLens;
            }

            IJavaElement element = resolveLensElement(resolved.handleId());
            if (element == null || !element.exists()) {
                codeLens.setCommand(fallbackCommand());
                return codeLens;
            }

            codeLens.setCommand(buildResolvedCommand(codeLens, element, resolved.uri()));
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

    private ResolvedLensData parseLensData(CodeLens codeLens) {
        JsonElement dataElement = (JsonElement) codeLens.getData();
        if (!dataElement.isJsonObject()) {
            return null;
        }

        JsonObject obj = dataElement.getAsJsonObject();
        String handleId = obj.has(HANDLE_ID_KEY) ? obj.get(HANDLE_ID_KEY).getAsString() : null;
        if (handleId == null) {
            return null;
        }
        String uri = obj.has(URI_KEY) ? obj.get(URI_KEY).getAsString() : null;
        return new ResolvedLensData(handleId, uri);
    }

    private IJavaElement resolveLensElement(String handleId) {
        IJavaElement element = JavaCore.create(handleId);
        IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
        return remappedElement != null ? remappedElement : element;
    }

    private Command buildResolvedCommand(CodeLens codeLens, IJavaElement element, String uri) {
        List<Location> locations = locationsFor(element, uri);
        int count = locations.size();
        if (count == 0) {
            return zeroReferencesCommand();
        }

        String label = count == 1 ? "1 reference" : count + " references";
        Command command = new Command(label, SHOW_REFERENCES_COMMAND);
        if (uri != null) {
            command.setArguments(List.of(uri, codeLens.getRange().getStart(), locations));
        }
        return command;
    }

    private List<Location> locationsFor(IJavaElement element, String uri) {
        String cacheKey = element.getHandleIdentifier();
        List<Location> cached = resolveCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<Location> locations = findReferenceLocations(element, uri);
        resolveCache.put(cacheKey, locations);
        return locations;
    }

    private record ResolvedLensData(String handleId, String uri) {
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
            addCodeLensIfAbsent(lenses, typeLens);
        }

        for (IMethod method : type.getMethods()) {
            if (shouldSkipMethodCodeLens(method, content, uri, cachedAst)) {
                continue;
            }
            CodeLens methodLens = createUnresolvedCodeLens(method, content);
            if (methodLens != null) {
                addCodeLensIfAbsent(lenses, methodLens);
            }
        }

        for (IType innerType : type.getTypes()) {
            addCodeLensesForType(innerType, content, uri, lenses, cachedAst);
        }
    }

    boolean shouldSkipMethodCodeLens(IMethod method, String uri) {
        return spockSupport.shouldSkipMethodCodeLens(method, uri);
    }

    private boolean shouldSkipMethodCodeLens(
            IMethod method, String content, String uri, ModuleNode cachedAst) {
        return spockSupport.shouldSkipMethodCodeLens(method, content, uri, cachedAst);
    }

    boolean isSpockSpecification(IType type) {
        return spockSupport.isSpockSpecification(type);
    }

    boolean isSpockLifecycleMethod(String methodName) {
        return spockSupport.isSpockLifecycleMethod(methodName);
    }

    boolean isValidIdentifier(String name) {
        return spockSupport.isValidIdentifier(name);
    }

    CodeLens createCodeLensIfReferenced(IJavaElement element, String content, String uri) {
        IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
        if (remappedElement != null) {
            element = remappedElement;
        }
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
            IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
            if (remappedElement != null) {
                element = remappedElement;
            }
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
        String uri = documentManager.resolveElementUri(element);
        data.addProperty(URI_KEY, uri != null ? uri : "");
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

    private void addCodeLensIfAbsent(List<CodeLens> lenses, CodeLens candidate) {
        Range candidateRange = candidate.getRange();
        if (candidateRange == null || candidateRange.getStart() == null) {
            lenses.add(candidate);
            return;
        }

        for (CodeLens existing : lenses) {
            Range existingRange = existing.getRange();
            if (existingRange == null || existingRange.getStart() == null) {
                continue;
            }
            if (existingRange.getStart().equals(candidateRange.getStart())) {
                return;
            }
        }

        lenses.add(candidate);
    }
}
