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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * Provides rename refactoring for Groovy documents.
 * <p>
 * Uses JDT's search engine to find all occurrences (declarations + references)
 * of the selected element, then generates a {@link WorkspaceEdit} replacing the
 * old name with the new name at every location.
 * <p>
 * Note: This is a lightweight rename based on search-and-replace. For more
 * complex renames (e.g., renaming a package, moving files, updating imports),
 * a full JDT refactoring session would be needed.
 */
public class RenameProvider {

    private final DocumentManager documentManager;

    public RenameProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Perform a rename refactoring.
     */
    public WorkspaceEdit rename(RenameParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();
        String newName = params.getNewName();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);

                    // Resolve the element at cursor
                    IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
                    if (elements != null && elements.length > 0) {
                        IJavaElement element = elements[0];
                        String oldName = element.getElementName();

                        // Find ALL occurrences (declarations + references)
                        SearchPattern pattern = SearchPattern.createPattern(
                                element,
                                IJavaSearchConstants.ALL_OCCURRENCES);

                        if (pattern != null) {
                            Map<String, List<TextEdit>> editsByUri = new HashMap<>();
                            SearchEngine engine = new SearchEngine();

                            engine.search(pattern,
                                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                                    SearchEngine.createWorkspaceScope(),
                                    new SearchRequestor() {
                                        @Override
                                        public void acceptSearchMatch(SearchMatch match) {
                                            addRenameEdit(match, newName, editsByUri);
                                        }
                                    },
                                    null);

                            if (!editsByUri.isEmpty()) {
                                WorkspaceEdit edit = new WorkspaceEdit();
                                edit.setChanges(editsByUri);
                                return edit;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                GroovyLanguageServerPlugin.logError("Rename JDT failed for " + uri + ", falling back to AST", t);
            }
        }

        // Fallback: text-based rename within the same file
        return renameFromGroovyAST(uri, position, newName);
    }

    /**
     * Add a text edit for a search match to the rename edit map.
     */
    private void addRenameEdit(SearchMatch match, String newName,
                                Map<String, List<TextEdit>> editsByUri) {
        try {
            IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return;
            }

            String targetUri = resource.getLocationURI().toString();
            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();

            // Get file content for offset→position conversion
            String content = documentManager.getContent(targetUri);
            if (content == null && resource instanceof org.eclipse.core.resources.IFile) {
                try {
                    java.io.InputStream is = ((org.eclipse.core.resources.IFile) resource).getContents();
                    content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    is.close();
                } catch (Exception e) {
                    return;
                }
            }

            if (content == null) {
                return;
            }

            Position start = offsetToPosition(content, startOffset);
            Position end = offsetToPosition(content, endOffset);
            Range range = new Range(start, end);

            TextEdit textEdit = new TextEdit(range, newName);
            editsByUri.computeIfAbsent(targetUri, k -> new ArrayList<>()).add(textEdit);

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to create rename edit", e);
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

    // ---- Groovy AST fallback rename ----

    /**
     * Perform a text-based rename within the same file when JDT is not available.
     * Finds all word-boundary occurrences of the identifier and replaces them.
     */
    private WorkspaceEdit renameFromGroovyAST(String uri, Position position, String newName) {
        String content = documentManager.getContent(uri);
        if (content == null) {
            return null;
        }

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }

        // Verify the word is a known symbol in the AST
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

        // Still allow rename for local variables / parameters not in top-level AST
        // by proceeding even if not a known top-level symbol

        // Find all word-boundary occurrences and create text edits
        List<TextEdit> edits = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int matchStart = matcher.start();
            int matchEnd = matcher.end();
            Position start = offsetToPosition(content, matchStart);
            Position end = offsetToPosition(content, matchEnd);
            edits.add(new TextEdit(new Range(start, end), newName));
        }

        if (edits.isEmpty()) {
            return null;
        }

        Map<String, List<TextEdit>> changes = new HashMap<>();
        changes.put(uri, edits);
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(changes);
        return edit;
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
