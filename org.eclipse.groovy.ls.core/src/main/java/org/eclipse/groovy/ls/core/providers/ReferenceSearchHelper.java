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
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class ReferenceSearchHelper {

    enum ReferenceExistence {
        FOUND,
        NOT_FOUND,
        INDETERMINATE
    }

    /**
     * Maximum number of Groovy files the text-fallback existence check will
     * scan before giving up and returning {@link ReferenceExistence#INDETERMINATE}.
     * This caps the per-call cost of unused-declaration fading on large projects.
     */
    static final int MAX_TEXT_FALLBACK_FILES_FOR_EXISTENCE = 50;

    private ReferenceSearchHelper() {
    }

    /**
     * Invalidate cached on-disk content for a single document URI.
     */
    public static void invalidateFileContentCache(String uri) {
        ReferenceSearchTextFallbackSupport.invalidateFileContentCache(uri);
    }

    /**
     * Clear all textual-reference caches after workspace-level file changes.
     */
    public static void clearCaches() {
        ReferenceSearchTextFallbackSupport.clearCaches();
    }

    /**
     * Check whether an element has at least one source reference in scope.
     * The search is cancelled as soon as the first match is found.
     */
    static boolean hasReferences(IJavaElement element, String uri, DocumentManager documentManager) {
        if (referenceExistenceWithJdt(element, uri) == ReferenceExistence.FOUND) {
            return true;
        }
        return !findTextFallbackLocations(element, uri, documentManager, true).isEmpty();
    }

    /**
     * Fast yes/no reference existence check used by unused-declaration fading.
     * Reuses the same textual fallback as code lens resolution so declarations
     * are not faded when the code lens can still find Groovy-only references.
     * <p>
     * The textual fallback is capped at {@link #MAX_TEXT_FALLBACK_FILES_FOR_EXISTENCE}
     * files to avoid long UI stalls on large projects; when the cap is exceeded
     * without finding a reference the result is {@link ReferenceExistence#INDETERMINATE}.
     */
    static ReferenceExistence referenceExistenceForUnusedDeclaration(
            IJavaElement element, String uri, DocumentManager documentManager) {
        ReferenceExistence result = referenceExistenceWithJdt(element, uri);
        if (result == ReferenceExistence.FOUND) {
            return result;
        }
        ReferenceExistence textResult = ReferenceSearchTextFallbackSupport.textFallbackExistence(
            element,
            uri,
            documentManager);
        if (textResult == ReferenceExistence.FOUND) {
            return ReferenceExistence.FOUND;
        }
        if (result == ReferenceExistence.INDETERMINATE
                || textResult == ReferenceExistence.INDETERMINATE) {
            return ReferenceExistence.INDETERMINATE;
        }
        return result;
    }

    static List<Location> findReferenceLocations(
            IJavaElement element, String uri, DocumentManager documentManager) {
        List<Location> locations = findReferenceLocationsWithJdt(element, uri, documentManager);
        if (!locations.isEmpty()) {
            return locations;
        }
        return findTextFallbackLocations(element, uri, documentManager, false);
    }

    private static ReferenceExistence referenceExistenceWithJdt(IJavaElement element, String uri) {
        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return ReferenceExistence.INDETERMINATE;
            }

            org.eclipse.jdt.core.IJavaProject javaProject = element.getJavaProject();
            IJavaSearchScope scope = SearchScopeHelper.createSourceScope(javaProject, uri);

            boolean[] found = {false};
            org.eclipse.core.runtime.IProgressMonitor cancelOnFound =
                    new org.eclipse.core.runtime.NullProgressMonitor() {
                        @Override
                        public boolean isCanceled() {
                            return found[0];
                        }
                    };

            JdtSearchSupport.search(pattern,
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                            found[0] = true;
                        }
                    },
                    cancelOnFound);

            return found[0] ? ReferenceExistence.FOUND : ReferenceExistence.NOT_FOUND;
        } catch (org.eclipse.core.runtime.OperationCanceledException ignored) {
            return ReferenceExistence.FOUND;
        } catch (Exception e) {
            return ReferenceExistence.INDETERMINATE;
        }
    }

    private static List<Location> findReferenceLocationsWithJdt(
            IJavaElement element, String uri, DocumentManager documentManager) {
        List<Location> locations = new ArrayList<>();
        Map<String, String> contentCache = new HashMap<>();
        Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return locations;
            }

            org.eclipse.jdt.core.IJavaProject javaProject = element.getJavaProject();
            IJavaSearchScope scope = SearchScopeHelper.createSourceScope(javaProject, uri);
            JdtSearchSupport.search(pattern,
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            Location location = toLocation(match, documentManager, contentCache, lineIndexCache);
                            if (location != null) {
                                locations.add(location);
                            }
                        }
                    },
                    null);
        } catch (Exception e) {
            return locations;
        }
        return locations;
    }

    static List<Location> findTextFallbackLocations(
            IJavaElement element, String uri, DocumentManager documentManager, boolean stopAfterFirst) {
        return ReferenceSearchTextFallbackSupport.findTextFallbackLocations(
                element,
                uri,
                documentManager,
                stopAfterFirst);
    }

    static int findNextIdentifierMatch(String content, String symbolName, int fromIndex) {
        return ReferenceSearchTextFallbackSupport.findNextIdentifierMatch(content, symbolName, fromIndex);
    }

    private static Location toLocation(SearchMatch match,
            DocumentManager documentManager,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            IResource resource = match.getResource();
            String targetUri = JdtSearchSupport.resolveResourceUri(documentManager, resource);
            if (targetUri == null) {
                return null;
            }
            String content = readContent(documentManager, targetUri, resource, contentCache);

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();
            Range range;
            if (content != null) {
                PositionUtils.LineIndex lineIndex = ReferenceSearchTextFallbackSupport.lineIndexFor(
                    targetUri,
                    content,
                    lineIndexCache);
                Position start = lineIndex.offsetToPosition(startOffset);
                Position end = lineIndex.offsetToPosition(endOffset);
                range = new Range(start, end);
            } else {
                range = new Range(new Position(0, 0), new Position(0, 0));
            }

            return new Location(targetUri, range);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readContent(
            DocumentManager documentManager,
            String targetUri,
            IResource resource,
            Map<String, String> contentCache) {
        if (contentCache != null && contentCache.containsKey(targetUri)) {
            return contentCache.get(targetUri);
        }

        String content = documentManager.getContent(targetUri);
        if (content == null && resource instanceof IFile file) {
            content = ReferenceSearchTextFallbackSupport.readContent(documentManager, targetUri, file);
        }

        if (contentCache != null) {
            contentCache.put(targetUri, content);
        }
        return content;
    }
}
