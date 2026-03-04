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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.groovy.ls.core.providers.CodeActionProvider;
import org.eclipse.groovy.ls.core.providers.CallHierarchyProvider;
import org.eclipse.groovy.ls.core.providers.CodeLensProvider;
import org.eclipse.groovy.ls.core.providers.CompletionProvider;
import org.eclipse.groovy.ls.core.providers.DefinitionProvider;
import org.eclipse.groovy.ls.core.providers.DiagnosticsProvider;
import org.eclipse.groovy.ls.core.providers.DocumentHighlightProvider;
import org.eclipse.groovy.ls.core.providers.DocumentSymbolProvider;
import org.eclipse.groovy.ls.core.providers.FoldingRangeProvider;
import org.eclipse.groovy.ls.core.providers.FormattingProvider;
import org.eclipse.groovy.ls.core.providers.HoverProvider;
import org.eclipse.groovy.ls.core.providers.ImplementationProvider;
import org.eclipse.groovy.ls.core.providers.InlayHintProvider;
import org.eclipse.groovy.ls.core.providers.ReferenceProvider;
import org.eclipse.groovy.ls.core.providers.RenameProvider;
import org.eclipse.groovy.ls.core.providers.SemanticTokensProvider;
import org.eclipse.groovy.ls.core.providers.SignatureHelpProvider;
import org.eclipse.groovy.ls.core.providers.TypeDefinitionProvider;
import org.eclipse.groovy.ls.core.providers.TypeHierarchyProvider;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * Implements the LSP {@link TextDocumentService} by delegating to individual providers.
 * <p>
 * Each provider wraps JDT/groovy-eclipse APIs for a specific LSP feature (completion,
 * hover, diagnostics, etc.). This class also handles document synchronization (open/change/
 * close/save) by routing to the {@link DocumentManager}.
 */
public class GroovyTextDocumentService implements TextDocumentService {

    private final GroovyLanguageServer server;
    private final DocumentManager documentManager;
    private LanguageClient client;

    /**
     * Dedicated executor for all LSP request handlers (completion, hover,
     * semantic tokens, definition, …).  Using {@code ForkJoinPool.commonPool()}
     * (the default for {@code CompletableFuture.supplyAsync()}) caused pool
     * exhaustion — the common pool has only {@code availableProcessors - 1}
     * threads (as few as 1 on 2-core machines), and a single slow
     * {@code codeSelect()} or {@code getModuleNode()} call would starve
     * every other feature.
     * <p>
     * Uses {@code corePoolSize == maxPoolSize} with
     * {@link ThreadPoolExecutor#allowCoreThreadTimeOut(boolean)} so that the
     * pool scales up immediately under load (no SynchronousQueue needed)
     * while idle threads are still reclaimed after 60 s.  A bounded
     * {@link LinkedBlockingQueue} absorbs request bursts (e.g. opening
     * 4 files simultaneously) instead of rejecting them.
     */
    private volatile ExecutorService lspRequestExecutor;
    static {
        // Initialised in the instance initialiser — see below.
    }
    {
        int poolSize = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        int queueCapacity = 128;
        lspRequestExecutor = createLspExecutor(poolSize, queueCapacity);
        GroovyLanguageServerPlugin.logInfo(
                "LSP request executor: poolSize=" + poolSize + ", queueCapacity=" + queueCapacity);
    }

