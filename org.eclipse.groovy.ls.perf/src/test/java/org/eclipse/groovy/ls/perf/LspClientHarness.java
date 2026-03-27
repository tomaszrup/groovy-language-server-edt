package org.eclipse.groovy.ls.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


/**
 * A reusable test harness that launches the Groovy Language Server as a
 * child process (Equinox / OSGi), connects via JSON-RPC over stdio, and
 * exposes typed APIs for every LSP request we want to benchmark.
 * <p>
 * Lifecycle: {@link #start()} → wait for Ready → run benchmarks → {@link #shutdown()}.
 */
public class LspClientHarness implements AutoCloseable {

    // ---- Configuration ----

    private final Path serverDir;
    private final String javaHome;
    private final Path workspaceRoot;
    private final long serverTimeoutSeconds;

    // ---- Runtime state ----

    private Process serverProcess;
    private LanguageServer server;
    /** Custom server proxy that supports groovy/* notifications. */
    private GroovyLanguageServer groovyServer;

    /** Latch that releases once the server sends {@code groovy/status} with {@code state: "Ready"}. */
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    /** Most recent diagnostics per URI — updated by the client notification handler. */
    private final ConcurrentHashMap<String, List<Diagnostic>> latestDiagnostics = new ConcurrentHashMap<>();

    /** Listeners that are notified when diagnostics arrive for a specific URI. */
    private final ConcurrentHashMap<String, CompletableFuture<List<Diagnostic>>> diagnosticFutures = new ConcurrentHashMap<>();

    /** Collected server log messages for debugging. */
    private final List<String> serverLogs = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, Long> firstStatusTimesMs = new ConcurrentHashMap<>();
    private final AtomicLong processStartedAtMs = new AtomicLong();
    private final AtomicLong initializeStartedAtMs = new AtomicLong();
    private final AtomicLong initializeCompletedAtMs = new AtomicLong();
    private final AtomicLong initializedSentAtMs = new AtomicLong();
    private final AtomicLong readyAtMs = new AtomicLong();
    private final AtomicLong semanticTokensRefreshCount = new AtomicLong();
    private final Object semanticTokensRefreshMonitor = new Object();

    // ---- Construction ----

    /**
     * @param serverDir     path to the assembled product (contains {@code plugins/} and {@code config_win/})
     * @param javaHome      path to a JDK 17+ installation
     * @param workspaceRoot path to the synthetic workspace root
     */
    public LspClientHarness(Path serverDir, String javaHome, Path workspaceRoot) {
        this(serverDir, javaHome, workspaceRoot, 180);
    }

