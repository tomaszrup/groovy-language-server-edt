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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentManagerUriNormalizationTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizeUriReturnsNullForNullInput() {
        assertNull(DocumentManager.normalizeUri(null));
    }

    @Test
    void normalizeUriLeavesNonFileUriUnchanged() {
        String uri = "untitled:Scratch.groovy";
        assertEquals(uri, DocumentManager.normalizeUri(uri));
    }

    @Test
    void normalizeUriIsIdempotentForFileUri() throws IOException {
        Path sourceFile = createSourceFile("My File — Name.groovy");
        String normalized = DocumentManager.normalizeUri(sourceFile.toUri().toString());

        assertNotNull(normalized);
        assertTrue(normalized.startsWith("file:"));
        assertFalse(normalized.endsWith("/"));
        assertEquals(normalized, DocumentManager.normalizeUri(normalized));
    }

    @Test
    void normalizeUriHandlesJdtLikeUriVariants() throws IOException {
        Path sourceFile = createSourceFile("My File — Name.groovy");
        String canonical = DocumentManager.normalizeUri(sourceFile.toUri().toString());

        String jdtLike = canonical
                .replace("file:///", "file:/")
                .replace("%20", " ")
                .replace("%E2%80%94", "—");

        assertEquals(canonical, DocumentManager.normalizeUri(jdtLike));
    }

    @Test
    void normalizeUriNormalizesWindowsDriveLetterCase() throws IOException {
        Path sourceFile = createSourceFile("Drive Letter Test.groovy");
        String canonical = DocumentManager.normalizeUri(sourceFile.toUri().toString());

        String absolute = sourceFile.toAbsolutePath().toString();
        if (absolute.length() < 2 || absolute.charAt(1) != ':') {
            return;
        }

        String upperDrivePath = Character.toUpperCase(absolute.charAt(0)) + absolute.substring(1);
        String encodedPath = upperDrivePath
                .replace("\\", "/")
                .replace(" ", "%20")
                .replace("—", "%E2%80%94");
        String windowsStyleUri = "file:///" + encodedPath;

        assertEquals(canonical, DocumentManager.normalizeUri(windowsStyleUri));
    }

    private Path createSourceFile(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, "class Example {}\n");
        return file;
    }
}
