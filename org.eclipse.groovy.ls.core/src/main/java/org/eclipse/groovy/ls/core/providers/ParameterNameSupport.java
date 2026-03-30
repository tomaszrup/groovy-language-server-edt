package org.eclipse.groovy.ls.core.providers;

import java.util.regex.Pattern;

import org.codehaus.groovy.ast.Parameter;

final class ParameterNameSupport {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(?:arg|args|p)\\d+");

    private ParameterNameSupport() {
    }

    static boolean isMeaningfulName(String parameterName) {
        return parameterName != null
                && !parameterName.isBlank()
                && !PLACEHOLDER_PATTERN.matcher(parameterName).matches();
    }

    static String displayName(String parameterName) {
        return isMeaningfulName(parameterName) ? parameterName : null;
    }

    static int placeholderPenalty(Parameter[] parameters, int argumentCount) {
        if (parameters == null || parameters.length == 0) {
            return 0;
        }

        int max = argumentCount <= 0 ? parameters.length : Math.min(argumentCount, parameters.length);
        int penalty = 0;
        for (int index = 0; index < max; index++) {
            if (!isMeaningfulName(parameters[index].getName())) {
                penalty++;
            }
        }
        return penalty;
    }
}