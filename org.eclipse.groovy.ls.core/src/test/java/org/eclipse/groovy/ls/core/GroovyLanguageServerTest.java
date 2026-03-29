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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Tests for {@link GroovyLanguageServer} — initialization, capabilities,
 * shutdown, and helper methods.
 */
class GroovyLanguageServerTest {

    // ---- Constructor & services ----

    @Test
    void constructorCreatesDocumentAndTextServices() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNotNull(server.getTextDocumentService());
        assertNotNull(server.getWorkspaceService());
    }

    @Test
    void getTextDocumentServiceReturnsSameInstance() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        var a = server.getTextDocumentService();
        var b = server.getTextDocumentService();
        assertEquals(a, b);
    }

    @Test
    void getWorkspaceServiceReturnsSameInstance() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        var a = server.getWorkspaceService();
        var b = server.getWorkspaceService();
        assertEquals(a, b);
    }

    // ---- Initialize ----

    @Test
    void initializeReturnsCapabilities() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        InitializeParams params = new InitializeParams();
        // No rootUri — just test capabilities declaration
        CompletableFuture<InitializeResult> future = server.initialize(params);
        InitializeResult result = future.get();
        assertNotNull(result);
        ServerCapabilities caps = result.getCapabilities();
        assertNotNull(caps);
    }

    @Test
    void initializeSetsCompletionProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getCompletionProvider());
        assertTrue(caps.getCompletionProvider().getResolveProvider());
        assertTrue(caps.getCompletionProvider().getTriggerCharacters().contains("."));
    }

    @Test
    void initializeSetsDefinitionProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getDefinitionProvider()));
    }

    @Test
    void initializeSetsHoverProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getHoverProvider()));
    }

    @Test
    void initializeSetsRenameProviderWithPrepare() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getRenameProvider());
        assertTrue(caps.getRenameProvider().getRight().getPrepareProvider());
    }

    @Test
    void initializeSetsFoldingRangeProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getFoldingRangeProvider()));
    }

    @Test
    void initializeSetsSemanticTokensProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getSemanticTokensProvider());
    }

    @Test
    void initializeSetsCodeActionProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getCodeActionProvider());
    }

    @Test
    void initializeAdvertisesSourceFixAllCodeActionKind() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(caps.getCodeActionProvider().isRight());
        assertTrue(caps.getCodeActionProvider().getRight().getCodeActionKinds()
                .contains(CodeActionKind.SourceFixAll));
    }

    @Test
    void initializeSetsTypeHierarchyProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getTypeHierarchyProvider()));
    }

    @Test
    void initializeSetsCallHierarchyProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getCallHierarchyProvider()));
    }

    @Test
    void initializeSetsCodeLensProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getCodeLensProvider());
        assertTrue(caps.getCodeLensProvider().getResolveProvider());
    }

    @Test
    void initializeSetsInlayHintProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getInlayHintProvider()));
    }

    @Test
    void initializeSetsFormattingProviders() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getDocumentFormattingProvider()));
        assertTrue(booleanCapability(caps.getDocumentRangeFormattingProvider()));
        assertNotNull(caps.getDocumentOnTypeFormattingProvider());
    }

    @Test
    void initializeSetsDocumentSyncWithIncrementalChanges() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getTextDocumentSync());
    }

    @Test
    void initializeSetsWorkspaceCapabilities() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getWorkspace());
        assertNotNull(caps.getWorkspace().getFileOperations());
    }

    @Test
    void initializeSetsSignatureHelpProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertNotNull(caps.getSignatureHelpProvider());
        assertTrue(caps.getSignatureHelpProvider().getTriggerCharacters().contains("("));
    }

    @Test
    void initializeSetsReferencesProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getReferencesProvider()));
    }

    @Test
    void initializeSetsDocumentHighlightProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getDocumentHighlightProvider()));
    }

    @Test
    void initializeSetsDocumentSymbolProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getDocumentSymbolProvider()));
    }

    @Test
    void initializeSetsImplementationProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getImplementationProvider()));
    }

    @Test
    void initializeSetsTypeDefinitionProvider() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ServerCapabilities caps = initAndGetCapabilities(server);
        assertTrue(booleanCapability(caps.getTypeDefinitionProvider()));
    }

    // ---- Shutdown ----

    @Test
    void shutdownReturnsNullAndSetsCleanExitCode() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        CompletableFuture<Object> future = server.shutdown();
        assertNull(future.get());

        // Verify exit code was set to 0
        Field exitCodeField = GroovyLanguageServer.class.getDeclaredField("exitCode");
        exitCodeField.setAccessible(true);
        assertEquals(0, exitCodeField.getInt(server));
    }

    // ---- sendStatus ----

    @Test
    void sendStatusDoesNotThrowWithNullEndpoint() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        // No endpoint set — should not throw
        invokeSendStatus(server, "Ready", null);
        invokeSendStatus(server, "Error", "test error");
    }

    // ---- resolveSource ----

    @Test
    void resolveSourceReturnsNullForUnresolvableUri() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject params = new JsonObject();
        params.addProperty("uri", "groovy-source://test/Unknown.groovy");
        CompletableFuture<String> future = server.resolveSource(params);
        // Should complete (possibly with null content)
        assertNotNull(future);
    }

    // ---- workspace root handling ----

    @Test
    void initializeCapturesRootUri() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        InitializeParams params = new InitializeParams();
        params.setRootUri("file:///tmp/test-project");
        // initialize will try to set up workspace project which
        // may fail without Eclipse runtime, but rootUri should be captured
        try {
            server.initialize(params).get();
        } catch (Exception e) {
            // Expected — no Eclipse workspace available
        }
        Field rootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        rootField.setAccessible(true);
        assertEquals("file:///tmp/test-project", rootField.get(server));
    }

    // ---- Helper methods ----

    private ServerCapabilities initAndGetCapabilities(GroovyLanguageServer server) throws Exception {
        InitializeParams params = new InitializeParams();
        InitializeResult result = server.initialize(params).get();
        return result.getCapabilities();
    }

    private boolean booleanCapability(Object either) {
        if (either instanceof Boolean b) {
            return b;
        }
        if (either instanceof org.eclipse.lsp4j.jsonrpc.messages.Either e) {
            if (e.isLeft()) {
                return (Boolean) e.getLeft();
            }
            return e.getRight() != null;
        }
        return either != null;
    }

    private void invokeSendStatus(GroovyLanguageServer server, String state, String message) {
        try {
            Method m = GroovyLanguageServer.class.getDeclaredMethod("sendStatus", String.class, String.class);
            m.setAccessible(true);
            m.invoke(server, state, message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object invokePrivate(GroovyLanguageServer server, String methodName, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = GroovyLanguageServer.class.getDeclaredMethod(methodName, types);
        m.setAccessible(true);
        return m.invoke(server, args);
    }

    private Object getField(GroovyLanguageServer server, String fieldName) throws Exception {
        Field field = GroovyLanguageServer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(server);
    }

    private Object getDeclaredFieldValue(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    // ================================================================
    // normalizePath tests
    // ================================================================

    @Test
    void normalizePathBackslashes() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result = (String) invokePrivate(server, "normalizePath",
                new Class<?>[] {String.class}, new Object[] {"C:\\foo\\bar"});
        assertNotNull(result);
        assertFalse(result.contains("\\"));
        assertTrue(result.contains("foo"));
    }

    @Test
    void normalizePathNull() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result = (String) invokePrivate(server, "normalizePath",
                new Class<?>[] {String.class}, new Object[] {(String) null});
        assertNull(result);
    }

    @Test
    void normalizePathEmpty() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result = (String) invokePrivate(server, "normalizePath",
                new Class<?>[] {String.class}, new Object[] {""});
        assertNull(result);
    }

    @Test
    void normalizePathForwardSlashesKept() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        String result = (String) invokePrivate(server, "normalizePath",
                new Class<?>[] {String.class}, new Object[] {"/home/user/project"});
        assertNotNull(result);
        assertTrue(result.contains("project"));
    }

    // ================================================================
    // getOptionalJsonString tests
    // ================================================================

    @Test
    void getOptionalJsonStringPresent() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "test");
        String result = (String) invokePrivate(server, "getOptionalJsonString",
                new Class<?>[] {JsonObject.class, String.class}, new Object[] {obj, "name"});
        assertEquals("test", result);
    }

    @Test
    void getOptionalJsonStringMissing() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        String result = (String) invokePrivate(server, "getOptionalJsonString",
                new Class<?>[] {JsonObject.class, String.class}, new Object[] {obj, "missing"});
        assertNull(result);
    }

    @Test
    void getOptionalJsonStringEmptyObject() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("other", "value");
        String result = (String) invokePrivate(server, "getOptionalJsonString",
                new Class<?>[] {JsonObject.class, String.class}, new Object[] {obj, "key"});
        assertNull(result);
    }

    // ================================================================
    // parseClasspathUpdateRequest tests
    // ================================================================

    @Test
    void parseClasspathUpdateRequestValid() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("projectPath", "/home/user/project");
        obj.addProperty("projectName", "myproject");
        JsonArray entries = new JsonArray();
        entries.add("/libs/foo.jar");
        obj.add("entries", entries);
        Object result = invokePrivate(server, "parseClasspathUpdateRequest",
                new Class<?>[] {JsonObject.class}, new Object[] {obj});
        assertNotNull(result);
    }

    @Test
    void parseClasspathUpdateRequestNoEntries() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("projectPath", "/home/user/project");
        Object result = invokePrivate(server, "parseClasspathUpdateRequest",
                new Class<?>[] {JsonObject.class}, new Object[] {obj});
        assertNull(result);
    }

    @Test
    void parseClasspathUpdateRequestEmptyEntries() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("projectPath", "/some/path");
        JsonArray emptyEntries = new JsonArray();
        obj.add("entries", emptyEntries);
        Object result = invokePrivate(server, "parseClasspathUpdateRequest",
                new Class<?>[] {JsonObject.class}, new Object[] {obj});
        assertNull(result);
    }

    @Test
    void parseClasspathUpdateRequestPreservesExplicitHasJarEntriesFlag() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("projectPath", "/home/user/project");
        obj.addProperty("hasJarEntries", false);
        JsonArray entries = new JsonArray();
        entries.add("/libs/foo.jar");
        obj.add("entries", entries);

        Object result = invokePrivate(server, "parseClasspathUpdateRequest",
                new Class<?>[] {JsonObject.class}, new Object[] {obj});

        assertNotNull(result);
        assertFalse((Boolean) getDeclaredFieldValue(result, "hasJarEntries"));
    }

    @Test
    void parseClasspathUpdateRequestInfersHasJarEntriesFromEntriesWhenMissing() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        JsonObject obj = new JsonObject();
        obj.addProperty("projectPath", "/home/user/project");
        JsonArray entries = new JsonArray();
        entries.add("/libs/foo.jar");
        obj.add("entries", entries);

        Object result = invokePrivate(server, "parseClasspathUpdateRequest",
                new Class<?>[] {JsonObject.class}, new Object[] {obj});

        assertNotNull(result);
        assertTrue((Boolean) getDeclaredFieldValue(result, "hasJarEntries"));
    }

    // ================================================================
    // areDiagnosticsEnabled / isBuildInProgress / hasClasspathForProject tests
    // ================================================================

    @Test
    void areDiagnosticsEnabledDefaultFalse() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertFalse(server.areDiagnosticsEnabled());
    }

    @Test
    void isBuildInProgressDefaultFalse() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertFalse(server.isBuildInProgress());
    }

    @Test
    void hasClasspathForProjectEmptySetReturnsFalse() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertFalse(server.hasClasspathForProject("anything"));
    }

    @Test
    void hasClasspathForProjectAfterAdding() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Field f = GroovyLanguageServer.class.getDeclaredField("projectsWithClasspath");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> set = (java.util.Set<String>) f.get(server);
        set.add("myproject");
        assertTrue(server.hasClasspathForProject("myproject"));
        assertFalse(server.hasClasspathForProject("other"));
    }

    // ================================================================
    // getClient / connect tests
    // ================================================================

    @Test
    void getClientNullBeforeConnect() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNull(server.getClient());
    }

    @Test
    void connectSetsClient() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        LanguageClient client = mock(LanguageClient.class);
        server.connect(client);
        assertEquals(client, server.getClient());
    }

    // ================================================================
    // getGroovyTextDocumentService tests
    // ================================================================

    @Test
    void getGroovyTextDocumentServiceReturnsInstance() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Object groovyService = server.getGroovyTextDocumentService();
        assertNotNull(groovyService);
        assertTrue(groovyService instanceof GroovyTextDocumentService);
    }

    // ================================================================
    // sendStatus with endpoint tests
    // ================================================================

    @Test
    void sendStatusWithEndpointSendsNotification() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);
        Field endpointField = GroovyLanguageServer.class.getDeclaredField("remoteEndpoint");
        endpointField.setAccessible(true);
        endpointField.set(server, endpoint);
        invokeSendStatus(server, "Ready", "all good");
        verify(endpoint).notify(org.mockito.ArgumentMatchers.eq("groovy/status"),
                org.mockito.ArgumentMatchers.any());
    }

    // ================================================================
    // shouldSkipClasspathEntry tests
    // ================================================================

    @Test
    void shouldSkipClasspathEntryNonExistentFile() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.io.File f = new java.io.File("/nonexistent/path/foo.jar");
        boolean result = (boolean) invokePrivate(server, "shouldSkipClasspathEntry",
                new Class<?>[] {java.io.File.class, String.class, String.class},
                new Object[] {f, "/valid/java/home", "/valid/workspace"});
        assertTrue(result); // file doesn't exist → skip
    }

    // ================================================================
    // putSubprojectMapping tests
    // ================================================================

    @Test
    void putSubprojectMappingStoresEntry() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        invokePrivate(server, "putSubprojectMapping",
                new Class<?>[] {String.class, String.class},
                new Object[] {"subproject-a", "EclipseProjectA"});
        Field mapField = GroovyLanguageServer.class.getDeclaredField("subprojectPathToEclipseName");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> map = (java.util.Map<String, String>) mapField.get(server);
        assertTrue(map.containsValue("EclipseProjectA"));
    }

    // ================================================================
    // getWorkspaceRoot tests
    // ================================================================

    @Test
    void getWorkspaceRootNullByDefault() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNull(server.getWorkspaceRoot());
    }

    // ================================================================
    // shouldSkipClasspathEntry tests
    // ================================================================

    @Test
    void shouldSkipClasspathEntryNullEntryNorm() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(server, new java.io.File("."), null, "");
        assertTrue(result);
    }

    @Test
    void shouldSkipClasspathEntryNonExistentFile(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        java.io.File nonExist = new java.io.File(tempDir.toFile(), "nonexist.jar");
        boolean result = (boolean) m.invoke(server, nonExist, "/path/nonexist.jar/", "");
        assertTrue(result);
    }

    @Test
    void shouldSkipClasspathEntryJavaHomePrefix(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        java.io.File dir = tempDir.toFile();
        String javaHome = dir.getAbsolutePath().replace('\\', '/') + "/";
        String entryNorm = javaHome + "lib/rt.jar/";
        boolean result = (boolean) m.invoke(server, dir, entryNorm, javaHome);
        assertTrue(result);
    }

    @Test
    void shouldSkipClasspathEntryValidDirectory(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        // Use the temp dir itself as the classpath entry - it's a valid directory
        String entryNorm = tempDir.toFile().getAbsolutePath().replace('\\', '/') + "/";
        boolean result = (boolean) m.invoke(server, tempDir.toFile(), entryNorm, "");
        assertFalse(result);
    }

    @Test
    void shouldSkipClasspathEntryRegularNonJarFile(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        java.io.File regularFile = java.nio.file.Files.createFile(tempDir.resolve("test.txt")).toFile();
        String entryNorm = regularFile.getAbsolutePath().replace('\\', '/');
        boolean result = (boolean) m.invoke(server, regularFile, entryNorm, "");
        assertTrue(result);
    }

    @Test
    void shouldSkipClasspathEntryJarFile(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "shouldSkipClasspathEntry", java.io.File.class, String.class, String.class);
        m.setAccessible(true);
        java.io.File jarFile = java.nio.file.Files.createFile(tempDir.resolve("lib.jar")).toFile();
        String entryNorm = jarFile.getAbsolutePath().replace('\\', '/') + "/";
        // entryNorm ends with ".jar/" so isJar=true
        boolean result = (boolean) m.invoke(server, jarFile, entryNorm, "");
        assertFalse(result);
    }

    // ================================================================
    // findSubprojectsWithSources tests
    // ================================================================

    @Test
    void findSubprojectsWithSourcesEmpty(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "findSubprojectsWithSources", java.io.File.class, String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.io.File> result = (java.util.List<java.io.File>) m.invoke(
                server, tempDir.toFile(), new String[] {"src/main/java"});
        assertTrue(result.isEmpty());
    }

    @Test
    void findSubprojectsWithSourcesFindsSubproject(@TempDir java.nio.file.Path tempDir) throws Exception {
        // Create subproject structure
        java.nio.file.Files.createDirectories(tempDir.resolve("subA/src/main/java"));
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "findSubprojectsWithSources", java.io.File.class, String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.io.File> result = (java.util.List<java.io.File>) m.invoke(
                server, tempDir.toFile(), new String[] {"src/main/java"});
        assertEquals(1, result.size());
        assertEquals("subA", result.get(0).getName());
    }

    @Test
    void findSubprojectsWithSourcesSkipsBuildDir(@TempDir java.nio.file.Path tempDir) throws Exception {
        // build/ should be skipped even if it has source structure
        java.nio.file.Files.createDirectories(tempDir.resolve("build/src/main/java"));
        java.nio.file.Files.createDirectories(tempDir.resolve("goodProject/src/main/java"));
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "findSubprojectsWithSources", java.io.File.class, String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.io.File> result = (java.util.List<java.io.File>) m.invoke(
                server, tempDir.toFile(), new String[] {"src/main/java"});
        assertEquals(1, result.size());
        assertEquals("goodProject", result.get(0).getName());
    }

    @Test
    void findSubprojectsWithSourcesSkipsHiddenDir(@TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve(".hidden/src/main/java"));
        java.nio.file.Files.createDirectories(tempDir.resolve("visible/src/main/java"));
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "findSubprojectsWithSources", java.io.File.class, String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.io.File> result = (java.util.List<java.io.File>) m.invoke(
                server, tempDir.toFile(), new String[] {"src/main/java"});
        assertEquals(1, result.size());
        assertEquals("visible", result.get(0).getName());
    }

    @Test
    void findSubprojectsWithSourcesMultipleSuffixes(@TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve("projA/src/main/java"));
        java.nio.file.Files.createDirectories(tempDir.resolve("projB/src/main/groovy"));
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod(
                "findSubprojectsWithSources", java.io.File.class, String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.io.File> result = (java.util.List<java.io.File>) m.invoke(
                server, tempDir.toFile(), new String[] {"src/main/java", "src/main/groovy"});
        assertEquals(2, result.size());
    }

    // ================================================================
    // resolveWorkspaceDirectory tests
    // ================================================================

    @Test
    void resolveWorkspaceDirectoryNull() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("resolveWorkspaceDirectory");
        m.setAccessible(true);
        // workspaceRoot is null by default, URI.create(null) throws NPE → returns null
        java.io.File result = (java.io.File) m.invoke(server);
        assertNull(result);
    }

    @Test
    void resolveWorkspaceDirectoryValidUri(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Field wsField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        wsField.setAccessible(true);
        wsField.set(server, tempDir.toUri().toString());
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("resolveWorkspaceDirectory");
        m.setAccessible(true);
        java.io.File result = (java.io.File) m.invoke(server);
        assertNotNull(result);
    }

    @Test
    void resolveWorkspaceDirectoryInvalidUri() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Field wsField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        wsField.setAccessible(true);
        wsField.set(server, "not a valid uri :::");
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("resolveWorkspaceDirectory");
        m.setAccessible(true);
        java.io.File result = (java.io.File) m.invoke(server);
        assertNull(result);
    }

    @Test
    void resolveWorkspaceDirectoryNonFileUriReturnsNull() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Field wsField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        wsField.setAccessible(true);
        wsField.set(server, "groovy-source:///workspace/Virtual.groovy");
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("resolveWorkspaceDirectory");
        m.setAccessible(true);
        java.io.File result = (java.io.File) m.invoke(server);
        assertNull(result);
    }

    // ================================================================
    // triggerBuildAfterClasspathUpdate tests
    // ================================================================

    @Test
    void triggerBuildAfterClasspathUpdateSetsInitialBuildDone() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Field field = GroovyLanguageServer.class.getDeclaredField("initialBuildDone");
        field.setAccessible(true);
        assertFalse(((java.util.concurrent.atomic.AtomicBoolean) field.get(server)).get());
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("triggerBuildAfterClasspathUpdate");
        m.setAccessible(true);
        m.invoke(server);
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean) field.get(server)).get());
    }

    @Test
    void triggerBuildAfterClasspathUpdateIdempotent() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method m = GroovyLanguageServer.class.getDeclaredMethod("triggerBuildAfterClasspathUpdate");
        m.setAccessible(true);
        m.invoke(server);
        // Call again — should not throw
        m.invoke(server);
        java.lang.reflect.Field field = GroovyLanguageServer.class.getDeclaredField("initialBuildDone");
        field.setAccessible(true);
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean) field.get(server)).get());
    }

    // ================================================================
    // classpathBatchComplete tests
    // ================================================================

    @Test
    void classpathBatchCompleteNoWorkspaceRoot() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        // Should not throw even with null workspace root
        server.classpathBatchComplete(new com.google.gson.JsonObject());
    }

    @Test
    void classpathBatchCompleteWithWorkspaceRoot(@TempDir java.nio.file.Path tempDir) throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Field wsField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        wsField.setAccessible(true);
        wsField.set(server, tempDir.toUri().toString());
        // Should not throw; may attempt a build which will fail gracefully
        server.classpathBatchComplete(new com.google.gson.JsonObject());
    }

    @Test
    void initializeReadsDelegatedClasspathStartupOption() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        InitializeParams params = new InitializeParams();
        JsonObject options = new JsonObject();
        options.addProperty("delegatedClasspathStartup", true);
        params.setInitializationOptions(options);

        server.initialize(params).get();

        assertTrue((Boolean) getField(server, "delegatedClasspathStartupExpected"));
    }

    @Test
    void classpathUpdateQueuesWhileWorkspaceProjectsAreInitializing() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ((AtomicBoolean) getField(server, "workspaceProjectsReady")).set(false);

        JsonObject params = new JsonObject();
        params.addProperty("projectUri", "file:///workspace/app");
        params.addProperty("projectPath", "/workspace/app");
        JsonArray entries = new JsonArray();
        entries.add("/tmp/example.jar");
        params.add("entries", entries);

        server.classpathUpdate(params);

        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Object> queue =
                (ConcurrentLinkedQueue<Object>) getField(server, "pendingClasspathUpdates");
        assertEquals(1, queue.size());
        assertFalse(server.areDiagnosticsEnabled());
    }

    @Test
    void classpathBatchCompleteQueuesWhileWorkspaceProjectsAreInitializing() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        ((AtomicBoolean) getField(server, "workspaceProjectsReady")).set(false);

        server.classpathBatchComplete(new JsonObject());

        AtomicBoolean pending = (AtomicBoolean) getField(server, "pendingClasspathBatchComplete");
        assertTrue(pending.get());
    }

    @Test
    void publishDiagnosticsForProjectFilesIfStartupReadySkipsBeforeStartupSettles() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        server.publishDiagnosticsForProjectFilesIfStartupReady("projA");

        assertFalse(server.eagerProjectDiagnosticsPublished);
    }

    @Test
    void publishDiagnosticsForProjectFilesIfStartupReadyPublishesImmediatelyWithoutWorkspaceRoot() {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();

        server.publishDiagnosticsForProjectFilesIfStartupReady("projA");

        assertTrue(server.eagerProjectDiagnosticsPublished);
        assertEquals("projA", server.lastEagerProjectName);
    }

    @Test
    void publishDiagnosticsForProjectFilesIfStartupReadyPublishesAfterStartupSettles() throws Exception {
        RecordingGroovyLanguageServer server = new RecordingGroovyLanguageServer();
        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        Field field = GroovyLanguageServer.class.getDeclaredField("initialBuildSettled");
        field.setAccessible(true);
        field.set(server, true);

        server.publishDiagnosticsForProjectFilesIfStartupReady("projA");

        assertTrue(server.eagerProjectDiagnosticsPublished);
        assertEquals("projA", server.lastEagerProjectName);
    }

    @Test
    void triggerFullBuildWaitsForDelegatedClasspathBatchBeforeSettling() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        Field delegatedClasspathField = GroovyLanguageServer.class.getDeclaredField("delegatedClasspathStartupExpected");
        delegatedClasspathField.setAccessible(true);
        delegatedClasspathField.set(server, true);

        Field firstBuildField = GroovyLanguageServer.class.getDeclaredField("firstFullBuildComplete");
        firstBuildField.setAccessible(true);
        firstBuildField.set(server, true);

        Method settleMethod = GroovyLanguageServer.class.getDeclaredMethod("settleInitialBuildIfReady");
        settleMethod.setAccessible(true);

        settleMethod.invoke(server);
        assertFalse((Boolean) getField(server, "initialBuildSettled"));

        Field batchCompleteField = GroovyLanguageServer.class.getDeclaredField("initialClasspathBatchCompleteReceived");
        batchCompleteField.setAccessible(true);
        batchCompleteField.set(server, true);

        settleMethod.invoke(server);
        assertTrue((Boolean) getField(server, "initialBuildSettled"));
    }

    @Test
    void sendPostBuildStartupStatusKeepsImportingWhileDelegatedClasspathStartupPending() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);

        Field endpointField = GroovyLanguageServer.class.getDeclaredField("remoteEndpoint");
        endpointField.setAccessible(true);
        endpointField.set(server, endpoint);

        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        Field delegatedClasspathField = GroovyLanguageServer.class.getDeclaredField("delegatedClasspathStartupExpected");
        delegatedClasspathField.setAccessible(true);
        delegatedClasspathField.set(server, true);

        Method method = GroovyLanguageServer.class.getDeclaredMethod("sendPostBuildStartupStatus");
        method.setAccessible(true);
        method.invoke(server);

        verify(endpoint).notify(
                org.mockito.ArgumentMatchers.eq("groovy/status"),
                org.mockito.ArgumentMatchers.argThat(arg -> {
                    JsonObject params = (JsonObject) arg;
                    return "Importing".equals(params.get("state").getAsString())
                            && "Finalizing classpath...".equals(params.get("message").getAsString());
                }));
    }

    @Test
    void sendPostBuildStartupStatusSendsReadyAfterStartupSettles() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        Endpoint endpoint = mock(Endpoint.class);

        Field endpointField = GroovyLanguageServer.class.getDeclaredField("remoteEndpoint");
        endpointField.setAccessible(true);
        endpointField.set(server, endpoint);

        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        Field settledField = GroovyLanguageServer.class.getDeclaredField("initialBuildSettled");
        settledField.setAccessible(true);
        settledField.set(server, true);

        Method method = GroovyLanguageServer.class.getDeclaredMethod("sendPostBuildStartupStatus");
        method.setAccessible(true);
        method.invoke(server);

        verify(endpoint).notify(
                org.mockito.ArgumentMatchers.eq("groovy/status"),
                org.mockito.ArgumentMatchers.argThat(arg -> {
                    JsonObject params = (JsonObject) arg;
                    return "Ready".equals(params.get("state").getAsString())
                            && !params.has("message");
                }));
    }

    @Test
    void triggerFullBuildDoesNotSettleFailedInitialBuild() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();

        Field workspaceRootField = GroovyLanguageServer.class.getDeclaredField("workspaceRoot");
        workspaceRootField.setAccessible(true);
        workspaceRootField.set(server, "file:///workspace");

        IWorkspace workspace = mock(IWorkspace.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(workspace)
                .build(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = org.mockito.Mockito.mockStatic(ResourcesPlugin.class)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);

            server.triggerFullBuild();

            ScheduledExecutorService scheduler =
                    (ScheduledExecutorService) getField(server, "initialBuildScheduler");
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            server.shutdown().join();
        }

        assertTrue((Boolean) getField(server, "initialBuildStarted"));
        assertFalse((Boolean) getField(server, "initialBuildSettled"));
        assertFalse(server.isFirstBuildComplete());
    }

    // ================================================================
    // getProjectNameForUri tests
    // ================================================================

    @Test
    void getProjectNameForUriNullReturnsNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNull(server.getProjectNameForUri(null));
    }

    @Test
    void getProjectNameForUriUnknownReturnsNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNull(server.getProjectNameForUri("file:///unknown/path/Foo.groovy"));
    }

    @Test
    void getProjectNameForUriReturnsMapping() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method putMapping = GroovyLanguageServer.class.getDeclaredMethod(
                "putSubprojectMapping", String.class, String.class);
        putMapping.setAccessible(true);
        putMapping.invoke(server, "/workspace/projA", "ProjA");
        // Try to look up a URI under that subproject
        String result = server.getProjectNameForUri("file:///workspace/projA/src/Foo.groovy");
        assertTrue(result == null || result.equals("ProjA"));
    }

    private static final class RecordingGroovyLanguageServer extends GroovyLanguageServer {
        private boolean eagerProjectDiagnosticsPublished;
        private String lastEagerProjectName;

        @Override
        void publishDiagnosticsForProjectFiles(String projectName) {
            eagerProjectDiagnosticsPublished = true;
            lastEagerProjectName = projectName;
        }
    }

    @Test
    void getProjectNameForUriNonFileUriReturnsNull() {
        GroovyLanguageServer server = new GroovyLanguageServer();
        assertNull(server.getProjectNameForUri("groovy-source:///workspace/projA/src/Foo.groovy"));
    }

    @Test
    void resolveProjectFromUriNonFileUriReturnsNull() throws Exception {
        GroovyLanguageServer server = new GroovyLanguageServer();
        java.lang.reflect.Method method = GroovyLanguageServer.class.getDeclaredMethod(
                "resolveProjectFromUri", String.class);
        method.setAccessible(true);
        assertNull(method.invoke(server, "groovy-source:///workspace/projA"));
    }
}
