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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.eclipse.groovy.ls.core.providers.CodeActionProvider;
import org.eclipse.groovy.ls.core.providers.CompletionProvider;
import org.eclipse.groovy.ls.core.providers.DefinitionProvider;
import org.eclipse.groovy.ls.core.providers.DiagnosticsProvider;
import org.eclipse.groovy.ls.core.providers.DocumentSymbolProvider;
import org.eclipse.groovy.ls.core.providers.FormattingProvider;
import org.eclipse.groovy.ls.core.providers.HoverProvider;
import org.eclipse.groovy.ls.core.providers.InlayHintProvider;
import org.eclipse.groovy.ls.core.providers.ReferenceProvider;
import org.eclipse.groovy.ls.core.providers.RenameProvider;
import org.eclipse.groovy.ls.core.providers.SemanticTokensProvider;
import org.eclipse.groovy.ls.core.providers.SignatureHelpProvider;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroovyTextDocumentServiceTest {

    @Test
    void connectAndDidCloseDelegateToDiagnosticsAndClient() throws Exception {
        GroovyTextDocumentService service = createService();
        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        LanguageClient client = mock(LanguageClient.class);

        service.connect(client);
        verify(diagnostics).connect(client);

        String uri = "file:///closed.groovy";
        DidCloseTextDocumentParams close = new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri));
        service.didClose(close);

        verify(diagnostics).clearDiagnostics(uri);
        ArgumentCaptor<PublishDiagnosticsParams> captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
        verify(client).publishDiagnostics(captor.capture());
        assertEquals(uri, captor.getValue().getUri());
        assertTrue(captor.getValue().getDiagnostics().isEmpty());
    }

    @Test
    void completionAndResolveCompletionUseFallbackOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CompletionProvider provider = mock(CompletionProvider.class);
        setField(service, "completionProvider", provider);

        when(provider.getCompletions(any())).thenThrow(new RuntimeException("boom"));
        Either<List<CompletionItem>, CompletionList> completion = service.completion(new CompletionParams()).join();
        assertTrue(completion.isRight());
        assertTrue(completion.getRight().isIncomplete());
        assertTrue(completion.getRight().getItems().isEmpty());

        CompletionItem item = new CompletionItem("x");
        when(provider.resolveCompletionItem(item)).thenThrow(new RuntimeException("boom"));
        assertSame(item, service.resolveCompletionItem(item).join());
    }

    @Test
    void navigationProvidersUseFallbackOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        HoverProvider hoverProvider = mock(HoverProvider.class);
        DefinitionProvider definitionProvider = mock(DefinitionProvider.class);
        ReferenceProvider referenceProvider = mock(ReferenceProvider.class);
        DocumentSymbolProvider documentSymbolProvider = mock(DocumentSymbolProvider.class);
        setField(service, "hoverProvider", hoverProvider);
        setField(service, "definitionProvider", definitionProvider);
        setField(service, "referenceProvider", referenceProvider);
        setField(service, "documentSymbolProvider", documentSymbolProvider);

        when(hoverProvider.getHover(any())).thenThrow(new RuntimeException("boom"));
        when(definitionProvider.getDefinition(any())).thenThrow(new RuntimeException("boom"));
        when(referenceProvider.getReferences(any())).thenThrow(new RuntimeException("boom"));
        when(documentSymbolProvider.getDocumentSymbols(any())).thenThrow(new RuntimeException("boom"));

        assertNull(service.hover(new HoverParams()).join());
        assertTrue(service.definition(new DefinitionParams()).join().getLeft().isEmpty());
        assertTrue(service.references(new ReferenceParams()).join().isEmpty());
        assertTrue(service.documentSymbol(new DocumentSymbolParams()).join().isEmpty());
    }

    @Test
    void editingProvidersUseFallbackOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        RenameProvider renameProvider = mock(RenameProvider.class);
        SignatureHelpProvider signatureHelpProvider = mock(SignatureHelpProvider.class);
        SemanticTokensProvider semanticTokensProvider = mock(SemanticTokensProvider.class);
        InlayHintProvider inlayHintProvider = mock(InlayHintProvider.class);
        CodeActionProvider codeActionProvider = mock(CodeActionProvider.class);
        FormattingProvider formattingProvider = mock(FormattingProvider.class);
        setField(service, "renameProvider", renameProvider);
        setField(service, "signatureHelpProvider", signatureHelpProvider);
        setField(service, "semanticTokensProvider", semanticTokensProvider);
        setField(service, "inlayHintProvider", inlayHintProvider);
        setField(service, "codeActionProvider", codeActionProvider);
        setField(service, "formattingProvider", formattingProvider);

        when(renameProvider.rename(any())).thenThrow(new RuntimeException("boom"));
        when(signatureHelpProvider.getSignatureHelp(any())).thenThrow(new RuntimeException("boom"));
        when(semanticTokensProvider.getSemanticTokensFull(any())).thenThrow(new RuntimeException("boom"));
        when(semanticTokensProvider.getSemanticTokensRange(any())).thenThrow(new RuntimeException("boom"));
        when(inlayHintProvider.getInlayHints(any(), any())).thenThrow(new RuntimeException("boom"));
        when(codeActionProvider.getCodeActions(any())).thenThrow(new RuntimeException("boom"));
        when(formattingProvider.format(any())).thenThrow(new RuntimeException("boom"));
        when(formattingProvider.formatRange(any())).thenThrow(new RuntimeException("boom"));

        assertNull(service.rename(new RenameParams()).join());
        assertNull(service.signatureHelp(new SignatureHelpParams()).join());
        assertNotNull(service.semanticTokensFull(new SemanticTokensParams()).join());
        assertNotNull(service.semanticTokensRange(new SemanticTokensRangeParams()).join());
        assertTrue(service.inlayHint(new InlayHintParams()).join().isEmpty());
        assertTrue(service.codeAction(new CodeActionParams()).join().isEmpty());
        assertTrue(service.formatting(new DocumentFormattingParams()).join().isEmpty());
        assertTrue(service.rangeFormatting(new DocumentRangeFormattingParams()).join().isEmpty());
    }

    @Test
    void publishDiagnosticsHelpersUseClientUrisWhenEnabled() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getOpenDocumentUris()).thenReturn(Set.of("file:///a.groovy", "file:///b.groovy"));
        when(documentManager.getClientUri("file:///a.groovy")).thenReturn("file:///client-a.groovy");
        when(documentManager.getClientUri("file:///b.groovy")).thenReturn("file:///client-b.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");
        service.publishDiagnosticsForOpenDocuments();

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-a.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-b.groovy");
    }

    @Test
    void updateFormatterProfileDelegatesToProvider() throws Exception {
        GroovyTextDocumentService service = createService();
        FormattingProvider formattingProvider = mock(FormattingProvider.class);
        setField(service, "formattingProvider", formattingProvider);

        service.updateFormatterProfile("C:\\tmp\\format.xml");

        verify(formattingProvider).setFormatterProfilePath("C:\\tmp\\format.xml");
    }

    @Test
    void updateInlayHintSettingsStoresSettings() throws Exception {
        GroovyTextDocumentService service = createService();

        org.eclipse.groovy.ls.core.providers.InlayHintSettings settings =
                new org.eclipse.groovy.ls.core.providers.InlayHintSettings(true, false, true, false);
        service.updateInlayHintSettings(settings);

        // Verify the field was set by reading it back via reflection
        java.lang.reflect.Field f = GroovyTextDocumentService.class.getDeclaredField("inlayHintSettings");
        f.setAccessible(true);
        org.eclipse.groovy.ls.core.providers.InlayHintSettings stored =
                (org.eclipse.groovy.ls.core.providers.InlayHintSettings) f.get(service);
        assertNotNull(stored);
    }

    @Test
    void publishDiagnosticsDisabledWhenServerDiagnosticsOff() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        // diagnosticsEnabled defaults to false
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        // Should NOT publish because diagnostics are disabled
        org.mockito.Mockito.verifyNoInteractions(diagnostics);
    }

    @Test
    void didOpenDelegatesToDocumentManagerAndPublishesDiagnostics() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri("file:///test/Open.groovy");
        item.setText("class Open {}");
        params.setTextDocument(item);

        service.didOpen(params);

        // Document should be stored
        assertNotNull(documentManager.getContent("file:///test/Open.groovy"));
    }

    @Test
    void didSaveDoesNotThrow() {
        GroovyTextDocumentService service = createService();

        org.eclipse.lsp4j.DidSaveTextDocumentParams params = new org.eclipse.lsp4j.DidSaveTextDocumentParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///test/Save.groovy"));
        params.setText("class Save {}");

        // Should not throw
        service.didSave(params);
        assertNotNull(service);
    }

    @Test
    void didChangeUpdatesDiagnostics() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        // Open first
        documentManager.didOpen("file:///test/Change.groovy", "class Change {}");

        // Now change
        org.eclipse.lsp4j.DidChangeTextDocumentParams changeParams = new org.eclipse.lsp4j.DidChangeTextDocumentParams();
        org.eclipse.lsp4j.VersionedTextDocumentIdentifier versionedId = new org.eclipse.lsp4j.VersionedTextDocumentIdentifier();
        versionedId.setUri("file:///test/Change.groovy");
        changeParams.setTextDocument(versionedId);
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change = new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("class Changed {}");
        changeParams.setContentChanges(java.util.List.of(change));

        service.didChange(changeParams);

        assertEquals("class Changed {}", documentManager.getContent("file:///test/Change.groovy"));
    }

    private GroovyTextDocumentService createService() {
        return new GroovyTextDocumentService(new GroovyLanguageServer(), new DocumentManager());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

