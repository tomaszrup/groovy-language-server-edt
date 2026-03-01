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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceJarHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void buildUriCachesContentAndResolveReturnsCachedSource() {
        String fqn = "com.example.cache.CachedType";
        String content = "class CachedType {}\n";

        String uri = SourceJarHelper.buildGroovySourceUri(fqn, ".groovy", null, false, content);

        assertEquals("groovy-source:///com/example/cache/CachedType.groovy", uri);
        assertEquals(content, SourceJarHelper.getCachedContent(fqn));
        assertEquals(content, SourceJarHelper.resolveSourceContent(uri));
    }

    @Test
    void extractFqnFromUriHandlesNormalizedAndDecoratedForms() {
        assertEquals("a.b.C", SourceJarHelper.extractFqnFromUri("groovy-source:///a/b/C.java"));
        assertEquals("a.b.C", SourceJarHelper.extractFqnFromUri("groovy-source:/a/b/C.groovy"));
        assertEquals("a.b.C", SourceJarHelper.extractFqnFromUri("groovy-source:///a/b/C.java?x=1#frag"));
        assertNull(SourceJarHelper.extractFqnFromUri("file:///a/b/C.java"));
    }

    @Test
    void findSourcesJarForBinaryJarFindsSiblingSourcesJar() throws IOException {
        Path libsDir = tempDir.resolve("libs");
        Files.createDirectories(libsDir);

        File binaryJar = createJar(libsDir.resolve("demo-1.0.jar"), "com/example/Demo.class", "bytecode");
        File sourcesJar = createJar(libsDir.resolve("demo-1.0-sources.jar"), "com/example/Demo.java", "class Demo {}\n");

        File found = SourceJarHelper.findSourcesJarForBinaryJar(binaryJar);

        assertNotNull(found);
        assertEquals(sourcesJar.getAbsolutePath(), found.getAbsolutePath());
    }

    @Test
    void readSourceFromJarReadsJavaOrGroovyEntryByFqn() throws IOException {
        File sourcesJar = createJar(
                tempDir.resolve("example-sources.jar"),
                "com/example/Foo.groovy",
                "class Foo {}\n");

        String source = SourceJarHelper.readSourceFromJar(sourcesJar, "com.example.Foo");

        assertNotNull(source);
        assertTrue(source.contains("class Foo"));
    }

    @Test
    void extractJavadocReturnsCleanedClassDocumentation() {
        String source = """
                /**
                 * Example class for docs.
                 * Uses {@code code} and <p>paragraphs.
                 */
                @Deprecated
                public class Demo {
                }
                """;

        String doc = SourceJarHelper.extractJavadoc(source, "Demo");

        assertNotNull(doc);
        assertTrue(doc.contains("Example class for docs."));
        assertTrue(doc.contains("`code`"));
        assertTrue(doc.contains("paragraphs."));
    }

    @Test
    void extractMemberJavadocFindsMethodDocumentation() {
        String source = """
                class Demo {
                    /**
                     * Greets a user.
                     * @param name user name
                     * @return greeting text
                     */
                    String greet(String name) {
                        return "hi"
                    }
                }
                """;

        String doc = SourceJarHelper.extractMemberJavadoc(source, "greet");

        assertNotNull(doc);
        assertTrue(doc.contains("Greets a user."));
        assertTrue(doc.contains("**@param** `name` —"));
        assertTrue(doc.contains("**@return**"));
    }

    private File createJar(Path jarPath, String entryName, String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return jarPath.toFile();
    }
}
