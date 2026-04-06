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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class ReferenceSearchTextFallbackSupport {

    private static final int GROOVY_FILE_LIST_CACHE_SIZE = 32;
    private static final long GROOVY_FILE_LIST_CACHE_TTL_MS = 10_000;
    private static final int FILE_CONTENT_CACHE_SIZE = 128;
    private static final long FILE_CONTENT_CACHE_TTL_MS = 10_000;

    private record CachedGroovyFiles(List<IFile> files, long timestampMs) {
        private boolean isExpired() { return System.currentTimeMillis() - timestampMs > GROOVY_FILE_LIST_CACHE_TTL_MS; }
    }
    private record CachedFileContent(String content, long modificationStamp, long timestampMs) {
        private boolean matches(long currentModificationStamp) {
            return modificationStamp == currentModificationStamp && System.currentTimeMillis() - timestampMs <= FILE_CONTENT_CACHE_TTL_MS;
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

    private ReferenceSearchTextFallbackSupport() {
    }

    static void invalidateFileContentCache(String uri) {
        if (uri != null) { FILE_CONTENT_CACHE.remove(DocumentManager.normalizeUri(uri)); }
    }

    static void clearCaches() { SCOPED_GROOVY_FILE_CACHE.clear(); FILE_CONTENT_CACHE.clear(); }

    static ReferenceSearchHelper.ReferenceExistence textFallbackExistence(
            IJavaElement element, String uri, DocumentManager documentManager) {
        if (element == null || documentManager == null) {
            return ReferenceSearchHelper.ReferenceExistence.INDETERMINATE;
        }

        String symbolName = element.getElementName();
        String declarationUri = documentManager.resolveElementUri(element);
        if (symbolName == null || symbolName.isBlank()
                || declarationUri == null || !isGroovyFileUri(declarationUri)) {
            return ReferenceSearchHelper.ReferenceExistence.INDETERMINATE;
        }

        List<IFile> candidateFiles = collectScopedGroovyFiles(element.getJavaProject(), uri);
        if (candidateFiles.isEmpty()) {
            return ReferenceSearchHelper.ReferenceExistence.NOT_FOUND;
        }

        org.eclipse.jdt.core.ISourceRange declarationRange = getDeclarationRange(element);
        Set<String> visitedUris = new HashSet<>();
        int filesScanned = 0;

        for (IFile file : candidateFiles) {
            FileScanInput scanInput = createFileScanInput(file, visitedUris, documentManager, null);
            if (scanInput == null) {
                continue;
            }

            if (filesScanned >= ReferenceSearchHelper.MAX_TEXT_FALLBACK_FILES_FOR_EXISTENCE) {
                return ReferenceSearchHelper.ReferenceExistence.INDETERMINATE;
            }

            filesScanned++;
            if (containsReference(scanInput.content(), symbolName, scanInput.targetUri(), declarationUri, declarationRange)) {
                return ReferenceSearchHelper.ReferenceExistence.FOUND;
            }
        }

        return ReferenceSearchHelper.ReferenceExistence.NOT_FOUND;
    }

    static List<Location> findTextFallbackLocations(IJavaElement element, String uri, DocumentManager documentManager, boolean stopAfterFirst) {
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
        Map<String, PositionUtils.LineIndex> lineIndexCache = new java.util.HashMap<>();

        for (IFile file : candidateFiles) {
            FileScanInput scanInput = createFileScanInput(file, visitedUris, documentManager, lineIndexCache);
            if (scanInput == null) {
                continue;
            }

            if (collectLocations(scanInput,
                    symbolName,
                    declarationUri,
                    declarationRange,
                    locations,
                    stopAfterFirst)) {
                return locations;
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

        private static boolean containsReference(String content, String symbolName, String targetUri,
            String declarationUri, org.eclipse.jdt.core.ISourceRange declarationRange) {
        if (symbolName == null) {
            return false;
        }
        int matchStart = -1;
        while ((matchStart = findNextIdentifierMatch(content, symbolName, matchStart + 1)) >= 0) {
            int matchEnd = matchStart + symbolName.length();
            if (!isDeclarationMatch(targetUri, declarationUri, declarationRange, matchStart, matchEnd)) {
                return true;
            }
        }
        return false;
    }

        private static boolean collectLocations(FileScanInput scanInput, String symbolName, String declarationUri,
            org.eclipse.jdt.core.ISourceRange declarationRange, List<Location> locations, boolean stopAfterFirst) {
        if (symbolName == null) {
            return false;
        }
        int matchStart = -1;
        while ((matchStart = findNextIdentifierMatch(scanInput.content(), symbolName, matchStart + 1)) >= 0) {
            int matchEnd = matchStart + symbolName.length();
            if (isDeclarationMatch(scanInput.targetUri(), declarationUri, declarationRange, matchStart, matchEnd)) {
                continue;
            }
            Position start = scanInput.lineIndex().offsetToPosition(matchStart);
            Position end = scanInput.lineIndex().offsetToPosition(matchEnd);
            locations.add(new Location(scanInput.targetUri(), new Range(start, end)));
            if (stopAfterFirst) {
                return true;
            }
        }
        return false;
    }

        private static FileScanInput createFileScanInput(IFile file, Set<String> visitedUris,
            DocumentManager documentManager, Map<String, PositionUtils.LineIndex> lineIndexCache) {
        if (file == null || file.getLocationURI() == null) {
            return null;
        }

        String targetUri = DocumentManager.normalizeUri(file.getLocationURI().toString());
        if (targetUri == null || !visitedUris.add(targetUri)) {
            return null;
        }

        String content = readContent(documentManager, targetUri, file);
        if (content == null) {
            return null;
        }

        PositionUtils.LineIndex lineIndex = lineIndexCache == null ? null : lineIndexFor(targetUri, content, lineIndexCache);
        return new FileScanInput(targetUri, content, lineIndex);
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

        List<org.eclipse.jdt.core.IPackageFragmentRoot> roots = SearchScopeHelper.getSourceRoots(javaProject, uri);
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
            collectGroovyFiles(root, files);
        }

        List<IFile> snapshot = List.copyOf(files);
        SCOPED_GROOVY_FILE_CACHE.put(cacheKey, new CachedGroovyFiles(snapshot, System.currentTimeMillis()));
        return snapshot;
    }

    private static void collectGroovyFiles(org.eclipse.jdt.core.IPackageFragmentRoot root, List<IFile> files) {
        try {
            IResource resource = root.getResource();
            if (resource == null) {
                return;
            }
            resource.accept(candidate -> {
                if (candidate instanceof IFile file && "groovy".equalsIgnoreCase(file.getFileExtension())) {
                    files.add(file);
                }
                return true;
            });
        } catch (Exception e) {
            // Ignore one broken root and continue scanning others.
        }
    }

    private static String buildScopedGroovyFileCacheKey(
            org.eclipse.jdt.core.IJavaProject javaProject,
            List<org.eclipse.jdt.core.IPackageFragmentRoot> roots) {
        StringBuilder key = new StringBuilder(projectCacheKey(javaProject)).append('|');
        for (org.eclipse.jdt.core.IPackageFragmentRoot root : roots) {
            if (root != null) {
                key.append(root.getPath()).append('|');
            }
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

    private static boolean isDeclarationMatch(String targetUri,
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

        static PositionUtils.LineIndex lineIndexFor(String targetUri,
            String content, Map<String, PositionUtils.LineIndex> lineIndexCache) {
        PositionUtils.LineIndex cached = lineIndexCache.get(targetUri);
        if (cached != null) {
            return cached;
        }
        PositionUtils.LineIndex built = PositionUtils.buildLineIndex(content);
        lineIndexCache.put(targetUri, built);
        return built;
    }

    static String readContent(DocumentManager documentManager, String targetUri, IResource resource) {
        String content = documentManager.getContent(targetUri);
        if (content == null && resource instanceof IFile file) {
            content = readCachedFileContent(targetUri, file);
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

    private record FileScanInput(String targetUri, String content, PositionUtils.LineIndex lineIndex) {
    }
}