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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InlayHintSettingsTest {

    @Test
    void defaultsEnablesAllHintTypes() {
        InlayHintSettings defaults = InlayHintSettings.defaults();

        assertTrue(defaults.isVariableTypesEnabled());
        assertTrue(defaults.isParameterNamesEnabled());
        assertTrue(defaults.isClosureParameterTypesEnabled());
        assertTrue(defaults.isMethodReturnTypesEnabled());
    }

    @Test
    void constructorAppliesCustomFeatureFlags() {
        InlayHintSettings settings = new InlayHintSettings(false, true, false, true);

        assertFalse(settings.isVariableTypesEnabled());
        assertTrue(settings.isParameterNamesEnabled());
        assertFalse(settings.isClosureParameterTypesEnabled());
        assertTrue(settings.isMethodReturnTypesEnabled());
    }
}
