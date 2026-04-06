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

import java.util.HashSet;
import java.util.Set;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;

final class SemanticTokensFallbackSupport {

    ModuleNode getStandaloneAST(GroovyCompilerService compilerService, String uri, String content) {
        try {
            GroovyCompilerService.ParseResult firstResult = compilerService.parse(uri, content);
            if (firstResult.hasAST()) {
                ModuleNode module = firstResult.getModuleNode();
                int classCount = module.getClasses() != null ? module.getClasses().size() : 0;
                GroovyLanguageServerPlugin.logInfo(
                        "[semantic] Standalone compiler fallback for " + uri + " classes=" + classCount);
                if (classCount > 0) {
                    return module;
                }
            }

            return tryParsePatchedContent(compilerService, uri, content, firstResult);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[semantic] Standalone compiler fallback failed for " + uri, e);
            return null;
        }
    }

    private ModuleNode tryParsePatchedContent(GroovyCompilerService compilerService,
            String uri,
            String content,
            GroovyCompilerService.ParseResult result) {
        String[] lines = content.split("\n", -1);
        Set<Integer> blankedLines = new HashSet<>();
        GroovyCompilerService.ParseResult current = result;

        for (int attempt = 0; attempt < 5; attempt++) {
            if (current.getErrors() == null || current.getErrors().isEmpty()) {
                return null;
            }

            boolean blankedNew = blankErrorRanges(lines, blankedLines, current);
            if (!blankedNew && !blankPreviousCauseLine(lines, blankedLines, current)) {
                return null;
            }

            String patched = String.join("\n", lines);
            GroovyLanguageServerPlugin.logInfo(
                    "[semantic] Trying error-line-blanked content for " + uri
                            + " (attempt " + (attempt + 1) + ", blanked " + blankedLines.size() + " lines)");
            current = compilerService.parse(uri + "#semantic-patched-" + attempt, patched);

            if (current.hasAST()) {
                ModuleNode module = current.getModuleNode();
                int classCount = module.getClasses() != null ? module.getClasses().size() : 0;
                GroovyLanguageServerPlugin.logInfo(
                        "[semantic] Error-line-blanked content for " + uri + " classes=" + classCount);
                if (classCount > 0) {
                    return module;
                }
            }
        }
        return null;
    }

    private boolean blankErrorRanges(String[] lines,
            Set<Integer> blankedLines,
            GroovyCompilerService.ParseResult current) {
        boolean blankedNew = false;
        for (org.codehaus.groovy.syntax.SyntaxException error : current.getErrors()) {
            int startLine = error.getStartLine() - 1;
            int endLine = Math.max(startLine, error.getEndLine() - 1);
            int blockEnd = findClosingBrace(lines, startLine, endLine);
            for (int index = Math.max(0, startLine); index <= Math.min(blockEnd, lines.length - 1); index++) {
                if (blankedLines.add(index) && !lines[index].isBlank()) {
                    blankedNew = true;
                }
                lines[index] = "";
            }
        }
        return blankedNew;
    }

    private boolean blankPreviousCauseLine(String[] lines,
            Set<Integer> blankedLines,
            GroovyCompilerService.ParseResult current) {
        for (org.codehaus.groovy.syntax.SyntaxException error : current.getErrors()) {
            for (int scan = error.getStartLine() - 2; scan >= 0; scan--) {
                if (!blankedLines.contains(scan) && !lines[scan].isBlank()) {
                    blankedLines.add(scan);
                    lines[scan] = "";
                    return true;
                }
            }
        }
        return false;
    }

    private int findClosingBrace(String[] lines, int startLine, int endLine) {
        int depth = countBraceDelta(lines, Math.max(0, startLine), Math.min(endLine, lines.length - 1));
        if (depth <= 0) {
            return endLine;
        }

        int closingLine = findBalancedClosingLine(lines, Math.min(endLine, lines.length - 1) + 1, depth);
        if (closingLine >= 0) {
            return closingLine;
        }
        return lines.length - 1;
    }

    private int countBraceDelta(String[] lines, int startLine, int endLine) {
        int depth = 0;
        for (int index = startLine; index <= endLine; index++) {
            depth += countBraceDelta(lines[index]);
        }
        return depth;
    }

    private int countBraceDelta(String line) {
        int depth = 0;
        for (char ch : line.toCharArray()) {
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            }
        }
        return depth;
    }

    private int findBalancedClosingLine(String[] lines, int startLine, int depth) {
        int currentDepth = depth;
        for (int index = startLine; index < lines.length; index++) {
            currentDepth += countBraceDelta(lines[index]);
            if (currentDepth == 0) {
                return index;
            }
        }
        return -1;
    }
}