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
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Cached source entry holding content and metadata for re-resolution.
     */
    private static class CacheEntry {
        final String content;
        final String jarPath;   // null for JDT attachment or stubs
        final boolean isJdk;

        CacheEntry(String content, String jarPath, boolean isJdk) {
            this.content = content;
            this.jarPath = jarPath;
            this.isJdk = isJdk;
        }
    }

    /** Cache of virtual document content keyed by FQN (e.g. "spock.lang.Specification"). */
    private static final ConcurrentHashMap<String, CacheEntry> contentCache = new ConcurrentHashMap<>();

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
            contentCache.put(fqn, new CacheEntry(content, sourcesJarPath, isJdk));
        }
        GroovyLanguageServerPlugin.logInfo("[source] Built virtual URI: " + uriStr
                + " (cached FQN=" + fqn + ")");
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
        if (uriStr == null || !uriStr.startsWith("groovy-source:")) return null;
        // Strip scheme: "groovy-source:" → rest starts with slashes + path
        String rest = uriStr.substring("groovy-source:".length());
        // Strip leading slashes (could be /// or /)
        while (rest.startsWith("/")) rest = rest.substring(1);
        // Strip query/fragment if present (shouldn't be, but be safe)
        int qIdx = rest.indexOf('?');
        if (qIdx >= 0) rest = rest.substring(0, qIdx);
        int hIdx = rest.indexOf('#');
        if (hIdx >= 0) rest = rest.substring(0, hIdx);
        // Strip file extension (.java or .groovy)
        if (rest.endsWith(".java")) rest = rest.substring(0, rest.length() - 5);
        else if (rest.endsWith(".groovy")) rest = rest.substring(0, rest.length() - 7);
        // Convert path separators to dots
        return rest.replace('/', '.');
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
            contentCache.put(fqn, new CacheEntry(jdkSource, null, true));
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
        if (jarFile == null || !jarFile.exists()) return null;

        String jarName = jarFile.getName();
        if (!jarName.endsWith(".jar")) return null;

        GroovyLanguageServerPlugin.logInfo(
                "[source] Looking for sources JAR for: " + jarFile.getAbsolutePath());

        // Strategy 1: Same directory (Maven convention)
        String baseName = jarName.substring(0, jarName.length() - 4);
        String sourcesJarName = baseName + "-sources.jar";
        File sourcesJar = new File(jarFile.getParentFile(), sourcesJarName);

        // Strategy 2: Gradle cache sibling hash dirs
        if (!sourcesJar.exists()) {
            File hashDir = jarFile.getParentFile();
            File versionDir = hashDir != null ? hashDir.getParentFile() : null;
            if (versionDir != null && versionDir.isDirectory()) {
                File[] siblings = versionDir.listFiles();
                if (siblings != null) {
                    for (File sibling : siblings) {
                        if (sibling.isDirectory()) {
                            File candidate = new File(sibling, sourcesJarName);
                            if (candidate.exists()) {
                                sourcesJar = candidate;
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Strategy 3: Cross-cache search (Maven ↔ Gradle)
        if (!sourcesJar.exists()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[source] Not found locally, searching caches for: " + sourcesJarName);
            sourcesJar = searchCachesForSourcesJar(sourcesJarName, jarFile);
        }

        if (sourcesJar == null || !sourcesJar.exists()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[source] No sources JAR found for " + jarName);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[source] Found sources JAR: " + sourcesJar.getAbsolutePath());
        return sourcesJar;
    }

    /**
     * Read source for a fully-qualified type from a sources JAR.
     * Tries both .groovy and .java extensions.
     */
    public static String readSourceFromJar(File sourcesJar, String fqn) {
        String basePath = fqn.replace('.', '/');
        String[] extensions = {".groovy", ".java"};

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
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                String pkgPrefix = fqn.substring(0, lastDot).replace('.', '/');
                StringBuilder sb = new StringBuilder();
                sb.append("[source] No entry for ").append(basePath)
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

        String basePath = fqn.replace('.', '/') + ".java";

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

        // Find all occurrences of the member name and look for preceding Javadoc
        int searchFrom = 0;
        while (searchFrom < source.length()) {
            int idx = source.indexOf(memberName, searchFrom);
            if (idx < 0) break;

            // Check it's a word boundary (not part of a larger identifier)
            if (idx > 0 && Character.isJavaIdentifierPart(source.charAt(idx - 1))) {
                searchFrom = idx + 1;
                continue;
            }
            int afterIdx = idx + memberName.length();
            if (afterIdx < source.length() && Character.isJavaIdentifierPart(source.charAt(afterIdx))) {
                searchFrom = idx + 1;
                continue;
            }

            // Look backwards for /** ... */
            String before = source.substring(0, idx).stripTrailing();
            int docEnd = before.lastIndexOf("*/");
            if (docEnd >= 0) {
                int docStart = before.lastIndexOf("/**", docEnd);
                if (docStart >= 0) {
                    String between = before.substring(docEnd + 2).strip();
                    String cleaned = between.replaceAll("@\\w+(\\([^)]*\\))?", "").strip();
                    cleaned = cleaned.replaceAll("\\b(public|protected|private|abstract|static|final|" +
                            "synchronized|native|transient|volatile|default|void|" +
                            "boolean|byte|char|short|int|long|float|double|" +
                            "[A-Z]\\w*(<[^>]*>)?)\\b", "").strip();
                    // Allow return types, generics, etc.
                    cleaned = cleaned.replaceAll("[<>,\\[\\]\\s]", "").strip();
                    if (cleaned.isEmpty()) {
                        return cleanJavadoc(before.substring(docStart, docEnd + 2));
                    }
                }
            }

            searchFrom = idx + 1;
        }

        return null;
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
        result = result.replaceAll("@return", "\n**@return**");
        result = result.replaceAll("@throws (\\w+)", "\n**@throws** `$1` —");
        result = result.replaceAll("@since ([^\n]+)", "\n*@since $1*");
        result = result.replaceAll("@see ([^\n]+)", "\n*@see* `$1`");
        result = result.replaceAll("@author ([^\n]+)", "\n*@author $1*");
        result = result.replaceAll("<p>", "\n\n");
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

        String group = null, artifact = null, version = null;

        // Parse Maven path
        int m2Idx = jarAbsPath.indexOf(".m2/repository/");
        if (m2Idx >= 0) {
            String relPath = jarAbsPath.substring(m2Idx + ".m2/repository/".length());
            String[] parts = relPath.split("/");
            if (parts.length >= 4) {
                version = parts[parts.length - 2];
                artifact = parts[parts.length - 3];
                StringBuilder gb = new StringBuilder();
                for (int i = 0; i < parts.length - 3; i++) {
                    if (i > 0) gb.append(".");
                    gb.append(parts[i]);
                }
                group = gb.toString();
            }
        }

        // Parse Gradle cache path
        int gradleCacheIdx = jarAbsPath.indexOf("files-2.1/");
        if (gradleCacheIdx >= 0 && group == null) {
            String relPath = jarAbsPath.substring(gradleCacheIdx + "files-2.1/".length());
            String[] parts = relPath.split("/");
            if (parts.length >= 4) {
                group = parts[0];
                artifact = parts[1];
                version = parts[2];
            }
        }

        if (group == null || artifact == null || version == null) {
            GroovyLanguageServerPlugin.logInfo("[source] Could not extract GAV from: " + jarAbsPath);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[source] Extracted GAV: " + group + ":" + artifact + ":" + version);

        // Search Gradle cache
        File gradleCacheDir = new File(userHome,
                ".gradle/caches/modules-2/files-2.1/" + group + "/" + artifact + "/" + version);
        if (gradleCacheDir.isDirectory()) {
            File[] hashDirs = gradleCacheDir.listFiles();
            if (hashDirs != null) {
                for (File hashDir : hashDirs) {
                    if (hashDir.isDirectory()) {
                        File candidate = new File(hashDir, sourcesJarName);
                        if (candidate.exists()) {
                            GroovyLanguageServerPlugin.logInfo(
                                    "[source] Found in Gradle cache: " + candidate.getAbsolutePath());
                            return candidate;
                        }
                    }
                }
            }
        }

        // Search Maven cache
        String groupPath = group.replace('.', '/');
        File mavenDir = new File(userHome, ".m2/repository/" + groupPath + "/" + artifact + "/" + version);
        if (mavenDir.isDirectory()) {
            File candidate = new File(mavenDir, sourcesJarName);
            if (candidate.exists()) {
                GroovyLanguageServerPlugin.logInfo(
                        "[source] Found in Maven cache: " + candidate.getAbsolutePath());
                return candidate;
            }
        }

        return null;
    }
}
