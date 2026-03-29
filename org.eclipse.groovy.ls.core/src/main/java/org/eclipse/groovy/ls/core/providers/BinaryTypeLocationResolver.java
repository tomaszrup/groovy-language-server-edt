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

import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class BinaryTypeLocationResolver {

    private static final String EXT_JAVA = ".java";
    private static final String EXT_GROOVY = ".groovy";

    private BinaryTypeLocationResolver() {
    }

    static Location resolveLocation(IType type) {
        return resolveLocation(type, SourceJarHelper.binaryTypeFqn(type), type.getElementName());
    }

    static Location resolveLocation(IType type, String fqn, String simpleName) {
        Location jarLoc = toLocationFromSourcesJar(type, fqn, simpleName);
        if (jarLoc != null) {
            return jarLoc;
        }

        return toLocationFromJdtAttachment(type, fqn, simpleName);
    }

    static Location toLocationFromSourcesJar(IType type, String fqn, String simpleName) {
        java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
        if (sourcesJar == null) {
            return null;
        }

        String source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
        if (source == null || source.isEmpty()) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Read source from JAR for " + fqn + " (" + source.length() + " chars)");

        String ext = determineSourceExtension(sourcesJar, fqn);
        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                fqn, ext, sourcesJar.getAbsolutePath(), false, source);
        Range range = findDeclarationRange(source, type, simpleName);
        return new Location(virtualUri, range);
    }

    static Location toLocationFromJdtAttachment(IType type, String fqn, String simpleName) {
        IClassFile classFile = type.getClassFile();
        if (classFile == null) {
            return null;
        }

        String source = null;
        try {
            source = classFile.getSource();
        } catch (Exception e) {
            // no source available
        }

        if (source == null || source.isEmpty()) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Found source via JDT attachment for " + fqn);
        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                fqn, EXT_JAVA, null, false, source);
        Range range = findDeclarationRange(source, type, simpleName);
        return new Location(virtualUri, range);
    }

    static String determineSourceExtension(java.io.File sourcesJar, String fqn) {
        String entryPath = SourceJarHelper.sourceFileFqn(fqn).replace('.', '/') + EXT_GROOVY;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(sourcesJar)) {
            if (zf.getEntry(entryPath) != null) {
                return EXT_GROOVY;
            }
        } catch (Exception e) {
            // Intentionally ignored — default to .java
        }
        return EXT_JAVA;
    }

    static Range findClassDeclarationRange(String source, String simpleName) {
        int classIdx = source.indexOf("class " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("interface " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("enum " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("trait " + simpleName);
        if (classIdx >= 0) {
            PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(source);
            Position start = lineIndex.offsetToPosition(classIdx);
            Position end = lineIndex.offsetToPosition(classIdx + simpleName.length() + 6);
            return new Range(start, end);
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }

    static Range findDeclarationRange(String source, IJavaElement element, String simpleName) {
        Range sourceReferenceRange = findSourceReferenceRange(source, element);
        if (sourceReferenceRange != null) {
            return sourceReferenceRange;
        }

        SearchWindow searchWindow = findDeclaringTypeBody(source, element);

        if (element instanceof IMethod method) {
            Range methodRange = findMethodDeclarationRange(source, method, searchWindow);
            if (methodRange != null) {
                return methodRange;
            }
        }

        if (element instanceof IField field) {
            Range fieldRange = findFieldDeclarationRange(source, field, searchWindow);
            if (fieldRange != null) {
                return fieldRange;
            }
        }

        return findClassDeclarationRange(source, simpleName);
    }

    private static Range findSourceReferenceRange(String source, IJavaElement element) {
        if (!(element instanceof ISourceReference sourceReference)) {
            return null;
        }

        try {
            ISourceRange nameRange = sourceReference.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return null;
            }
            return toRange(source, nameRange.getOffset(), nameRange.getLength());
        } catch (Exception e) {
            return null;
        }
    }

    private static Range findMethodDeclarationRange(String source, IMethod method, SearchWindow searchWindow) {
        String simpleName = method.getElementName();
        int searchFrom = searchWindow != null ? searchWindow.start : 0;
        int searchLimit = searchWindow != null ? searchWindow.end : source.length();
        while (searchFrom >= 0 && searchFrom < searchLimit) {
            int match = source.indexOf(simpleName, searchFrom);
            if (match < 0 || match >= searchLimit) {
                return null;
            }

            if (isIdentifierBoundary(source, match - 1)
                    && isIdentifierBoundary(source, match + simpleName.length())) {
                int next = skipWhitespace(source, match + simpleName.length());
                if (next < searchLimit && source.charAt(next) == '('
                        && isDirectMemberDeclaration(source, match, searchWindow)
                        && looksLikeMethodDeclaration(source, match, next)) {
                    return toRange(source, match, simpleName.length());
                }
            }

            searchFrom = match + simpleName.length();
        }

        return null;
    }

    private static Range findFieldDeclarationRange(String source, IField field, SearchWindow searchWindow) {
        String simpleName = field.getElementName();
        int searchFrom = searchWindow != null ? searchWindow.start : 0;
        int searchLimit = searchWindow != null ? searchWindow.end : source.length();
        while (searchFrom >= 0 && searchFrom < searchLimit) {
            int match = source.indexOf(simpleName, searchFrom);
            if (match < 0 || match >= searchLimit) {
                return null;
            }

            if (isIdentifierBoundary(source, match - 1)
                    && isIdentifierBoundary(source, match + simpleName.length())) {
                int next = skipWhitespace(source, match + simpleName.length());
                if (next < searchLimit
                        && ",;=".indexOf(source.charAt(next)) >= 0
                        && isDirectMemberDeclaration(source, match, searchWindow)
                        && looksLikeFieldDeclaration(source, match, next)) {
                    return toRange(source, match, simpleName.length());
                }
            }

            searchFrom = match + simpleName.length();
        }

        return null;
    }

    private static boolean isIdentifierBoundary(String source, int index) {
        if (index < 0 || index >= source.length()) {
            return true;
        }
        return !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static boolean looksLikeMethodDeclaration(String source, int match, int openParen) {
        int previousSignificant = skipWhitespaceBackward(source, match - 1);
        if (previousSignificant >= 0 && ".=:+-*/%!?&|,<([{\"'".indexOf(source.charAt(previousSignificant)) >= 0) {
            return false;
        }

        int closingParen = findMatchingDelimiter(source, openParen, '(', ')');
        if (closingParen < 0) {
            return false;
        }

        int afterSignature = skipWhitespace(source, closingParen + 1);
        if (startsWithWord(source, afterSignature, "throws")) {
            afterSignature = skipThrowsClause(source, afterSignature + "throws".length());
        }
        if (afterSignature >= source.length()) {
            return false;
        }

        char terminator = source.charAt(afterSignature);
        if (terminator != '{' && terminator != ';') {
            return false;
        }

        if (previousSignificant < 0) {
            return terminator == '{';
        }

        String prefixToken = lastToken(source, match);
        return !isControlFlowToken(prefixToken);
    }

    private static boolean looksLikeFieldDeclaration(String source, int match, int next) {
        int previousSignificant = skipWhitespaceBackward(source, match - 1);
        if (previousSignificant >= 0 && ".=:+-*/%!?&|,<([{\"'".indexOf(source.charAt(previousSignificant)) >= 0) {
            return false;
        }

        if (previousSignificant < 0) {
            return source.charAt(next) == ',' || source.charAt(next) == ';';
        }

        String prefixToken = lastToken(source, match);
        return !isControlFlowToken(prefixToken);
    }

    private static SearchWindow findDeclaringTypeBody(String source, IJavaElement element) {
        IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
        if (!(ancestor instanceof IType declaringType)) {
            return null;
        }

        String simpleName = declaringType.getElementName();
        if (simpleName == null || simpleName.isBlank()) {
            return null;
        }

        int declaration = findTypeDeclarationOffset(source, simpleName);
        if (declaration < 0) {
            return null;
        }

        int typeName = source.indexOf(simpleName, declaration);
        if (typeName < 0) {
            return null;
        }

        int openBrace = source.indexOf('{', typeName + simpleName.length());
        if (openBrace < 0) {
            return null;
        }

        int closeBrace = findMatchingDelimiter(source, openBrace, '{', '}');
        if (closeBrace < 0) {
            return null;
        }

        return new SearchWindow(openBrace + 1, closeBrace);
    }

    private static int findTypeDeclarationOffset(String source, String simpleName) {
        String[] keywords = {"class ", "interface ", "enum ", "trait ", "record "};
        for (String keyword : keywords) {
            int searchFrom = 0;
            while (searchFrom >= 0 && searchFrom < source.length()) {
                int match = source.indexOf(keyword + simpleName, searchFrom);
                if (match < 0) {
                    break;
                }
                int nameOffset = match + keyword.length();
                if (isIdentifierBoundary(source, nameOffset + simpleName.length())) {
                    return match;
                }
                searchFrom = nameOffset + simpleName.length();
            }
        }
        return -1;
    }

    private static boolean isDirectMemberDeclaration(String source, int match, SearchWindow searchWindow) {
        if (searchWindow == null) {
            return true;
        }

        int depth = 0;
        for (int index = searchWindow.start; index < match && index < searchWindow.end; index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth == 0;
    }

    private static int skipWhitespaceBackward(String source, int index) {
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        return index;
    }

    private static int skipWhitespace(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int skipThrowsClause(String source, int index) {
        int current = index;
        while (current < source.length()) {
            char ch = source.charAt(current);
            if (ch == '{' || ch == ';') {
                return current;
            }
            current++;
        }
        return current;
    }

    private static boolean startsWithWord(String source, int index, String word) {
        if (index < 0 || index + word.length() > source.length()) {
            return false;
        }
        if (!source.regionMatches(index, word, 0, word.length())) {
            return false;
        }
        return isIdentifierBoundary(source, index - 1)
                && isIdentifierBoundary(source, index + word.length());
    }

    private static int findMatchingDelimiter(String source, int openIndex, char openChar, char closeChar) {
        int depth = 0;
        for (int index = openIndex; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == openChar) {
                depth++;
            } else if (current == closeChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String lastToken(String source, int match) {
        int end = skipWhitespaceBackward(source, match - 1);
        if (end < 0) {
            return "";
        }

        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
            start--;
        }
        return source.substring(start + 1, end + 1);
    }

    private static boolean isControlFlowToken(String token) {
        return "return".equals(token)
                || "throw".equals(token)
                || "case".equals(token)
                || "assert".equals(token)
                || "if".equals(token)
                || "for".equals(token)
                || "while".equals(token)
                || "switch".equals(token)
                || "catch".equals(token)
                || "new".equals(token);
    }

    private static final class SearchWindow {
        final int start;
        final int end;

        private SearchWindow(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static Range toRange(String source, int offset, int length) {
        int startOffset = Math.max(0, Math.min(offset, source.length()));
        int endOffset = Math.max(startOffset, Math.min(offset + Math.max(length, 0), source.length()));
        PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(source);
        Position start = lineIndex.offsetToPosition(startOffset);
        Position end = lineIndex.offsetToPosition(endOffset);
        return new Range(start, end);
    }
}
