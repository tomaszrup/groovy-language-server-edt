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

import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class BinaryTypeLocationResolver {

    private static final String EXT_JAVA = ".java";
    private static final String EXT_GROOVY = ".groovy";

    private BinaryTypeLocationResolver() {
    }

    static Location resolveLocation(IType type) {
        return resolveLocation(type, type.getFullyQualifiedName(), type.getElementName());
    }

    static Location resolveLocation(IType type, String fqn, String simpleName) {
        Location jarLoc = toLocationFromSourcesJar(type, fqn, simpleName);
        if (jarLoc != null) {
            return jarLoc;
        }

        return toLocationFromJdtAttachment(type, fqn, simpleName);
    }

    static Location toLocationFromSourcesJar(IType type, String fqn, String simpleName) {
        java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
        if (sourcesJar == null) {
            return null;
        }

        String source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
        if (source == null || source.isEmpty()) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Read source from JAR for " + fqn + " (" + source.length() + " chars)");

        String ext = determineSourceExtension(sourcesJar, fqn);
        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                fqn, ext, sourcesJar.getAbsolutePath(), false, source);
        Range range = findClassDeclarationRange(source, simpleName);
        return new Location(virtualUri, range);
    }

    static Location toLocationFromJdtAttachment(IType type, String fqn, String simpleName) {
        IClassFile classFile = type.getClassFile();
        if (classFile == null) {
            return null;
        }

        String source = null;
        try {
            source = classFile.getSource();
        } catch (Exception e) {
            // no source available
        }

        if (source == null || source.isEmpty()) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Found source via JDT attachment for " + fqn);
        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                fqn, EXT_JAVA, null, false, source);
        Range range = findClassDeclarationRange(source, simpleName);
        return new Location(virtualUri, range);
    }

    static String determineSourceExtension(java.io.File sourcesJar, String fqn) {
        String entryPath = SourceJarHelper.sourceFileFqn(fqn).replace('.', '/') + EXT_GROOVY;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(sourcesJar)) {
            if (zf.getEntry(entryPath) != null) {
                return EXT_GROOVY;
            }
        } catch (Exception e) {
            // Intentionally ignored — default to .java
        }
        return EXT_JAVA;
    }

    static Range findClassDeclarationRange(String source, String simpleName) {
        int classIdx = source.indexOf("class " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("interface " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("enum " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("trait " + simpleName);
        if (classIdx >= 0) {
            PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(source);
            Position start = lineIndex.offsetToPosition(classIdx);
            Position end = lineIndex.offsetToPosition(classIdx + simpleName.length() + 6);
            return new Range(start, end);
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }
}
