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

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
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

