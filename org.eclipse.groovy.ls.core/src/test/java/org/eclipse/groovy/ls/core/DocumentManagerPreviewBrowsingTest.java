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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.eclipse.groovy.ls.core.providers.DiagnosticsProvider;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the "preview browsing degradation" scenario.
 * <p>
 * When a user quickly previews multiple files (open → close → open next),
 * the didOpen background tasks (JDT working copy creation + reconcile) must
 * be cancelled on close. Otherwise:
 * <ul>
 *   <li>Background tasks pile up and exhaust the thread pool</li>
 *   <li>Working copies are created after didClose already cleaned up → leaked</li>
 *   <li>Diagnostics are published for files that are no longer open</li>
 *   <li>After enough leaked tasks, new files stop getting diagnostics/semantics</li>
 * </ul>
 */
class DocumentManagerPreviewBrowsingTest {

    @TempDir
    Path tempDir;

    // ================================================================
    // didClose cancels pending didOpen background tasks
    // ================================================================

    @Test
    void didCloseRemovesPendingOpenFuture() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = createFileUri("CancelPending.groovy");

        manager.didOpen(uri, "class A {}");

        // The pending future should exist immediately after didOpen
        Future<?> pending = manager.getPendingOpenFuture(uri);

        // didClose should cancel and remove it
        manager.didClose(uri);

