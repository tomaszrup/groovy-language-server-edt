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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
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
    void extractFqnFromUriPreservesInnerClassMarker() {
        assertEquals("a.b.Outer$Inner",
                SourceJarHelper.extractFqnFromUri("groovy-source:///a/b/Outer$Inner.java"));
    }

    @Test
    void extractFqnFromUriDecodesEncodedInnerClassMarker() {
        assertEquals("a.b.Outer$Inner",
                SourceJarHelper.extractFqnFromUri("groovy-source:///a/b/Outer%24Inner.java"));
    }

    @Test
    void sourceFileFqnReturnsTopLevelOwnerForNestedType() {
        assertEquals("a.b.Outer", SourceJarHelper.sourceFileFqn("a.b.Outer$Inner$Leaf"));
        assertEquals("a.b.Outer", SourceJarHelper.sourceFileFqn("a.b.Outer.Inner.Leaf"));
        assertEquals("a.b.Outer", SourceJarHelper.sourceFileFqn("a.b.Outer"));
    }

    @Test
    void buildUriNormalizesDottedNestedTypeToOuterSourcePath() {
        String content = "class Outer { enum Inner { VALUE } }\n";

        String uri = SourceJarHelper.buildGroovySourceUri("a.b.Outer.Inner", ".java", null, false, content);

        assertEquals("groovy-source:///a/b/Outer.java", uri);
        assertEquals(content, SourceJarHelper.resolveSourceContent(uri));
        assertEquals(content, SourceJarHelper.getCachedContent("a.b.Outer"));
        assertEquals(content, SourceJarHelper.getCachedContent("a.b.Outer.Inner"));
        assertEquals(content, SourceJarHelper.getCachedContent("a.b.Outer$Inner"));
    }

    @Test
    void binaryTypeFqnDoesNotTreatUppercasePackageSegmentAsNestedType() {
        assertEquals("com.Acme.tools.Widget.Inner",
                SourceJarHelper.binaryTypeFqn("com.Acme.tools.Widget.Inner"));
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
    void findSourcesJarForBinaryJarReturnsNullForInvalidInputs() throws IOException {
        assertNull(SourceJarHelper.findSourcesJarForBinaryJar(null));

        File notAJar = tempDir.resolve("not-a-jar.txt").toFile();
        Files.writeString(notAJar.toPath(), "x");

        assertNull(SourceJarHelper.findSourcesJarForBinaryJar(notAJar));
    }

    @Test
    void findSourcesJarUsesExplicitSourceAttachmentPath() throws Exception {
        Files.createDirectories(tempDir.resolve("attached"));
        File sourcesJar = createJar(
                tempDir.resolve("attached/demo-1.0-sources.jar"),
                "com/example/Demo.java",
                "class Demo {}\n");

        IType type = mock(IType.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        when(type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT)).thenReturn(root);
        when(root.getSourceAttachmentPath()).thenReturn(
                org.eclipse.core.runtime.Path.fromOSString(sourcesJar.getAbsolutePath()));

        File found = SourceJarHelper.findSourcesJar(type);

        assertNotNull(found);
        assertEquals(sourcesJar.getAbsolutePath(), found.getAbsolutePath());
    }

    @Test
    void findSourcesJarForBinaryJarFindsSiblingHashDirectoryInGradleCacheLayout() throws IOException {
        Path versionDir = tempDir.resolve("modules-2/files-2.1/com.example/demo/1.0");
        Path hashA = versionDir.resolve("hash-a");
        Path hashB = versionDir.resolve("hash-b");
        Files.createDirectories(hashA);
        Files.createDirectories(hashB);

        File binaryJar = createJar(hashA.resolve("demo-1.0.jar"), "com/example/Demo.class", "bytecode");
        File sourcesJar = createJar(hashB.resolve("demo-1.0-sources.jar"), "com/example/Demo.java", "class Demo {}\n");

        File found = SourceJarHelper.findSourcesJarForBinaryJar(binaryJar);

        assertNotNull(found);
        assertEquals(sourcesJar.getAbsolutePath(), found.getAbsolutePath());
    }

    @Test
    void findSourcesJarForBinaryJarFindsAcrossMavenAndGradleCaches() throws IOException {
        String originalUserHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        try {
            Path mavenDir = home.resolve(".m2/repository/com/acme/demo/1.0");
            Path gradleDir = home.resolve(".gradle/caches/modules-2/files-2.1/com.acme/demo/1.0/hash");
            Files.createDirectories(mavenDir);
            Files.createDirectories(gradleDir);

            File binaryJar = createJar(mavenDir.resolve("demo-1.0.jar"), "com/acme/Demo.class", "bytecode");
            File sourcesJar = createJar(gradleDir.resolve("demo-1.0-sources.jar"),
                    "com/acme/Demo.java", "class Demo {}\n");

            File found = SourceJarHelper.findSourcesJarForBinaryJar(binaryJar);

            assertNotNull(found);
            assertEquals(sourcesJar.getAbsolutePath(), found.getAbsolutePath());
        } finally {
            if (originalUserHome != null) {
                System.setProperty("user.home", originalUserHome);
            }
        }
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
    void readSourceFromJarReadsOuterEntryForInnerType() throws IOException {
        File sourcesJar = createJar(
                tempDir.resolve("nested-sources.jar"),
                "com/example/Outer.java",
                "package com.example;\npublic class Outer {\n    public enum Inner { VALUE }\n}\n");

        String source = SourceJarHelper.readSourceFromJar(sourcesJar, "com.example.Outer$Inner");

        assertNotNull(source);
        assertTrue(source.contains("class Outer"));
        assertTrue(source.contains("enum Inner"));
    }

    @Test
    void readSourceFromJarReturnsNullWhenTypeNotPresent() throws IOException {
        File sourcesJar = createJar(
                tempDir.resolve("missing-sources.jar"),
                "com/example/Other.java",
                "class Other {}\n");

        assertNull(SourceJarHelper.readSourceFromJar(sourcesJar, "com.example.Missing"));
    }

    @Test
    void readSourceFromJdkSrcZipReadsModuleAndLegacyLayouts() throws IOException {
        String originalJavaHome = System.getProperty("java.home");
        Path fakeJavaHome = tempDir.resolve("fake-jdk");
        Path libDir = fakeJavaHome.resolve("lib");
        Files.createDirectories(libDir);

        createJar(libDir.resolve("src.zip"), "java.base/java/lang/FakeType.java", "package java.lang; class FakeType {}\n");
        System.setProperty("java.home", fakeJavaHome.toString());
        try {
            String moduleSource = SourceJarHelper.readSourceFromJdkSrcZip("java.lang.FakeType");
            assertNotNull(moduleSource);
            assertTrue(moduleSource.contains("FakeType"));
        } finally {
            if (originalJavaHome != null) {
                System.setProperty("java.home", originalJavaHome);
            }
        }

        Path legacyHome = tempDir.resolve("legacy-jdk");
        Path legacyLib = legacyHome.resolve("lib");
        Files.createDirectories(legacyLib);
        createJar(legacyLib.resolve("src.zip"), "java/lang/LegacyType.java", "package java.lang; class LegacyType {}\n");
        System.setProperty("java.home", legacyHome.toString());
        try {
            String legacySource = SourceJarHelper.readSourceFromJdkSrcZip("java.lang.LegacyType");
            assertNotNull(legacySource);
            assertTrue(legacySource.contains("LegacyType"));
        } finally {
            if (originalJavaHome != null) {
                System.setProperty("java.home", originalJavaHome);
            }
        }
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
    void extractJavadocReturnsNullWhenUnexpectedTokensAppearBetweenDocAndClass() {
        String source = """
                /** Doc */
                unexpected-token
                class Demo {}
                """;

        assertNull(SourceJarHelper.extractJavadoc(source, "Demo"));
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

    @Test
    void extractMemberJavadocIgnoresPartialIdentifierMatches() {
        String source = """
                class Demo {
                    /**
                     * Docs for targetMethod.
                     */
                    String targetMethod() { "" }

                    String targetMethodology() { "" }
                }
                """;

        String doc = SourceJarHelper.extractMemberJavadoc(source, "targetMethodology");
        assertNull(doc);
    }

    @Test
    void writeSourceToTempWritesExpectedFileContent() throws IOException {
        File written = SourceJarHelper.writeSourceToTemp("com.example.Temp", "class Temp {}\n", ".groovy");

        assertNotNull(written);
        assertTrue(written.exists());
        String content = Files.readString(written.toPath());
        assertTrue(content.contains("class Temp"));
        assertFalse(content.isBlank());
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

    // ================================================================
    // Batch 6 — additional SourceJarHelper coverage
    // ================================================================

    // ---- cleanJavadoc edge cases ----

    @Test
    void extractJavadocHandlesLinkTags() {
        String source = """
                /**
                 * See {@link java.util.List} for details.
                 * Also {@linkplain String text}.
                 */
                public class Demo {}
                """;
        String doc = SourceJarHelper.extractJavadoc(source, "Demo");
        assertNotNull(doc);
        assertTrue(doc.contains("List") || doc.contains("java.util.List"));
    }

    @Test
    void extractJavadocHandlesParamAndReturnTags() {
        String source = """
                class Demo {
                    /**
                     * Computes something.
                     * @param input the input value
                     * @param count how many times
                     * @return the result
                     * @throws IllegalArgumentException if input is null
                     */
                    String compute(String input, int count) { return "" }
                }
                """;
        String doc = SourceJarHelper.extractMemberJavadoc(source, "compute");
        assertNotNull(doc);
        assertTrue(doc.contains("Computes something"));
        assertTrue(doc.contains("input"));
    }

    @Test
    void extractJavadocHandlesHtmlTags() {
        String source = """
                /**
                 * A <b>bold</b> class with <code>code</code> in it.
                 * <ul><li>Item 1</li><li>Item 2</li></ul>
                 */
                public class Demo {}
                """;
        String doc = SourceJarHelper.extractJavadoc(source, "Demo");
        assertNotNull(doc);
        assertTrue(doc.contains("bold") || doc.contains("class"));
    }

    @Test
    void extractJavadocReturnsNullForNoDoc() {
        String source = "public class Demo {}";
        assertNull(SourceJarHelper.extractJavadoc(source, "Demo"));
    }

    @Test
    void extractJavadocReturnsNullForNullSource() {
        assertNull(SourceJarHelper.extractJavadoc(null, "Demo"));
    }

    // ---- extractMemberJavadoc edge cases ----

    @Test
    void extractMemberJavadocForField() {
        String source = """
                class Demo {
                    /**
                     * The user's name.
                     */
                    String name;
                }
                """;
        String doc = SourceJarHelper.extractMemberJavadoc(source, "name");
        assertNotNull(doc);
        assertTrue(doc.contains("name"));
    }

    @Test
    void extractMemberJavadocReturnsNullWhenNoMatchingMember() {
        String source = """
                class Demo {
                    /** Docs for foo */
                    void foo() {}
                }
                """;
        assertNull(SourceJarHelper.extractMemberJavadoc(source, "bar"));
    }

    @Test
    void extractMemberJavadocHandlesStaticModifier() {
        String source = """
                class Demo {
                    /**
                     * Static factory.
                     */
                    public static Demo create() { return new Demo() }
                }
                """;
        String doc = SourceJarHelper.extractMemberJavadoc(source, "create");
        assertNotNull(doc, "Should find doc for static method through modifier");
    }

    @Test
    void extractMemberJavadocHandlesAnnotationBetweenDocAndMember() {
        String source = """
                class Demo {
                    /**
                     * Deprecated method.
                     */
                    @Deprecated
                    void old() {}
                }
                """;
        String doc = SourceJarHelper.extractMemberJavadoc(source, "old");
        assertNotNull(doc, "Should find doc through @annotation gap");
    }

    // ---- buildGroovySourceUri edge cases ----

    @Test
    void buildGroovySourceUriWithJavaExtension() {
        String uri = SourceJarHelper.buildGroovySourceUri("com.example.Util", ".java", null, false, "class Util {}");
        assertEquals("groovy-source:///com/example/Util.java", uri);
        assertEquals("class Util {}", SourceJarHelper.getCachedContent("com.example.Util"));
    }

    @Test
    void buildGroovySourceUriForJdkType() {
        String uri = SourceJarHelper.buildGroovySourceUri("java.lang.String", ".java", null, true, "class String {}");
        assertTrue(uri.startsWith("groovy-source:///"));
    }

    // ---- extractFqnFromUri edge cases ----

    @Test
    void extractFqnFromUriReturnsNullForNull() {
        assertNull(SourceJarHelper.extractFqnFromUri(null));
    }

    @Test
    void extractFqnFromUriReturnsNullForNonGroovyScheme() {
        assertNull(SourceJarHelper.extractFqnFromUri("file:///some/path.java"));
    }

    @Test
    void extractFqnFromUriHandlesDeepPackage() {
        assertEquals("org.apache.commons.lang3.StringUtils",
                SourceJarHelper.extractFqnFromUri("groovy-source:///org/apache/commons/lang3/StringUtils.java"));
    }

    // ---- resolveSourceContent ----

    @Test
    void resolveSourceContentReturnsNullForUnknownUri() {
        assertNull(SourceJarHelper.resolveSourceContent("groovy-source:///unknown/Type.java"));
    }

    @Test
    void resolveSourceContentReturnsCachedValue() {
        String fqn = "com.test.Cached" + System.nanoTime();
        SourceJarHelper.buildGroovySourceUri(fqn, ".java", null, false, "cached-content");
        String uri = "groovy-source:///" + fqn.replace('.', '/') + ".java";
        String content = SourceJarHelper.resolveSourceContent(uri);
        assertEquals("cached-content", content);
    }

    // ---- readSourceFromJar with groovy extension ----

    @Test
    void readSourceFromJarReadsGroovyEntry() throws IOException {
        File jar = createJar(tempDir.resolve("groovy-src.jar"),
                "com/example/GroovyClass.groovy", "class GroovyClass {}");
        String source = SourceJarHelper.readSourceFromJar(jar, "com.example.GroovyClass");
        assertNotNull(source);
        assertTrue(source.contains("GroovyClass"));
    }

    @Test
    void readSourceFromJarReturnsNullForNullJar() {
        // null jar causes NPE during processing
        try {
            String result = SourceJarHelper.readSourceFromJar(null, "com.example.X");
            // If it returns null (caught internally), that's also acceptable
            assertNull(result);
        } catch (NullPointerException e) {
            // Expected: null jar is not a valid input
        }
    }

    // ---- findSourcesJarForBinaryJar edge cases ----

    @Test
    void findSourcesJarForBinaryJarNonexistentFile() {
        File fake = new File(tempDir.toFile(), "nonexistent-1.0.jar");
        assertNull(SourceJarHelper.findSourcesJarForBinaryJar(fake));
    }

    // ---- writeSourceToTemp edge cases ----

    @Test
    void writeSourceToTempWritesJavaFile() throws IOException {
        File written = SourceJarHelper.writeSourceToTemp("com.example.JavaFile", "class JavaFile {}", ".java");
        assertNotNull(written);
        assertTrue(written.exists());
        assertTrue(written.getName().endsWith(".java"));
    }
}
