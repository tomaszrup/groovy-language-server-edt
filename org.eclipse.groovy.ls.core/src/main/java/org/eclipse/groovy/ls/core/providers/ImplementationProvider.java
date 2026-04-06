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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Provides go-to-implementation for Groovy documents.
 * <p>
 * Given an interface, abstract class, or abstract method, finds all concrete
 * implementations/subtypes across the workspace using JDT's {@link SearchEngine}.
 */
public class ImplementationProvider {

    private final DocumentManager documentManager;

    public ImplementationProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute the implementation location(s) for the element at the cursor.
     */
    public List<Location> getImplementations(ImplementationParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        return resolveViaJdt(uri, position);
    }

    private List<Location> resolveViaJdt(String uri, Position position) {
        List<Location> locations = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return locations;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return locations;
            }

            int offset = positionToOffset(content, position);
            IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
            if (elements == null || elements.length == 0) {
                return locations;
            }

            IJavaElement element = documentManager.remapToWorkingCopyElement(elements[0]);
            if (element == null) {
                element = elements[0];
            }

            if (element instanceof IType type) {
                findTypeImplementors(type, locations);
            } else if (element instanceof IMethod method) {
                findMethodImplementors(method, locations);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Implementation search failed for " + uri, e);
        }

        return locations;
    }

    private void findTypeImplementors(IType type, List<Location> locations) throws org.eclipse.core.runtime.CoreException {
        SearchPattern pattern = SearchPattern.createPattern(
                type, IJavaSearchConstants.IMPLEMENTORS);
        if (pattern == null) {
            return;
        }
        search(pattern, locations);
    }

    private void findMethodImplementors(IMethod method, List<Location> locations) throws org.eclipse.core.runtime.CoreException {
        // First find implementors of the declaring type, then look for the method
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) {
            return;
        }

        // Search for declarations of methods with same name in subtypes
        SearchPattern pattern = SearchPattern.createPattern(
                method, IJavaSearchConstants.DECLARATIONS);
        if (pattern == null) {
            return;
        }
        search(pattern, locations);
    }

    private void search(SearchPattern pattern, List<Location> locations) throws org.eclipse.core.runtime.CoreException {
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        Map<String, String> contentCache = new HashMap<>();
        Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();

        JdtSearchSupport.search(pattern,
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        Location location = toLocation(match, contentCache, lineIndexCache);
                        if (location != null) {
                            locations.add(location);
                        }
                    }
                },
                null);
    }

    /**
     * Convert a JDT {@link SearchMatch} to an LSP {@link Location}.
     */
    Location toLocation(SearchMatch match) {
        return toLocation(match, new HashMap<>(), new HashMap<>());
    }

    private Location toLocation(SearchMatch match,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            org.eclipse.core.resources.IResource resource = match.getResource();
            String targetUri = JdtSearchSupport.resolveResourceUri(documentManager, resource);
            if (targetUri == null) {
                return null;
            }
            String content = readContent(targetUri, resource, contentCache);

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();

            Range range;
            if (content != null) {
                PositionUtils.LineIndex lineIndex = lineIndexFor(targetUri, content, lineIndexCache);
                Position start = lineIndex.offsetToPosition(startOffset);
                Position end = lineIndex.offsetToPosition(endOffset);
                range = new Range(start, end);
            } else {
                range = new Range(new Position(0, 0), new Position(0, 0));
            }

            return new Location(targetUri, range);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to convert implementation match to location", e);
            return null;
        }
    }

    private String readContent(String targetUri,
            org.eclipse.core.resources.IResource resource,
            Map<String, String> contentCache) {
        return JdtSearchSupport.readContent(documentManager, targetUri, resource, contentCache);
    }

    private PositionUtils.LineIndex lineIndexFor(String targetUri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        return lineIndexCache.computeIfAbsent(
                targetUri,
                ignored -> PositionUtils.buildLineIndex(content));
    }

    // ---- Helpers ----

    Position offsetToPosition(String content, int offset) {
        int line = 0;
        int col = 0;
        int safeOffset = Math.min(offset, content.length());
        for (int i = 0; i < safeOffset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    int positionToOffset(String content, Position position) {
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
