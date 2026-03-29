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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
import org.eclipse.groovy.ls.core.providers.TraitMemberResolver;
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

    private static final String CODE_LENS_FALLBACK_COMMAND = "groovy.showOutputChannel";
    private static final String CODE_LENS_FALLBACK_TITLE = "References unavailable";

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
     *
     * <h3>Dual-pool architecture</h3>
     * <ul>
     *   <li><b>Fast pool</b> ({@link #lspRequestExecutor}) — user-interactive requests:
     *       hover, completion, definition, semanticTokens, codeAction, etc.</li>
     *   <li><b>Background pool</b> ({@link #lspBackgroundExecutor}) — decorative/gutter
     *       features: documentSymbol, codeLens, foldingRange, inlayHint.</li>
     * </ul>
     * This isolation prevents background decoration work from starving
     * latency-sensitive interactive requests.
     */
    private volatile ExecutorService lspRequestExecutor;
    private volatile ExecutorService lspBackgroundExecutor;
    static {
        // Initialised in the instance initialiser — see below.
    }
    {
        int cpus = Runtime.getRuntime().availableProcessors();
        int fastPoolSize = Math.max(4, cpus);
        int fastQueueCapacity = 64;
        int bgPoolSize = Math.max(3, cpus - 1);
        int bgQueueCapacity = 128;
        lspRequestExecutor = createLspExecutor(fastPoolSize, fastQueueCapacity, "groovy-ls-fast");
        lspBackgroundExecutor = createLspExecutor(bgPoolSize, bgQueueCapacity, "groovy-ls-background");
        GroovyLanguageServerPlugin.logInfo(
                "LSP fast pool: poolSize=" + fastPoolSize + ", queueCapacity=" + fastQueueCapacity
                + "; background pool: poolSize=" + bgPoolSize + ", queueCapacity=" + bgQueueCapacity);
    }

    private static ExecutorService createLspExecutor(int poolSize, int queueCapacity,
            String threadNamePrefix) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, threadNamePrefix);
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    // Reject instead of running on the LSP dispatch thread
                    // (CallerRunsPolicy would freeze ALL message processing).
                    String message = "LSP request executor saturated (" + threadNamePrefix
                            + ") - rejecting task";
                    GroovyLanguageServerPlugin.logError(message, null);
                    throw new RejectedExecutionException(message);
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static <T> CompletableFuture<T> submitLspRequest(
            String requestName,
            ExecutorService executor,
            Supplier<T> task,
            Supplier<T> fallbackSupplier) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            GroovyLanguageServerPlugin.logError(
                    requestName + " rejected because the LSP executor is saturated", e);
            return CompletableFuture.completedFuture(fallbackSupplier.get());
        }
    }

    /**
     * Reconfigure the LSP fast request thread pool.  Called from
     * {@link GroovyLanguageServer#initialize} when the client sends
     * custom {@code initializationOptions}.
     */
    void configureRequestPool(int poolSize, int queueCapacity) {
        ExecutorService old = this.lspRequestExecutor;
        this.lspRequestExecutor = createLspExecutor(poolSize, queueCapacity, "groovy-ls-fast");
        old.shutdownNow();
        GroovyLanguageServerPlugin.logInfo(
                "LSP fast pool reconfigured: poolSize=" + poolSize
                + ", queueCapacity=" + queueCapacity);
    }

    /**
     * Reconfigure the LSP background thread pool.  Called from
     * {@link GroovyLanguageServer#initialize} when the client sends
     * custom {@code initializationOptions}.
     */
    void configureBackgroundPool(int poolSize, int queueCapacity) {
        ExecutorService old = this.lspBackgroundExecutor;
        this.lspBackgroundExecutor = createLspExecutor(poolSize, queueCapacity, "groovy-ls-background");
        old.shutdownNow();
        GroovyLanguageServerPlugin.logInfo(
                "LSP background pool reconfigured: poolSize=" + poolSize
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

    /** Per-URI cancellation for completions (prevents 20 stacked requests during rapid typing). */
    private final Map<String, CompletableFuture<?>> pendingCompletions =
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
        this.documentManager.setWorkingCopyReadyListener(this::publishDiagnosticsIfEnabled);

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
            if (server.getWorkspaceRoot() == null && server.isFirstBuildComplete()) return true;
            // Once rooted startup fully settles, the initial delegated
            // classpath handoff is complete and open documents can use the
            // normal post-build diagnostics path even if the per-project
            // mapping is still catching up.
            if (server.isInitialBuildSettled()) return true;
            boolean allowWorkingCopyFallback = allowWorkingCopyClasspathFallback();
            if (server.getWorkspaceRoot() != null && !allowWorkingCopyFallback) {
                return false;
            }
            return hasResolvedClasspathForUri(uri, allowWorkingCopyFallback);
        });
        this.diagnosticsProvider.setInitializationCompleteSupplier(server::isInitialBuildSettled);
        this.diagnosticsProvider.setStandaloneFallbackReadyChecker(this::canPublishFullDiagnostics);
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
        TraitMemberResolver.invalidateCache();

        // During workspace bootstrap, stay in syntax-only mode until the initial
        // classpath batch and first build have completed. In no-root sessions,
        // there is no workspace build, so a file can upgrade as soon as its
        // imported project has received a concrete classpath update.
        if (canPublishFullDiagnostics(uri) && documentManager.isReadyForDiagnostics(uri)) {
            clearStandaloneFallbackCacheIfNeeded(uri);
            diagnosticsProvider.publishDiagnosticsImmediate(uri);
        } else {
            diagnosticsProvider.publishSyntaxDiagnosticsImmediate(uri);
        }

        // Lazily warm the JDT type search index for this file's project.
        // Runs on the background pool so it never blocks interactive requests.
        // The warmTypeIndex method is idempotent — already-warmed projects are skipped.
        lspBackgroundExecutor.execute(() -> {
            try {
                org.eclipse.jdt.core.ICompilationUnit wc = documentManager.getWorkingCopy(uri);
                if (wc != null) {
                    org.eclipse.jdt.core.IJavaProject project = wc.getJavaProject();
                    if (project != null && project.exists()) {
                        completionProvider.warmTypeIndex(project);
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logInfo(
                        "[didOpen] Type index warm skipped: " + e.getMessage());
            }
        });
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

        // Invalidate inlay hint hierarchy cache — types may have changed
        inlayHintProvider.invalidateCache(uri);
        TraitMemberResolver.invalidateCache();

        // Invalidate shared hierarchy cache used by type hierarchy and
        // generated accessor resolution.
        typeHierarchyProvider.invalidateCache();

        // Invalidate ALL code lens resolve cache entries — editing file A
        // may change reference counts displayed in file B's code lenses.
        codeLensProvider.invalidateAllResolveCache();

        // Ask the client to re-request code lenses (debounced 500ms)
        documentManager.scheduleCodeLensRefresh();

        // Re-publish diagnostics after changes
        if (server.areDiagnosticsEnabled()) {
            if (canPublishFullDiagnostics(uri)) {
                clearStandaloneFallbackCacheIfNeeded(uri);
                diagnosticsProvider.publishDiagnosticsAfterChange(uri);
            } else {
                diagnosticsProvider.publishSyntaxDiagnosticsImmediate(uri);
            }
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        GroovyLanguageServerPlugin.logInfo("Document closed: " + uri);
        documentManager.didClose(uri);
        TraitMemberResolver.invalidateCache();
        typeHierarchyProvider.invalidateCache();

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

        // Invalidate type hierarchy cache on save — structural changes
        // (new supertypes, removed interfaces) are picked up at this point.
        completionProvider.invalidateHierarchyCache();
        TraitMemberResolver.invalidateCache();
        typeHierarchyProvider.invalidateCache();

        // Invalidate type-name cache on save — new types or removed types
        // should be reflected in subsequent type-name completions.
        completionProvider.invalidateTypeNameCache();

        // Republish diagnostics for ALL open documents — not just the saved
        // file.  Changes in this file may affect unused-declaration fading in
        // other open files (e.g. removing a call makes the callee unused).
        publishDiagnosticsForOpenDocuments();

        // Refresh code lenses — structural changes committed on save may
        // affect reference counts across multiple files.
        codeLensProvider.invalidateAllResolveCache();
        documentManager.scheduleCodeLensRefresh();
    }

    // ---- Completion ----

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        // Cancel any previous in-flight completion for this URI.
        // Without this, rapid typing queues 20+ completions (each 1-3s)
        // that occupy threads long after their results are stale.
        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingCompletions.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<Either<List<CompletionItem>, CompletionList>> baseFuture =
                submitLspRequest("Completion", lspRequestExecutor, () -> {
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
        }, () -> {
            CompletionList empty = new CompletionList();
            empty.setItems(new ArrayList<>());
            empty.setIsIncomplete(true);
            return Either.<List<CompletionItem>, CompletionList>forRight(empty);
        });
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> future = baseFuture
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

        if (normalizedUri != null) {
            pendingCompletions.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingCompletions.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        return submitLspRequest("Completion resolve", lspRequestExecutor, () -> {
            try {
                return completionProvider.resolveCompletionItem(item);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Completion resolve failed", e);
                return item;
            }
        }, () -> item)
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

        CompletableFuture<Hover> baseFuture = submitLspRequest("Hover", lspRequestExecutor, () -> {
            try {
                return hoverProvider.getHover(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Hover failed", e);
                return null;
            }
        }, () -> null);
        CompletableFuture<Hover> future = baseFuture
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
            pendingHovers.put(normalizedUri, baseFuture);
            future.whenComplete((r, t) -> pendingHovers.remove(normalizedUri, baseFuture));
        }
        return future;
    }

    // ---- Definition ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> future =
                submitLspRequest("Definition", lspRequestExecutor, () -> {
            try {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        definitionProvider.getDefinition(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Definition failed", e);
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        new ArrayList<>());
            }
        }, () -> Either.forLeft(new ArrayList<>()));
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
        // References use SearchEngine which blocks on the workspace lock during
        // builds — return empty to avoid thread starvation.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        CompletableFuture<List<? extends Location>> future =
                submitLspRequest("References", lspRequestExecutor, () -> {
            try {
                return referenceProvider.getReferences(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("References failed", e);
                List<Location> empty = new ArrayList<>();
                return empty;
            }
        }, ArrayList::new);

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
                submitLspRequest("Document symbols", lspBackgroundExecutor, () -> {
            try {
                return documentSymbolProvider.getDocumentSymbols(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Document symbols failed", e);
                return new ArrayList<Either<SymbolInformation, DocumentSymbol>>();
            }
        }, ArrayList::new);
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
        // Document highlights use SearchEngine which blocks on the workspace lock
        // during builds — return empty to avoid thread starvation.
        if (server.isBuildInProgress()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        CompletableFuture<List<? extends DocumentHighlight>> future =
                submitLspRequest("Document highlight", lspRequestExecutor, () -> {
            try {
                return documentHighlightProvider.getDocumentHighlights(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Document highlight failed", e);
                return new ArrayList<DocumentHighlight>();
            }
        }, ArrayList::new);
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
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> future =
                submitLspRequest("Type definition", lspRequestExecutor, () -> {
            try {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        typeDefinitionProvider.getTypeDefinition(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type definition failed", e);
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        new ArrayList<>());
            }
        }, () -> Either.forLeft(new ArrayList<>()));
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Type definition timed out", null);
                    }
                    return Either.forLeft(new ArrayList<>());
                });
    }

    // ---- Implementation ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> future =
                submitLspRequest("Implementation", lspRequestExecutor, () -> {
            try {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        implementationProvider.getImplementations(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Implementation failed", e);
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                        new ArrayList<>());
            }
        }, () -> Either.forLeft(new ArrayList<>()));
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Implementation timed out", null);
                    }
                    return Either.forLeft(new ArrayList<>());
                });
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

        CompletableFuture<List<FoldingRange>> baseFuture = submitLspRequest(
                "Folding range", lspBackgroundExecutor, () -> {
            try {
                return foldingRangeProvider.getFoldingRanges(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Folding range failed", e);
                return new ArrayList<FoldingRange>();
            }
        }, ArrayList::new);
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
        return submitLspRequest("Prepare rename", lspRequestExecutor, () -> {
            try {
                return renameProvider.prepareRename(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare rename failed", e);
                return null;
            }
        }, () -> null)
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Prepare rename timed out", null);
                    }
                    return null;
                });
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return submitLspRequest("Rename", lspRequestExecutor, () -> {
            try {
                return renameProvider.rename(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Rename failed", e);
                return null;
            }
        }, () -> null)
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError("Rename timed out", null);
                    }
                    return null;
                });
    }

    // ---- Signature Help ----

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        return submitLspRequest("Signature help", lspRequestExecutor, () -> {
            try {
                return signatureHelpProvider.getSignatureHelp(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Signature help failed", e);
                return null;
            }
        }, () -> null)
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
        // During builds, avoid the JDT working-copy path so we do not block on
        // the workspace lock. If we already have cached AST/content for the
        // open document, return best-effort tokens immediately instead of
        // waiting for the full build to complete.
        if (server.isBuildInProgress()) {
            try {
            org.eclipse.lsp4j.SemanticTokens tokens =
                semanticTokensProvider.getSemanticTokensFullBestEffort(params);
            return CompletableFuture.completedFuture(tokens != null
                ? tokens
                : new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
            } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                "Semantic tokens (full, best-effort) failed", e);
            return CompletableFuture.completedFuture(
                new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
            }
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
                submitLspRequest("Semantic tokens (full)", lspRequestExecutor, () -> {
            try {
                return semanticTokensProvider.getSemanticTokensFull(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (full) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        }, () -> new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
        CompletableFuture<org.eclipse.lsp4j.SemanticTokens> timedFuture = future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Semantic tokens (full) timed out for " + uri, null);
                    }
                    return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
                });
        if (normalizedUri != null) {
            pendingSemanticTokens.put(normalizedUri, future);
            timedFuture.whenComplete((r, t) -> pendingSemanticTokens.remove(normalizedUri, future));
        }
        return timedFuture;
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(
            org.eclipse.lsp4j.SemanticTokensRangeParams params) {
        if (server.isBuildInProgress()) {
            try {
            org.eclipse.lsp4j.SemanticTokens tokens =
                semanticTokensProvider.getSemanticTokensRangeBestEffort(params);
            return CompletableFuture.completedFuture(tokens != null
                ? tokens
                : new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
            } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                "Semantic tokens (range, best-effort) failed", e);
            return CompletableFuture.completedFuture(
                new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
            }
        }

        String uri = params.getTextDocument() != null ? params.getTextDocument().getUri() : null;
        String normalizedUri = uri != null ? DocumentManager.normalizeUri(uri) : null;

        if (normalizedUri != null) {
            CompletableFuture<?> prev = pendingSemanticTokens.get(normalizedUri);
            if (prev != null) {
                prev.cancel(true);
            }
        }

        CompletableFuture<org.eclipse.lsp4j.SemanticTokens> future =
                submitLspRequest("Semantic tokens (range)", lspRequestExecutor, () -> {
            try {
                return semanticTokensProvider.getSemanticTokensRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (range) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        }, () -> new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>()));
        CompletableFuture<org.eclipse.lsp4j.SemanticTokens> timedFuture = future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Semantic tokens (range) timed out for " + uri, null);
                    }
                    return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
                });
        if (normalizedUri != null) {
            pendingSemanticTokens.put(normalizedUri, future);
            timedFuture.whenComplete((r, t) -> pendingSemanticTokens.remove(normalizedUri, future));
        }
        return timedFuture;
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

        CompletableFuture<List<InlayHint>> baseFuture = submitLspRequest(
                "Inlay hints", lspBackgroundExecutor, () -> {
            try {
                return inlayHintProvider.getInlayHints(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Inlay hints failed", e);
                return new ArrayList<InlayHint>();
            }
        }, ArrayList::new);
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
                submitLspRequest("Code action", lspRequestExecutor, () -> {
            try {
                return codeActionProvider.getCodeActions(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code action failed", e);
                return new ArrayList<Either<Command, CodeAction>>();
            }
        }, ArrayList::new);
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

    @Override
    public CompletableFuture<CodeAction> resolveCodeAction(CodeAction unresolved) {
        return submitLspRequest("Code action resolve", lspRequestExecutor, () -> {
            try {
                return codeActionProvider.resolveCodeAction(unresolved);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code action resolve failed", e);
                return unresolved;
            }
        }, () -> unresolved)
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    GroovyLanguageServerPlugin.logError("Code action resolve timed out", ex);
                    return unresolved;
                });
    }

    // ---- Formatting ----

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(
            DocumentFormattingParams params) {
        CompletableFuture<List<? extends TextEdit>> future =
                submitLspRequest("Formatting", lspRequestExecutor, () -> {
            try {
                return formattingProvider.format(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, ArrayList::new);
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
                submitLspRequest("Range formatting", lspRequestExecutor, () -> {
            try {
                return formattingProvider.formatRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Range formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, ArrayList::new);
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
                submitLspRequest("On-type formatting", lspRequestExecutor, () -> {
            try {
                return formattingProvider.formatOnType(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("On-type formatting failed", e);
                return new ArrayList<TextEdit>();
            }
        }, ArrayList::new);
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
        CompletableFuture<List<TypeHierarchyItem>> future = submitLspRequest(
                "Prepare type hierarchy", lspRequestExecutor, () -> {
            try {
                return typeHierarchyProvider.prepareTypeHierarchy(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare type hierarchy failed", e);
                return new ArrayList<TypeHierarchyItem>();
            }
        }, ArrayList::new);
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Prepare type hierarchy timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
            TypeHierarchySupertypesParams params) {
        CompletableFuture<List<TypeHierarchyItem>> future = submitLspRequest(
                "Type hierarchy supertypes", lspRequestExecutor, () -> {
            try {
                return typeHierarchyProvider.getSupertypes(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type hierarchy supertypes failed", e);
                return new ArrayList<TypeHierarchyItem>();
            }
        }, ArrayList::new);
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Type hierarchy supertypes timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
            TypeHierarchySubtypesParams params) {
        CompletableFuture<List<TypeHierarchyItem>> future = submitLspRequest(
                "Type hierarchy subtypes", lspRequestExecutor, () -> {
            try {
                return typeHierarchyProvider.getSubtypes(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Type hierarchy subtypes failed", e);
                return new ArrayList<>();
            }
        }, ArrayList::new);
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
        CompletableFuture<List<CallHierarchyItem>> future = submitLspRequest(
                "Prepare call hierarchy", lspRequestExecutor, () -> {
            try {
                return callHierarchyProvider.prepareCallHierarchy(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Prepare call hierarchy failed", e);
                return new ArrayList<CallHierarchyItem>();
            }
        }, ArrayList::new);
        return future
                .orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Prepare call hierarchy timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
            CallHierarchyIncomingCallsParams params) {
        CompletableFuture<List<CallHierarchyIncomingCall>> future = submitLspRequest(
                "Call hierarchy incoming calls", lspRequestExecutor, () -> {
            try {
                return callHierarchyProvider.getIncomingCalls(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Call hierarchy incoming calls failed", e);
                return new ArrayList<CallHierarchyIncomingCall>();
            }
        }, ArrayList::new);
        return future
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Call hierarchy incoming calls timed out", null);
                    }
                    return new ArrayList<>();
                });
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
            CallHierarchyOutgoingCallsParams params) {
        CompletableFuture<List<CallHierarchyOutgoingCall>> future = submitLspRequest(
                "Call hierarchy outgoing calls", lspRequestExecutor, () -> {
            try {
                return callHierarchyProvider.getOutgoingCalls(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Call hierarchy outgoing calls failed", e);
                return new ArrayList<CallHierarchyOutgoingCall>();
            }
        }, ArrayList::new);
        return future
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || (ex.getCause() instanceof TimeoutException)) {
                        GroovyLanguageServerPlugin.logError(
                                "Call hierarchy outgoing calls timed out", null);
                    }
                    return new ArrayList<>();
                });
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

        CompletableFuture<List<? extends CodeLens>> baseFuture = submitLspRequest(
                "Code lens", lspBackgroundExecutor, () -> {
            try {
                return codeLensProvider.getCodeLenses(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code lens failed", e);
                return new ArrayList<CodeLens>();
            }
        }, ArrayList::new);
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
        // resolveCodeLens uses SearchEngine which blocks on workspace lock
        // during builds — return with a fallback command to avoid "no commands".
        if (server.isBuildInProgress()) {
            ensureFallbackCommand(codeLens);
            return CompletableFuture.completedFuture(codeLens);
        }

        CompletableFuture<CodeLens> future = submitLspRequest(
                "Code lens resolve", lspBackgroundExecutor, () -> {
            try {
                return codeLensProvider.resolveCodeLens(codeLens);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code lens resolve failed", e);
                ensureFallbackCommand(codeLens);
                return codeLens;
            }
        }, () -> {
            ensureFallbackCommand(codeLens);
            return codeLens;
        });
        return future.orTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    GroovyLanguageServerPlugin.logError("Code lens resolve timed out", ex);
                    ensureFallbackCommand(codeLens);
                    return codeLens;
                });
    }

    private static void ensureFallbackCommand(CodeLens codeLens) {
        if (codeLens.getCommand() == null) {
            codeLens.setCommand(new Command(CODE_LENS_FALLBACK_TITLE, CODE_LENS_FALLBACK_COMMAND));
        }
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
            if (canPublishFullDiagnostics(uri)) {
                clearStandaloneFallbackCacheIfNeeded(uri);
                diagnosticsProvider.publishDiagnosticsDebounced(clientUri);
            } else {
                diagnosticsProvider.publishSyntaxDiagnosticsImmediate(clientUri);
            }
        }
    }

    private boolean canPublishFullDiagnostics(String uri) {
        if (server.getWorkspaceRoot() == null && server.isFirstBuildComplete()) {
            return true;
        }
        if (server.isInitialBuildSettled()) {
            return true;
        }
        boolean allowWorkingCopyFallback = allowWorkingCopyClasspathFallback();
        if (server.getWorkspaceRoot() != null && !allowWorkingCopyFallback) {
            return false;
        }

        return hasResolvedClasspathForUri(uri, allowWorkingCopyFallback);
    }

    private boolean allowWorkingCopyClasspathFallback() {
        if (server.getWorkspaceRoot() == null) {
            return true;
        }
        return server.isInitialBuildStarted() && !server.isBuildInProgress();
    }

    private boolean hasResolvedClasspathForUri(String uri, boolean allowWorkingCopyFallback) {
        String projectName = resolveProjectNameForDiagnostics(uri, allowWorkingCopyFallback);
        return projectName != null && server.hasClasspathForProject(projectName);
    }

    private String resolveProjectNameForDiagnostics(String uri, boolean allowWorkingCopyFallback) {
        String projectName = resolveProjectNameFromServer(uri);
        if (projectName != null || !allowWorkingCopyFallback) {
            return projectName;
        }

        // In no-root sessions the URI→project mapping can briefly lag behind
        // the imported working copy. Use this document's concrete JDT project
        // as a narrow fallback instead of treating any project as ready.
        org.eclipse.jdt.core.ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return null;
        }

        org.eclipse.jdt.core.IJavaProject javaProject = workingCopy.getJavaProject();
        if (javaProject == null) {
            return null;
        }

        String workingCopyProject = javaProject.getElementName();
        return workingCopyProject == null || workingCopyProject.isBlank()
                ? null
                : workingCopyProject;
    }

    private String resolveProjectNameFromServer(String uri) {
        String clientUri = documentManager.getClientUri(uri);
        String uriForLookup = clientUri != null ? clientUri : uri;
        return server.getProjectNameForUri(uriForLookup);
    }

    private void clearStandaloneFallbackCacheIfNeeded(String uri) {
        if (!documentManager.hasJdtWorkingCopy(uri)) {
            return;
        }
        if (!server.isFirstBuildComplete()) {
            boolean allowWorkingCopyFallback = allowWorkingCopyClasspathFallback();
            if (!hasResolvedClasspathForUri(uri, allowWorkingCopyFallback)) {
                return;
            }
        }
        // Once a usable JDT working copy is available, drop the startup
        // standalone parse so cached fallback errors cannot mask reconcile.
        documentManager.getCompilerService().invalidateDocumentFamily(uri);
    }

    /**
     * Invalidate all cached code lens reference counts and ask the client
     * to re-request code lenses.  Called from workspace-level handlers
     * (watched file changes, post-build) where any open file's code lens
     * may be stale.
     */
    void refreshCodeLenses() {
        codeLensProvider.invalidateAllResolveCache();
        documentManager.scheduleCodeLensRefresh();
    }

    void refreshOpenDocumentsSemanticState() {
        documentManager.replayOpenDocuments(documentManager.getOpenDocumentUris());
    }

    void refreshOpenDocumentsSemanticState(String projectName) {
        if (projectName == null) {
            return;
        }

        java.util.List<String> urisToReplay = new java.util.ArrayList<>();
        for (String uri : documentManager.getOpenDocumentUris()) {
            String ownerProject = server.getProjectNameForUri(documentManager.getClientUri(uri));
            if (projectName.equals(ownerProject) || ownerProject == null) {
                urisToReplay.add(uri);
            }
        }
        documentManager.replayOpenDocuments(urisToReplay);
    }

    void publishDiagnosticsForOpenDocuments() {
        if (server.areDiagnosticsEnabled()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] publishDiagnosticsForOpenDocuments count="
                    + documentManager.getOpenDocumentUris().size());
            for (String uri : documentManager.getOpenDocumentUris()) {
                publishDiagnosticsIfEnabled(uri);
            }
        }
    }

    /**
     * Shut down executor pools. Called from {@link GroovyLanguageServer#shutdown()}.
     */
    void shutdown() {
        lspRequestExecutor.shutdownNow();
        lspBackgroundExecutor.shutdownNow();
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
        pendingCompletions.values().forEach(f -> f.cancel(true));
        pendingCompletions.clear();
        codeActionProvider.invalidateCache();
        TraitMemberResolver.invalidateCache();
        typeHierarchyProvider.invalidateCache();
        diagnosticsProvider.shutdown();
    }
}
