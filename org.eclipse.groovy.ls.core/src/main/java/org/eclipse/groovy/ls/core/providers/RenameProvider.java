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
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;

/**
 * Provides rename refactoring for Groovy documents.
 * <p>
 * Uses JDT's search engine to find all occurrences (declarations + references)
 * of the selected element, then generates a {@link WorkspaceEdit} replacing the
 * old name with the new name at every location.
 * <p>
 * Enhanced rename features:
 * <ul>
 *   <li>Type rename: also updates import statements across workspace files</li>
 *   <li>Type rename: also renames constructors</li>
 *   <li>Field rename: also renames corresponding getters/setters</li>
 * </ul>
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

        WorkspaceEdit jdtEdit = renameWithJdt(uri, position, newName);
        if (jdtEdit != null) {
            return jdtEdit;
        }

        return renameFromGroovyAST(uri, position, newName);
    }

    /**
     * Validate whether the element at the cursor can be renamed, and return
     * its range and current name. Returns {@code null} if renaming is not
     * possible at the given position.
     */
    public Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> prepareRename(
            PrepareRenameParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        // Try JDT first
        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);
                    IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
                    if (elements != null && elements.length > 0) {
                        String word = extractWordAt(content, offset);
                        if (word != null && !word.isEmpty()) {
                            Range range = wordRangeAt(content, offset, word);
                            PrepareRenameResult result = new PrepareRenameResult(range, word);
                            return Either3.forSecond(result);
                        }
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("prepareRename JDT failed for " + uri, e);
            }
        }

        // AST fallback — accept rename of any known identifier
        String content = documentManager.getContent(uri);
        if (content == null) {
            return null;
        }
        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }
        Range range = wordRangeAt(content, offset, word);
        PrepareRenameResult result = new PrepareRenameResult(range, word);
        return Either3.forSecond(result);
    }

    /**
     * Compute the range of the word at the given offset.
     */
    Range wordRangeAt(String content, int offset, String word) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = start + word.length();
        return new Range(offsetToPosition(content, start), offsetToPosition(content, end));
    }

    private WorkspaceEdit renameWithJdt(String uri, Position position, String newName) {
        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return null;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return null;
            }

            int offset = positionToOffset(content, position);
            IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
            if (elements == null || elements.length == 0) {
                return null;
            }

            SearchPattern pattern = SearchPattern.createPattern(
                    elements[0],
                    IJavaSearchConstants.ALL_OCCURRENCES);
            if (pattern == null) {
                return null;
            }

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

            if (editsByUri.isEmpty()) {
                return null;
            }

            // Enhanced rename: additional edits for specific element types
            IJavaElement element = elements[0];
            addEnhancedRenameEdits(element, newName, editsByUri);

            WorkspaceEdit edit = new WorkspaceEdit();
            edit.setChanges(editsByUri);
            return edit;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Rename JDT failed for " + uri + ", falling back to AST", e);
            return null;
        }
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
            String content = readContent(targetUri, resource);

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

    private String readContent(String targetUri, IResource resource) {
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

    // ---- Enhanced rename: type/field-specific additions ----

    /**
     * Add additional edits when renaming specific element types:
     * <ul>
     *   <li>Type rename: update import statements, rename constructors</li>
     *   <li>Field rename: rename corresponding getters/setters</li>
     * </ul>
     */
    private void addEnhancedRenameEdits(IJavaElement element, String newName,
                                         Map<String, List<TextEdit>> editsByUri) {
        try {
            if (element instanceof IType type) {
                addImportRenameEdits(type, newName, editsByUri);
                addConstructorRenameEdits(type, newName, editsByUri);
            } else if (element instanceof IField field) {
                addAccessorRenameEdits(field, newName, editsByUri);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Enhanced rename edits failed", e);
        }
    }

    /**
     * When renaming a type, update all import statements that reference it.
     * The SearchEngine ALL_OCCURRENCES search may not find import statements in all cases,
     * so we scan workspace files explicitly.
     */
    private void addImportRenameEdits(IType type, String newName,
                                       Map<String, List<TextEdit>> editsByUri) {
        try {
            String oldFqn = type.getFullyQualifiedName();
            String oldSimpleName = type.getElementName();

            // Scan all workspace source files
            org.eclipse.core.resources.IProject[] projects =
                    org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (org.eclipse.core.resources.IProject project : projects) {
                if (!project.isOpen()) continue;
                scanProjectForImportRenames(project.members(), oldFqn, oldSimpleName, newName, editsByUri);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Import rename scan failed", e);
        }
    }

    private void scanProjectForImportRenames(IResource[] resources, String oldFqn,
                                              String oldSimpleName, String newName,
                                              Map<String, List<TextEdit>> editsByUri) throws Exception {
        for (IResource resource : resources) {
            if (resource instanceof org.eclipse.core.resources.IFile file) {
                String fileName = file.getName();
                if (fileName.endsWith(".groovy") || fileName.endsWith(".java")) {
                    scanFileForImportRename(file, oldFqn, oldSimpleName, newName, editsByUri);
                }
            } else if (resource instanceof org.eclipse.core.resources.IFolder folder) {
                if (folder.isAccessible()) {
                    scanProjectForImportRenames(folder.members(), oldFqn, oldSimpleName, newName, editsByUri);
                }
            }
        }
    }

    private void scanFileForImportRename(org.eclipse.core.resources.IFile file, String oldFqn,
                                          String oldSimpleName, String newName,
                                          Map<String, List<TextEdit>> editsByUri) {
        try {
            String uri = file.getLocationURI().toString();
            String content = readContent(uri, file);
            if (content == null) return;

            // Look for import statements containing the old FQN
            String importLine = "import " + oldFqn;
            int idx = content.indexOf(importLine);
            while (idx >= 0) {
                // Find the position of just the simple name within the import
                int nameStart = idx + "import ".length() + oldFqn.length() - oldSimpleName.length();
                int nameEnd = nameStart + oldSimpleName.length();

                // Verify it's actually the simple name
                String found = content.substring(nameStart, nameEnd);
                if (found.equals(oldSimpleName)) {
                    Position start = offsetToPosition(content, nameStart);
                    Position end = offsetToPosition(content, nameEnd);

                    // Only add if not already covered by the search edit
                    List<TextEdit> existingEdits = editsByUri.get(uri);
                    if (!hasEditAt(existingEdits, start.getLine(), start.getCharacter())) {
                        editsByUri.computeIfAbsent(uri, k -> new ArrayList<>())
                                .add(new TextEdit(new Range(start, end), newName));
                    }
                }

                idx = content.indexOf(importLine, idx + 1);
            }
        } catch (Exception e) {
            // Silently skip files we can't process
        }
    }

    private boolean hasEditAt(List<TextEdit> edits, int line, int character) {
        if (edits == null) return false;
        for (TextEdit edit : edits) {
            Position start = edit.getRange().getStart();
            if (start.getLine() == line && start.getCharacter() == character) {
                return true;
            }
        }
        return false;
    }

    /**
     * When renaming a type, also rename its constructors.
     */
    private void addConstructorRenameEdits(IType type, String newName,
                                            Map<String, List<TextEdit>> editsByUri) {
        try {
            for (IMethod method : type.getMethods()) {
                if (method.isConstructor()) {
                    SearchPattern pattern = SearchPattern.createPattern(
                            method, IJavaSearchConstants.ALL_OCCURRENCES);
                    if (pattern != null) {
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
                    }
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Constructor rename failed", e);
        }
    }

    /**
     * When renaming a field, also rename corresponding getter/setter methods.
     * <p>
     * Groovy auto-generates getters and setters for properties, so renaming
     * a field should also rename {@code getFieldName()}, {@code setFieldName()},
     * and {@code isFieldName()} if they exist.
     */
    private void addAccessorRenameEdits(IField field, String newName,
                                         Map<String, List<TextEdit>> editsByUri) {
        try {
            IType declaringType = field.getDeclaringType();
            if (declaringType == null) return;

            String oldName = field.getElementName();
            String oldCapName = capitalize(oldName);
            String newCapName = capitalize(newName);

            // Look for getOldName, setOldName, isOldName
            renameAccessorIfExists(declaringType, "get" + oldCapName, "get" + newCapName, editsByUri);
            renameAccessorIfExists(declaringType, "set" + oldCapName, "set" + newCapName, editsByUri);
            renameAccessorIfExists(declaringType, "is" + oldCapName, "is" + newCapName, editsByUri);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Accessor rename failed", e);
        }
    }

    private void renameAccessorIfExists(IType type, String oldAccessorName, String newAccessorName,
                                         Map<String, List<TextEdit>> editsByUri) throws Exception {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(oldAccessorName)) {
                SearchPattern pattern = SearchPattern.createPattern(
                        method, IJavaSearchConstants.ALL_OCCURRENCES);
                if (pattern != null) {
                    SearchEngine engine = new SearchEngine();
                    engine.search(pattern,
                            new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                            SearchEngine.createWorkspaceScope(),
                            new SearchRequestor() {
                                @Override
                                public void acceptSearchMatch(SearchMatch match) {
                                    addRenameEdit(match, newAccessorName, editsByUri);
                                }
                            },
                            null);
                }
                break; // Found the accessor, no need to check further
            }
        }
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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

        isKnownAstSymbol(documentManager.getGroovyAST(uri), word);

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

    private boolean isKnownAstSymbol(ModuleNode ast, String word) {
        if (ast == null) {
            return false;
        }
        for (ClassNode classNode : ast.getClasses()) {
            if (word.equals(classNode.getNameWithoutPackage())) {
                return true;
            }
            if (hasMethodNamed(classNode, word) || hasFieldNamed(classNode, word) || hasPropertyNamed(classNode, word)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMethodNamed(ClassNode classNode, String word) {
        for (MethodNode method : classNode.getMethods()) {
            if (word.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFieldNamed(ClassNode classNode, String word) {
        for (FieldNode field : classNode.getFields()) {
            if (word.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPropertyNamed(ClassNode classNode, String word) {
        for (PropertyNode property : classNode.getProperties()) {
            if (word.equals(property.getName())) {
                return true;
            }
        }
        return false;
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
