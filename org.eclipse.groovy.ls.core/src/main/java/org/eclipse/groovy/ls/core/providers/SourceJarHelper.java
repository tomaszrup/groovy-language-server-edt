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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;

/**
 * Shared utility for finding and reading source from -sources.jar files
 * in Maven and Gradle caches.
 */
public class SourceJarHelper {

    private static final String GROOVY_SOURCE_SCHEME = "groovy-source:";
    private static final String JAVA_EXTENSION = ".java";
    private static final String GROOVY_EXTENSION = ".groovy";
    private static final String JAR_EXTENSION = ".jar";
    private static final String SOURCES_SUFFIX = "-sources.jar";
    private static final String M2_REPOSITORY_SEGMENT = ".m2/repository/";
    private static final String GRADLE_FILES_SEGMENT = "files-2.1/";

    private SourceJarHelper() {
    }

    /**
     * Cached source entry holding content and metadata for re-resolution.
     */
    private static class CacheEntry {
        final String content;

        CacheEntry(String content) {
            this.content = content;
        }
    }

    /** Maximum number of source entries to cache. LRU eviction keeps memory bounded. */
    private static final int MAX_CACHE_SIZE = 200;

    /** Cache of virtual document content keyed by FQN (e.g. "spock.lang.Specification"). */
    private static final java.util.Map<String, CacheEntry> contentCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                            return size() > MAX_CACHE_SIZE;
                        }
                    });

    /**
     * Build a {@code groovy-source://} URI for a binary type's source.
     * <p>
     * URI format: {@code groovy-source:///org/package/ClassName.ext}
     * No query parameters — all metadata is stored server-side in the cache
     * to avoid URI mangling by VS Code's URI normalization.
     *
     * @param fqn            fully-qualified type name
     * @param ext            file extension (.java or .groovy)
     * @param sourcesJarPath absolute path to the sources JAR (null for JDK/stubs)
     * @param isJdk          true if the source comes from JDK src.zip
     * @param content        the source content to cache
     * @return the groovy-source:// URI string
     */
    public static String buildGroovySourceUri(String fqn, String ext, String sourcesJarPath,
                                               boolean isJdk, String content) {
        String pathPart = fqn.replace('.', '/') + ext;
        String uriStr = "groovy-source:///" + pathPart;
        if (content != null) {
            contentCache.put(fqn, new CacheEntry(content));
        }
        GroovyLanguageServerPlugin.logInfo("[source] Built virtual URI: " + uriStr
                + " (cached FQN=" + fqn + ", sourceJar=" + sourcesJarPath
                + ", isJdk=" + isJdk + ")");
        return uriStr;
    }

    /**
     * Extract a fully-qualified type name from a {@code groovy-source:} URI.
     * <p>
     * Handles both the original {@code groovy-source:///pkg/Class.java} and
     * the VS Code–normalized {@code groovy-source:/pkg/Class.java} forms.
     *
     * @param uriStr the groovy-source URI
     * @return the FQN, or null if not extractable
     */
    public static String extractFqnFromUri(String uriStr) {
        if (uriStr == null || !uriStr.startsWith(GROOVY_SOURCE_SCHEME)) return null;
        String rest = uriStr.substring(GROOVY_SOURCE_SCHEME.length());
        // Strip leading slashes (could be /// or /)
        while (rest.startsWith("/")) rest = rest.substring(1);
        // Strip query/fragment if present (shouldn't be, but be safe)
        int qIdx = rest.indexOf('?');
        if (qIdx >= 0) rest = rest.substring(0, qIdx);
        int hIdx = rest.indexOf('#');
        if (hIdx >= 0) rest = rest.substring(0, hIdx);
        if (rest.endsWith(JAVA_EXTENSION)) {
            rest = rest.substring(0, rest.length() - JAVA_EXTENSION.length());
        } else if (rest.endsWith(GROOVY_EXTENSION)) {
            rest = rest.substring(0, rest.length() - GROOVY_EXTENSION.length());
        }
        return rest.replace('/', '.');
    }

    /**
     * Map a nested type FQN to the top-level source file owner.
     * <p>
     * Example: {@code a.b.Outer$Inner$Leaf -> a.b.Outer}.
     */
    static String sourceFileFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return fqn;
        }
        int dollar = fqn.indexOf('$');
        return dollar >= 0 ? fqn.substring(0, dollar) : fqn;
    }

    /**
     * Resolve source content for a {@code groovy-source:} URI.
     * Extracts the FQN from the URI path, looks up in the cache,
     * and falls back to re-reading from the JAR or JDK src.zip.
     *
     * @param uriStr the full groovy-source: URI (any normalized form)
     * @return source content, or null if unavailable
     */
    public static String resolveSourceContent(String uriStr) {
        String fqn = extractFqnFromUri(uriStr);
        if (fqn == null) {
            GroovyLanguageServerPlugin.logInfo("[source] Could not extract FQN from: " + uriStr);
            return null;
        }
        GroovyLanguageServerPlugin.logInfo("[source] Resolving FQN=" + fqn + " from URI: " + uriStr);

        // 1. Check in-memory cache by FQN
        CacheEntry entry = contentCache.get(fqn);
        if (entry != null) {
            GroovyLanguageServerPlugin.logInfo("[source] Cache hit for FQN: " + fqn);
            return entry.content;
        }

        // 2. Try JDK src.zip
        String jdkSource = readSourceFromJdkSrcZip(fqn);
        if (jdkSource != null) {
            contentCache.put(fqn, new CacheEntry(jdkSource));
            return jdkSource;
        }

        GroovyLanguageServerPlugin.logInfo("[source] Cache miss for FQN: " + fqn);
        return null;
    }

    /**
     * Get cached content for a FQN directly (used by navigation within virtual docs).
     */
    public static String getCachedContent(String fqn) {
        CacheEntry entry = contentCache.get(fqn);
        return entry != null ? entry.content : null;
    }

    /**
     * Find a -sources.jar for the given binary type.
     * Checks same directory, Gradle cache sibling hash dirs, and cross-cache search.
     */
    public static File findSourcesJar(IType type) {
        try {
            IJavaElement root = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (!(root instanceof IPackageFragmentRoot)) return null;
            IPackageFragmentRoot pfr = (IPackageFragmentRoot) root;

            File attachedSourcesJar = findAttachedSourcesJar(pfr);
            if (attachedSourcesJar != null) {
                return attachedSourcesJar;
            }

            org.eclipse.core.runtime.IPath jarPath = pfr.getPath();
            if (jarPath == null) return null;

            // Get the OS-level file path
            File jarFile = jarPath.toFile();
            if (!jarFile.exists()) {
                String rawPath = pfr.getPath().toOSString();
                jarFile = new File(rawPath);
            }

            // For external JARs, try the classpath entry
            if (!jarFile.exists()) {
                IClasspathEntry cpEntry = pfr.getRawClasspathEntry();
                if (cpEntry != null) {
                    File cpAttachedSourcesJar = toExistingFile(cpEntry.getSourceAttachmentPath());
                    if (cpAttachedSourcesJar != null) {
                        return cpAttachedSourcesJar;
                    }
                    org.eclipse.core.runtime.IPath cpPath = cpEntry.getPath();
                    if (cpPath != null) {
                        jarFile = cpPath.toFile();
                    }
                }
            }

            if (!jarFile.exists()) {
                GroovyLanguageServerPlugin.logInfo(
                        "[source] JAR file not found on disk: " + jarPath);
                return null;
            }

            return findSourcesJarForBinaryJar(jarFile);

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[source] Failed to find source JAR", e);
            return null;
        }
    }

    private static File findAttachedSourcesJar(IPackageFragmentRoot pfr) {
        try {
            File sourceAttachment = toExistingFile(pfr.getSourceAttachmentPath());
            if (sourceAttachment != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[source] Using attached source JAR: " + sourceAttachment.getAbsolutePath());
                return sourceAttachment;
            }
        } catch (Exception e) {
            // Fall through to other lookup strategies.
        }

        try {
            IClasspathEntry cpEntry = pfr.getRawClasspathEntry();
            if (cpEntry != null) {
                File sourceAttachment = toExistingFile(cpEntry.getSourceAttachmentPath());
                if (sourceAttachment != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[source] Using classpath source attachment: "
                            + sourceAttachment.getAbsolutePath());
                    return sourceAttachment;
                }
            }
        } catch (Exception e) {
            // Fall through to other lookup strategies.
        }

        return null;
    }

    private static File toExistingFile(org.eclipse.core.runtime.IPath path) {
        if (path == null) {
            return null;
        }

        File file = path.toFile();
        if (file.exists()) {
            return file;
        }

        String osPath = path.toOSString();
        if (osPath == null || osPath.isBlank()) {
            return null;
        }

        file = new File(osPath);
        return file.exists() ? file : null;
    }

    /**
     * Find a -sources.jar corresponding to a binary JAR file.
     * <p>
     * Checks same directory (Maven convention), Gradle cache sibling hash dirs,
     * and cross-cache search (Maven ↔ Gradle).
     *
     * @param jarFile the binary JAR file
     * @return the sources JAR, or null if not found
     */
    public static File findSourcesJarForBinaryJar(File jarFile) {
        if (!isUsableBinaryJar(jarFile)) {
            return null;
        }

        String jarName = jarFile.getName();

        GroovyLanguageServerPlugin.logInfo(
                "[source] Looking for sources JAR for: " + jarFile.getAbsolutePath());

        String sourcesJarName = buildSourcesJarName(jarName);
        File sourcesJar = findSourcesJarInLocalCache(jarFile, sourcesJarName);

        if (sourcesJar == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[source] Not found locally, searching caches for: " + sourcesJarName);
            sourcesJar = searchCachesForSourcesJar(sourcesJarName, jarFile);
        }

        if (sourcesJar == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[source] No sources JAR found for " + jarName);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[source] Found sources JAR: " + sourcesJar.getAbsolutePath());
        return sourcesJar;
    }

    private static boolean isUsableBinaryJar(File jarFile) {
        return jarFile != null && jarFile.exists() && jarFile.getName().endsWith(JAR_EXTENSION);
    }

    private static String buildSourcesJarName(String jarName) {
        String baseName = jarName.substring(0, jarName.length() - JAR_EXTENSION.length());
        return baseName + SOURCES_SUFFIX;
    }

    private static File findSourcesJarInLocalCache(File jarFile, String sourcesJarName) {
        File localCandidate = new File(jarFile.getParentFile(), sourcesJarName);
        if (localCandidate.exists()) {
            return localCandidate;
        }
        return findSourcesJarInSiblingHashDirs(jarFile, sourcesJarName);
    }

    private static File findSourcesJarInSiblingHashDirs(File jarFile, String sourcesJarName) {
        File hashDir = jarFile.getParentFile();
        File versionDir = hashDir != null ? hashDir.getParentFile() : null;
        if (versionDir == null || !versionDir.isDirectory()) {
            return null;
        }

        File[] siblings = versionDir.listFiles();
        if (siblings == null) {
            return null;
        }
        for (File sibling : siblings) {
            if (!sibling.isDirectory()) {
                continue;
            }
            File candidate = new File(sibling, sourcesJarName);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Read source for a fully-qualified type from a sources JAR.
     * Tries both .groovy and .java extensions.
     */
    public static String readSourceFromJar(File sourcesJar, String fqn) {
        String sourceOwnerFqn = sourceFileFqn(fqn);
        String basePath = sourceOwnerFqn.replace('.', '/');
        String[] extensions = {GROOVY_EXTENSION, JAVA_EXTENSION};

        try (ZipFile zf = new ZipFile(sourcesJar)) {
            for (String ext : extensions) {
                String entryName = basePath + ext;
                ZipEntry entry = zf.getEntry(entryName);
                if (entry != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[source] Found entry " + entryName + " in " + sourcesJar.getName());
                    try (InputStream is = zf.getInputStream(entry)) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }

                        // Debugging: log entries matching the package
                        int lastDot = sourceOwnerFqn.lastIndexOf('.');
            if (lastDot > 0) {
                                String pkgPrefix = sourceOwnerFqn.substring(0, lastDot).replace('.', '/');
                StringBuilder sb = new StringBuilder();
                                sb.append("[source] No entry for requested type ").append(fqn)
                                    .append(" (source owner ").append(sourceOwnerFqn).append(") as ")
                                    .append(basePath)
                                    .append(".groovy/.java in ").append(sourcesJar.getName())
                                    .append(". Entries matching pkg: ");
                int count = 0;
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements() && count < 10) {
                    ZipEntry ze = entries.nextElement();
                    if (ze.getName().startsWith(pkgPrefix)) {
                        sb.append(ze.getName()).append(", ");
                        count++;
                    }
                }
                GroovyLanguageServerPlugin.logInfo(sb.toString());
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[source] Failed to read from sources JAR: " + sourcesJar.getName(), e);
        }
        return null;
    }

    /**
     * Read source for a JDK type from $JAVA_HOME/lib/src.zip.
     * JDK sources use module-based paths: java.base/java/lang/String.java
     */
    public static String readSourceFromJdkSrcZip(String fqn) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return null;

        File srcZip = new File(javaHome, "lib/src.zip");
        if (!srcZip.exists()) {
            // Try parent dir (in case java.home points to jre inside a JDK)
            srcZip = new File(javaHome, "../lib/src.zip");
        }
        if (!srcZip.exists()) {
            return null;
        }

        String basePath = sourceFileFqn(fqn).replace('.', '/') + JAVA_EXTENSION;

        // JDK 9+ src.zip has module prefixes like java.base/, java.sql/, etc.
        // Try common modules first
        String[] modules = {
            "java.base", "java.sql", "java.logging", "java.xml",
            "java.desktop", "java.net.http", "java.compiler",
            "java.management", "java.naming", "java.security.jgss",
            "java.instrument", "java.prefs", "java.rmi",
            "java.scripting", "java.datatransfer", "java.xml.crypto"
        };

        try (ZipFile zf = new ZipFile(srcZip)) {
            // Try with module prefix
            for (String module : modules) {
                String entryName = module + "/" + basePath;
                ZipEntry entry = zf.getEntry(entryName);
                if (entry != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[source] Found JDK source: " + entryName);
                    try (InputStream is = zf.getInputStream(entry)) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }

            // Try without module prefix (JDK 8)
            ZipEntry entry = zf.getEntry(basePath);
            if (entry != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[source] Found JDK source (no module): " + basePath);
                try (InputStream is = zf.getInputStream(entry)) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[source] Failed to read from JDK src.zip", e);
        }
        return null;
    }

    /**
     * Extract Javadoc/Groovydoc comment for a class from its source code.
     * Returns the doc comment as cleaned-up text, or null if none found.
     */
    public static String extractJavadoc(String source, String simpleName) {
        if (source == null || simpleName == null) return null;

        // Find the class/interface/enum/trait declaration
        int classIdx = -1;
        String[] keywords = {"class " + simpleName, "interface " + simpleName,
            "enum " + simpleName, "trait " + simpleName};
        for (String kw : keywords) {
            classIdx = source.indexOf(kw);
            if (classIdx >= 0) break;
        }
        if (classIdx < 0) return null;

        // Search backwards for the end of a /** ... */ block before the declaration
        String before = source.substring(0, classIdx).stripTrailing();
        int docEnd = before.lastIndexOf("*/");
        if (docEnd < 0) return null;

        int docStart = before.lastIndexOf("/**", docEnd);
        if (docStart < 0) return null;

        // Make sure there's nothing significant between the doc end and the class declaration
        // (just whitespace, annotations, modifiers)
        String between = before.substring(docEnd + 2).strip();
        // Allow annotations (@...) and modifiers (public, abstract, etc.)
        if (!between.isEmpty()) {
            String cleaned = between.replaceAll("@\\w+(\\([^)]*\\))?", "").strip();
            cleaned = cleaned.replaceAll("\\b(public|protected|private|abstract|static|final|strictfp)\\b", "").strip();
            if (!cleaned.isEmpty()) return null; // Something unexpected between doc and class
        }

        String rawDoc = before.substring(docStart, docEnd + 2);
        return cleanJavadoc(rawDoc);
    }

    /**
     * Extract Javadoc for a specific member (method/field) from source.
     */
    public static String extractMemberJavadoc(String source, String memberName) {
        if (source == null || memberName == null) return null;

        int searchFrom = 0;
        while (searchFrom < source.length()) {
            int idx = source.indexOf(memberName, searchFrom);
            if (idx < 0) {
                searchFrom = source.length();
            } else {
                searchFrom = idx + 1;
                if (isWordBoundary(source, idx, memberName.length())) {
                    String rawDoc = findPrecedingMemberDoc(source, idx);
                    if (rawDoc != null) {
                        return cleanJavadoc(rawDoc);
                    }
                }
            }
        }

        return null;
    }

    private static boolean isWordBoundary(String text, int idx, int length) {
        if (idx > 0 && Character.isJavaIdentifierPart(text.charAt(idx - 1))) {
            return false;
        }
        int afterIdx = idx + length;
        return afterIdx >= text.length() || !Character.isJavaIdentifierPart(text.charAt(afterIdx));
    }

    private static String findPrecedingMemberDoc(String source, int memberIndex) {
        String before = source.substring(0, memberIndex).stripTrailing();
        int docEnd = before.lastIndexOf("*/");
        if (docEnd < 0) {
            return null;
        }
        int docStart = before.lastIndexOf("/**", docEnd);
        if (docStart < 0) {
            return null;
        }
        String between = before.substring(docEnd + 2).strip();
        return isIgnorableMemberPrefix(between) ? before.substring(docStart, docEnd + 2) : null;
    }

    private static boolean isIgnorableMemberPrefix(String between) {
        if (between.isEmpty()) {
            return true;
        }
        String cleaned = between.replaceAll("@\\w+(\\([^)]*\\))?", "").strip();
        cleaned = removeMemberDeclarationNoise(cleaned);
        return cleaned.isEmpty();
    }

    private static String removeMemberDeclarationNoise(String text) {
        String cleaned = text.replaceAll("\\b(public|protected|private|abstract|static|final)\\b", " ");
        cleaned = cleaned.replaceAll("\\b(synchronized|native|transient|volatile|default)\\b", " ");
        cleaned = cleaned.replaceAll("\\b(void|boolean|byte|char|short|int|long|float|double)\\b", " ");
        cleaned = cleaned.replaceAll("\\b[A-Z]\\w*(<[^>]*>)?\\b", " ");
        cleaned = cleaned.replaceAll("[<>,\\[\\]]", " ");
        return cleaned.replaceAll("\\s+", "").strip();
    }

    /**
     * Clean a raw Javadoc comment: strip comment markers, leading asterisks, and convert
     * common Javadoc tags to Markdown.
     */
    private static String cleanJavadoc(String rawDoc) {
        // Remove opening /** and closing */
        String doc = rawDoc.strip();
        if (doc.startsWith("/**")) doc = doc.substring(3);
        if (doc.endsWith("*/")) doc = doc.substring(0, doc.length() - 2);

        // Process line by line
        StringBuilder sb = new StringBuilder();
        String[] lines = doc.split("\n");
        for (String line : lines) {
            String trimmed = line.strip();
            // Remove leading *
            if (trimmed.startsWith("* ")) {
                trimmed = trimmed.substring(2);
            } else if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
            }
            sb.append(trimmed).append("\n");
        }

        String result = sb.toString().strip();

        // Convert common Javadoc tags to Markdown
        result = result.replaceAll("\\{@code ([^}]+)}", "`$1`");
        result = result.replaceAll("\\{@link ([^}]+)}", "`$1`");
        result = result.replaceAll("\\{@literal ([^}]+)}", "$1");
        result = result.replaceAll("@param (\\w+)", "\n**@param** `$1` —");
        result = result.replace("@return", "\n**@return**");
        result = result.replaceAll("@throws (\\w+)", "\n**@throws** `$1` —");
        result = result.replaceAll("@since ([^\n]+)", "\n*@since $1*");
        result = result.replaceAll("@see ([^\n]+)", "\n*@see* `$1`");
        result = result.replaceAll("@author ([^\n]+)", "\n*@author $1*");
        result = result.replace("<p>", "\n\n");
        result = result.replaceAll("<br\\s*/?>", "\n");
        result = result.replaceAll("</?[a-zA-Z][^>]*>", ""); // strip remaining HTML tags

        return result.strip();
    }

    /**
     * Write source to a temp file.
     */
    public static File writeSourceToTemp(String fqn, String source, String extension) {
        try {
            String relativePath = fqn.replace('.', File.separatorChar);
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "groovy-ls-sources");
            File targetFile = new File(tempDir, relativePath + extension);
            targetFile.getParentFile().mkdirs();
            Files.writeString(targetFile.toPath(), source, StandardCharsets.UTF_8);
            GroovyLanguageServerPlugin.logInfo("[source] Wrote source to " + targetFile.getAbsolutePath());
            return targetFile;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[source] Failed to write temp source for " + fqn, e);
            return null;
        }
    }

    // ---- Private helpers ----

    private static File searchCachesForSourcesJar(String sourcesJarName, File jarFile) {
        String userHome = System.getProperty("user.home");
        String jarAbsPath = jarFile.getAbsolutePath().replace('\\', '/');

        GavCoordinates gav = extractCoordinates(jarAbsPath);
        if (gav == null) {
            GroovyLanguageServerPlugin.logInfo("[source] Could not extract GAV from: " + jarAbsPath);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[source] Extracted GAV: " + gav.group + ':' + gav.artifact + ':' + gav.version);
        File gradleCandidate = findInGradleCache(userHome, sourcesJarName, gav);
        if (gradleCandidate != null) {
            return gradleCandidate;
        }
        return findInMavenCache(userHome, sourcesJarName, gav);
    }

    private static GavCoordinates extractCoordinates(String jarAbsPath) {
        GavCoordinates fromMaven = parseMavenCoordinates(jarAbsPath);
        if (fromMaven != null) {
            return fromMaven;
        }
        return parseGradleCoordinates(jarAbsPath);
    }

    private static GavCoordinates parseMavenCoordinates(String jarAbsPath) {
        int m2Idx = jarAbsPath.indexOf(M2_REPOSITORY_SEGMENT);
        if (m2Idx < 0) {
            return null;
        }
        String relPath = jarAbsPath.substring(m2Idx + M2_REPOSITORY_SEGMENT.length());
        String[] parts = relPath.split("/");
        if (parts.length < 4) {
            return null;
        }
        String version = parts[parts.length - 2];
        String artifact = parts[parts.length - 3];
        String group = buildGroup(parts, parts.length - 3);
        return new GavCoordinates(group, artifact, version);
    }

    private static GavCoordinates parseGradleCoordinates(String jarAbsPath) {
        int gradleCacheIdx = jarAbsPath.indexOf(GRADLE_FILES_SEGMENT);
        if (gradleCacheIdx < 0) {
            return null;
        }
        String relPath = jarAbsPath.substring(gradleCacheIdx + GRADLE_FILES_SEGMENT.length());
        String[] parts = relPath.split("/");
        if (parts.length < 4) {
            return null;
        }
        return new GavCoordinates(parts[0], parts[1], parts[2]);
    }

    private static String buildGroup(String[] parts, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < endExclusive; i++) {
            if (i > 0) {
                builder.append('.');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private static File findInGradleCache(String userHome, String sourcesJarName, GavCoordinates gav) {
        File gradleCacheDir = appendSegments(new File(userHome),
                ".gradle", "caches", "modules-2", "files-2.1", gav.group, gav.artifact, gav.version);
        if (!gradleCacheDir.isDirectory()) {
            return null;
        }

        File[] hashDirs = gradleCacheDir.listFiles();
        if (hashDirs == null) {
            return null;
        }

        for (File hashDir : hashDirs) {
            if (!hashDir.isDirectory()) {
                continue;
            }
            File candidate = new File(hashDir, sourcesJarName);
            if (candidate.exists()) {
                GroovyLanguageServerPlugin.logInfo("[source] Found in Gradle cache: " + candidate.getAbsolutePath());
                return candidate;
            }
        }
        return null;
    }

    private static File findInMavenCache(String userHome, String sourcesJarName, GavCoordinates gav) {
        String groupPath = gav.group.replace('.', File.separatorChar);
        File mavenDir = appendSegments(new File(userHome), ".m2", "repository", groupPath, gav.artifact, gav.version);
        if (!mavenDir.isDirectory()) {
            return null;
        }

        File candidate = new File(mavenDir, sourcesJarName);
        if (candidate.exists()) {
            GroovyLanguageServerPlugin.logInfo("[source] Found in Maven cache: " + candidate.getAbsolutePath());
            return candidate;
        }
        return null;
    }

    private static File appendSegments(File base, String... segments) {
        File current = base;
        for (String segment : segments) {
            current = new File(current, segment);
        }
        return current;
    }

    private static final class GavCoordinates {
        private final String group;
        private final String artifact;
        private final String version;

        private GavCoordinates(String group, String artifact, String version) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
        }
    }
}
