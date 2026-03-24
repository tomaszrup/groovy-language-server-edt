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
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

final class JavaRecordSourceSupport {

    private static final class ParseState {
        private int parenDepth;
        private int angleDepth;
        private int bracketDepth;
        private int braceDepth;
        private boolean inSingleQuote;
        private boolean inDoubleQuote;

        boolean isTopLevel() {
            return parenDepth == 0 && angleDepth == 0 && bracketDepth == 0 && braceDepth == 0;
        }
    }

    record RecordComponentInfo(String name, String type) {
    }

    private JavaRecordSourceSupport() {
    }

    static List<RecordComponentInfo> getRecordComponents(IType type) {
        if (type == null) {
            return Collections.emptyList();
        }

        try {
            String source = type.getSource();
            String typeName = type.getElementName();
            if (source == null || typeName == null || typeName.isBlank()) {
                return Collections.emptyList();
            }
            return parseRecordComponents(typeName, source);
        } catch (JavaModelException e) {
            return Collections.emptyList();
        }
    }

    private static List<RecordComponentInfo> parseRecordComponents(String typeName, String source) {
        String marker = "record " + typeName;
        int recordIndex = source.indexOf(marker);
        if (recordIndex < 0) {
            return Collections.emptyList();
        }

        int openParen = source.indexOf('(', recordIndex + marker.length());
        if (openParen < 0) {
            return Collections.emptyList();
        }

        int closeParen = findMatchingParen(source, openParen);
        if (closeParen < 0) {
            return Collections.emptyList();
        }

        String header = source.substring(openParen + 1, closeParen).trim();
        if (header.isEmpty()) {
            return Collections.emptyList();
        }

        List<RecordComponentInfo> components = new ArrayList<>();
        for (String component : splitTopLevel(header)) {
            RecordComponentInfo parsed = parseComponent(component);
            if (parsed != null) {
                components.add(parsed);
            }
        }
        return components;
    }

    private static int findMatchingParen(String text, int openParen) {
        ParseState state = new ParseState();

        for (int i = openParen; i < text.length(); i++) {
            char current = text.charAt(i);
            char previous = i > 0 ? text.charAt(i - 1) : '\0';

            boolean literal = shouldTreatAsLiteral(state, current, previous);
            if (!literal) {
                updateDepth(state, current);
                if (current == ')' && state.parenDepth == 0 && state.isTopLevel()) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static List<String> splitTopLevel(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        ParseState state = new ParseState();

        for (int i = 0; i < header.length(); i++) {
            char ch = header.charAt(i);
            char previous = i > 0 ? header.charAt(i - 1) : '\0';

            boolean literal = shouldTreatAsLiteral(state, ch, previous);
            boolean split = !literal && ch == ',' && state.isTopLevel();
            if (split) {
                addPart(parts, current);
            } else {
                if (!literal) {
                    updateDepth(state, ch);
                }
                current.append(ch);
            }
        }

        addPart(parts, current);
        return parts;
    }

    private static boolean shouldTreatAsLiteral(ParseState state, char current, char previous) {
        if (current == '\'' && !state.inDoubleQuote && previous != '\\') {
            state.inSingleQuote = !state.inSingleQuote;
            return true;
        }
        if (current == '"' && !state.inSingleQuote && previous != '\\') {
            state.inDoubleQuote = !state.inDoubleQuote;
            return true;
        }
        return state.inSingleQuote || state.inDoubleQuote;
    }

    private static void updateDepth(ParseState state, char current) {
        switch (current) {
        case '(':
            state.parenDepth++;
            break;
        case ')':
            if (state.parenDepth > 0) {
                state.parenDepth--;
            }
            break;
        case '<':
            state.angleDepth++;
            break;
        case '>':
            if (state.angleDepth > 0) {
                state.angleDepth--;
            }
            break;
        case '[':
            state.bracketDepth++;
            break;
        case ']':
            if (state.bracketDepth > 0) {
                state.bracketDepth--;
            }
            break;
        case '{':
            state.braceDepth++;
            break;
        case '}':
            if (state.braceDepth > 0) {
                state.braceDepth--;
            }
            break;
        default:
            break;
        }
    }

    private static void addPart(List<String> parts, StringBuilder current) {
        String part = current.toString().trim();
        if (!part.isEmpty()) {
            parts.add(part);
        }
        current.setLength(0);
    }

    private static RecordComponentInfo parseComponent(String component) {
        String trimmed = component.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int nameEnd = trimmed.length() - 1;
        while (nameEnd >= 0 && Character.isWhitespace(trimmed.charAt(nameEnd))) {
            nameEnd--;
        }
        if (nameEnd < 0) {
            return null;
        }

        int nameStart = nameEnd;
        while (nameStart >= 0 && Character.isJavaIdentifierPart(trimmed.charAt(nameStart))) {
            nameStart--;
        }
        nameStart++;
        if (nameStart > nameEnd) {
            return null;
        }

        String name = trimmed.substring(nameStart, nameEnd + 1);
        String type = normalizeWhitespace(trimmed.substring(0, nameStart).trim());
        if (name.isBlank() || type.isBlank()) {
            return null;
        }

        return new RecordComponentInfo(name, type);
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean lastWasWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!lastWasWhitespace) {
                    normalized.append(' ');
                    lastWasWhitespace = true;
                }
            } else {
                normalized.append(ch);
                lastWasWhitespace = false;
            }
        }
        return normalized.toString().trim();
    }
}