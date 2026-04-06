package org.eclipse.groovy.ls.core.providers;

import java.util.ArrayList;
import java.util.List;

final class JdtSourceParameterParser {

    static final String[] NO_NAMES = new String[0];

    private JdtSourceParameterParser() {
    }

    static String[] recoverSourceParameterNames(String source, String methodName, int parameterCount) {
        if (source == null || source.isBlank()) {
            return NO_NAMES;
        }

        int parameterListStart = findParameterListStart(source, methodName);
        if (parameterListStart < 0) {
            return NO_NAMES;
        }

        int parameterListEnd = findMatchingParen(source, parameterListStart);
        if (parameterListEnd < 0) {
            return NO_NAMES;
        }

        String parameterList = source.substring(parameterListStart + 1, parameterListEnd).trim();
        if (parameterList.isEmpty()) {
            return NO_NAMES;
        }

        List<String> declarations = splitTopLevelParameters(parameterList);
        if (declarations.size() != parameterCount) {
            return NO_NAMES;
        }

        String[] recoveredNames = new String[parameterCount];
        for (int index = 0; index < parameterCount; index++) {
            String parameterName = extractDeclaredParameterName(declarations.get(index));
            if (parameterName == null || parameterName.isBlank()) {
                return NO_NAMES;
            }
            recoveredNames[index] = parameterName;
        }
        return recoveredNames;
    }

    private static int findParameterListStart(String source, String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return -1;
        }

        int searchFrom = 0;
        while (searchFrom >= 0 && searchFrom < source.length()) {
            int nameIndex = source.indexOf(methodName, searchFrom);
            if (nameIndex < 0) {
                return -1;
            }

            int nameEnd = nameIndex + methodName.length();
            if (isIdentifierBoundary(source, nameIndex - 1) && isIdentifierBoundary(source, nameEnd)) {
                int next = skipWhitespace(source, nameEnd);
                if (next < source.length() && source.charAt(next) == '(') {
                    return next;
                }
            }

            searchFrom = nameEnd;
        }
        return -1;
    }

    private static boolean isIdentifierBoundary(String source, int index) {
        return index < 0 || index >= source.length() || !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length() && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int findMatchingParen(String source, int openParenIndex) {
        DelimiterState delimiters = new DelimiterState();
        LiteralState literals = new LiteralState();
        int index = openParenIndex + 1;

        while (index < source.length()) {
            int commentEnd = skipComment(source, index);
            if (commentEnd != index) {
                index = commentEnd;
            } else {
                if (isMatchingParen(source.charAt(index), literals, delimiters)) {
                    return index;
                }
                index++;
            }
        }

        return -1;
    }

    private static boolean isMatchingParen(char current, LiteralState literals, DelimiterState delimiters) {
        if (literals.consumeQuoted(current) || literals.startQuoted(current)) {
            return false;
        }
        if (current == '(') {
            delimiters.incrementParen();
            return false;
        }
        if (current == ')') {
            if (delimiters.isTopLevelParen()) {
                return true;
            }
            delimiters.decrementParen();
        }
        return false;
    }

    private static List<String> splitTopLevelParameters(String parameterList) {
        List<String> parameters = new ArrayList<>();
        DelimiterState delimiters = new DelimiterState();
        LiteralState literals = new LiteralState();
        int tokenStart = 0;
        int index = 0;

        while (index < parameterList.length()) {
            int commentEnd = skipComment(parameterList, index);
            if (commentEnd != index) {
                index = commentEnd;
            } else {
                char current = parameterList.charAt(index);
                if (!literals.consumeQuoted(current) && !literals.startQuoted(current)) {
                    if (current == ',' && delimiters.isTopLevel()) {
                        parameters.add(parameterList.substring(tokenStart, index).trim());
                        tokenStart = index + 1;
                    } else {
                        delimiters.consume(current);
                    }
                }
                index++;
            }
        }

        parameters.add(parameterList.substring(tokenStart).trim());
        return parameters;
    }

    private static int skipComment(String source, int index) {
        if (index + 1 >= source.length() || source.charAt(index) != '/') {
            return index;
        }

        char next = source.charAt(index + 1);
        if (next == '/') {
            return skipLineComment(source, index + 2);
        }
        if (next == '*') {
            return skipBlockComment(source, index + 2);
        }
        return index;
    }

    private static int skipLineComment(String source, int index) {
        int current = index;
        while (current < source.length() && source.charAt(current) != '\n' && source.charAt(current) != '\r') {
            current++;
        }
        return current;
    }

    private static int skipBlockComment(String source, int index) {
        int current = index;
        while (current + 1 < source.length()) {
            if (source.charAt(current) == '*' && source.charAt(current + 1) == '/') {
                return current + 2;
            }
            current++;
        }
        return source.length();
    }

    private static String extractDeclaredParameterName(String parameterDeclaration) {
        if (parameterDeclaration == null || parameterDeclaration.isBlank()) {
            return null;
        }

        int end = trimTrailingArrayDeclarators(parameterDeclaration, parameterDeclaration.length() - 1);
        if (end < 0 || !Character.isJavaIdentifierPart(parameterDeclaration.charAt(end))) {
            return null;
        }

        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(parameterDeclaration.charAt(start))) {
            start--;
        }
        return parameterDeclaration.substring(start + 1, end + 1);
    }

    private static int trimTrailingArrayDeclarators(String declaration, int end) {
        int current = trimWhitespaceBackward(declaration, end);
        while (current >= 0 && declaration.charAt(current) == ']') {
            current = trimWhitespaceBackward(declaration, current - 1);
            if (current < 0 || declaration.charAt(current) != '[') {
                return current;
            }
            current = trimWhitespaceBackward(declaration, current - 1);
        }
        return current;
    }

    private static int trimWhitespaceBackward(String text, int index) {
        int current = index;
        while (current >= 0 && Character.isWhitespace(text.charAt(current))) {
            current--;
        }
        return current;
    }

    private static final class LiteralState {
        private boolean inString;
        private boolean inChar;
        private boolean escaping;

        private boolean consumeQuoted(char current) {
            if (!inString && !inChar) {
                return false;
            }

            if (!escaping && ((inString && current == '"') || (inChar && current == '\''))) {
                inString = false;
                inChar = false;
            }
            escaping = current == '\\' && !escaping;
            return true;
        }

        private boolean startQuoted(char current) {
            if (current == '"') {
                inString = true;
                escaping = false;
                return true;
            }
            if (current == '\'') {
                inChar = true;
                escaping = false;
                return true;
            }
            return false;
        }
    }

    private static final class DelimiterState {
        private int parenDepth;
        private int angleDepth;
        private int bracketDepth;
        private int braceDepth;

        private void incrementParen() {
            parenDepth++;
        }

        private void decrementParen() {
            parenDepth = Math.max(0, parenDepth - 1);
        }

        private boolean isTopLevelParen() {
            return parenDepth == 0;
        }

        private boolean isTopLevel() {
            return parenDepth == 0 && angleDepth == 0 && bracketDepth == 0 && braceDepth == 0;
        }

        private void consume(char current) {
            switch (current) {
            case '(' -> parenDepth++;
            case ')' -> parenDepth = Math.max(0, parenDepth - 1);
            case '<' -> angleDepth++;
            case '>' -> angleDepth = Math.max(0, angleDepth - 1);
            case '[' -> bracketDepth++;
            case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
            case '{' -> braceDepth++;
            case '}' -> braceDepth = Math.max(0, braceDepth - 1);
            default -> {
                // No delimiter depth change.
            }
            }
        }
    }
}