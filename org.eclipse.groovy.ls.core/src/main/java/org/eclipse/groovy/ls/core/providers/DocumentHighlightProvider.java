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
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Provides document highlight for Groovy documents.
 * <p>
 * Highlights all occurrences of the symbol under the cursor within the current
 * document. Uses JDT's {@link SearchEngine} when available, falling back to
 * text-based matching for documents without a working copy.
 */
public class DocumentHighlightProvider {

    private final DocumentManager documentManager;

    public DocumentHighlightProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute document highlights for the element at the cursor.
     */
    public List<DocumentHighlight> getDocumentHighlights(DocumentHighlightParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        List<DocumentHighlight> jdtHighlights = getHighlightsWithJdt(uri, position);
        if (!jdtHighlights.isEmpty()) {
            return jdtHighlights;
        }

        return getHighlightsFromText(uri, position);
    }

    private List<DocumentHighlight> getHighlightsWithJdt(String uri, Position position) {
        List<DocumentHighlight> highlights = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return highlights;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return highlights;
            }

            int offset = positionToOffset(content, position);
            IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
            if (elements == null || elements.length == 0) {
                return highlights;
            }

            SearchPattern pattern = SearchPattern.createPattern(
                    elements[0], IJavaSearchConstants.ALL_OCCURRENCES);
            if (pattern == null) {
                return highlights;
            }

            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
            SearchEngine engine = new SearchEngine();

            // Normalize the target URI for comparison
            String normalizedUri = normalizeUri(uri);

            engine.search(pattern,
                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            if (match.getResource() == null
                                    || match.getResource().getLocationURI() == null) {
                                return;
                            }
                            String matchUri = match.getResource().getLocationURI().toString();
                            if (!normalizedUri.equals(normalizeUri(matchUri))) {
                                return;
                            }

                            String matchContent = documentManager.getContent(matchUri);
                            if (matchContent == null) {
                                matchContent = content;
                            }

                            int startOffset = match.getOffset();
                            int endOffset = startOffset + match.getLength();
                            Position start = offsetToPosition(matchContent, startOffset);
                            Position end = offsetToPosition(matchContent, endOffset);

                            DocumentHighlightKind kind = match.isInsideDocComment()
                                    ? DocumentHighlightKind.Text
                                    : (match.getAccuracy() == SearchMatch.A_ACCURATE
                                            ? DocumentHighlightKind.Read
                                            : DocumentHighlightKind.Text);
                            highlights.add(new DocumentHighlight(new Range(start, end), kind));
                        }
                    },
                    null);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Document highlight JDT failed for " + uri + ", falling back to text",
                    e);
        }

        return highlights;
    }

    /**
     * Fallback: find all word-boundary occurrences of the identifier under the
     * cursor within the same file.
     */
    List<DocumentHighlight> getHighlightsFromText(String uri, Position position) {
        List<DocumentHighlight> highlights = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) {
            return highlights;
        }

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return highlights;
        }

        Pattern pat = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        Matcher matcher = pat.matcher(content);
        while (matcher.find()) {
            Position start = offsetToPosition(content, matcher.start());
            Position end = offsetToPosition(content, matcher.end());
            highlights.add(new DocumentHighlight(
                    new Range(start, end), DocumentHighlightKind.Text));
        }

        return highlights;
    }

    // ---- helpers ----

    private String normalizeUri(String uri) {
        if (uri == null) return "";
        return uri.replace("\\", "/").toLowerCase();
    }

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

    String extractWordAt(String content, int offset) {
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
}