    private static ExecutorService createLspExecutor(int poolSize, int queueCapacity) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "groovy-ls-request");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    // Reject instead of running on the LSP dispatch thread
                    // (CallerRunsPolicy would freeze ALL message processing).
                    GroovyLanguageServerPlugin.logError(
                            "LSP request executor saturated — rejecting task", null);
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Reconfigure the LSP request thread pool.  Called from
     * {@link GroovyLanguageServer#initialize} when the client sends
     * custom {@code initializationOptions}.
     */
    void configureRequestPool(int poolSize, int queueCapacity) {
        ExecutorService old = this.lspRequestExecutor;
        this.lspRequestExecutor = createLspExecutor(poolSize, queueCapacity);
        old.shutdownNow();
        GroovyLanguageServerPlugin.logInfo(
                "LSP request executor reconfigured: poolSize=" + poolSize
                + ", queueCapacity=" + queueCapacity);
    }

    /**
     * Tracks the latest in-flight semantic-token future per URI so that we
     * can cancel a superseded request when a new one arrives (VS Code sends
     * {@code textDocument/semanticTokens/full} after every edit).
     */
    private final Map<String, CompletableFuture<?>> pendingSemanticTokens =
            new ConcurrentHashMap<>();

    /**
     * Tracks the latest in-flight hover future per URI so that we can
     * cancel a superseded request when a new one arrives (e.g. rapid
     * cursor movement).  Mirrors the {@link #pendingSemanticTokens} pattern.
     */
    private final Map<String, CompletableFuture<?>> pendingHovers =
            new ConcurrentHashMap<>();

    /** Per-URI cancellation for document symbols. */
    private final Map<String, CompletableFuture<?>> pendingDocumentSymbols =
            new ConcurrentHashMap<>();

    /** Per-URI cancellation for code lenses. */
    private final Map<String, CompletableFuture<?>> pendingCodeLenses =
            new ConcurrentHashMap<>();

    /** Per-URI cancellation for folding ranges. */
    private final Map<String, CompletableFuture<?>> pendingFoldingRanges =
            new ConcurrentHashMap<>();

    /** Per-URI cancellation for inlay hints. */
    private final Map<String, CompletableFuture<?>> pendingInlayHints =
            new ConcurrentHashMap<>();

    /** Per-URI cancellation for code actions. */
    private final Map<String, CompletableFuture<?>> pendingCodeActions =
            new ConcurrentHashMap<>();

    // Providers
    private final CompletionProvider completionProvider;
    private final HoverProvider hoverProvider;
    private final DefinitionProvider definitionProvider;
    private final DocumentSymbolProvider documentSymbolProvider;
    private final ReferenceProvider referenceProvider;
    private final RenameProvider renameProvider;
    private final SignatureHelpProvider signatureHelpProvider;
    private final SemanticTokensProvider semanticTokensProvider;
    private final FormattingProvider formattingProvider;
    private final DiagnosticsProvider diagnosticsProvider;
    private final CodeActionProvider codeActionProvider;
    private final InlayHintProvider inlayHintProvider;
    private final DocumentHighlightProvider documentHighlightProvider;
    private final FoldingRangeProvider foldingRangeProvider;
    private final TypeDefinitionProvider typeDefinitionProvider;
    private final ImplementationProvider implementationProvider;
    private final TypeHierarchyProvider typeHierarchyProvider;
    private final CallHierarchyProvider callHierarchyProvider;
    private final CodeLensProvider codeLensProvider;

    public GroovyTextDocumentService(GroovyLanguageServer server, DocumentManager documentManager) {
        this.server = server;
        this.documentManager = documentManager;

        this.completionProvider = new CompletionProvider(documentManager);
        this.hoverProvider = new HoverProvider(documentManager);
        this.definitionProvider = new DefinitionProvider(documentManager);
        this.documentSymbolProvider = new DocumentSymbolProvider(documentManager);
        this.referenceProvider = new ReferenceProvider(documentManager);
        this.renameProvider = new RenameProvider(documentManager);
        this.signatureHelpProvider = new SignatureHelpProvider(documentManager);
        this.semanticTokensProvider = new SemanticTokensProvider(documentManager);
        this.formattingProvider = new FormattingProvider(documentManager);
        this.diagnosticsProvider = new DiagnosticsProvider(documentManager);
        this.diagnosticsProvider.setClasspathChecker(uri -> {
            String projectName = server.getProjectNameForUri(uri);
            return projectName != null && server.hasClasspathForProject(projectName);
        });
        this.diagnosticsProvider.setBuildInProgressSupplier(server::isBuildInProgress);
        this.codeActionProvider = new CodeActionProvider(documentManager, diagnosticsProvider);
        this.inlayHintProvider = new InlayHintProvider(documentManager);
        this.documentHighlightProvider = new DocumentHighlightProvider(documentManager);
        this.foldingRangeProvider = new FoldingRangeProvider(documentManager);
        this.typeDefinitionProvider = new TypeDefinitionProvider(documentManager);
        this.implementationProvider = new ImplementationProvider(documentManager);
        this.typeHierarchyProvider = new TypeHierarchyProvider(documentManager);
        this.callHierarchyProvider = new CallHierarchyProvider(documentManager);
        this.codeLensProvider = new CodeLensProvider(documentManager);
    }

    void connect(LanguageClient client) {
        this.client = client;
        this.diagnosticsProvider.connect(client);
        this.documentManager.setLanguageClient(client);
    }

    // ---- Document synchronization ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();

        GroovyLanguageServerPlugin.logInfo("Document opened: " + uri);
        documentManager.didOpen(uri, text);

        // Publish diagnostics immediately — on didOpen the full file content
        // is already available and there are no rapid-fire edits to coalesce.
        // The task still runs asynchronously so the LSP dispatch thread is
        // never stalled by workspace-lock contention during a build.
        if (server.areDiagnosticsEnabled()) {
            diagnosticsProvider.publishDiagnosticsImmediate(uri);
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        GroovyLanguageServerPlugin.logInfo(
            "[diag-trace] didChange uri=" + uri
            + " changes=" + (params.getContentChanges() != null ? params.getContentChanges().size() : 0));
        documentManager.didChange(uri, params.getContentChanges());

        // Invalidate hover cache so stale results are not served
        hoverProvider.invalidateHoverCache(uri);

        // Invalidate code lens resolve cache so stale reference counts are refreshed
        codeLensProvider.invalidateCodeLensCache(uri);

        // Re-publish diagnostics after changes
        if (server.areDiagnosticsEnabled()) {
            diagnosticsProvider.publishDiagnosticsDebounced(uri);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        GroovyLanguageServerPlugin.logInfo("Document closed: " + uri);
        documentManager.didClose(uri);
        // NOTE: we intentionally do NOT invalidate the semantic tokens cache here.
        // Cached tokens must survive close/reopen cycles so that if a file is
        // reopened while still broken, the last-known-good tokens are returned
        // instead of an empty result that wipes all highlighting.

        // Clear diagnostics for closed document
        diagnosticsProvider.clearDiagnostics(uri);
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        GroovyLanguageServerPlugin.logInfo("Document saved: " + uri);

        // Use debounced diagnostics (same as didChange) so that the save
        // coalesces with any pending didChange debounce instead of running
        // a redundant third reconcile.
        if (server.areDiagnosticsEnabled()) {
            diagnosticsProvider.publishDiagnosticsDebounced(uri);
        }
    }

    // ---- Completion ----

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                CompletionList completionList = new CompletionList();
                completionList.setItems(completionProvider.getCompletions(params));
                completionList.setIsIncomplete(true);
                return Either.<List<CompletionItem>, CompletionList>forRight(completionList);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Completion failed", e);
                CompletionList empty = new CompletionList();
                empty.setItems(new ArrayList<>());
                empty.setIsIncomplete(true);
                return Either.<List<CompletionItem>, CompletionList>forRight(empty);
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Completion timed out", null);
                    }
                    CompletionList empty = new CompletionList();
                    empty.setItems(new ArrayList<>());
                    empty.setIsIncomplete(true);
                    return Either.forRight(empty);
                });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return completionProvider.resolveCompletionItem(item);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Completion resolve failed", e);
                return item;
            }
        }, lspRequestExecutor)
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Completion resolve timed out", null);
                    }
                    return item;
                });
    }

    // ---- Hover ----

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        // Cancel any previous in-flight hover for this URI
        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingHovers.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<Hover> future = CompletableFuture.supplyAsync(() -> {
            try {
                return hoverProvider.getHover(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Hover failed", e);
                return null;
            }
        }, lspRequestExecutor)
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Hover timed out for " + uri, null);
                    }
                    return null;
                });

        if (normalizedUri != null) {
            pendingHovers.put(normalizedUri, future);
            future.whenComplete((r, t) -> pendingHovers.remove(normalizedUri, future));
        }
        return future;
    }

    // ---- Definition ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        definitionProvider.getDefinition(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Definition failed", e);
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        new ArrayList<>());
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Definition timed out for "
                                + params.getTextDocument().getUri(), null);
                    }
                    return Either.forLeft(new ArrayList<>());
                });
    }

    // ---- References ----

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        CompletableFuture<List<? extends Location>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return referenceProvider.getReferences(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("References failed", e);
                List<Location> empty = new ArrayList<>();
                return empty;
            }
        }, lspRequestExecutor);

        return future
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "References timed out for "
                                + params.getTextDocument().getUri(), null);
                    }
                    return new ArrayList<>();
                });
    }

    // ---- Document Symbols ----

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingDocumentSymbols.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> baseFuture =
                CompletableFuture.supplyAsync(() -> {
            try {
                return documentSymbolProvider.getDocumentSymbols(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Document symbols failed", e);
                return new ArrayList<Either<SymbolInformation, DocumentSymbol>>();
            }
        }, lspRequestExecutor);
        CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> future = baseFuture
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Document symbols timed out for " + uri, null);
                    }
                    return new ArrayList<>();
                });

        if (normalizedUri != null) {
            pendingDocumentSymbols.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingDocumentSymbols.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    // ---- Document Highlight ----

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
            DocumentHighlightParams params) {
        CompletableFuture<List<? extends DocumentHighlight>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return documentHighlightProvider.getDocumentHighlights(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Document highlight failed", e);
                return new ArrayList<DocumentHighlight>();
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Document highlight timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    // ---- Type Definition ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            TypeDefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Either.forLeft(typeDefinitionProvider.getTypeDefinition(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type definition failed", e);
                return Either.forLeft(new ArrayList<>());
            }
        }, lspRequestExecutor);
    }

    // ---- Implementation ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Either.forLeft(implementationProvider.getImplementations(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Implementation failed", e);
                return Either.forLeft(new ArrayList<>());
            }
        }, lspRequestExecutor);
    }

    // ---- Folding Range ----

    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        // Folding ranges are non-essential during build — return empty to avoid
        // blocking a thread on the workspace lock.  VS Code will re-request
        // after the next edit or focus change.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingFoldingRanges.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<List<FoldingRange>> baseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return foldingRangeProvider.getFoldingRanges(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Folding range failed", e);
                return new ArrayList<FoldingRange>();
            }
        }, lspRequestExecutor);
        CompletableFuture<List<FoldingRange>> future = baseFuture
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Folding range timed out for " + uri, null);
                    }
                    return new ArrayList<>();
                });

        if (normalizedUri != null) {
            pendingFoldingRanges.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingFoldingRanges.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    // ---- Rename ----

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
            PrepareRenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renameProvider.prepareRename(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare rename failed", e);
                return null;
            }
        }, lspRequestExecutor);
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renameProvider.rename(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Rename failed", e);
                return null;
            }
        }, lspRequestExecutor);
    }

    // ---- Signature Help ----

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return signatureHelpProvider.getSignatureHelp(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Signature help failed", e);
                return null;
            }
        }, lspRequestExecutor)
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Signature help timed out", null);
                    }
                    return null;
                });
    }

    // ---- Semantic Tokens ----

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(
            org.eclipse.lsp4j.SemanticTokensParams params) {
        // Semantic tokens are non-essential during build — return empty to avoid
        // blocking a thread on the workspace lock.  VS Code will re-request
        // via workspace/semanticTokens/refresh after the build completes.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(
                    new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
        }

        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        // Cancel any previous in-flight semantic token request for this URI.
        // VS Code sends a new request after every edit; without cancellation
        // the old requests pile up on the executor and block newer ones.
        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingSemanticTokens.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<org.eclipse.lsp4j.SemanticTokens> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return semanticTokensProvider.getSemanticTokensFull(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (full) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        }, lspRequestExecutor);
        if (normalizedUri != null) {
            pendingSemanticTokens.put(normalizedUri, future);
            future.whenComplete((r, t) -> pendingSemanticTokens.remove(normalizedUri, future));
        }
        return future;
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(
            org.eclipse.lsp4j.SemanticTokensRangeParams params) {
        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingSemanticTokens.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<org.eclipse.lsp4j.SemanticTokens> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return semanticTokensProvider.getSemanticTokensRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (range) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        }, lspRequestExecutor);
        if (normalizedUri != null) {
            pendingSemanticTokens.put(normalizedUri, future);
            future.whenComplete((r, t) -> pendingSemanticTokens.remove(normalizedUri, future));
        }
        return future;
    }

    // ---- Inlay Hints ----

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        // Inlay hints are non-essential during build.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingInlayHints.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<List<InlayHint>> baseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return inlayHintProvider.getInlayHints(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Inlay hints failed", e);
                return new ArrayList<InlayHint>();
            }
        }, lspRequestExecutor);
        CompletableFuture<List<InlayHint>> future = baseFuture
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Inlay hints timed out for " + uri, null);
                    }
                    return new ArrayList<>();
                });

        if (normalizedUri != null) {
            pendingInlayHints.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingInlayHints.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    // ---- Code Actions ----

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingCodeActions.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<List<Either<Command, CodeAction>>> baseFuture =
                CompletableFuture.supplyAsync(() -> {
            try {
                return codeActionProvider.getCodeActions(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code action failed", e);
                return new ArrayList<Either<Command, CodeAction>>();
            }
        }, lspRequestExecutor);
        CompletableFuture<List<Either<Command, CodeAction>>> future = baseFuture
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Code action timed out for " + uri, null);
                    }
                    return new ArrayList<>();
                });

        if (normalizedUri != null) {
            pendingCodeActions.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingCodeActions.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    // ---- Formatting ----

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(
            DocumentFormattingParams params) {
        CompletableFuture<List<? extends TextEdit>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return formattingProvider.format(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Formatting timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            DocumentRangeFormattingParams params) {
        CompletableFuture<List<? extends TextEdit>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return formattingProvider.formatRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Range formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Range formatting timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
            DocumentOnTypeFormattingParams params) {
        CompletableFuture<List<? extends TextEdit>> future =
                CompletableFuture.supplyAsync(() -> {
            try {
                return formattingProvider.formatOnType(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("On-type formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, lspRequestExecutor);
        return future
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("On-type formatting timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    // ---- Type Hierarchy ----

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
            TypeHierarchyPrepareParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return typeHierarchyProvider.prepareTypeHierarchy(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare type hierarchy failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
            TypeHierarchySupertypesParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return typeHierarchyProvider.getSupertypes(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type hierarchy supertypes failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
            TypeHierarchySubtypesParams params) {
        CompletableFuture<List<TypeHierarchyItem>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return typeHierarchyProvider.getSubtypes(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type hierarchy subtypes failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
        return future.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .exceptionally(ex -> {
            GroovyLanguageServerPlugin.logError("Type hierarchy subtypes timed out", ex);
            return new ArrayList<>();
        });
    }

    // ---- Call Hierarchy ----

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
            CallHierarchyPrepareParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callHierarchyProvider.prepareCallHierarchy(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare call hierarchy failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
            CallHierarchyIncomingCallsParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callHierarchyProvider.getIncomingCalls(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Call hierarchy incoming calls failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
            CallHierarchyOutgoingCallsParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callHierarchyProvider.getOutgoingCalls(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Call hierarchy outgoing calls failed", e);
                return new ArrayList<>();
            }
        }, lspRequestExecutor);
    }

    // ---- Code Lens ----

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        // Code lenses are non-essential during build.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingCodeLenses.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<List<? extends CodeLens>> baseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return codeLensProvider.getCodeLenses(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code lens failed", e);
                return new ArrayList<CodeLens>();
            }
        }, lspRequestExecutor);
        CompletableFuture<List<? extends CodeLens>> future = baseFuture
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Code lens timed out for " + uri, null);
                    }
                    return new ArrayList<>();
                });

        if (normalizedUri != null) {
            pendingCodeLenses.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingCodeLenses.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens codeLens) {
        CompletableFuture<CodeLens> future = CompletableFuture.supplyAsync(() -> {
            try {
                return codeLensProvider.resolveCodeLens(codeLens);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code lens resolve failed", e);
                return codeLens;
            }
        }, lspRequestExecutor);
        return future.orTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    GroovyLanguageServerPlugin.logError("Code lens resolve timed out", ex);
                    return codeLens;
                });
    }

    /**
     * Update the formatter profile path from configuration.
     */
    public void updateFormatterProfile(String profilePath) {
        formattingProvider.setFormatterProfilePath(profilePath);
    }

    public void updateInlayHintSettings(Object settings) {
        inlayHintProvider.updateSettingsFromObject(settings);
    }

    void publishDiagnosticsIfEnabled(String uri) {
        if (server.areDiagnosticsEnabled()) {
            String clientUri = documentManager.getClientUri(uri);
            GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnosticsIfEnabled uri=" + clientUri);
            diagnosticsProvider.publishDiagnosticsDebounced(clientUri);
        }
    }

    void publishDiagnosticsForOpenDocuments() {
        if (server.areDiagnosticsEnabled()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] publishDiagnosticsForOpenDocuments count="
                    + documentManager.getOpenDocumentUris().size());
            for (String uri : documentManager.getOpenDocumentUris()) {
                diagnosticsProvider.publishDiagnosticsDebounced(documentManager.getClientUri(uri));
            }
        }
    }

    /**
     * Shut down executor pools. Called from {@link GroovyLanguageServer#shutdown()}.
     */
    void shutdown() {
        lspRequestExecutor.shutdownNow();
        pendingHovers.values().forEach(f -> f.cancel(true));
        pendingHovers.clear();
        pendingSemanticTokens.values().forEach(f -> f.cancel(true));
        pendingSemanticTokens.clear();
        pendingDocumentSymbols.values().forEach(f -> f.cancel(true));
        pendingDocumentSymbols.clear();
        pendingCodeLenses.values().forEach(f -> f.cancel(true));
        pendingCodeLenses.clear();
        pendingFoldingRanges.values().forEach(f -> f.cancel(true));
        pendingFoldingRanges.clear();
        pendingInlayHints.values().forEach(f -> f.cancel(true));
        pendingInlayHints.clear();
        pendingCodeActions.values().forEach(f -> f.cancel(true));
        pendingCodeActions.clear();
        diagnosticsProvider.shutdown();
    }
}
