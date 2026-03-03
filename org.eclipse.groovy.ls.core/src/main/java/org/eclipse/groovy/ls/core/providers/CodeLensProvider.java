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
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Provides code lens with reference counts for Groovy documents.
 * <p>
 * Displays the number of references above each type and method declaration.
 * Uses JDT's {@link SearchEngine} to count references. Code lenses are
 * resolved lazily — the initial {@code codeLens} request returns positions
 * only, and {@code resolveCodeLens} performs the actual reference count.
 */
public class CodeLensProvider {

    private final DocumentManager documentManager;

    public CodeLensProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Generate code lenses for all types and methods in the document.
     * Reference counting is performed eagerly so that items with 0 references
     * can be omitted entirely.
     */
    public List<CodeLens> getCodeLenses(CodeLensParams params) {
        String uri = params.getTextDocument().getUri();
        List<CodeLens> lenses = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return lenses;
        }

        String content = documentManager.getContent(uri);
        if (content == null) {
            return lenses;
        }

        try {
            IType[] types = workingCopy.getTypes();
            for (IType type : types) {
                addCodeLensesForType(type, content, lenses);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("CodeLens generation failed for " + uri, e);
        }

        return lenses;
    }

    /**
     * Resolve a code lens. Since lenses are now resolved eagerly during
     * {@link #getCodeLenses}, this simply returns the already-resolved lens.
     */
    public CodeLens resolveCodeLens(CodeLens codeLens) {
        // Lenses are resolved eagerly in getCodeLenses; nothing to do here.
        if (codeLens.getCommand() != null) {
            return codeLens;
        }

        // Fallback for any unresolved lens (should not happen)
        if (codeLens.getData() == null) {
            return codeLens;
        }

        try {
            JsonElement dataElement = (JsonElement) codeLens.getData();
            String handleId = null;
            if (dataElement.isJsonObject()) {
                JsonObject obj = dataElement.getAsJsonObject();
                if (obj.has("handleId")) {
                    handleId = obj.get("handleId").getAsString();
                }
            }

            if (handleId == null) {
                return codeLens;
            }

            IJavaElement element = JavaCore.create(handleId);
            if (element == null || !element.exists()) {
                return codeLens;
            }

            int count = countReferences(element);
            if (count > 0) {
                String label = count == 1 ? "1 reference" : count + " references";
                codeLens.setCommand(new Command(label, ""));
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("CodeLens resolve failed", e);
        }

        return codeLens;
    }

    // ---- Helpers ----

    private void addCodeLensesForType(IType type, String content, List<CodeLens> lenses)
            throws JavaModelException {
        // Code lens for the type itself
        CodeLens typeLens = createCodeLens(type, content);
        if (typeLens != null) {
            lenses.add(typeLens);
        }

        // Code lens for each method
        for (IMethod method : type.getMethods()) {
            CodeLens methodLens = createCodeLens(method, content);
            if (methodLens != null) {
                lenses.add(methodLens);
            }
        }

        // Recurse into inner types
        for (IType innerType : type.getTypes()) {
            addCodeLensesForType(innerType, content, lenses);
        }
    }

    private CodeLens createCodeLens(IJavaElement element, String content) {
        try {
            if (!(element instanceof ISourceReference sourceRef)) {
                return null;
            }

            ISourceRange nameRange = sourceRef.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return null;
            }

            // Count references eagerly — skip if 0
            int count = countReferences(element);
            if (count == 0) {
                return null;
            }

            Position start = offsetToPosition(content, nameRange.getOffset());
            Range range = new Range(start, start);

            String label = count == 1 ? "1 reference" : count + " references";

            CodeLens lens = new CodeLens();
            lens.setRange(range);
            lens.setCommand(new Command(label, ""));

            // Store handle identifier (kept for compatibility)
            JsonObject data = new JsonObject();
            data.addProperty("handleId", element.getHandleIdentifier());
            lens.setData(data);

            return lens;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to create code lens for " + element.getElementName(), e);
            return null;
        }
    }

    /**
     * Count references to an element using JDT SearchEngine.
     */
    private int countReferences(IJavaElement element) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                element, IJavaSearchConstants.REFERENCES);
        if (pattern == null) {
            return 0;
        }

        int[] count = {0};
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        SearchEngine engine = new SearchEngine();

        engine.search(pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        count[0]++;
                    }
                },
                null);

        return count[0];
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
}
