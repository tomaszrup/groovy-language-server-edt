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

/**
 * User-configurable inlay hint feature toggles.
 */
public class InlayHintSettings {

    private final boolean variableTypesEnabled;
    private final boolean parameterNamesEnabled;
    private final boolean closureParameterTypesEnabled;
    private final boolean methodReturnTypesEnabled;

    public InlayHintSettings(
            boolean variableTypesEnabled,
            boolean parameterNamesEnabled,
            boolean closureParameterTypesEnabled,
            boolean methodReturnTypesEnabled) {
        this.variableTypesEnabled = variableTypesEnabled;
        this.parameterNamesEnabled = parameterNamesEnabled;
        this.closureParameterTypesEnabled = closureParameterTypesEnabled;
        this.methodReturnTypesEnabled = methodReturnTypesEnabled;
    }

    public static InlayHintSettings defaults() {
        return new InlayHintSettings(true, true, true, true);
    }

    public boolean isVariableTypesEnabled() {
        return variableTypesEnabled;
    }

    public boolean isParameterNamesEnabled() {
        return parameterNamesEnabled;
    }

    public boolean isClosureParameterTypesEnabled() {
        return closureParameterTypesEnabled;
    }

    public boolean isMethodReturnTypesEnabled() {
        return methodReturnTypesEnabled;
    }
}