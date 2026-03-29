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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void collectSyntaxErrorsDoesNotCacheConversionResult() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        String uri = createSourceUri("SyntaxOnly.groovy");

        List<org.codehaus.groovy.syntax.SyntaxException> errors =
                service.collectSyntaxErrors(uri, "class Broken {\n");

        assertFalse(errors.isEmpty());
        assertNull(service.getCachedResult(uri));
    }

    @Test
    void collectSyntaxErrorsDoesNotReportUnresolvedTypes() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        String uri = createSourceUri("UnresolvedType.groovy");

        List<org.codehaus.groovy.syntax.SyntaxException> errors =
                service.collectSyntaxErrors(uri, "import foo.Bar\nclass A { Bar field }\n");

        assertTrue(errors.isEmpty());
        assertNull(service.getCachedResult(uri));
    }

    @Test
    void classpathFailureDetectionTreatsQuotedUnresolvedClassAsClasspathDependent() throws Exception {
        GroovyCompilerService service = new GroovyCompilerService();
        Method method = GroovyCompilerService.class
                .getDeclaredMethod("isClasspathDependentFailure", String.class);
        method.setAccessible(true);

        boolean classpathDependent = (boolean) method.invoke(
                service,
                "unable to resolve class 'foo.Bar'");

        assertTrue(classpathDependent);
    }

    @Test
    void classpathFailureDetectionTreatsWrappedClasspathFailuresAsClasspathDependent() throws Exception {
    GroovyCompilerService service = new GroovyCompilerService();
    Method method = GroovyCompilerService.class
        .getDeclaredMethod("isClasspathDependentFailure", String.class);
    method.setAccessible(true);

    assertTrue((boolean) method.invoke(
        service,
        "Parse error: No such class: foo.Bar -- while compiling"));
    assertTrue((boolean) method.invoke(
        service,
        "Parse error: Groovy:General error during conversion: startup wrapper"));
    assertTrue((boolean) method.invoke(
        service,
        "Internal parse error: unable to resolve class 'foo.Bar'"));
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

    @Test
    void invalidateDocumentFamilyRemovesSemanticPatchedEntries() throws IOException {
        GroovyCompilerService service = new GroovyCompilerService();
        String uri = createSourceUri("SemanticPatched.groovy");
        String patchedUri = uri + "#semantic-patched-0";

        service.parse(uri, "class Sample {}\n");
        service.parse(patchedUri, "class Sample {}\n");

        assertNotNull(service.getCachedResult(uri));
        assertNotNull(service.getCachedResult(patchedUri));

        service.invalidateDocumentFamily(uri);

        assertNull(service.getCachedResult(uri));
        assertNull(service.getCachedResult(patchedUri));
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