    public LspClientHarness(Path serverDir, String javaHome, Path workspaceRoot, long serverTimeoutSeconds) {
        this.serverDir = serverDir;
        this.javaHome = javaHome;
        this.workspaceRoot = workspaceRoot;
        this.serverTimeoutSeconds = serverTimeoutSeconds;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Start the language server process, perform the LSP handshake, and wait
     * until the server reports {@code Ready} status.
     */
    public void start() throws Exception {
        start(true);
    }

    /**
     * Start the language server process and complete the LSP handshake.
     *
     * @param waitForReady when true, block until the server reports Ready;
     *                     otherwise return after initialize/initialized.
     */
    public void start(boolean waitForReady) throws Exception {
        Path dataDir = Files.createTempDirectory("groovy-ls-perf-data");
        Path launcherJar = findLauncherJar();
        String configDirName = getConfigDirName();

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(javaHome, "bin", "java").toString());
        cmd.add("--add-modules=ALL-SYSTEM");
        cmd.add("--add-opens");
        cmd.add("java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens");
        cmd.add("java.base/java.lang=ALL-UNNAMED");
        cmd.add("-Xmx2G");
        cmd.add("-Declipse.application=org.eclipse.groovy.ls.core.id1");
        cmd.add("-Declipse.product=org.eclipse.groovy.ls.core.product");
        cmd.add("-Dosgi.bundles.defaultStartLevel=4");
        cmd.add("-Dosgi.checkConfiguration=true");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(launcherJar.toString());
        cmd.add("-configuration");
        cmd.add(serverDir.resolve(configDirName).toString());
        cmd.add("-data");
        cmd.add(dataDir.toString());

        System.out.println("[Harness] Starting server: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false); // keep stderr separate
        processStartedAtMs.set(System.currentTimeMillis());
        serverProcess = pb.start();

        // Drain stderr in a background thread
        Thread stderrDrain = new Thread(() -> drainStream(serverProcess.getErrorStream(), "[Server STDERR] "), "stderr-drain");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        // Connect via LSP4J
        InputStream in = serverProcess.getInputStream();
        OutputStream out = serverProcess.getOutputStream();

        LanguageClientImpl client = new LanguageClientImpl();
        Launcher<GroovyLanguageServer> launcher = new Launcher.Builder<GroovyLanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(GroovyLanguageServer.class)
                .setInput(in)
                .setOutput(out)
                .create();
        groovyServer = launcher.getRemoteProxy();
        server = groovyServer;
        launcher.startListening();

        // Send initialize
        InitializeParams initParams = new InitializeParams();
        initParams.setProcessId((int) ProcessHandle.current().pid());
        initParams.setRootUri(workspaceRoot.toUri().toString());
        initParams.setCapabilities(new ClientCapabilities());
        initParams.setWorkspaceFolders(List.of(
                new WorkspaceFolder(workspaceRoot.toUri().toString(), workspaceRoot.getFileName().toString())
        ));

        System.out.println("[Harness] Sending initialize...");
        initializeStartedAtMs.set(System.currentTimeMillis());
        server.initialize(initParams).get(60, TimeUnit.SECONDS);
        initializeCompletedAtMs.set(System.currentTimeMillis());
        System.out.println("[Harness] Server initialized. Capabilities received.");

        // Send initialized
        initializedSentAtMs.set(System.currentTimeMillis());
        server.initialized(new InitializedParams());
        System.out.println("[Harness] Sent initialized notification.");

        if (waitForReady) {
            waitForReady();
        }
    }

    public StartupMetrics getStartupMetrics() {
        return new StartupMetrics(
                duration(processStartedAtMs.get(), readyAtMs.get()),
                duration(initializeStartedAtMs.get(), initializeCompletedAtMs.get()),
                duration(initializedSentAtMs.get(), readyAtMs.get()),
                Collections.unmodifiableMap(new TreeMap<>(firstStatusTimesMs)));
    }

    public long durationFromProcessStart(long endedAtMs) {
        return duration(processStartedAtMs.get(), endedAtMs);
    }

    public void waitForReady() throws Exception {
        System.out.println("[Harness] Waiting for server Ready status...");
        if (!readyLatch.await(serverTimeoutSeconds, TimeUnit.SECONDS)) {
            throw new TimeoutException("Server did not reach Ready status within " + serverTimeoutSeconds + "s");
        }
        System.out.println("[Harness] Server is Ready.");
    }

    public long getSemanticTokensRefreshCount() {
        return semanticTokensRefreshCount.get();
    }

    public boolean waitForSemanticTokensRefresh(long previousCount, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineAtMs = System.currentTimeMillis() + unit.toMillis(timeout);
        synchronized (semanticTokensRefreshMonitor) {
            while (semanticTokensRefreshCount.get() <= previousCount) {
                long remainingMs = deadlineAtMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    return false;
                }
                semanticTokensRefreshMonitor.wait(remainingMs);
            }
            return true;
        }
    }

    /**
     * Send classpath data for all generated projects via the
     * {@code groovy/classpathUpdate} custom notification.
     *
     * @param projectNames project directory names (e.g. "module-0")
     * @param jarPaths     absolute paths to library JARs to include in every project
     */
    public void sendClasspathUpdates(List<String> projectNames, List<Path> jarPaths) {
        // Build the shared JAR entries array once
        JsonArray sharedEntries = new JsonArray();
        for (Path jar : jarPaths) {
            sharedEntries.add(jar.toAbsolutePath().toString());
        }

        for (String name : projectNames) {
            Path projectPath = workspaceRoot.resolve(name);
            JsonObject params = new JsonObject();
            params.addProperty("projectUri", projectPath.toUri().toString());
            params.addProperty("projectPath", projectPath.toString());
            params.add("entries", sharedEntries.deepCopy());
            groovyServer.classpathUpdate(params);
        }

        // Signal that the batch is complete so the server can build immediately
        groovyServer.classpathBatchComplete(new JsonObject());
        System.out.println("[Harness] Classpath updates sent for " + projectNames.size()
                + " projects (" + jarPaths.size() + " JARs each).");
    }