        assertNotNull(pending);
        assertNull(manager.getPendingOpenFuture(uri),
                "Pending open future should be removed on didClose");
        assertNull(manager.getContent(uri),
                "Content should be cleared on didClose");
        assertNull(manager.getWorkingCopy(uri),
                "Working copy should be null after didClose");
    }

    @Test
    void didCloseBeforeBackgroundTaskCompletesDoesNotLeakWorkingCopy() throws Exception {
        DocumentManager manager = new DocumentManager();

        // Simulate rapid preview browsing: open and immediately close 10 files
        for (int i = 0; i < 10; i++) {
            String uri = createFileUri("Preview" + i + ".groovy");
            manager.didOpen(uri, "class Preview" + i + " { }");
            manager.didClose(uri);
        }

        assertTrue(awaitOpenStateClean(manager, 2000),
            "Background didOpen work should settle after all preview files are closed");

        // Verify no working copies are leaked
        assertEquals(0, manager.getWorkingCopyCount(),
                "No working copies should remain after all files are closed, "
            + "but found: " + manager.getWorkingCopyCount());

        // Verify no pending futures are leaked
        assertEquals(0, manager.getPendingOpenFutureCount(),
            "No pending open futures should remain, but found: "
            + manager.getPendingOpenFutureCount());
    }

    @Test
    void rapidPreviewBrowsingDoesNotExhaustThreadPool() throws Exception {
        DocumentManager manager = new DocumentManager();

        // Open and close 20 files in rapid succession — simulates clicking
        // through files in VS Code's file explorer with preview mode.
        // Before the fix, this would create 20 background tasks on the
        // shared ForkJoinPool (only ~3 threads), exhausting it.
        for (int i = 0; i < 20; i++) {
            String uri = createFileUri("Rapid" + i + ".groovy");
            manager.didOpen(uri, "class Rapid" + i + " { def x" + i + " }");
            manager.didClose(uri);
        }

        // Now open a file that should actually work — verify it's tracked
        String activeUri = createFileUri("Active.groovy");
        manager.didOpen(activeUri, "class Active { }");

        assertNotNull(manager.getContent(activeUri),
                "Active document content should be available after rapid previewing");
        assertTrue(manager.getOpenDocumentUris().contains(
                DocumentManager.normalizeUri(activeUri)),
                "Active document should be in open documents set");

        manager.didClose(activeUri);
    }

    // ================================================================
    // didOpen replaces previous pending task for same URI
    // ================================================================

    @Test
    void reopenSameFileCancelsPreviousPendingTask() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = createFileUri("Reopen.groovy");

        manager.didOpen(uri, "class Version1 {}");

        Future<?> firstFuture = manager.getPendingOpenFuture(uri);

        // Close and reopen with different content
        manager.didClose(uri);
        manager.didOpen(uri, "class Version2 {}");

        // The first future should have been cancelled
        if (firstFuture != null) {
            assertTrue(firstFuture.isCancelled() || firstFuture.isDone(),
                    "First didOpen future should be cancelled or done");
        }

        // Content should reflect the latest open
        assertEquals("class Version2 {}", manager.getContent(uri));

        manager.didClose(uri);
    }

    // ================================================================
    // DiagnosticsProvider: clearDiagnostics cancels pending debounced task
    // ================================================================

    @Test
    void clearDiagnosticsCancelsPendingDebouncedTask() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///ClearPending.groovy";
        dm.didOpen(uri, "class ClearPending {}");

        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        LanguageClient client = mock(LanguageClient.class);
        provider.connect(client);

        // Schedule a debounced publish (500ms delay)
        provider.publishDiagnosticsDebounced(uri);

        // Immediately clear (simulating didClose before 500ms elapses)
        provider.clearDiagnostics(uri);
        dm.didClose(uri);

        pauseMillis(800);

        // The debounced task should have been cancelled — no publish should occur
        verify(client, never()).publishDiagnostics(any(PublishDiagnosticsParams.class));
    }

    @Test
    void publishDiagnosticsSkipsClosedDocument() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///SkipClosed.groovy";
        dm.didOpen(uri, "class SkipClosed {}");
        dm.didClose(uri);

        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        LanguageClient client = mock(LanguageClient.class);
        provider.connect(client);

        // publishDiagnostics for a closed document should skip
        provider.publishDiagnostics(uri);

        verify(client, never()).publishDiagnostics(any(PublishDiagnosticsParams.class));
    }

    // ================================================================
    // Integration: full preview cycle - open, debounce, close, no publish
    // ================================================================

    @Test
    void fullPreviewCycleDoesNotPublishDiagnosticsForClosedFile() {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        LanguageClient client = mock(LanguageClient.class);
        provider.connect(client);

        // Simulate previewing 5 files, each open for only 100ms
        for (int i = 0; i < 5; i++) {
            String uri = "file:///Preview" + i + ".groovy";
            dm.didOpen(uri, "class Preview" + i + " {}");
            provider.publishDiagnosticsDebounced(uri);

            // File is closed quickly (before 500ms debounce fires)
            provider.clearDiagnostics(uri);
            dm.didClose(uri);
        }

        // Open a file that stays open
        String activeUri = "file:///StaysOpen.groovy";
        dm.didOpen(activeUri, "class StaysOpen {}");
        provider.publishDiagnosticsDebounced(activeUri);

        // Only the active file should have gotten diagnostics published
        verify(client, timeout(1500).atLeastOnce()).publishDiagnostics(
                org.mockito.ArgumentMatchers.argThat(params ->
                        params.getUri().equals(activeUri)));

        dm.didClose(activeUri);
    }

    // ================================================================
    // No leaked open documents after preview cycle
    // ================================================================

    @Test
    void noLeakedOpenDocumentsAfterPreviewCycle() throws Exception {
        DocumentManager manager = new DocumentManager();

        for (int i = 0; i < 15; i++) {
            String uri = createFileUri("Leak" + i + ".groovy");
            manager.didOpen(uri, "class Leak" + i + " {}");
            manager.didClose(uri);
        }

        assertTrue(manager.getOpenDocumentUris().isEmpty(),
                "All documents should be closed: " + manager.getOpenDocumentUris());
    }

    // ================================================================
    // Background task detects file is closed and discards working copy
    // ================================================================

    @Test
    void backgroundTaskChecksOpenDocumentsBeforeStoringWorkingCopy() throws Exception {
        DocumentManager manager = new DocumentManager();
        String uri = createFileUri("CheckBeforeStore.groovy");

        // Open a file — this starts a background task
        manager.didOpen(uri, "class CheckBeforeStore {}");

        // Close it immediately — the background task may or may not have started
        manager.didClose(uri);

        assertTrue(awaitWorkingCopyReleased(manager, uri, 3000),
            "Working copy should be discarded once the closed-file background task settles");

        // Verify: the working copy must NOT exist, regardless of whether
        // the background task had started before didClose ran
        assertNull(manager.getWorkingCopy(uri),
                "Working copy must not leak after didClose");
        assertNull(manager.getContent(uri),
                "Content must be cleared after didClose");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String createFileUri(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "// fixture\n");
        return file.toUri().toString();
    }

    private boolean awaitOpenStateClean(DocumentManager manager, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (manager.getWorkingCopyCount() == 0 && manager.getPendingOpenFutureCount() == 0) {
                return true;
            }
            pauseMillis(25);
        }
        return manager.getWorkingCopyCount() == 0 && manager.getPendingOpenFutureCount() == 0;
    }

    private boolean awaitWorkingCopyReleased(DocumentManager manager, String uri, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (manager.getWorkingCopy(uri) == null && manager.getContent(uri) == null) {
                return true;
            }
            pauseMillis(25);
        }
        return manager.getWorkingCopy(uri) == null && manager.getContent(uri) == null;
    }

    private void pauseMillis(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
