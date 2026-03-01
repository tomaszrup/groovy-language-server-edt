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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentManagerSynchronizationTest {

    @TempDir
    Path tempDir;

    @Test
    void didOpenStoresContentUnderNormalizedUriAndTracksClientUri() throws IOException {
        DocumentManager manager = new DocumentManager();
        Path file = createFile("My File — Name.groovy");

        String canonical = file.toUri().toString();
        String jdtLike = canonical
                .replace("file:///", "file:/")
                .replace("%20", " ")
                .replace("%E2%80%94", "—");

        manager.didOpen(jdtLike, "class A {}\n");

        assertEquals("class A {}\n", manager.getContent(canonical));
        assertEquals(jdtLike, manager.getClientUri(canonical));
        assertTrue(manager.getOpenDocumentUris().contains(DocumentManager.normalizeUri(canonical)));
    }

    @Test
    void didChangeWithNullRangeReplacesEntireDocument() throws IOException {
        DocumentManager manager = new DocumentManager();
        String uri = createFile("ReplaceAll.groovy").toUri().toString();

        manager.didOpen(uri, "old text\n");

        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setText("new text\n");
        change.setRange(null);

        manager.didChange(uri, List.of(change));

        assertEquals("new text\n", manager.getContent(uri));
        assertEquals(uri, manager.getClientUri(uri));
    }

    @Test
    void didChangeWithRangeAppliesIncrementalEdit() throws IOException {
        DocumentManager manager = new DocumentManager();
        String uri = createFile("Incremental.groovy").toUri().toString();

        manager.didOpen(uri, "hello world\n");

        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setRange(new Range(new Position(0, 6), new Position(0, 11)));
        change.setText("groovy");

        manager.didChange(uri, List.of(change));

        assertEquals("hello groovy\n", manager.getContent(uri));
    }

    @Test
    void didCloseRemovesContentAndClientUriMapping() throws IOException {
        DocumentManager manager = new DocumentManager();
        String uri = createFile("CloseMe.groovy").toUri().toString();

        manager.didOpen(uri, "class A {}\n");
        manager.didClose(uri);

        assertNull(manager.getContent(uri));
        assertEquals(DocumentManager.normalizeUri(uri), manager.getClientUri(uri));
        assertTrue(manager.getOpenDocumentUris().isEmpty());
    }

    private Path createFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "// fixture\n");
        return file;
    }
}
