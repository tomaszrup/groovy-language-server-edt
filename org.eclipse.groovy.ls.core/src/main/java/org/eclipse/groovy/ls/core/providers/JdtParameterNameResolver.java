package org.eclipse.groovy.ls.core.providers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

final class JdtParameterNameResolver {

    private static final int MAX_PARAMETER_COUNT = 1000;

    private JdtParameterNameResolver() {
    }

    static String[] resolve(IMethod method) {
        int parameterCount = getParameterCount(method);
        if (parameterCount <= 0 || parameterCount > MAX_PARAMETER_COUNT) {
            return new String[0];
        }

        String[] rawNames = readRawParameterNames(method);
        boolean replaceSyntheticNames = isConstructor(method) && containsSyntheticNames(rawNames);

        if (replaceSyntheticNames) {
            String[] recoveredNames = recoverConstructorFieldNames(method, parameterCount);
            if (recoveredNames != null) {
                return recoveredNames;
            }
        }

        if (needsSourceRecovery(rawNames, parameterCount, replaceSyntheticNames)) {
            String[] recoveredNames = recoverSourceParameterNames(method, parameterCount);
            if (recoveredNames != null) {
                return recoveredNames;
            }
        }

        return normalizeRawNames(rawNames, parameterCount, replaceSyntheticNames);
    }

