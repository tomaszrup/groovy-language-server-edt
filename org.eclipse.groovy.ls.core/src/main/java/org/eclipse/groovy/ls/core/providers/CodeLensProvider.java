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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
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
        resolveCache.entrySet().removeIf(entry -> {
            // Handle IDs produced by JDT contain the project-relative path;
            // a simple URI suffix check covers the common case.
            return entry.getKey().contains(uri) || uri.contains(entry.getKey());
        });
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
     * each lens scrolls into view.  This avoids O(n) workspace searches
     * per element on every keystroke in large workspaces.
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
                addCodeLensesForType(type, content, lenses);
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
        return new Command("0 references", SHOW_REFERENCES_COMMAND);
    }

    // ---- Helpers ----

    private void addCodeLensesForType(IType type, String content, List<CodeLens> lenses)
            throws JavaModelException {
        // Code lens for the type itself
        CodeLens typeLens = createUnresolvedCodeLens(type, content);
        if (typeLens != null) {
            lenses.add(typeLens);
        }

        // Code lens for each method
        for (IMethod method : type.getMethods()) {
            CodeLens methodLens = createUnresolvedCodeLens(method, content);
            if (methodLens != null) {
                lenses.add(methodLens);
            }
        }

        // Recurse into inner types
        for (IType innerType : type.getTypes()) {
            addCodeLensesForType(innerType, content, lenses);
        }
    }

    /**
     * Create an unresolved code lens containing only the position and
     * a handle identifier for later resolution.  No workspace search
     * is performed here — resolution is deferred to
     * {@link #resolveCodeLens(CodeLens)}.
     */
    private CodeLens createUnresolvedCodeLens(IJavaElement element, String content) {
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
        List<Location> locations = new ArrayList<>();
        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return locations;
            }

            org.eclipse.jdt.core.IJavaProject javaProject = element.getJavaProject();
            IJavaSearchScope scope = SearchScopeHelper.createSourceScope(javaProject, uri);
            SearchEngine engine = new SearchEngine();

            engine.search(pattern,
                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            Location location = toLocation(match);
                            if (location != null) {
                                locations.add(location);
                            }
                        }
                    },
                    null);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to find reference locations for " + element.getElementName(), e);
        }
        return locations;
    }

    /**
     * Convert a JDT {@link SearchMatch} to an LSP {@link Location}.
     */
    private Location toLocation(SearchMatch match) {
        try {
            org.eclipse.core.resources.IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return null;
            }

            String targetUri = resource.getLocationURI().toString();
            String content = readContent(targetUri, resource);

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();

            Range range;
            if (content != null) {
                Position start = offsetToPosition(content, startOffset);
                Position end = offsetToPosition(content, endOffset);
                range = new Range(start, end);
            } else {
                range = new Range(new Position(0, 0), new Position(0, 0));
            }

            return new Location(targetUri, range);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to convert search match to location", e);
            return null;
        }
    }

    private String readContent(String targetUri, org.eclipse.core.resources.IResource resource) {
        String content = documentManager.getContent(targetUri);
        if (content != null) {
            return content;
        }

        if (resource instanceof org.eclipse.core.resources.IFile file) {
            try (java.io.InputStream is = file.getContents()) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    Position offsetToPosition(String content, int offset) {
        return PositionUtils.offsetToPosition(content, offset);
    }
}
