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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroovyCompilerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void parseValidSourceBuildsAstWithoutErrors() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        String uri = createSourceUri("ValidSource.groovy");

        GroovyCompilerService.ParseResult result = service.parse(uri, "class Person { String name }\n");

        assertNotNull(result);
        assertTrue(result.hasAST());
        assertNotNull(result.getModuleNode());
        assertTrue(result.getErrors().isEmpty());
        assertSame(result, service.getCachedResult(uri));
    }

    @Test
    void parseInvalidSourceCollectsSyntaxErrors() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        String uri = createSourceUri("InvalidSource.groovy");

        GroovyCompilerService.ParseResult result = service.parse(uri, "class Broken {\n");

        assertNotNull(result);
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void cacheLookupUsesNormalizedUriVariants() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        Path file = createSourceFile("My File — Name.groovy");

        String canonical = file.toUri().toString();
        String jdtLike = canonical
                .replace("file:///", "file:/")
                .replace("%20", " ")
                .replace("%E2%80%94", "—");

        GroovyCompilerService.ParseResult result = service.parse(jdtLike, "class A {}\n");

        assertSame(result, service.getCachedResult(canonical));
    }

    @Test
    void invalidateRemovesCachedResultAcrossUriVariants() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        Path file = createSourceFile("Invalidate Me.groovy");

        String canonical = file.toUri().toString();
        String jdtLike = canonical.replace("file:///", "file:/");

        service.parse(canonical, "class A {}\n");
        service.invalidate(jdtLike);

        assertNull(service.getCachedResult(canonical));
    }

    private String createSourceUri(String fileName) throws IOException {
        return createSourceFile(fileName).toUri().toString();
    }

    private Path createSourceFile(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, "// fixture\n");
        return file;
    }
}