    private static int getParameterCount(IMethod method) {
        try {
            return method.getParameterTypes().length;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String[] readRawParameterNames(IMethod method) {
        try {
            return method.getParameterNames();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isConstructor(IMethod method) {
        try {
            return method != null && method.isConstructor();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean containsSyntheticNames(String[] parameterNames) {
        if (parameterNames == null || parameterNames.length == 0) {
            return false;
        }

        for (String parameterName : parameterNames) {
            if (isSyntheticParameterName(parameterName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsSourceRecovery(String[] rawNames, int parameterCount, boolean replaceSyntheticNames) {
        if (rawNames == null || rawNames.length != parameterCount) {
            return true;
        }

        for (int index = 0; index < parameterCount; index++) {
            String rawName = rawNames[index];
            if (rawName == null || rawName.isBlank()) {
                return true;
            }
            if ((replaceSyntheticNames && isSyntheticParameterName(rawName)) || isDefaultArgumentName(rawName, index)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSyntheticParameterName(String parameterName) {
        if (parameterName == null || parameterName.length() < 2 || parameterName.charAt(0) != 'p') {
            return false;
        }

        for (int index = 1; index < parameterName.length(); index++) {
            if (!Character.isDigit(parameterName.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultArgumentName(String parameterName, int index) {
        return parameterName != null && parameterName.equals("arg" + index);
    }

    private static String[] recoverConstructorFieldNames(IMethod method, int parameterCount) {
        try {
            IType declaringType = method.getDeclaringType();
            if (declaringType == null) {
                return null;
            }

            String[] parameterTypes = method.getParameterTypes();
            if (parameterTypes == null || parameterTypes.length != parameterCount) {
                return null;
            }

            List<String> resolvedNames = new ArrayList<>(parameterCount);
            int parameterIndex = 0;
            for (IField field : declaringType.getFields()) {
                if (parameterIndex >= parameterCount || shouldSkipField(field)) {
                    continue;
                }
                if (signaturesCompatible(field.getTypeSignature(), parameterTypes[parameterIndex])) {
                    resolvedNames.add(field.getElementName());
                    parameterIndex++;
                }
            }

            if (parameterIndex != parameterCount) {
                return null;
            }
            return resolvedNames.toArray(String[]::new);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String[] recoverSourceParameterNames(IMethod method, int parameterCount) {
        try {
            String source = method.getSource();
            if (source == null || source.isBlank()) {
                return null;
            }

            int parameterListStart = findParameterListStart(source, method.getElementName());
            if (parameterListStart < 0) {
                return null;
            }

            int parameterListEnd = findMatchingParen(source, parameterListStart);
            if (parameterListEnd < 0) {
                return null;
            }

            String parameterList = source.substring(parameterListStart + 1, parameterListEnd).trim();
            if (parameterList.isEmpty()) {
                return parameterCount == 0 ? new String[0] : null;
            }

            List<String> declarations = splitTopLevelParameters(parameterList);
            if (declarations.size() != parameterCount) {
                return null;
            }

            String[] recoveredNames = new String[parameterCount];
            for (int index = 0; index < parameterCount; index++) {
                String parameterName = extractDeclaredParameterName(declarations.get(index));
                if (parameterName == null || parameterName.isBlank()) {
                    return null;
                }
                recoveredNames[index] = parameterName;
            }
            return recoveredNames;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int findParameterListStart(String source, String methodName) {
        if (source == null || source.isBlank()) {
            return -1;
        }

        if (methodName != null && !methodName.isBlank()) {
            int searchFrom = 0;
            while (searchFrom >= 0 && searchFrom < source.length()) {
                int nameIndex = source.indexOf(methodName, searchFrom);
                if (nameIndex < 0) {
                    break;
                }

                int nameEnd = nameIndex + methodName.length();
                if (isIdentifierBoundary(source, nameIndex - 1)
                        && isIdentifierBoundary(source, nameEnd)) {
                    int next = skipWhitespace(source, nameEnd);
                    if (next >= 0 && next < source.length() && source.charAt(next) == '(') {
                        return next;
                    }
                }

                searchFrom = nameEnd;
            }
        }

        return -1;
    }

    private static boolean isIdentifierBoundary(String source, int index) {
        return index < 0
                || index >= source.length()
                || !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length() && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int findMatchingParen(String source, int openParenIndex) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = openParenIndex + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (inString) {
                if (!escaping && current == '"') {
                    inString = false;
                }
                escaping = current == '\\' && !escaping;
                continue;
            }

            if (inChar) {
                if (!escaping && current == '\'') {
                    inChar = false;
                }
                escaping = current == '\\' && !escaping;
                continue;
            }

            if (current == '/' && next == '/') {
                index = skipLineComment(source, index + 2);
                continue;
            }

            if (current == '/' && next == '*') {
                index = skipBlockComment(source, index + 2);
                continue;
            }

            if (current == '"') {
                inString = true;
                escaping = false;
                continue;
            }

            if (current == '\'') {
                inChar = true;
                escaping = false;
                continue;
            }

            if (current == '(') {
                depth++;
            } else if (current == ')') {
                if (depth == 0) {
                    return index;
                }
                depth--;
            }
        }

        return -1;
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
                return current + 1;
            }
            current++;
        }
        return source.length() - 1;
    }

    private static List<String> splitTopLevelParameters(String parameterList) {
        List<String> parameters = new ArrayList<>();
        int tokenStart = 0;
        int parenDepth = 0;
        int angleDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = 0; index < parameterList.length(); index++) {
            char current = parameterList.charAt(index);
            char next = index + 1 < parameterList.length() ? parameterList.charAt(index + 1) : '\0';

            if (inString) {
                if (!escaping && current == '"') {
                    inString = false;
                }
                escaping = current == '\\' && !escaping;
                continue;
            }

            if (inChar) {
                if (!escaping && current == '\'') {
                    inChar = false;
                }
                escaping = current == '\\' && !escaping;
                continue;
            }

            if (current == '/' && next == '/') {
                index = skipLineComment(parameterList, index + 2);
                continue;
            }

            if (current == '/' && next == '*') {
                index = skipBlockComment(parameterList, index + 2);
                continue;
            }

            if (current == '"') {
                inString = true;
                escaping = false;
                continue;
            }

            if (current == '\'') {
                inChar = true;
                escaping = false;
                continue;
            }

            switch (current) {
            case '(': parenDepth++; break;
            case ')': if (parenDepth > 0) parenDepth--; break;
            case '<': angleDepth++; break;
            case '>': if (angleDepth > 0) angleDepth--; break;
            case '[': bracketDepth++; break;
            case ']': if (bracketDepth > 0) bracketDepth--; break;
            case '{': braceDepth++; break;
            case '}': if (braceDepth > 0) braceDepth--; break;
            case ',':
                if (parenDepth == 0 && angleDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    parameters.add(parameterList.substring(tokenStart, index).trim());
                    tokenStart = index + 1;
                }
                break;
            default:
                break;
            }
        }

        parameters.add(parameterList.substring(tokenStart).trim());
        return parameters;
    }

    private static String extractDeclaredParameterName(String parameterDeclaration) {
        if (parameterDeclaration == null || parameterDeclaration.isBlank()) {
            return null;
        }

        int end = parameterDeclaration.length() - 1;
        while (end >= 0) {
            while (end >= 0 && Character.isWhitespace(parameterDeclaration.charAt(end))) {
                end--;
            }
            if (end >= 0 && parameterDeclaration.charAt(end) == ']') {
                end = skipArrayDeclarator(parameterDeclaration, end);
                continue;
            }
            break;
        }

        if (end < 0 || !Character.isJavaIdentifierPart(parameterDeclaration.charAt(end))) {
            return null;
        }

        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(parameterDeclaration.charAt(start))) {
            start--;
        }
        return parameterDeclaration.substring(start + 1, end + 1);
    }

    private static int skipArrayDeclarator(String parameterDeclaration, int end) {
        int current = end;
        while (current >= 0 && Character.isWhitespace(parameterDeclaration.charAt(current))) {
            current--;
        }
        if (current >= 0 && parameterDeclaration.charAt(current) == ']') {
            current--;
            while (current >= 0 && Character.isWhitespace(parameterDeclaration.charAt(current))) {
                current--;
            }
            if (current >= 0 && parameterDeclaration.charAt(current) == '[') {
                current--;
            }
        }
        return current;
    }

    private static boolean shouldSkipField(IField field) {
        try {
            String fieldName = field.getElementName();
            return fieldName == null
                    || fieldName.isBlank()
                    || fieldName.startsWith("$")
                    || Flags.isStatic(field.getFlags());
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean signaturesCompatible(String fieldSignature, String parameterSignature) {
        if (fieldSignature == null || parameterSignature == null) {
            return false;
        }

        String fieldErasure = Signature.getTypeErasure(fieldSignature);
        String parameterErasure = Signature.getTypeErasure(parameterSignature);
        if (fieldErasure.equals(parameterErasure)) {
            return true;
        }

        try {
            return Signature.toString(fieldErasure).equals(Signature.toString(parameterErasure));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String[] normalizeRawNames(String[] rawNames, int parameterCount, boolean replaceSyntheticNames) {
        String[] resolvedNames = new String[parameterCount];
        for (int index = 0; index < parameterCount; index++) {
            String rawName = rawNames != null && index < rawNames.length ? rawNames[index] : null;
            if (rawName != null && !rawName.isBlank() && !(replaceSyntheticNames && isSyntheticParameterName(rawName))) {
                resolvedNames[index] = rawName;
            } else {
                resolvedNames[index] = "arg" + index;
            }
        }
        return resolvedNames;
    }
}