    /**
     * Overload for backward compatibility when there are no JARs.
     */
    public void sendClasspathUpdates(List<String> projectNames) throws Exception {
        sendClasspathUpdates(projectNames, List.of());
    }

    /**
     * Gracefully stop the language server.
     */
    public void shutdown() {
        if (server != null) {
            try {
                System.out.println("[Harness] Sending shutdown...");
                server.shutdown().get(30, TimeUnit.SECONDS);
                server.exit();
            } catch (Exception e) {
                System.err.println("[Harness] Shutdown error: " + e.getMessage());
            }
        }
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
            try {
                serverProcess.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[Harness] Server stopped.");
    }

    @Override
    public void close() {
        shutdown();
    }

    // ========================================================================
    // Document lifecycle APIs
    // ========================================================================

    public void didOpen(String uri, String content) {
        server.getTextDocumentService().didOpen(
                new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, content)));
    }

    public void didChange(String uri, String newContent, int version) {
        // Full-document change (incremental sync would use range, but full is simpler for benchmarks)
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(newContent);
        server.getTextDocumentService().didChange(
                new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(uri, version),
                        List.of(change)));
    }

    public void didClose(String uri) {
        server.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    }

    // ========================================================================
    // LSP request APIs (timed externally by the benchmark)
    // ========================================================================

    /** Request completions at the given position. */
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(String uri, int line, int character) {
        CompletionParams params = new CompletionParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character));
        return server.getTextDocumentService().completion(params);
    }

    /** Request hover at the given position. */
    public CompletableFuture<Hover> hover(String uri, int line, int character) {
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character));
        return server.getTextDocumentService().hover(params);
    }

    /** Request references at the given position. */
    public CompletableFuture<List<? extends Location>> references(String uri, int line, int character) {
        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character),
                new ReferenceContext(true));
        return server.getTextDocumentService().references(params);
    }

    /** Request rename at the given position. */
    public CompletableFuture<WorkspaceEdit> rename(String uri, int line, int character, String newName) {
        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character),
                newName);
        return server.getTextDocumentService().rename(params);
    }

    /** Request semantic tokens for the full document. */
    public CompletableFuture<SemanticTokens> semanticTokensFull(String uri) {
        SemanticTokensParams params = new SemanticTokensParams(
                new TextDocumentIdentifier(uri));
        return server.getTextDocumentService().semanticTokensFull(params);
    }

    /** Request document symbols. */
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(String uri) {
        DocumentSymbolParams params = new DocumentSymbolParams(
                new TextDocumentIdentifier(uri));
        return server.getTextDocumentService().documentSymbol(params);
    }

    /** Request code lenses for the given document. */
    public CompletableFuture<List<? extends CodeLens>> codeLens(String uri) {
        CodeLensParams params = new CodeLensParams(
                new TextDocumentIdentifier(uri));
        return server.getTextDocumentService().codeLens(params);
    }

    /** Request folding ranges for the given document. */
    public CompletableFuture<List<FoldingRange>> foldingRange(String uri) {
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        return server.getTextDocumentService().foldingRange(params);
    }

    /** Request inlay hints for the given document and range. */
    public CompletableFuture<List<InlayHint>> inlayHint(String uri, int startLine, int endLine) {
        InlayHintParams params = new InlayHintParams(
                new TextDocumentIdentifier(uri),
                new Range(new Position(startLine, 0), new Position(endLine, 0)));
        return server.getTextDocumentService().inlayHint(params);
    }

    // ========================================================================
    // Diagnostics observation
    // ========================================================================

    /**
     * Register a future that will complete the next time diagnostics arrive
     * for the given URI. Call this <em>before</em> making the edit that
     * triggers diagnostics.
     */
    public CompletableFuture<List<Diagnostic>> awaitDiagnostics(String uri) {
        CompletableFuture<List<Diagnostic>> future = new CompletableFuture<>();
        diagnosticFutures.put(uri, future);
        return future;
    }

    /** Get the most recently received diagnostics for a URI (may be null). */
    public List<Diagnostic> getLatestDiagnostics(String uri) {
        return latestDiagnostics.get(uri);
    }

    /** Get collected server log messages. */
    public List<String> getServerLogs() {
        return Collections.unmodifiableList(serverLogs);
    }

    public record StartupMetrics(
            long processStartToReadyMs,
            long initializeRoundTripMs,
            long initializedToReadyMs,
            Map<String, Long> firstStatusTimesMs) {
    }

    // ========================================================================
    // Language Client implementation
    // ========================================================================

    /**
     * Extended client interface that adds the custom {@code groovy/status} notification.
     */
    private interface GroovyLanguageClient extends LanguageClient {
        @JsonNotification("groovy/status")
        void groovyStatus(JsonObject params);
    }

    private class LanguageClientImpl implements GroovyLanguageClient {

        @Override
        public void groovyStatus(JsonObject params) {
            String state = params.has("state") ? params.get("state").getAsString() : "";
            System.out.println("[Server Status] " + state);
            recordStatus(state);
            if ("Ready".equals(state)) {
                recordReady();
                readyLatch.countDown();
            }
        }

        @Override
        public void telemetryEvent(Object object) {
            // ignored
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            String uri = params.getUri();
            latestDiagnostics.put(uri, params.getDiagnostics());

            CompletableFuture<List<Diagnostic>> future = diagnosticFutures.remove(uri);
            if (future != null) {
                future.complete(params.getDiagnostics());
            }
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            System.out.println("[Server Message] " + messageParams.getType() + ": " + messageParams.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams messageParams) {
            String msg = messageParams.getMessage();
            serverLogs.add(msg);
            // Check for Ready status in log messages in case the custom notification isn't captured
            if (msg != null && msg.contains("Ready")) {
                recordReady();
                readyLatch.countDown();
            }
        }

        @Override
        public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void notifyProgress(ProgressParams params) {
            // no-op — groovy/status is handled via groovyStatus()
        }

        @Override
        public CompletableFuture<Void> refreshSemanticTokens() {
            synchronized (semanticTokensRefreshMonitor) {
                semanticTokensRefreshCount.incrementAndGet();
                semanticTokensRefreshMonitor.notifyAll();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> refreshCodeLenses() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> refreshDiagnostics() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> refreshInlayHints() {
            return CompletableFuture.completedFuture(null);
        }
    }

    // ========================================================================
    // Custom server interface for groovy/* notifications
    // ========================================================================

    /**
     * Extends the standard {@link LanguageServer} with custom
     * {@code groovy/classpathUpdate} and {@code groovy/classpathBatchComplete}
     * JSON-RPC notifications that the Groovy Language Server accepts.
     */
    private interface GroovyLanguageServer extends LanguageServer {
        @JsonNotification("groovy/classpathUpdate")
        void classpathUpdate(JsonObject params);

        @JsonNotification("groovy/classpathBatchComplete")
        void classpathBatchComplete(JsonObject params);
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private Path findLauncherJar() throws IOException {
        Path pluginsDir = serverDir.resolve("plugins");
        if (!Files.isDirectory(pluginsDir)) {
            throw new IllegalStateException("Server plugins directory not found: " + pluginsDir);
        }

        try (var stream = Files.list(pluginsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Equinox launcher JAR not found in " + pluginsDir));
        }
    }

    private String getConfigDirName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "config_win";
        if (os.contains("mac")) return "config_mac";
        return "config_linux";
    }

    private void drainStream(InputStream stream, String prefix) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(prefix + line);
                // Detect Ready status from stderr output too
                if (line.contains("\"state\":\"Ready\"") || line.contains("state=Ready")) {
                    recordReady();
                    readyLatch.countDown();
                }
            }
        } catch (IOException e) {
            // Process ended — expected on shutdown
        }
    }

    private void recordStatus(String state) {
        if (state == null || state.isBlank()) {
            return;
        }
        long processStartedAt = processStartedAtMs.get();
        if (processStartedAt == 0) {
            return;
        }
        firstStatusTimesMs.putIfAbsent(state, System.currentTimeMillis() - processStartedAt);
    }

    private void recordReady() {
        readyAtMs.compareAndSet(0L, System.currentTimeMillis());
    }

    private static long duration(long startedAt, long endedAt) {
        if (startedAt <= 0 || endedAt <= 0 || endedAt < startedAt) {
            return -1;
        }
        return endedAt - startedAt;
    }
}
