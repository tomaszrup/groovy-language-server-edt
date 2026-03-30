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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.Set;

import org.eclipse.groovy.ls.core.providers.CallHierarchyProvider;
import org.eclipse.groovy.ls.core.providers.CodeActionProvider;
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
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

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
        setField(server, "firstFullBuildComplete", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        GroovyCompilerService compilerService = mock(GroovyCompilerService.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getOpenDocumentUris()).thenReturn(Set.of("file:///a.groovy", "file:///b.groovy"));
        when(documentManager.getClientUri("file:///a.groovy")).thenReturn("file:///client-a.groovy");
        when(documentManager.getClientUri("file:///b.groovy")).thenReturn("file:///client-b.groovy");
        when(documentManager.hasJdtWorkingCopy("file:///a.groovy")).thenReturn(true);
        when(documentManager.getCompilerService()).thenReturn(compilerService);

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");
        service.publishDiagnosticsForOpenDocuments();

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-a.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-b.groovy");
        verify(compilerService).invalidateDocumentFamily("file:///a.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesSyntaxOnlyPathDuringStartup() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsDebounced("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesFullDiagnosticsAfterRootedStartupSettles() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "firstFullBuildComplete", true);
        setField(server, "initialBuildSettled", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledStaysSyntaxOnlyAfterFirstBuildUntilRootedStartupSettles()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "firstFullBuildComplete", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsDebounced("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesFullDiagnosticsForRootedResolvedProjectBeforeStartupSettles()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getWorkspaceRoot() {
                return "file:///workspace";
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return "projA";
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "projA".equals(projectName);
            }

            @Override
            boolean isInitialBuildStarted() {
                return true;
            }

            @Override
            public boolean isBuildInProgress() {
                return false;
            }
        };
        setField(server, "firstFullBuildComplete", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesFullDiagnosticsForNoRootProjectOnceClasspathArrives() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return "extProj";
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "extProj".equals(projectName);
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledInvalidatesStandaloneCacheForResolvedNoRootProjectWorkingCopy()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return "extProj";
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "extProj".equals(projectName);
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        GroovyCompilerService compilerService = mock(GroovyCompilerService.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.hasJdtWorkingCopy("file:///doc.groovy")).thenReturn(true);
        when(documentManager.getCompilerService()).thenReturn(compilerService);

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(compilerService).invalidateDocumentFamily("file:///doc.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesFullDiagnosticsAfterRootedInitialBuildFailureWhenClasspathReady()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return "projA";
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "projA".equals(projectName);
            }
        };
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "initialBuildStarted", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledKeepsSyntaxOnlyForUnresolvedNoRootDocumentWithoutClasspath() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return null;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsDebounced("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledUsesFullDiagnosticsForUnresolvedNoRootDocumentWorkingCopyProject()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return null;
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "extProj".equals(projectName);
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        GroovyCompilerService compilerService = mock(GroovyCompilerService.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getCompilerService()).thenReturn(compilerService);
        when(documentManager.hasJdtWorkingCopy("file:///doc.groovy")).thenReturn(true);
        when(documentManager.getWorkingCopy("file:///doc.groovy")).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(javaProject.getElementName()).thenReturn("extProj");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(compilerService).invalidateDocumentFamily("file:///doc.groovy");
        verify(diagnostics).publishDiagnosticsDebounced("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
    }

    @Test
    void publishDiagnosticsIfEnabledKeepsSyntaxOnlyForUnresolvedNoRootWorkingCopyProjectWithoutClasspath()
            throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public boolean areDiagnosticsEnabled() {
                return true;
            }

            @Override
            public String getProjectNameForUri(String uri) {
                return null;
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return false;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getWorkingCopy("file:///doc.groovy")).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(javaProject.getElementName()).thenReturn("extProjB");

        service.publishDiagnosticsIfEnabled("file:///doc.groovy");

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///client-doc.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsDebounced("file:///client-doc.groovy");
    }

    @Test
    void rootedClasspathCheckerDoesNotUseWorkingCopyFallbackDuringStartup() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "workspaceRoot", "file:///workspace");
        @SuppressWarnings("unchecked")
        Set<String> projectsWithClasspath =
                (Set<String>) getField(server, "projectsWithClasspath");
        projectsWithClasspath.add("extProj");

        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DocumentManager documentManager = mock(DocumentManager.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getWorkingCopy("file:///doc.groovy")).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(javaProject.getElementName()).thenReturn("extProj");

        DiagnosticsProvider diagnosticsProvider =
                (DiagnosticsProvider) getField(service, "diagnosticsProvider");
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>> checkerRef =
                (java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>>)
                        getField(diagnosticsProvider, "classpathAvailableForUri");

        assertFalse(checkerRef.get().test("file:///doc.groovy"));
    }

    @Test
    void rootedClasspathCheckerUsesWorkingCopyFallbackAfterInitialBuildStarts() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "initialBuildStarted", true);
        @SuppressWarnings("unchecked")
        Set<String> projectsWithClasspath =
                (Set<String>) getField(server, "projectsWithClasspath");
        projectsWithClasspath.add("extProj");

        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DocumentManager documentManager = mock(DocumentManager.class);
        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        setField(service, "documentManager", documentManager);

        when(documentManager.getClientUri("file:///doc.groovy")).thenReturn("file:///client-doc.groovy");
        when(documentManager.getWorkingCopy("file:///doc.groovy")).thenReturn(workingCopy);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(javaProject.getElementName()).thenReturn("extProj");

        DiagnosticsProvider diagnosticsProvider =
                (DiagnosticsProvider) getField(service, "diagnosticsProvider");
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>> checkerRef =
                (java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>>)
                        getField(diagnosticsProvider, "classpathAvailableForUri");

        assertTrue(checkerRef.get().test("file:///doc.groovy"));
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

        // Verify the settings were delegated to the InlayHintProvider
        java.lang.reflect.Field providerField = GroovyTextDocumentService.class.getDeclaredField("inlayHintProvider");
        providerField.setAccessible(true);
        org.eclipse.groovy.ls.core.providers.InlayHintProvider provider =
                (org.eclipse.groovy.ls.core.providers.InlayHintProvider) providerField.get(service);
        java.lang.reflect.Field settingsField = org.eclipse.groovy.ls.core.providers.InlayHintProvider.class.getDeclaredField("currentSettings");
        settingsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<org.eclipse.groovy.ls.core.providers.InlayHintSettings> ref =
                (java.util.concurrent.atomic.AtomicReference<org.eclipse.groovy.ls.core.providers.InlayHintSettings>) settingsField.get(provider);
        org.eclipse.groovy.ls.core.providers.InlayHintSettings stored = ref.get();
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
    void didOpenDelegatesToDocumentManagerAndPublishesDiagnosticsImmediately() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "firstFullBuildComplete", true);
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public boolean isReadyForDiagnostics(String uri) {
                return true;
            }
        };
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
        verify(diagnostics).publishDiagnosticsImmediate("file:///test/Open.groovy");
    }

    @Test
    void didOpenPublishesSyntaxDiagnosticsDuringStartupEvenWhenWorkingCopyIsReady() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "workspaceRoot", "file:///workspace");
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public boolean isReadyForDiagnostics(String uri) {
                return true;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri("file:///test/StartupReady.groovy");
        item.setText("class StartupReady {}");
        params.setTextDocument(item);

        service.didOpen(params);

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///test/StartupReady.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsImmediate("file:///test/StartupReady.groovy");
    }

    @Test
    void didOpenPublishesFullDiagnosticsAfterRootedStartupSettlesWhenWorkingCopyIsReady() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "firstFullBuildComplete", true);
        setField(server, "initialBuildSettled", true);
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public boolean isReadyForDiagnostics(String uri) {
                return true;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri("file:///test/SettledReady.groovy");
        item.setText("class SettledReady {}");
        params.setTextDocument(item);

        service.didOpen(params);

        verify(diagnostics).publishDiagnosticsImmediate("file:///test/SettledReady.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///test/SettledReady.groovy");
    }

    @Test
    void didOpenPublishesFullDiagnosticsForNoRootProjectOnceClasspathArrives() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public String getProjectNameForUri(String uri) {
                return "extProj";
            }

            @Override
            public boolean hasClasspathForProject(String projectName) {
                return "extProj".equals(projectName);
            }
        };
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public boolean isReadyForDiagnostics(String uri) {
                return true;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri("file:///test/NoRootReady.groovy");
        item.setText("class NoRootReady {}");
        params.setTextDocument(item);

        service.didOpen(params);

        verify(diagnostics).publishDiagnosticsImmediate("file:///test/NoRootReady.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///test/NoRootReady.groovy");
    }

    @Test
    void didOpenPublishesSyntaxDiagnosticsWhileWorkingCopyIsPending() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        DocumentManager documentManager = new DocumentManager() {
            @Override
            public boolean isReadyForDiagnostics(String uri) {
                return false;
            }
        };
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidOpenTextDocumentParams params = new org.eclipse.lsp4j.DidOpenTextDocumentParams();
        org.eclipse.lsp4j.TextDocumentItem item = new org.eclipse.lsp4j.TextDocumentItem();
        item.setUri("file:///test/OpenPending.groovy");
        item.setText("class OpenPending {}");
        params.setTextDocument(item);

        service.didOpen(params);

        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///test/OpenPending.groovy");
    }

    @Test
    void readyWorkingCopyCallbackPublishesDebouncedDiagnostics() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "firstFullBuildComplete", true);
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);

        String uri = "file:///test/ListenerReady.groovy";
        documentManager.didOpen(uri, "class ListenerReady {}\n");

        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IBuffer buffer = mock(org.eclipse.jdt.core.IBuffer.class);
        when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(documentManager).put(DocumentManager.normalizeUri(uri), workingCopy);

        documentManager.replayOpenDocuments(List.of(uri));

        verify(diagnostics, org.mockito.Mockito.timeout(2000))
                .publishDiagnosticsDebounced(uri);
    }

    @Test
    void readyWorkingCopyCallbackPublishesSyntaxDiagnosticsDuringStartup() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);

        String uri = "file:///test/ListenerStartup.groovy";
        documentManager.didOpen(uri, "class ListenerStartup {}\n");

        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IBuffer buffer = mock(org.eclipse.jdt.core.IBuffer.class);
        when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(documentManager).put(DocumentManager.normalizeUri(uri), workingCopy);

        documentManager.replayOpenDocuments(List.of(uri));

        verify(diagnostics, org.mockito.Mockito.timeout(2000))
                .publishSyntaxDiagnosticsImmediate(uri);
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsDebounced(uri);
    }

    @Test
    void readyWorkingCopyCallbackPublishesDebouncedDiagnosticsAfterRootedStartupSettles() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        setField(server, "firstFullBuildComplete", true);
        setField(server, "initialBuildSettled", true);
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);

        String uri = "file:///test/ListenerSettled.groovy";
        documentManager.didOpen(uri, "class ListenerSettled {}\n");

        org.eclipse.jdt.core.ICompilationUnit workingCopy = mock(org.eclipse.jdt.core.ICompilationUnit.class);
        org.eclipse.jdt.core.IBuffer buffer = mock(org.eclipse.jdt.core.IBuffer.class);
        when(workingCopy.getBuffer()).thenReturn(buffer);
        getWorkingCopies(documentManager).put(DocumentManager.normalizeUri(uri), workingCopy);

        documentManager.replayOpenDocuments(List.of(uri));

        verify(diagnostics, org.mockito.Mockito.timeout(2000))
                .publishDiagnosticsDebounced(uri);
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate(uri);
    }

    @Test
    void refreshOpenDocumentsSemanticStateInvalidatesCachesBeforeReplayingAllOpenDocuments() throws Exception {
        GroovyTextDocumentService service = createService();
        DocumentManager documentManager = mock(DocumentManager.class);
        CompletionProvider completionProvider = mock(CompletionProvider.class);
        InlayHintProvider inlayHintProvider = mock(InlayHintProvider.class);
        TypeHierarchyProvider typeHierarchyProvider = mock(TypeHierarchyProvider.class);
        Set<String> openUris = Set.of(
                "file:///test/A.groovy",
                "file:///test/B.groovy");

        try (MockedStatic<org.eclipse.groovy.ls.core.providers.TraitMemberResolver> traitResolver =
                org.mockito.Mockito.mockStatic(org.eclipse.groovy.ls.core.providers.TraitMemberResolver.class)) {
            setField(service, "documentManager", documentManager);
            setField(service, "completionProvider", completionProvider);
            setField(service, "inlayHintProvider", inlayHintProvider);
            setField(service, "typeHierarchyProvider", typeHierarchyProvider);

            when(documentManager.getOpenDocumentUris()).thenReturn(openUris);

            service.refreshOpenDocumentsSemanticState();

            verify(completionProvider).invalidateHierarchyCache();
            verify(completionProvider).invalidateTypeNameCache();
            verify(inlayHintProvider).invalidateCache();
            verify(typeHierarchyProvider).invalidateCache();
            traitResolver.verify(org.eclipse.groovy.ls.core.providers.TraitMemberResolver::invalidateCache);
            verify(documentManager).replayOpenDocuments(openUris);
        }
    }

    @Test
    void refreshOpenDocumentsSemanticStateFiltersByProjectAndInvalidatesCaches() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer() {
            @Override
            public String getProjectNameForUri(String uri) {
                if (uri.contains("A.groovy")) {
                    return "projA";
                }
                if (uri.contains("Unknown.groovy")) {
                    return null;
                }
                return "projB";
            }
        };
        DocumentManager documentManager = mock(DocumentManager.class);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);
        CompletionProvider completionProvider = mock(CompletionProvider.class);
        InlayHintProvider inlayHintProvider = mock(InlayHintProvider.class);
        TypeHierarchyProvider typeHierarchyProvider = mock(TypeHierarchyProvider.class);

        setField(service, "completionProvider", completionProvider);
        setField(service, "inlayHintProvider", inlayHintProvider);
        setField(service, "typeHierarchyProvider", typeHierarchyProvider);

        when(documentManager.getOpenDocumentUris()).thenReturn(Set.of(
                "file:///test/A.groovy",
                "file:///test/B.groovy",
                "file:///test/Unknown.groovy"));
        when(documentManager.getClientUri("file:///test/A.groovy")).thenReturn("file:///test/A.groovy");
        when(documentManager.getClientUri("file:///test/B.groovy")).thenReturn("file:///test/B.groovy");
        when(documentManager.getClientUri("file:///test/Unknown.groovy")).thenReturn("file:///test/Unknown.groovy");

        try (MockedStatic<org.eclipse.groovy.ls.core.providers.TraitMemberResolver> traitResolver =
                org.mockito.Mockito.mockStatic(org.eclipse.groovy.ls.core.providers.TraitMemberResolver.class)) {
            service.refreshOpenDocumentsSemanticState("projA");

            verify(completionProvider).invalidateHierarchyCache();
            verify(completionProvider).invalidateTypeNameCache();
            verify(inlayHintProvider).invalidateCache();
            verify(typeHierarchyProvider).invalidateCache();
            traitResolver.verify(org.eclipse.groovy.ls.core.providers.TraitMemberResolver::invalidateCache);
        }

        @SuppressWarnings("unchecked")
        Class<Iterable<String>> iterableClass = (Class<Iterable<String>>) (Class<?>) Iterable.class;
        ArgumentCaptor<Iterable<String>> captor = ArgumentCaptor.forClass(iterableClass);
        verify(documentManager).replayOpenDocuments(captor.capture());

        java.util.List<String> replayed = new java.util.ArrayList<>();
        captor.getValue().forEach(replayed::add);
        assertEquals(Set.of("file:///test/A.groovy", "file:///test/Unknown.groovy"), Set.copyOf(replayed));
    }

    @Test
    void didSaveInvalidatesSemanticCachesAndRefreshesCodeLenses() throws Exception {
        GroovyTextDocumentService service = createService();
        CompletionProvider completionProvider = mock(CompletionProvider.class);
        InlayHintProvider inlayHintProvider = mock(InlayHintProvider.class);
        TypeHierarchyProvider typeHierarchyProvider = mock(TypeHierarchyProvider.class);
        CodeLensProvider codeLensProvider = mock(CodeLensProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);

        setField(service, "completionProvider", completionProvider);
        setField(service, "inlayHintProvider", inlayHintProvider);
        setField(service, "typeHierarchyProvider", typeHierarchyProvider);
        setField(service, "codeLensProvider", codeLensProvider);
        setField(service, "documentManager", documentManager);

        org.eclipse.lsp4j.DidSaveTextDocumentParams params = new org.eclipse.lsp4j.DidSaveTextDocumentParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///test/Save.groovy"));

        try (MockedStatic<org.eclipse.groovy.ls.core.providers.TraitMemberResolver> traitResolver =
                org.mockito.Mockito.mockStatic(org.eclipse.groovy.ls.core.providers.TraitMemberResolver.class)) {
            service.didSave(params);

            verify(completionProvider).invalidateHierarchyCache();
            verify(completionProvider).invalidateTypeNameCache();
            verify(inlayHintProvider).invalidateCache();
            verify(typeHierarchyProvider).invalidateCache();
            verify(codeLensProvider).invalidateAllResolveCache();
            verify(documentManager).scheduleCodeLensRefresh();
            traitResolver.verify(org.eclipse.groovy.ls.core.providers.TraitMemberResolver::invalidateCache);
        }
    }

    @Test
    void didSavePublishesDiagnosticsForSavedUriOnly() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "firstFullBuildComplete", true);
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, new DocumentManager());

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        DocumentManager documentManager = mock(DocumentManager.class);
        CodeLensProvider codeLensProvider = mock(CodeLensProvider.class);

        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);
        setField(service, "codeLensProvider", codeLensProvider);

        when(documentManager.getClientUri("file:///test/Save.groovy")).thenReturn("file:///client-Save.groovy");
        when(documentManager.hasJdtWorkingCopy("file:///test/Save.groovy")).thenReturn(false);

        org.eclipse.lsp4j.DidSaveTextDocumentParams params = new org.eclipse.lsp4j.DidSaveTextDocumentParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///test/Save.groovy"));

        service.didSave(params);

        verify(diagnostics).publishDiagnosticsDebounced("file:///client-Save.groovy");
        verify(documentManager, org.mockito.Mockito.never()).getOpenDocumentUris();
        verify(codeLensProvider).invalidateAllResolveCache();
        verify(documentManager).scheduleCodeLensRefresh();
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
        setField(server, "firstFullBuildComplete", true);
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
        verify(diagnostics).publishDiagnosticsAfterChange("file:///test/Change.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishSyntaxDiagnosticsImmediate("file:///test/Change.groovy");
    }

    @Test
    void didChangePublishesSyntaxDiagnosticsDuringRootedStartup() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        setField(server, "diagnosticsEnabled", true);
        setField(server, "workspaceRoot", "file:///workspace");
        DocumentManager documentManager = new DocumentManager();
        GroovyTextDocumentService service = new GroovyTextDocumentService(server, documentManager);

        DiagnosticsProvider diagnostics = mock(DiagnosticsProvider.class);
        setField(service, "diagnosticsProvider", diagnostics);
        setField(service, "documentManager", documentManager);

        documentManager.didOpen("file:///test/StartupChange.groovy", "class StartupChange {}");

        org.eclipse.lsp4j.DidChangeTextDocumentParams changeParams = new org.eclipse.lsp4j.DidChangeTextDocumentParams();
        org.eclipse.lsp4j.VersionedTextDocumentIdentifier versionedId = new org.eclipse.lsp4j.VersionedTextDocumentIdentifier();
        versionedId.setUri("file:///test/StartupChange.groovy");
        changeParams.setTextDocument(versionedId);
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change = new org.eclipse.lsp4j.TextDocumentContentChangeEvent();
        change.setText("class StartupChanged {}");
        changeParams.setContentChanges(java.util.List.of(change));

        service.didChange(changeParams);

        assertEquals("class StartupChanged {}", documentManager.getContent("file:///test/StartupChange.groovy"));
        verify(diagnostics).publishSyntaxDiagnosticsImmediate("file:///test/StartupChange.groovy");
        org.mockito.Mockito.verify(diagnostics, org.mockito.Mockito.never())
                .publishDiagnosticsAfterChange("file:///test/StartupChange.groovy");
    }

    // ---- Additional delegation tests for untested methods ----

    @Test
    void documentHighlightDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        DocumentHighlightProvider provider = mock(DocumentHighlightProvider.class);
        setField(service, "documentHighlightProvider", provider);
        when(provider.getDocumentHighlights(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.documentHighlight(new DocumentHighlightParams()).join().isEmpty());
    }

    @Test
    void typeDefinitionDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        TypeDefinitionProvider provider = mock(TypeDefinitionProvider.class);
        setField(service, "typeDefinitionProvider", provider);
        when(provider.getTypeDefinition(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.typeDefinition(new TypeDefinitionParams()).join().getLeft().isEmpty());
    }

    @Test
    void implementationDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        ImplementationProvider provider = mock(ImplementationProvider.class);
        setField(service, "implementationProvider", provider);
        when(provider.getImplementations(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.implementation(new ImplementationParams()).join().getLeft().isEmpty());
    }

    @Test
    void foldingRangeDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        FoldingRangeProvider provider = mock(FoldingRangeProvider.class);
        setField(service, "foldingRangeProvider", provider);
        when(provider.getFoldingRanges(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.foldingRange(new FoldingRangeRequestParams()).join().isEmpty());
    }

    @Test
    void prepareRenameDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        RenameProvider provider = mock(RenameProvider.class);
        setField(service, "renameProvider", provider);
        when(provider.prepareRename(any())).thenThrow(new RuntimeException("boom"));
        assertNull(service.prepareRename(new PrepareRenameParams()).join());
    }

    @Test
    void onTypeFormattingDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        FormattingProvider provider = mock(FormattingProvider.class);
        setField(service, "formattingProvider", provider);
        when(provider.formatOnType(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.onTypeFormatting(new DocumentOnTypeFormattingParams()).join().isEmpty());
    }

    @Test
    void prepareTypeHierarchyDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        TypeHierarchyProvider provider = mock(TypeHierarchyProvider.class);
        setField(service, "typeHierarchyProvider", provider);
        when(provider.prepareTypeHierarchy(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.prepareTypeHierarchy(new TypeHierarchyPrepareParams()).join().isEmpty());
    }

    @Test
    void typeHierarchySupertypesDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        TypeHierarchyProvider provider = mock(TypeHierarchyProvider.class);
        setField(service, "typeHierarchyProvider", provider);
        when(provider.getSupertypes(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.typeHierarchySupertypes(new TypeHierarchySupertypesParams()).join().isEmpty());
    }

    @Test
    void typeHierarchySubtypesDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        TypeHierarchyProvider provider = mock(TypeHierarchyProvider.class);
        setField(service, "typeHierarchyProvider", provider);
        when(provider.getSubtypes(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.typeHierarchySubtypes(new TypeHierarchySubtypesParams()).join().isEmpty());
    }

    @Test
    void prepareCallHierarchyDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CallHierarchyProvider provider = mock(CallHierarchyProvider.class);
        setField(service, "callHierarchyProvider", provider);
        when(provider.prepareCallHierarchy(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.prepareCallHierarchy(new CallHierarchyPrepareParams()).join().isEmpty());
    }

    @Test
    void callHierarchyIncomingCallsDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CallHierarchyProvider provider = mock(CallHierarchyProvider.class);
        setField(service, "callHierarchyProvider", provider);
        when(provider.getIncomingCalls(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams()).join().isEmpty());
    }

    @Test
    void callHierarchyOutgoingCallsDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CallHierarchyProvider provider = mock(CallHierarchyProvider.class);
        setField(service, "callHierarchyProvider", provider);
        when(provider.getOutgoingCalls(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams()).join().isEmpty());
    }

    @Test
    void codeLensDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CodeLensProvider provider = mock(CodeLensProvider.class);
        setField(service, "codeLensProvider", provider);
        when(provider.getCodeLenses(any())).thenThrow(new RuntimeException("boom"));
        assertTrue(service.codeLens(new CodeLensParams()).join().isEmpty());
    }

    @Test
    void resolveCodeLensDelegatesToProviderOnFailure() throws Exception {
        GroovyTextDocumentService service = createService();
        CodeLensProvider provider = mock(CodeLensProvider.class);
        setField(service, "codeLensProvider", provider);
        CodeLens lens = new CodeLens();
        when(provider.resolveCodeLens(any())).thenThrow(new RuntimeException("boom"));
        CodeLens result = service.resolveCodeLens(lens).join();
        assertSame(lens, result);
        assertNotNull(result.getCommand());
        assertEquals("References unavailable", result.getCommand().getTitle());
    }

    @Test
    void shutdownPoolsAndClearsPendingTokens() {
        GroovyTextDocumentService service = createService();
        assertDoesNotThrow(service::shutdown);
    }

    // ================================================================
    // Happy-path delegation tests — success paths
    // ================================================================

    @Test
    void completionDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        CompletionProvider provider = mock(CompletionProvider.class);
        setField(service, "completionProvider", provider);

        CompletionItem ci = new CompletionItem("hello");
        when(provider.getCompletions(any())).thenReturn(List.of(ci));

        Either<List<CompletionItem>, CompletionList> result = service.completion(new CompletionParams()).join();
        assertTrue(result.isRight());
        assertEquals(1, result.getRight().getItems().size());
    }

    @Test
    void hoverDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        HoverProvider provider = mock(HoverProvider.class);
        setField(service, "hoverProvider", provider);

        org.eclipse.lsp4j.Hover expected = new org.eclipse.lsp4j.Hover();
        when(provider.getHover(any())).thenReturn(expected);

        org.eclipse.lsp4j.Hover result = service.hover(new HoverParams()).join();
        assertSame(expected, result);
    }

    @Test
    void signatureHelpDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        SignatureHelpProvider provider = mock(SignatureHelpProvider.class);
        setField(service, "signatureHelpProvider", provider);

        org.eclipse.lsp4j.SignatureHelp expected = new org.eclipse.lsp4j.SignatureHelp();
        when(provider.getSignatureHelp(any())).thenReturn(expected);

        org.eclipse.lsp4j.SignatureHelp result = service.signatureHelp(new SignatureHelpParams()).join();
        assertSame(expected, result);
    }

    @Test
    void formattingDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        FormattingProvider provider = mock(FormattingProvider.class);
        setField(service, "formattingProvider", provider);

        org.eclipse.lsp4j.TextEdit edit = new org.eclipse.lsp4j.TextEdit();
        when(provider.format(any())).thenReturn(List.of(edit));

        var result = service.formatting(new DocumentFormattingParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void rangeFormattingDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        FormattingProvider provider = mock(FormattingProvider.class);
        setField(service, "formattingProvider", provider);

        org.eclipse.lsp4j.TextEdit edit = new org.eclipse.lsp4j.TextEdit();
        when(provider.formatRange(any())).thenReturn(List.of(edit));

        var result = service.rangeFormatting(new DocumentRangeFormattingParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void semanticTokensFullDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        SemanticTokensProvider provider = mock(SemanticTokensProvider.class);
        setField(service, "semanticTokensProvider", provider);

        org.eclipse.lsp4j.SemanticTokens expected = new org.eclipse.lsp4j.SemanticTokens(List.of(1, 2, 3, 4, 5));
        when(provider.getSemanticTokensFull(any())).thenReturn(expected);

        SemanticTokensParams params = new SemanticTokensParams(new TextDocumentIdentifier("file:///test.groovy"));
        org.eclipse.lsp4j.SemanticTokens result = service.semanticTokensFull(params).join();
        assertNotNull(result);
    }

    @Test
    void semanticTokensFullUsesBestEffortProviderDuringBuild() throws Exception {
        GroovyTextDocumentService service = createService();
        GroovyLanguageServer server = (GroovyLanguageServer) getField(service, "server");
        SemanticTokensProvider provider = mock(SemanticTokensProvider.class);
        setField(service, "semanticTokensProvider", provider);
        setField(server, "buildInProgress", true);

        org.eclipse.lsp4j.SemanticTokens expected = new org.eclipse.lsp4j.SemanticTokens(List.of(1, 2, 3, 4, 5));
        when(provider.getSemanticTokensFullBestEffort(any())).thenReturn(expected);
        when(provider.getSemanticTokensFull(any())).thenThrow(new AssertionError("full provider should not be used during build"));

        SemanticTokensParams params = new SemanticTokensParams(new TextDocumentIdentifier("file:///test.groovy"));
        org.eclipse.lsp4j.SemanticTokens result = service.semanticTokensFull(params).join();

        assertSame(expected, result);
        verify(provider).getSemanticTokensFullBestEffort(any());
        verify(provider, org.mockito.Mockito.never()).getSemanticTokensFull(any());
    }

    @Test
    void semanticTokensRangeDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        SemanticTokensProvider provider = mock(SemanticTokensProvider.class);
        setField(service, "semanticTokensProvider", provider);

        org.eclipse.lsp4j.SemanticTokens expected = new org.eclipse.lsp4j.SemanticTokens(List.of(1, 2, 3, 4, 5));
        when(provider.getSemanticTokensRange(any())).thenReturn(expected);

        SemanticTokensRangeParams params = new SemanticTokensRangeParams(
                new TextDocumentIdentifier("file:///test.groovy"),
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(0, 0),
                        new org.eclipse.lsp4j.Position(10, 0)));
        org.eclipse.lsp4j.SemanticTokens result = service.semanticTokensRange(params).join();
        assertNotNull(result);
    }

    @Test
    void semanticTokensRangeUsesBestEffortProviderDuringBuild() throws Exception {
        GroovyTextDocumentService service = createService();
        GroovyLanguageServer server = (GroovyLanguageServer) getField(service, "server");
        SemanticTokensProvider provider = mock(SemanticTokensProvider.class);
        setField(service, "semanticTokensProvider", provider);
        setField(server, "buildInProgress", true);

        org.eclipse.lsp4j.SemanticTokens expected = new org.eclipse.lsp4j.SemanticTokens(List.of(1, 2, 3, 4, 5));
        when(provider.getSemanticTokensRangeBestEffort(any())).thenReturn(expected);
        when(provider.getSemanticTokensRange(any())).thenThrow(new AssertionError("range provider should not be used during build"));

        SemanticTokensRangeParams params = new SemanticTokensRangeParams(
                new TextDocumentIdentifier("file:///test.groovy"),
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(0, 0),
                        new org.eclipse.lsp4j.Position(10, 0)));
        org.eclipse.lsp4j.SemanticTokens result = service.semanticTokensRange(params).join();

        assertSame(expected, result);
        verify(provider).getSemanticTokensRangeBestEffort(any());
        verify(provider, org.mockito.Mockito.never()).getSemanticTokensRange(any());
    }

    @Test
    void inlayHintDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        InlayHintProvider provider = mock(InlayHintProvider.class);
        setField(service, "inlayHintProvider", provider);

        org.eclipse.lsp4j.InlayHint hint = new org.eclipse.lsp4j.InlayHint();
        when(provider.getInlayHints(any())).thenReturn(List.of(hint));

        var result = service.inlayHint(new InlayHintParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void codeActionDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        CodeActionProvider provider = mock(CodeActionProvider.class);
        setField(service, "codeActionProvider", provider);

        org.eclipse.lsp4j.CodeAction action = new org.eclipse.lsp4j.CodeAction("fix");
        when(provider.getCodeActions(any())).thenReturn(List.of(Either.forRight(action)));

        var result = service.codeAction(new CodeActionParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void codeLensDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        CodeLensProvider provider = mock(CodeLensProvider.class);
        setField(service, "codeLensProvider", provider);

        CodeLens lens = new CodeLens();
        when(provider.getCodeLenses(any())).thenReturn(List.of(lens));

        var result = service.codeLens(new CodeLensParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void resolveCompletionItemDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        CompletionProvider provider = mock(CompletionProvider.class);
        setField(service, "completionProvider", provider);

        CompletionItem item = new CompletionItem("hello");
        CompletionItem resolved = new CompletionItem("hello resolved");
        when(provider.resolveCompletionItem(item)).thenReturn(resolved);

        CompletionItem result = service.resolveCompletionItem(item).join();
        assertSame(resolved, result);
    }

    @Test
    void resolveCodeLensDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        CodeLensProvider provider = mock(CodeLensProvider.class);
        setField(service, "codeLensProvider", provider);

        CodeLens lens = new CodeLens();
        CodeLens resolved = new CodeLens(new org.eclipse.lsp4j.Range());
        when(provider.resolveCodeLens(lens)).thenReturn(resolved);

        CodeLens result = service.resolveCodeLens(lens).join();
        assertSame(resolved, result);
    }

    @Test
    void documentHighlightDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        DocumentHighlightProvider provider = mock(DocumentHighlightProvider.class);
        setField(service, "documentHighlightProvider", provider);

        org.eclipse.lsp4j.DocumentHighlight highlight = new org.eclipse.lsp4j.DocumentHighlight();
        when(provider.getDocumentHighlights(any())).thenReturn(List.of(highlight));

        var result = service.documentHighlight(new DocumentHighlightParams()).join();
        assertEquals(1, result.size());
    }

    @Test
    void foldingRangeDelegatesToProviderOnSuccess() throws Exception {
        GroovyTextDocumentService service = createService();
        FoldingRangeProvider provider = mock(FoldingRangeProvider.class);
        setField(service, "foldingRangeProvider", provider);

        org.eclipse.lsp4j.FoldingRange range = new org.eclipse.lsp4j.FoldingRange(0, 10);
        when(provider.getFoldingRanges(any())).thenReturn(List.of(range));

        var result = service.foldingRange(new FoldingRangeRequestParams()).join();
        assertEquals(1, result.size());
    }

    private GroovyTextDocumentService createService() {
        return new GroovyTextDocumentService(new GroovyLanguageServer(), new DocumentManager());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, org.eclipse.jdt.core.ICompilationUnit> getWorkingCopies(DocumentManager manager)
            throws Exception {
        Field field = DocumentManager.class.getDeclaredField("workingCopies");
        field.setAccessible(true);
        return (Map<String, org.eclipse.jdt.core.ICompilationUnit>) field.get(manager);
    }
}
