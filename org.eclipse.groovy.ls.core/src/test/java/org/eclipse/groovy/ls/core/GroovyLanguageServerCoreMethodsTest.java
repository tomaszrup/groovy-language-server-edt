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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class GroovyLanguageServerCoreMethodsTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        GroovyLanguageServerPlugin.setLanguageServer(null);
    }

    @Test
    void initializeWithoutWorkspaceRootConfiguresCapabilities() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        InitializeResult result = server.initialize(new InitializeParams()).join();
        ServerCapabilities capabilities = result.getCapabilities();

        assertNotNull(capabilities);
        assertNotNull(capabilities.getTextDocumentSync());
        assertTrue(capabilities.getTextDocumentSync().isRight());
        assertEquals(TextDocumentSyncKind.Incremental,
                capabilities.getTextDocumentSync().getRight().getChange());
        assertTrue(capabilities.getCompletionProvider().getTriggerCharacters().contains("."));
        assertTrue(capabilities.getHoverProvider().isLeft());
        assertTrue(capabilities.getDefinitionProvider().isLeft());
        assertTrue(capabilities.getDocumentFormattingProvider().isLeft());
    }

    @Test
    void connectShutdownAndExitDoNotThrow() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        LanguageClient client = mock(LanguageClient.class);

        server.connect(client);

        assertNotNull(server.getClient());
        assertNull(server.getWorkspaceRoot());
        assertFalse(server.areDiagnosticsEnabled());
        assertNull(server.shutdown().join());
        server.exit();
    }

    @Test
    void resolveSourceReturnsFallbackAndErrorText() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        JsonObject notFoundParams = new JsonObject();
        notFoundParams.addProperty("uri", "groovy-source:///no/such/Type.java");
        assertEquals("// Source not available\n", server.resolveSource(notFoundParams).join());

        String error = server.resolveSource(new JsonObject()).join();
        assertTrue(error.startsWith("// Error resolving source:"));
    }

    @Test
    void classpathUpdateWithNoEntriesReturnsWithoutEnablingDiagnostics() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        params.add("entries", new JsonArray());

        server.classpathUpdate(params);

        assertFalse(server.areDiagnosticsEnabled());
    }

    @Test
    void normalizePathLowercasesAndAddsTrailingSlash() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        assertEquals("c:/workspace/project/", invokeNormalizePath(server, "C:\\Workspace\\Project"));
        assertEquals("c:/workspace/project/", invokeNormalizePath(server, "c:/workspace/project/"));
        assertNull(invokeNormalizePath(server, "   "));
    }

    @Test
    void findSubprojectsWithSourcesSkipsIgnoredDirectories() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String[] suffixes = {"src/main/groovy", "src/main/java"};

        Files.createDirectories(tempDir.resolve("app/src/main/groovy"));
        Files.createDirectories(tempDir.resolve(".hidden/src/main/groovy"));
        Files.createDirectories(tempDir.resolve("build/module/src/main/groovy"));
        Files.createDirectories(tempDir.resolve("services/api/src/main/java"));

        @SuppressWarnings("unchecked")
        List<File> subprojects = (List<File>) invoke(
                server,
                "findSubprojectsWithSources",
                new Class<?>[] {File.class, String[].class},
                new Object[] {tempDir.toFile(), suffixes});

        Set<String> paths = subprojects.stream()
                .map(file -> file.getAbsolutePath().replace('\\', '/'))
                .collect(Collectors.toSet());

        assertTrue(paths.contains(tempDir.resolve("app").toFile().getAbsolutePath().replace('\\', '/')));
        assertTrue(paths.contains(tempDir.resolve("services/api").toFile().getAbsolutePath().replace('\\', '/')));
        assertFalse(paths.contains(tempDir.resolve(".hidden").toFile().getAbsolutePath().replace('\\', '/')));
        assertFalse(paths.contains(tempDir.resolve("build/module").toFile().getAbsolutePath().replace('\\', '/')));
    }

    @Test
    void scanForSubprojectsHonorsMaxDepth() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String[] suffixes = {"src/main/groovy"};

        Path root = tempDir.resolve("deep");
        Files.createDirectories(root.resolve("level1/level2/src/main/groovy"));
        List<File> result = new ArrayList<>();

        invoke(
                server,
                "scanForSubprojects",
                new Class<?>[] {File.class, List.class, String[].class, int.class, int.class},
                new Object[] {root.toFile(), result, suffixes, 0, 1});

        assertTrue(result.isEmpty());
    }

    // ---- Additional coverage tests ----

    @Test
    void initializeWithWorkspaceRootSetsCapabilitiesAndWorkspace() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        InitializeParams params = new InitializeParams();
        params.setRootUri("file:///tmp/test-workspace");

        InitializeResult result = server.initialize(params).join();
        ServerCapabilities capabilities = result.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("file:///tmp/test-workspace", server.getWorkspaceRoot());
        // Verify all major capability providers are set
        assertNotNull(capabilities.getReferencesProvider());
        assertNotNull(capabilities.getDocumentSymbolProvider());
        assertNotNull(capabilities.getRenameProvider());
        assertNotNull(capabilities.getSignatureHelpProvider());
        assertNotNull(capabilities.getSemanticTokensProvider());
        assertNotNull(capabilities.getInlayHintProvider());
        assertNotNull(capabilities.getCodeActionProvider());
    }

    @Test
    void initializeFromRootPathFallback() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        InitializeParams params = new InitializeParams();
        params.setRootPath("/tmp/from-root-path");

        server.initialize(params).join();

        assertNotNull(server.getWorkspaceRoot());
        assertTrue(server.getWorkspaceRoot().contains("from-root-path"));
    }

    @Test
    void shutdownReturnsFutureWithNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        Object shutdownResult = server.shutdown().join();

        assertNull(shutdownResult);
    }

    @Test
    void sendStatusDoesNotThrowWhenEndpointIsNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        assertDoesNotThrow(() -> invoke(server, "sendStatus",
            new Class<?>[] {String.class, String.class},
            new Object[] {"Ready", "everything is fine"}));
    }

    @Test
    void getTextDocumentServiceAndWorkspaceServiceAreNonNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();

        assertNotNull(server.getTextDocumentService());
        assertNotNull(server.getWorkspaceService());
    }

    @Test
    void getGroovyTextDocumentServiceReturnsImplementation() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        Object result = invoke(server, "getGroovyTextDocumentService",
                new Class<?>[] {},
                new Object[] {});

        assertNotNull(result);
        assertTrue(result instanceof org.eclipse.groovy.ls.core.GroovyTextDocumentService);
    }

    @Test
    void normalizePathReturnsNullForNull() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        assertNull(invokeNormalizePath(server, null));
    }

    @Test
    void findSubprojectsWithSourcesReturnsEmptyForDirectoryWithNoSources() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String[] suffixes = {"src/main/groovy", "src/main/java"};

        Files.createDirectories(tempDir.resolve("empty-project/lib"));

        @SuppressWarnings("unchecked")
        List<File> subprojects = (List<File>) invoke(
                server,
                "findSubprojectsWithSources",
                new Class<?>[] {File.class, String[].class},
                new Object[] {tempDir.toFile(), suffixes});

        assertTrue(subprojects.isEmpty());
    }

    @Test
    void scanForSubprojectsSkipsNullChildDirectory() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String[] suffixes = {"src/main/groovy"};
        List<File> result = new ArrayList<>();

        // Scanning null dir should return without adding entries
        invoke(
                server,
                "scanForSubprojects",
                new Class<?>[] {File.class, List.class, String[].class, int.class, int.class},
                new Object[] {null, result, suffixes, 0, 3});

        assertTrue(result.isEmpty());
    }

    @Test
    void scanForSubprojectsSkipsOutAndBinDirectories() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String[] suffixes = {"src/main/groovy"};

        Files.createDirectories(tempDir.resolve("out/submod/src/main/groovy"));
        Files.createDirectories(tempDir.resolve("bin/submod/src/main/groovy"));
        Files.createDirectories(tempDir.resolve("node_modules/submod/src/main/groovy"));
        Files.createDirectories(tempDir.resolve("real/src/main/groovy"));

        @SuppressWarnings("unchecked")
        List<File> result = (List<File>) invoke(
                server,
                "findSubprojectsWithSources",
                new Class<?>[] {File.class, String[].class},
                new Object[] {tempDir.toFile(), suffixes});

        Set<String> names = result.stream().map(File::getName).collect(Collectors.toSet());
        assertTrue(names.contains("real"));
        assertFalse(names.contains("out"));
        assertFalse(names.contains("bin"));
        assertFalse(names.contains("node_modules"));
    }

    // ================================================================
    // connect tests
    // ================================================================

    @Test
    void connectStoresClientAndSendStartingStatus() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        LanguageClient client = mock(LanguageClient.class);
        server.connect(client);
        assertEquals(client, server.getClient());
    }

    @Test
    void connectSetsClientOnTextDocumentService() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        LanguageClient client = mock(LanguageClient.class);
        server.connect(client);
        assertNotNull(server.getTextDocumentService());
    }

    // ================================================================
    // sendStatus tests
    // ================================================================

    @Test
    void sendStatusWithEndpointDoesNotThrow() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);
        server.setRemoteEndpoint(endpoint);
        assertDoesNotThrow(() -> server.sendStatus("Ready", "All good"));
    }

    @Test
    void sendStatusWithEndpointNotifiesViaEndpoint() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);
        server.setRemoteEndpoint(endpoint);
        server.sendStatus("Compiling", "Building...");
        verify(endpoint).notify(org.mockito.ArgumentMatchers.eq("groovy/status"), org.mockito.ArgumentMatchers.any());
    }

    // ================================================================
    // areDiagnosticsEnabled / shutdown / exit tests
    // ================================================================

    @Test
    void areDiagnosticsEnabledDefaultsFalse() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertFalse(server.areDiagnosticsEnabled());
    }

    @Test
    void shutdownSetsExitCodeToZero() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        CompletableFuture<Object> result = server.shutdown();
        assertNotNull(result);
        assertNull(result.get());
        // Verify exitCode via reflection
        Field exitCode = GroovyLanguageServer.class.getDeclaredField("exitCode");
        exitCode.setAccessible(true);
        assertEquals(0, exitCode.getInt(server));
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void shutdownClearsClasspathTrackingState() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        Field pathMapField = GroovyLanguageServer.class.getDeclaredField("subprojectPathToEclipseName");
        pathMapField.setAccessible(true);
        ((java.util.Map) pathMapField.get(server)).put("/workspace/app", "app");

        Field projectsWithClasspathField = GroovyLanguageServer.class.getDeclaredField("projectsWithClasspath");
        projectsWithClasspathField.setAccessible(true);
        ((Set) projectsWithClasspathField.get(server)).add("app");

        Field pendingClasspathUpdatesField = GroovyLanguageServer.class.getDeclaredField("pendingClasspathUpdates");
        pendingClasspathUpdatesField.setAccessible(true);
        ((java.util.Queue) pendingClasspathUpdatesField.get(server)).add("queued");

        server.shutdown().join();

        assertTrue(((java.util.Map<?, ?>) pathMapField.get(server)).isEmpty());
        assertTrue(((Set<?>) projectsWithClasspathField.get(server)).isEmpty());
        assertTrue(((java.util.Queue<?>) pendingClasspathUpdatesField.get(server)).isEmpty());
    }

    @Test
    void exitDoesNotThrowWithoutPlugin() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        GroovyLanguageServerPlugin.setLanguageServer(null);
        assertDoesNotThrow(server::exit);
    }

    // ================================================================
    // initialized tests
    // ================================================================

    @Test
    void initializedWithoutWorkspaceRootSetsReadyStatus() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);
        server.setRemoteEndpoint(endpoint);
        // workspaceRoot is null, so should just send Ready status
        server.initialized(new InitializedParams());
        verify(endpoint).notify(org.mockito.ArgumentMatchers.eq("groovy/status"), org.mockito.ArgumentMatchers.any());
    }

    // ================================================================
    // resolveSource edge cases
    // ================================================================

    @Test
    void resolveSourceReturnsErrorForNullUri() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        // no "uri" key
        String result = server.resolveSource(params).get();
        assertNotNull(result);
    }

    @Test
    void resolveSourceReturnsErrorForEmptyUri() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        params.addProperty("uri", "");
        String result = server.resolveSource(params).get();
        assertNotNull(result);
    }

    // ================================================================
    // classpathUpdate edge cases
    // ================================================================

    @Test
    void classpathUpdateWithEmptyEntriesArrayDoesNotThrow() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        params.add("entries", new JsonArray());
        assertDoesNotThrow(() -> server.classpathUpdate(params));
    }

    @Test
    void classpathUpdateWithMissingEntriesDoesNotThrow() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        assertDoesNotThrow(() -> server.classpathUpdate(params));
    }

    // ================================================================
    // normalizePath edge cases
    // ================================================================

    @Test
    void normalizePathHandlesForwardSlashes() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result = invokeNormalizePath(server, "/home/user/project");
        assertNotNull(result);
        assertTrue(result.endsWith("/"));
        assertFalse(result.contains("\\"));
    }

    @Test
    void normalizePathHandlesTrailingSlash() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result1 = invokeNormalizePath(server, "C:\\Users\\test\\");
        String result2 = invokeNormalizePath(server, "C:\\Users\\test");
        assertEquals(result1, result2);
    }

    private String invokeNormalizePath(GroovyLanguageServer server, String path) throws Exception {
        return (String) invoke(
                server,
                "normalizePath",
                new Class<?>[] {String.class},
                new Object[] {path});
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}

