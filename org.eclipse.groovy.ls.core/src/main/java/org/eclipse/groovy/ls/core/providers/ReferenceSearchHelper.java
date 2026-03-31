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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final int GROOVY_FILE_LIST_CACHE_SIZE = 32;
    private static final long GROOVY_FILE_LIST_CACHE_TTL_MS = 10_000;
    private static final int FILE_CONTENT_CACHE_SIZE = 128;
    private static final long FILE_CONTENT_CACHE_TTL_MS = 10_000;

    private record CachedGroovyFiles(List<IFile> files, long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > GROOVY_FILE_LIST_CACHE_TTL_MS;
        }
    }

    private record CachedFileContent(String content, long modificationStamp, long timestampMs) {
        boolean matches(long currentModificationStamp) {
            return modificationStamp == currentModificationStamp
                    && System.currentTimeMillis() - timestampMs <= FILE_CONTENT_CACHE_TTL_MS;
        }
    }

    private static final Map<String, CachedGroovyFiles> SCOPED_GROOVY_FILE_CACHE =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedGroovyFiles> eldest) {
                    return size() > GROOVY_FILE_LIST_CACHE_SIZE;
                }
            });

    private static final Map<String, CachedFileContent> FILE_CONTENT_CACHE =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedFileContent> eldest) {
                    return size() > FILE_CONTENT_CACHE_SIZE;
                }
            });

    private ReferenceSearchHelper() {
    }

    /**
     * Invalidate cached on-disk content for a single document URI.
     */
    public static void invalidateFileContentCache(String uri) {
        if (uri == null) {
            return;
        }
        FILE_CONTENT_CACHE.remove(DocumentManager.normalizeUri(uri));
    }

    /**
     * Clear all textual-reference caches after workspace-level file changes.
     */
    public static void clearCaches() {
        SCOPED_GROOVY_FILE_CACHE.clear();
        FILE_CONTENT_CACHE.clear();
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
     */
    static ReferenceExistence referenceExistenceForUnusedDeclaration(
            IJavaElement element, String uri, DocumentManager documentManager) {
        ReferenceExistence result = referenceExistenceWithJdt(element, uri);
        if (result == ReferenceExistence.FOUND) {
            return result;
        }
        return findTextFallbackLocations(element, uri, documentManager, true).isEmpty()
                ? result
                : ReferenceExistence.FOUND;
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
        if (element == null || documentManager == null) {
            return List.of();
        }

        String symbolName = element.getElementName();
        String declarationUri = documentManager.resolveElementUri(element);
        if (symbolName == null || symbolName.isBlank()
                || declarationUri == null || !isGroovyFileUri(declarationUri)) {
            return List.of();
        }

        List<IFile> candidateFiles = collectScopedGroovyFiles(element.getJavaProject(), uri);
        if (candidateFiles.isEmpty()) {
            return List.of();
        }

        org.eclipse.jdt.core.ISourceRange declarationRange = getDeclarationRange(element);
        List<Location> locations = new ArrayList<>();
        Set<String> visitedUris = new HashSet<>();
        Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();

        for (IFile file : candidateFiles) {
            if (file == null || file.getLocationURI() == null) {
                continue;
            }

            String targetUri = DocumentManager.normalizeUri(file.getLocationURI().toString());
            if (targetUri == null || !visitedUris.add(targetUri)) {
                continue;
            }

            String content = readContent(documentManager, targetUri, file);
            if (content == null) {
                continue;
            }

            PositionUtils.LineIndex lineIndex = lineIndexFor(targetUri, content, lineIndexCache);
            int matchStart = -1;
            while ((matchStart = findNextIdentifierMatch(content, symbolName, matchStart + 1)) >= 0) {
                int matchEnd = matchStart + symbolName.length();
                if (isDeclarationMatch(targetUri, declarationUri, declarationRange,
                        matchStart, matchEnd)) {
                    continue;
                }
                Position start = lineIndex.offsetToPosition(matchStart);
                Position end = lineIndex.offsetToPosition(matchEnd);
                locations.add(new Location(targetUri, new Range(start, end)));
                if (stopAfterFirst) {
                    return locations;
                }
            }
        }

        return locations;
    }

    static int findNextIdentifierMatch(String content, String symbolName, int fromIndex) {
        if (content == null || symbolName == null || symbolName.isEmpty()) {
            return -1;
        }

        int index = Math.max(0, fromIndex);
        while ((index = content.indexOf(symbolName, index)) >= 0) {
            int end = index + symbolName.length();
            if (isIdentifierBoundary(content, index - 1) && isIdentifierBoundary(content, end)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static boolean isIdentifierBoundary(String content, int index) {
        return index < 0
                || index >= content.length()
                || !Character.isJavaIdentifierPart(content.charAt(index));
    }

    private static List<IFile> collectScopedGroovyFiles(
            org.eclipse.jdt.core.IJavaProject javaProject, String uri) {
        if (javaProject == null) {
            return List.of();
        }

        List<org.eclipse.jdt.core.IPackageFragmentRoot> roots =
                SearchScopeHelper.getSourceRoots(javaProject, uri);
        if (roots.isEmpty()) {
            return List.of();
        }

        String cacheKey = buildScopedGroovyFileCacheKey(javaProject, roots);
        CachedGroovyFiles cached = SCOPED_GROOVY_FILE_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.files();
        }

        List<IFile> files = new ArrayList<>();
        for (org.eclipse.jdt.core.IPackageFragmentRoot root : roots) {
            try {
                IResource resource = root.getResource();
                if (resource == null) {
                    continue;
                }
                resource.accept(candidate -> {
                    if (candidate instanceof IFile file
                            && "groovy".equalsIgnoreCase(file.getFileExtension())) {
                        files.add(file);
                    }
                    return true;
                });
            } catch (Exception e) {
                // Ignore one broken root and continue scanning others.
            }
        }
        List<IFile> snapshot = List.copyOf(files);
        SCOPED_GROOVY_FILE_CACHE.put(cacheKey,
                new CachedGroovyFiles(snapshot, System.currentTimeMillis()));
        return snapshot;
    }

    private static String buildScopedGroovyFileCacheKey(
            org.eclipse.jdt.core.IJavaProject javaProject,
            List<org.eclipse.jdt.core.IPackageFragmentRoot> roots) {
        StringBuilder key = new StringBuilder(projectCacheKey(javaProject)).append('|');
        for (org.eclipse.jdt.core.IPackageFragmentRoot root : roots) {
            if (root == null) {
                continue;
            }
            key.append(root.getPath()).append('|');
        }
        return key.toString();
    }

    private static String projectCacheKey(org.eclipse.jdt.core.IJavaProject javaProject) {
        if (javaProject == null) {
            return "null-project";
        }
        try {
            String handleId = javaProject.getHandleIdentifier();
            if (handleId != null && !handleId.isBlank()) {
                return handleId;
            }
        } catch (Exception e) {
            // Fall through to other identifiers.
        }
        try {
            String name = javaProject.getElementName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception e) {
            // Fall through to identity hash below.
        }
        return "project@" + System.identityHashCode(javaProject);
    }

    private static org.eclipse.jdt.core.ISourceRange getDeclarationRange(IJavaElement element) {
        if (element instanceof org.eclipse.jdt.core.ISourceReference sourceReference) {
            try {
                return sourceReference.getNameRange();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isDeclarationMatch(
            String targetUri,
            String declarationUri,
            org.eclipse.jdt.core.ISourceRange declarationRange,
            int startOffset,
            int endOffset) {
        if (declarationRange == null
                || declarationRange.getOffset() < 0
                || !DocumentManager.normalizeUri(targetUri).equals(DocumentManager.normalizeUri(declarationUri))) {
            return false;
        }

        int declarationStart = declarationRange.getOffset();
        int declarationEnd = declarationStart + declarationRange.getLength();
        return startOffset < declarationEnd && declarationStart < endOffset;
    }

    private static boolean isGroovyFileUri(String uri) {
        return uri != null && uri.toLowerCase(java.util.Locale.ROOT).endsWith(".groovy");
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
                PositionUtils.LineIndex lineIndex = lineIndexFor(targetUri, content, lineIndexCache);
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

    private static PositionUtils.LineIndex lineIndexFor(
            String targetUri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        PositionUtils.LineIndex cached = lineIndexCache.get(targetUri);
        if (cached != null) {
            return cached;
        }
        PositionUtils.LineIndex built = PositionUtils.buildLineIndex(content);
        lineIndexCache.put(targetUri, built);
        return built;
    }

    @SuppressWarnings("unused")
    private static String readContent(
            DocumentManager documentManager, String targetUri, IResource resource) {
        return readContent(documentManager, targetUri, resource, null);
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
            content = readCachedFileContent(targetUri, file);
        }

        if (contentCache != null) {
            contentCache.put(targetUri, content);
        }
        return content;
    }

    private static String readCachedFileContent(String targetUri, IFile file) {
        long modificationStamp = safeModificationStamp(file);
        if (modificationStamp != IResource.NULL_STAMP) {
            CachedFileContent cached = FILE_CONTENT_CACHE.get(targetUri);
            if (cached != null && cached.matches(modificationStamp)) {
                return cached.content();
            }
        }

        String content = readFileContent(file);
        if (content != null && modificationStamp != IResource.NULL_STAMP) {
            FILE_CONTENT_CACHE.put(targetUri,
                    new CachedFileContent(content, modificationStamp, System.currentTimeMillis()));
        }
        return content;
    }

    private static long safeModificationStamp(IFile file) {
        try {
            return file.getModificationStamp();
        } catch (Exception e) {
            return IResource.NULL_STAMP;
        }
    }

    private static String readFileContent(IFile file) {
        try (java.io.InputStream is = file.getContents()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
