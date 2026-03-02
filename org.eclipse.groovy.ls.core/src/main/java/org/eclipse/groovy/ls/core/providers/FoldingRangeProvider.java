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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Position;

/**
 * Provides folding ranges for Groovy documents.
 * <p>
 * Walks the Groovy AST to produce folding ranges for classes, methods, fields
 * with closures, import blocks, and multi-line comments.
 */
public class FoldingRangeProvider {

    private final DocumentManager documentManager;

    public FoldingRangeProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute folding ranges for the given document.
     */
    public List<FoldingRange> getFoldingRanges(FoldingRangeRequestParams params) {
        String uri = params.getTextDocument().getUri();
        List<FoldingRange> ranges = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) {
            return ranges;
        }

        // Text-based folding: imports block, multi-line comments, multi-line strings
        addImportFoldingFromText(content, ranges);
        addCommentFolding(content, ranges);
        addMultiLineStringFolding(content, ranges);

        // AST-based folding: classes, methods
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast != null) {
            for (ClassNode classNode : ast.getClasses()) {
                addClassFolding(classNode, ranges);
            }
        }

        return ranges;
    }

    /**
     * Add a folding range for the import block (consecutive import lines).
     */
    void addImportFoldingFromText(String content, List<FoldingRange> ranges) {
        String[] lines = content.split("\n", -1);
        int firstImport = -1;
        int lastImport = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ")) {
                if (firstImport == -1) {
                    firstImport = i;
                }
                lastImport = i;
            }
        }

        if (firstImport >= 0 && lastImport > firstImport) {
            FoldingRange range = new FoldingRange(firstImport, lastImport);
            range.setKind(FoldingRangeKind.Imports);
            ranges.add(range);
        }
    }

    /**
     * Add folding ranges for block comments and Javadoc comments.
     */
    void addCommentFolding(String content, List<FoldingRange> ranges) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("/*")) {
                int startLine = i;
                // Find the end of the comment
                for (int j = i; j < lines.length; j++) {
                    if (lines[j].contains("*/")) {
                        if (j > startLine) {
                            FoldingRange range = new FoldingRange(startLine, j);
                            range.setKind(FoldingRangeKind.Comment);
                            ranges.add(range);
                        }
                        i = j; // skip past
                        break;
                    }
                }
            }
        }
    }

    /**
     * Add folding ranges for multi-line strings (triple-quoted).
     */
    void addMultiLineStringFolding(String content, List<FoldingRange> ranges) {
        String[] lines = content.split("\n", -1);
        boolean inTripleQuote = false;
        int startLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int idx = 0;
            while (idx < line.length()) {
                int tq = indexOfTripleQuote(line, idx);
                if (tq < 0) break;

                if (!inTripleQuote) {
                    inTripleQuote = true;
                    startLine = i;
                    idx = tq + 3;
                } else {
                    inTripleQuote = false;
                    if (i > startLine) {
                        ranges.add(new FoldingRange(startLine, i));
                    }
                    idx = tq + 3;
                }
            }
        }
    }

    private int indexOfTripleQuote(String line, int from) {
        int dq = line.indexOf("\"\"\"", from);
        int sq = line.indexOf("'''", from);
        if (dq < 0) return sq;
        if (sq < 0) return dq;
        return Math.min(dq, sq);
    }

    /**
     * Add folding for a class and its members.
     */
    private void addClassFolding(ClassNode classNode, List<FoldingRange> ranges) {
        // Skip synthetic/script classes with line -1
        if (classNode.getLineNumber() < 1) {
            return;
        }
        int startLine = classNode.getLineNumber() - 1; // AST is 1-based
        int endLine = classNode.getLastLineNumber() - 1;
        if (endLine > startLine) {
            ranges.add(new FoldingRange(startLine, endLine));
        }

        // Methods
        for (MethodNode method : classNode.getMethods()) {
            if (method.getLineNumber() < 1 || method.getLineNumber() == method.getLastLineNumber()) {
                continue;
            }
            int mStart = method.getLineNumber() - 1;
            int mEnd = method.getLastLineNumber() - 1;
            if (mEnd > mStart) {
                ranges.add(new FoldingRange(mStart, mEnd));
            }
        }

        // Inner classes
        java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> it = classNode.getInnerClasses();
        while (it.hasNext()) {
            addClassFolding(it.next(), ranges);
        }
    }
}
