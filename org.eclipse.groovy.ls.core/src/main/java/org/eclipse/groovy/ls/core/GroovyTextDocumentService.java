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
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.providers.CodeActionProvider;
import org.eclipse.groovy.ls.core.providers.CompletionProvider;
import org.eclipse.groovy.ls.core.providers.DefinitionProvider;
import org.eclipse.groovy.ls.core.providers.DiagnosticsProvider;
import org.eclipse.groovy.ls.core.providers.DocumentSymbolProvider;
import org.eclipse.groovy.ls.core.providers.FormattingProvider;
import org.eclipse.groovy.ls.core.providers.HoverProvider;
import org.eclipse.groovy.ls.core.providers.ReferenceProvider;
import org.eclipse.groovy.ls.core.providers.RenameProvider;
import org.eclipse.groovy.ls.core.providers.SemanticTokensProvider;
import org.eclipse.groovy.ls.core.providers.SignatureHelpProvider;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
        this.codeActionProvider = new CodeActionProvider(documentManager, diagnosticsProvider);
    }

    void connect(LanguageClient client) {
        this.client = client;
        this.diagnosticsProvider.connect(client);
    }

    // ---- Document synchronization ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();

        GroovyLanguageServerPlugin.logInfo("Document opened: " + uri);
        documentManager.didOpen(uri, text);

        // Publish diagnostics for the newly opened document
        if (server.areDiagnosticsEnabled()) {
            diagnosticsProvider.publishDiagnostics(uri);
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documentManager.didChange(uri, params.getContentChanges());

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

        // Trigger a full build and re-publish diagnostics
        if (server.areDiagnosticsEnabled()) {
            diagnosticsProvider.publishDiagnostics(uri);
        }
    }

    // ---- Completion ----

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletionList completionList = new CompletionList();
                completionList.setItems(completionProvider.getCompletions(params));
                completionList.setIsIncomplete(true);
                return Either.forRight(completionList);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Completion failed", e);
                CompletionList empty = new CompletionList();
                empty.setItems(new ArrayList<>());
                empty.setIsIncomplete(true);
                return Either.forRight(empty);
            }
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
        });
    }

    // ---- Hover ----

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return hoverProvider.getHover(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Hover failed", e);
                return null;
            }
        });
    }

    // ---- Definition ----

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Either.forLeft(definitionProvider.getDefinition(params));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Definition failed", e);
                return Either.forLeft(new ArrayList<>());
            }
        });
    }

    // ---- References ----

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return referenceProvider.getReferences(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("References failed", e);
                return new ArrayList<>();
            }
        });
    }

    // ---- Document Symbols ----

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return documentSymbolProvider.getDocumentSymbols(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Document symbols failed", e);
                return new ArrayList<>();
            }
        });
    }

    // ---- Rename ----

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renameProvider.rename(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Rename failed", e);
                return null;
            }
        });
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
        });
    }

    // ---- Semantic Tokens ----

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(
            org.eclipse.lsp4j.SemanticTokensParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return semanticTokensProvider.getSemanticTokensFull(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (full) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        });
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(
            org.eclipse.lsp4j.SemanticTokensRangeParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return semanticTokensProvider.getSemanticTokensRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Semantic tokens (range) failed", e);
                return new org.eclipse.lsp4j.SemanticTokens(new ArrayList<>());
            }
        });
    }

    // ---- Code Actions ----

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return codeActionProvider.getCodeActions(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Code action failed", e);
                return new ArrayList<>();
            }
        });
    }

    // ---- Formatting ----

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(
            DocumentFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return formattingProvider.format(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Formatting failed", e);
                return new ArrayList<>();
            }
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            DocumentRangeFormattingParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return formattingProvider.formatRange(params);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Range formatting failed", e);
                return new ArrayList<>();
            }
        });
    }

    /**
     * Update the formatter profile path from configuration.
     */
    public void updateFormatterProfile(String profilePath) {
        formattingProvider.setFormatterProfilePath(profilePath);
    }

    // ---- Build trigger ----

    /**
     * Trigger a full build of the workspace and publish diagnostics for all open documents.
     * Sends status notifications (Compiling → Ready/Error) via the server.
     */
    void triggerFullBuild(GroovyLanguageServer languageServer) {
        CompletableFuture.runAsync(() -> {
            GroovyLanguageServerPlugin.logInfo("Triggering full workspace build...");
            try {
                ResourcesPlugin.getWorkspace().build(
                        org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD,
                        new org.eclipse.core.runtime.NullProgressMonitor());

                // Publish diagnostics for all open documents
                if (server.areDiagnosticsEnabled()) {
                    for (String uri : documentManager.getOpenDocumentUris()) {
                        diagnosticsProvider.publishDiagnostics(uri);
                    }
                }

                GroovyLanguageServerPlugin.logInfo("Full build completed.");
                languageServer.sendStatus("Ready", null);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Full build failed", e);
                languageServer.sendStatus("Error", "Build failed: " + e.getMessage());
            }
        });
    }

    /**
     * Trigger a full build of the workspace (without status notifications).
     */
    void triggerFullBuild() {
        triggerFullBuild(server);
    }
}
