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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

final class ReferenceSearchHelper {

    private ReferenceSearchHelper() {
    }

    /**
     * Check whether an element has at least one source reference in scope.
     * The search is cancelled as soon as the first match is found.
     */
    static boolean hasReferences(IJavaElement element, String uri, DocumentManager documentManager) {
        if (hasReferencesWithJdt(element, uri)) {
            return true;
        }
        return !findTextFallbackLocations(element, uri, documentManager, true).isEmpty();
    }

    static List<Location> findReferenceLocations(
            IJavaElement element, String uri, DocumentManager documentManager) {
        List<Location> locations = findReferenceLocationsWithJdt(element, uri, documentManager);
        if (!locations.isEmpty()) {
            return locations;
        }
        return findTextFallbackLocations(element, uri, documentManager, false);
    }

    private static boolean hasReferencesWithJdt(IJavaElement element, String uri) {
        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return false;
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

            return found[0];
        } catch (org.eclipse.core.runtime.OperationCanceledException ignored) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Location> findReferenceLocationsWithJdt(
            IJavaElement element, String uri, DocumentManager documentManager) {
        List<Location> locations = new ArrayList<>();
        Map<String, String> contentCache = new HashMap<>();
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
                            Location location = toLocation(match, documentManager, contentCache);
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
        String declarationUri = getElementUri(element);
        if (symbolName == null || symbolName.isBlank()
                || declarationUri == null || !isGroovyFileUri(declarationUri)) {
            return List.of();
        }

        List<IFile> candidateFiles = collectScopedGroovyFiles(element.getJavaProject(), uri);
        if (candidateFiles.isEmpty()) {
            return List.of();
        }

        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbolName) + "\\b");
        org.eclipse.jdt.core.ISourceRange declarationRange = getDeclarationRange(element);
        List<Location> locations = new ArrayList<>();
        Set<String> visitedUris = new HashSet<>();

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

            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                if (isDeclarationMatch(targetUri, declarationUri, declarationRange,
                        matcher.start(), matcher.end())) {
                    continue;
                }
                Position start = PositionUtils.offsetToPosition(content, matcher.start());
                Position end = PositionUtils.offsetToPosition(content, matcher.end());
                locations.add(new Location(targetUri, new Range(start, end)));
                if (stopAfterFirst) {
                    return locations;
                }
            }
        }

        return locations;
    }

    private static List<IFile> collectScopedGroovyFiles(
            org.eclipse.jdt.core.IJavaProject javaProject, String uri) {
        if (javaProject == null) {
            return List.of();
        }

        List<IFile> files = new ArrayList<>();
        for (org.eclipse.jdt.core.IPackageFragmentRoot root : SearchScopeHelper.getSourceRoots(javaProject, uri)) {
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
        return files;
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

    private static String getElementUri(IJavaElement element) {
        try {
            if (element.getResource() != null && element.getResource().getLocationURI() != null) {
                return DocumentManager.normalizeUri(element.getResource().getLocationURI().toString());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static boolean isGroovyFileUri(String uri) {
        return uri != null && uri.toLowerCase(java.util.Locale.ROOT).endsWith(".groovy");
    }

    private static Location toLocation(SearchMatch match,
            DocumentManager documentManager,
            Map<String, String> contentCache) {
        try {
            IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return null;
            }

            String targetUri = DocumentManager.normalizeUri(resource.getLocationURI().toString());
            String content = readContent(documentManager, targetUri, resource, contentCache);

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();
            Range range;
            if (content != null) {
                Position start = PositionUtils.offsetToPosition(content, startOffset);
                Position end = PositionUtils.offsetToPosition(content, endOffset);
                range = new Range(start, end);
            } else {
                range = new Range(new Position(0, 0), new Position(0, 0));
            }

            return new Location(targetUri, range);
        } catch (Exception e) {
            return null;
        }
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
        return JdtSearchSupport.readContent(documentManager, targetUri, resource, contentCache);
    }
}