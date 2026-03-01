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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;

/**
 * Provides find-references for Groovy documents.
 * <p>
 * Uses JDT's {@link SearchEngine} to find all references to the selected element
 * across the workspace. The JDT search engine works with groovy-eclipse because the
 * Groovy sources are indexed by JDT's indexer through the patched JDT core.
 */
public class ReferenceProvider {

    private final DocumentManager documentManager;

    public ReferenceProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Find all references to the element at the cursor.
     */
    public List<Location> getReferences(ReferenceParams params) {
        List<Location> locations = new ArrayList<>();

        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);

                    // Resolve the element at the cursor
                    IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
                    if (elements != null && elements.length > 0) {
                        IJavaElement element = elements[0];

                        // Determine search scope
                        int searchFor = IJavaSearchConstants.REFERENCES;
                        if (params.getContext() != null && params.getContext().isIncludeDeclaration()) {
                            searchFor = IJavaSearchConstants.ALL_OCCURRENCES;
                        }

                        // Create a search pattern for the resolved element
                        SearchPattern pattern = SearchPattern.createPattern(
                                element,
                                searchFor);

                        if (pattern != null) {
                            // Search across the whole workspace
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
                    }
                }
                if (!locations.isEmpty()) {
                    return locations;
                }
            } catch (Throwable t) {
                GroovyLanguageServerPlugin.logError("Find references JDT failed for " + uri + ", falling back to AST", t);
            }
        }

        // Fallback: find references using text search within the same file
        return getReferencesFromGroovyAST(uri, position);
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

            // Get content for offset->position conversion
            String content = documentManager.getContent(targetUri);
            if (content == null && resource instanceof org.eclipse.core.resources.IFile) {
                try {
                    java.io.InputStream is = ((org.eclipse.core.resources.IFile) resource).getContents();
                    content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    is.close();
                } catch (Exception e) {
                    content = null;
                }
            }

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

    private Position offsetToPosition(String content, int offset) {
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

    // ---- Groovy AST fallback references ----

    /**
     * Find references using text search when JDT is not available.
     * Finds all occurrences of the identifier under the cursor within the same file,
     * filtering to word boundaries to avoid partial matches.
     */
    private List<Location> getReferencesFromGroovyAST(String uri, Position position) {
        List<Location> locations = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) {
            return locations;
        }

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return locations;
        }

        // Verify the word is a known symbol in the AST (class, method, field, property)
        ModuleNode ast = documentManager.getGroovyAST(uri);
        boolean isKnownSymbol = false;
        if (ast != null) {
            for (ClassNode classNode : ast.getClasses()) {
                if (classNode.getNameWithoutPackage().equals(word)) { isKnownSymbol = true; break; }
                for (MethodNode m : classNode.getMethods()) {
                    if (m.getName().equals(word)) { isKnownSymbol = true; break; }
                }
                if (isKnownSymbol) break;
                for (FieldNode f : classNode.getFields()) {
                    if (f.getName().equals(word)) { isKnownSymbol = true; break; }
                }
                if (isKnownSymbol) break;
                for (PropertyNode p : classNode.getProperties()) {
                    if (p.getName().equals(word)) { isKnownSymbol = true; break; }
                }
                if (isKnownSymbol) break;
            }
        }

        // If the word isn't a known AST symbol, still search for it (it could be a
        // local variable or parameter, which are also valid references)

        // Find all word-boundary occurrences in the document
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int matchStart = matcher.start();
            int matchEnd = matcher.end();
            Position start = offsetToPosition(content, matchStart);
            Position end = offsetToPosition(content, matchEnd);
            locations.add(new Location(uri, new Range(start, end)));
        }

        return locations;
    }

    /**
     * Extract the identifier word at the given offset.
     */
    private String extractWordAt(String content, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = offset;
        while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
            end++;
        }
        if (start == end) return null;
        return content.substring(start, end);
    }

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
