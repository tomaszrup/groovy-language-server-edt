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
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
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
            IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
            if (elements == null || elements.length == 0) {
                return locations;
            }

            IJavaElement element = elements[0];

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

    private void findTypeImplementors(IType type, List<Location> locations) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                type, IJavaSearchConstants.IMPLEMENTORS);
        if (pattern == null) {
            return;
        }
        search(pattern, locations);
    }

    private void findMethodImplementors(IMethod method, List<Location> locations) throws Exception {
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

    private void search(SearchPattern pattern, List<Location> locations) throws Exception {
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
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
            GroovyLanguageServerPlugin.logError("Failed to convert implementation match to location", e);
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
