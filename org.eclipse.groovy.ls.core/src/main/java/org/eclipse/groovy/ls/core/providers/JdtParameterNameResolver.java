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
            if (recoveredNames.length == parameterCount) {
                return recoveredNames;
            }
        }

        if (needsSourceRecovery(rawNames, parameterCount, replaceSyntheticNames)) {
            String[] recoveredNames = recoverSourceParameterNames(method, parameterCount);
            if (recoveredNames.length == parameterCount) {
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
            return JdtSourceParameterParser.NO_NAMES;
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
            if (isSyntheticParameterName(parameterName) || !ParameterNameSupport.isMeaningfulName(parameterName)) {
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
            if ((replaceSyntheticNames && isSyntheticParameterName(rawName))
                    || isDefaultArgumentName(rawName, index)
                    || !ParameterNameSupport.isMeaningfulName(rawName)) {
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
        return parameterName != null && (parameterName.equals("arg" + index) || parameterName.equals("args" + index));
    }

    private static String[] recoverConstructorFieldNames(IMethod method, int parameterCount) {
        try {
            IType declaringType = method.getDeclaringType();
            if (declaringType == null) {
                return JdtSourceParameterParser.NO_NAMES;
            }

            String[] parameterTypes = method.getParameterTypes();
            if (parameterTypes == null || parameterTypes.length != parameterCount) {
                return JdtSourceParameterParser.NO_NAMES;
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
                return JdtSourceParameterParser.NO_NAMES;
            }
            return resolvedNames.toArray(String[]::new);
        } catch (Exception ignored) {
            return JdtSourceParameterParser.NO_NAMES;
        }
    }

    private static String[] recoverSourceParameterNames(IMethod method, int parameterCount) {
        try {
            return JdtSourceParameterParser.recoverSourceParameterNames(
                    method.getSource(),
                    method.getElementName(),
                    parameterCount);
        } catch (Exception ignored) {
            return JdtSourceParameterParser.NO_NAMES;
        }
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
            if (rawName != null
                    && !rawName.isBlank()
                    && !(replaceSyntheticNames && isSyntheticParameterName(rawName))
                    && ParameterNameSupport.isMeaningfulName(rawName)) {
                resolvedNames[index] = rawName;
            } else {
                resolvedNames[index] = "arg" + index;
            }
        }
        return resolvedNames;
    }
}