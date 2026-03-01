/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.groovy.ls.core.providers.InlayHintSettings;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class GroovyWorkspaceServiceHelpersTest {

    @Test
    void parseInlayHintSettingsUsesDefaultsForNull() throws Exception {
        GroovyWorkspaceService service = createService();

        InlayHintSettings settings = (InlayHintSettings) invoke(
                service,
                "parseInlayHintSettings",
                new Class<?>[] { JsonObject.class },
                new Object[] { null });

        assertTrue(settings.isVariableTypesEnabled());
        assertTrue(settings.isParameterNamesEnabled());
        assertTrue(settings.isClosureParameterTypesEnabled());
        assertTrue(settings.isMethodReturnTypesEnabled());
    }

    @Test
        void parseInlayHintSettingsReadsNestedFlagsAndCoercesStringBooleans() throws Exception {
        GroovyWorkspaceService service = createService();

        JsonObject inlayHints = new JsonObject();
        inlayHints.add("variableTypes", objectWith("enabled", false));
        inlayHints.add("parameterNames", objectWith("enabled", true));
        inlayHints.add("closureParameterTypes", objectWith("enabled", false));

        JsonObject methodReturnTypes = new JsonObject();
        methodReturnTypes.addProperty("enabled", "not-a-boolean");
        inlayHints.add("methodReturnTypes", methodReturnTypes);

        InlayHintSettings settings = (InlayHintSettings) invoke(
                service,
                "parseInlayHintSettings",
                new Class<?>[] { JsonObject.class },
                new Object[] { inlayHints });

        assertFalse(settings.isVariableTypesEnabled());
        assertTrue(settings.isParameterNamesEnabled());
        assertFalse(settings.isClosureParameterTypesEnabled());
                assertFalse(settings.isMethodReturnTypesEnabled());
    }

    @Test
    void extractPackageNameAndImportHelpersWorkForTypicalSource() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = """
                package com.example.demo

                import java.time.LocalDate
                import java.time.LocalTime

                class Sample {
                }
                """;

        String packageName = (String) invoke(
                service,
                "extractPackageName",
                new Class<?>[] { String.class },
                new Object[] { source });

        boolean hasDateImport = (boolean) invoke(
                service,
                "hasExactImport",
                new Class<?>[] { String.class, String.class },
                new Object[] { source, "java.time.LocalDate" });

        boolean hasFakeImport = (boolean) invoke(
                service,
                "hasExactImport",
                new Class<?>[] { String.class, String.class },
                new Object[] { source, "java.time.Local" });

        int insertLine = (int) invoke(
                service,
                "findImportInsertLine",
                new Class<?>[] { String.class },
                new Object[] { source });

        assertEquals("com.example.demo", packageName);
        assertTrue(hasDateImport);
        assertFalse(hasFakeImport);
        assertEquals(4, insertLine);
    }

    @Test
    void findGroovyPackageDeclarationMoveEditUpdatesAndInsertsPackage() throws Exception {
        GroovyWorkspaceService service = createService();

        String withPackage = """
                package old.pkg

                class A {}
                """;

        TextEdit replaceEdit = (TextEdit) invoke(
                service,
                "findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { withPackage, "new.pkg" });

        assertNotNull(replaceEdit);
        assertEquals("new.pkg", replaceEdit.getNewText());

        String withoutPackage = "class A {}\n";
        TextEdit insertEdit = (TextEdit) invoke(
                service,
                "findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { withoutPackage, "inserted.pkg" });

        assertNotNull(insertEdit);
        assertEquals("package inserted.pkg\n\n", insertEdit.getNewText());
        assertEquals(0, insertEdit.getRange().getStart().getLine());
        assertEquals(0, insertEdit.getRange().getStart().getCharacter());
    }

    private GroovyWorkspaceService createService() {
        return new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
    }

    private JsonObject objectWith(String key, boolean value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
