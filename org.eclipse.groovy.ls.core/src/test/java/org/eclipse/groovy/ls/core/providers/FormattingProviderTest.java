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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormattingProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void preprocessForJdtReplacesDefAndPreservesOffsets() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String source = """
                def value = \"def in string\"
                String define = 'def in single quotes'
                """;

        String preprocessed = invokeString(provider, "preprocessForJdt", source);

        assertEquals(source.length(), preprocessed.length());
        assertTrue(preprocessed.contains("int value"));
        assertTrue(preprocessed.contains("define"));
        assertFalse(preprocessed.contains("\"def in string\""));
        assertFalse(preprocessed.contains("'def in single quotes'"));
    }

    @Test
    void detectLineSeparatorReturnsCrlfOrLf() throws Exception {
        FormattingProvider provider = new FormattingProvider(new DocumentManager());

        String crlf = invokeString(provider, "detectLineSeparator", "a\r\nb\r\n");
        String lf = invokeString(provider, "detectLineSeparator", "a\nb\n");

        assertEquals("\r\n", crlf);
        assertEquals("\n", lf);
    }

    @Test
    void loadEclipseFormatterProfileParsesSettingsXml() throws Exception {
        Path profile = tempDir.resolve("formatter.xml");
        Files.writeString(profile, """
                <profiles>
                  <profile kind=\"CodeFormatterProfile\" name=\"Test\" version=\"21\">
                    <setting id=\"org.eclipse.jdt.core.formatter.tabulation.char\" value=\"space\"/>
                    <setting id=\"org.eclipse.jdt.core.formatter.tabulation.size\" value=\"4\"/>
                  </profile>
                </profiles>
                """);

        Map<String, String> settings = FormattingProvider.loadEclipseFormatterProfile(profile.toString());

        assertNotNull(settings);
        assertEquals("space", settings.get("org.eclipse.jdt.core.formatter.tabulation.char"));
        assertEquals("4", settings.get("org.eclipse.jdt.core.formatter.tabulation.size"));
    }

    private String invokeString(Object target, String methodName, String arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, arg);
    }
}
