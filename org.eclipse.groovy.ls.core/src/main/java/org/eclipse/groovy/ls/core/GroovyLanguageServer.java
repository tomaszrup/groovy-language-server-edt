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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.FileInfoMatcherDescription;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceFilterDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions;
import org.eclipse.lsp4j.FileOperationFilter;
import org.eclipse.lsp4j.FileOperationOptions;
import org.eclipse.lsp4j.FileOperationPattern;
import org.eclipse.lsp4j.FileOperationPatternKind;
import org.eclipse.lsp4j.FileOperationsServerCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.groovy.ls.core.providers.SemanticTokensProvider;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * The Groovy Language Server implementation.
 * <p>
 * Implements the LSP {@link LanguageServer} interface and delegates
 * to {@link GroovyTextDocumentService} and {@link GroovyWorkspaceService}
 * for the actual language intelligence, which in turn use the JDT/groovy-eclipse
 * APIs for compilation, type inference, content assist, and navigation.
 */
public class GroovyLanguageServer implements LanguageServer, LanguageClientAware {

    private static final class StartupTracker {
        private final long startedAt = System.currentTimeMillis();
        private long lastMarkAt = startedAt;

        synchronized void mark(String phase) {
            mark(phase, null);
        }

        synchronized void mark(String phase, String details) {
            long now = System.currentTimeMillis();
            long sinceLast = now - lastMarkAt;
            long total = now - startedAt;
            GroovyLanguageServerPlugin.logInfo(
                    "[startup] " + phase
                    + " (+" + sinceLast + " ms, " + total + " ms total)"
                    + (details != null ? " - " + details : ""));
            lastMarkAt = now;
        }

        synchronized void duration(String phase, long phaseStartAt, String details) {
            long now = System.currentTimeMillis();
            long total = now - startedAt;
            GroovyLanguageServerPlugin.logInfo(
                    "[startup] " + phase
                    + " completed in " + (now - phaseStartAt) + " ms"
                    + " (" + total + " ms total)"
                    + (details != null ? " - " + details : ""));
        }
    }

    private static final String STATUS_STARTING = "Starting";
    private static final String STATUS_IMPORTING = "Importing";
    private static final String STATUS_COMPILING = "Compiling";
    private static final String STATUS_READY = "Ready";
    private static final String GROOVY_PROJECT_NAME = "GroovyProject";
    private static final String GROOVY_PROJECT_PREFIX = "Groovy_";
    private static final String EXTERNAL_GROOVY_PROJECT_PREFIX = "ExtGroovy_";
    private static final String LINKED_FOLDER_NAME = "linked";
    private static final String GROOVY_NATURE_ID = "org.eclipse.jdt.groovy.core.groovyNature";
    private static final String JRE_CONTAINER_ID = "org.eclipse.jdt.launching.JRE_CONTAINER";
    private static final String RESOURCE_FILTER_PATTERN =
        "\\..*|node_modules|build|out|target|dist|__pycache__|gradle";
    private static final String[] DEFAULT_SOURCE_DIR_SUFFIXES = {
        "src/main/java", "src/main/groovy",
        "src/test/java", "src/test/groovy",
        "src/main/resources", "src/test/resources",
    };

    private LanguageClient client;
    private final GroovyTextDocumentService textDocumentService;
    private final GroovyWorkspaceService workspaceService;
    private final DocumentManager documentManager;
    private final StartupTracker startupTracker = new StartupTracker();

    private String workspaceRoot;
    private int exitCode = 1; // non-zero until clean shutdown
    private Endpoint remoteEndpoint;

    private final java.util.concurrent.atomic.AtomicBoolean initialBuildDone = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean diagnosticsEnabled = false;
    private volatile boolean firstFullBuildComplete = false;
    private volatile boolean initialBuildStarted = false;
    private volatile boolean initialBuildSettled = false;
    private volatile boolean delegatedClasspathStartupExpected = false;
    private volatile boolean initialClasspathBatchCompleteReceived = false;
    private volatile boolean buildInProgress = false;
    private volatile java.util.concurrent.ScheduledFuture<?> initialBuildTimer;
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>
            debouncedBuildFuture = new java.util.concurrent.atomic.AtomicReference<>();
    private static final long BUILD_DEBOUNCE_MS = 3000;
    private final java.util.concurrent.ScheduledExecutorService initialBuildScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "initial-build-timer");
                t.setDaemon(true);
                return t;
            });

    /**
     * Maps normalized filesystem paths (lowercase, forward slashes, trailing slash)
     * to Eclipse project names. Used for per-subproject classpath isolation.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> subprojectPathToEclipseName
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tracks which Eclipse project names have received at least one successful
     * classpath update that is complete enough for full diagnostics. Projects
     * not in this set are considered to have no ready classpath configured yet
     * — diagnostics for their files are limited to syntax-only errors and a
     * warning is shown at the package declaration.
     */
    private final java.util.Set<String> projectsWithClasspath =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean firstClasspathReceived =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean firstReadySent =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean workspaceProjectsReady =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicBoolean workspaceProjectsInitializationScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean pendingClasspathBatchComplete =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.ConcurrentLinkedQueue<ClasspathUpdateRequest> pendingClasspathUpdates =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public GroovyLanguageServer() {
        this.documentManager = new DocumentManager();
        this.textDocumentService = new GroovyTextDocumentService(this, documentManager);
        this.workspaceService = new GroovyWorkspaceService(this, documentManager);
    }

    // ---- Status notification ----

    /**
     * Set the JSON-RPC endpoint for sending custom notifications.
     * Called from the application launcher after the Launcher is built.
     */
    void setRemoteEndpoint(Endpoint endpoint) {
        this.remoteEndpoint = endpoint;
    }

    /**
     * Send a status notification to the client.
     * The notification method is "groovy/status" with a JSON payload:
     * { "state": "Starting|Importing|Compiling|Ready|Error", "message": "..." }
     */
    void sendStatus(String state, String message) {
        if (remoteEndpoint == null) {
            return;
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("state", state);
            if (message != null) {
                params.addProperty("message", message);
            }
            remoteEndpoint.notify("groovy/status", params);
            GroovyLanguageServerPlugin.logInfo("[status] " + state + (message != null ? ": " + message : ""));
            if (STATUS_READY.equals(state) && firstReadySent.compareAndSet(false, true)) {
                startupTracker.mark("status.ready", message);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to send status notification", e);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return CompletableFuture.supplyAsync(() -> {
            long initializeStartedAt = System.currentTimeMillis();
            startupTracker.mark("initialize.begin");
            GroovyLanguageServerPlugin.logInfo("Initializing Groovy Language Server...");

            // Read optional pool tuning from initializationOptions
            applyInitializationOptions(params.getInitializationOptions());

            // Capture workspace root
            if (params.getRootUri() != null) {
                workspaceRoot = params.getRootUri();
            } else if (params.getRootPath() != null) {
                workspaceRoot = URI.create("file:///" + params.getRootPath().replace("\\", "/")).toString();
            }

            if (workspaceRoot != null) {
                GroovyLanguageServerPlugin.logInfo("Workspace root: " + workspaceRoot);
                sendStatus(STATUS_IMPORTING, "Configuring workspace...");
                scheduleWorkspaceProjectInitialization();
            }

            // Declare server capabilities
            ServerCapabilities capabilities = new ServerCapabilities();

            // Text document sync — incremental for efficiency
            TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
            syncOptions.setOpenClose(true);
            syncOptions.setChange(TextDocumentSyncKind.Incremental);
            SaveOptions saveOptions = new SaveOptions();
            saveOptions.setIncludeText(true);
            syncOptions.setSave(saveOptions);
            capabilities.setTextDocumentSync(syncOptions);

            // Completion
            CompletionOptions completionOptions = new CompletionOptions();
            completionOptions.setResolveProvider(true);
            completionOptions.setTriggerCharacters(Arrays.asList(".", " ", "@"));
            capabilities.setCompletionProvider(completionOptions);

            // Hover
            capabilities.setHoverProvider(true);

            // Go-to-definition
            capabilities.setDefinitionProvider(true);

            // Type definition
            capabilities.setTypeDefinitionProvider(true);

            // Find references
            capabilities.setReferencesProvider(true);

            // Document highlight
            capabilities.setDocumentHighlightProvider(true);

            // Document symbols
            capabilities.setDocumentSymbolProvider(true);

            // Workspace symbols
            capabilities.setWorkspaceSymbolProvider(true);

            // Implementation
            capabilities.setImplementationProvider(true);

            // Rename (with prepare support)
            RenameOptions renameOptions = new RenameOptions();
            renameOptions.setPrepareProvider(true);
            capabilities.setRenameProvider(renameOptions);

            // Workspace file operations (rename Groovy/Java source files)
            FileOperationPattern groovyPattern = new FileOperationPattern("**/*.groovy");
            groovyPattern.setMatches(FileOperationPatternKind.File);
            FileOperationPattern javaPattern = new FileOperationPattern("**/*.java");
            javaPattern.setMatches(FileOperationPatternKind.File);

            FileOperationFilter groovyRenameFilter = new FileOperationFilter(groovyPattern, "file");
            FileOperationFilter javaRenameFilter = new FileOperationFilter(javaPattern, "file");
            FileOperationOptions willRenameOptions = new FileOperationOptions(
                    java.util.List.of(groovyRenameFilter, javaRenameFilter));

            FileOperationsServerCapabilities fileOps = new FileOperationsServerCapabilities();
            fileOps.setWillRename(willRenameOptions);
            fileOps.setDidRename(willRenameOptions);

            WorkspaceServerCapabilities workspaceCapabilities = new WorkspaceServerCapabilities();
            workspaceCapabilities.setFileOperations(fileOps);
            capabilities.setWorkspace(workspaceCapabilities);

            // Code actions (quick fix, organize imports)
            CodeActionOptions codeActionOptions = new CodeActionOptions();
            codeActionOptions.setCodeActionKinds(java.util.Arrays.asList(
                    CodeActionKind.QuickFix,
                    CodeActionKind.Source,
                    CodeActionKind.SourceFixAll,
                    CodeActionKind.SourceOrganizeImports,
                    org.eclipse.groovy.ls.core.providers.CodeActionProvider.SOURCE_KIND_FIX_ALL_GROOVY,
                    org.eclipse.groovy.ls.core.providers.CodeActionProvider.SOURCE_KIND_ADD_MISSING_IMPORTS,
                    org.eclipse.groovy.ls.core.providers.CodeActionProvider.SOURCE_KIND_REMOVE_UNUSED_IMPORTS));
            codeActionOptions.setResolveProvider(true);
            capabilities.setCodeActionProvider(codeActionOptions);

            // Signature help
            capabilities.setSignatureHelpProvider(
                    new org.eclipse.lsp4j.SignatureHelpOptions(Arrays.asList("(", ",")));

            // Semantic tokens — rich, AST-based syntax highlighting
            SemanticTokensWithRegistrationOptions semanticTokensOptions =
                    new SemanticTokensWithRegistrationOptions();
            semanticTokensOptions.setLegend(SemanticTokensProvider.getLegend());
            semanticTokensOptions.setFull(true);
            semanticTokensOptions.setRange(true);
            capabilities.setSemanticTokensProvider(semanticTokensOptions);

                // Inlay hints
                capabilities.setInlayHintProvider(true);

            // Document formatting (Eclipse JDT formatter with XML profile support)
            capabilities.setDocumentFormattingProvider(true);
            capabilities.setDocumentRangeFormattingProvider(true);

            // On-type formatting (auto-format on }, ;, newline)
            DocumentOnTypeFormattingOptions onTypeOptions =
                    new DocumentOnTypeFormattingOptions("}");
            onTypeOptions.setMoreTriggerCharacter(List.of(";", "\n"));
            capabilities.setDocumentOnTypeFormattingProvider(onTypeOptions);

            // Folding ranges
            capabilities.setFoldingRangeProvider(true);

            // Type hierarchy (supertypes / subtypes navigation)
            capabilities.setTypeHierarchyProvider(true);

            // Call hierarchy (incoming / outgoing calls)
            capabilities.setCallHierarchyProvider(true);

            // Code lens (reference counts on types and methods)
            CodeLensOptions codeLensOptions = new CodeLensOptions(true);
            capabilities.setCodeLensProvider(codeLensOptions);

            GroovyLanguageServerPlugin.logInfo("Groovy Language Server initialized with capabilities.");
            startupTracker.duration("initialize", initializeStartedAt, null);

            return new InitializeResult(capabilities);
        });
    }

    @Override
    public void initialized(org.eclipse.lsp4j.InitializedParams params) {
        startupTracker.mark("initialized.notification");
        GroovyLanguageServerPlugin.logInfo("Client confirmed initialization. Server is fully operational.");

        // Defer the initial build to give the client time to send the classpathUpdate.
        // This prevents flashing errors while the classpath is still being resolved.
        // A fallback timer ensures the build happens even if no classpath arrives.
        if (workspaceRoot != null) {
            sendStatus(STATUS_IMPORTING, "Waiting for classpath...");
            scheduleDeferredInitialBuild();
        } else {
            sendStatus(STATUS_READY, null);
        }
    }

    private void scheduleDeferredInitialBuild() {
        try {
            initialBuildTimer = initialBuildScheduler.schedule(this::runInitialBuildIfNeeded,
                    10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to schedule deferred build", e);
            runInitialBuildIfNeeded();
        }
    }

    private void runInitialBuildIfNeeded() {
        if (!initialBuildDone.compareAndSet(false, true)) {
            return;
        }
        startupTracker.mark("initial-build.timer-fired");
        GroovyLanguageServerPlugin.logInfo("Initial build timer fired (no classpath received). Building now.");
        diagnosticsEnabled = true;
        sendStatus(STATUS_COMPILING, "Building workspace...");
        triggerFullBuild();
    }

    /**
     * Trigger a workspace build and publish diagnostics for all open documents.
     * Uses FULL_BUILD the first time (to initialise JDT indexes) and
     * INCREMENTAL_BUILD for all subsequent invocations so that the Eclipse
     * workspace lock is held for the shortest possible time.
     */
    void triggerFullBuild() {
        initialBuildScheduler.submit(() -> {
            boolean initialBuild = !firstFullBuildComplete;
            boolean buildSucceeded = false;
            long buildStartedAt = System.currentTimeMillis();
            int buildKind;
            if (initialBuild) {
                buildKind = org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD;
                GroovyLanguageServerPlugin.logInfo("Triggering initial FULL workspace build...");
                startupTracker.mark("build.initial.begin");
            } else {
                buildKind = org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD;
                GroovyLanguageServerPlugin.logInfo("Triggering INCREMENTAL workspace build...");
                startupTracker.mark("build.incremental.begin");
            }
            GroovyLanguageServerPlugin.logInfo("[diag-trace] triggerBuild start (kind=" + buildKind + ")");
            buildInProgress = true;
            if (initialBuild) {
                initialBuildStarted = true;
            }
            try {
                ResourcesPlugin.getWorkspace().build(buildKind, new NullProgressMonitor());

                firstFullBuildComplete = true;
                buildSucceeded = true;
                GroovyLanguageServerPlugin.logInfo("Build completed (kind=" + buildKind + ").");
                startupTracker.duration(
                        initialBuild ? "build.initial" : "build.incremental",
                        buildStartedAt,
                        "kind=" + buildKind);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Build failed (kind=" + buildKind + ")", e);
                sendStatus("Error", "Build failed: " + e.getMessage());
            } finally {
                buildInProgress = false;
            }

            // Now that the workspace lock is released, publish full JDT-based
            // diagnostics for every open file.  Keep the status as Compiling
            // until diagnostics have been dispatched — the reconcile work
            // itself runs asynchronously on the diagnostics thread pool.
            try {
                textDocumentService.refreshOpenDocumentsSemanticState();
                textDocumentService.publishDiagnosticsForOpenDocuments();
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Post-build diagnostics failed", e);
            }

            // Refresh code lenses — reference counts may have changed after
            // the build updated the JDT index.
            try {
                textDocumentService.refreshCodeLenses();
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Post-build code lens refresh failed", e);
            }

            if (buildSucceeded) {
                settleInitialBuildIfReady();
            }

            // Mark as ready only once rooted startup has crossed the same
            // classpath-safety gate used for full diagnostics.
            sendPostBuildStartupStatus();
        });
    }

    /**
     * Schedule a debounced build. Multiple rapid calls (e.g. 50 classpath
     * updates) are coalesced into a single build that fires {@value #BUILD_DEBOUNCE_MS}
     * ms after the last call. This prevents N full-builds when N subprojects
     * send their classpaths in quick succession.
     */
    void scheduleDebouncedBuild() {
        java.util.concurrent.ScheduledFuture<?> newFuture =
                initialBuildScheduler.schedule(() -> {
                    GroovyLanguageServerPlugin.logInfo(
                            "[debounced-build] Debounce window elapsed — triggering build.");
                    triggerFullBuild();
                }, BUILD_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        java.util.concurrent.ScheduledFuture<?> prev = debouncedBuildFuture.getAndSet(newFuture);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        GroovyLanguageServerPlugin.logInfo("Shutting down Groovy Language Server...");
        initialBuildScheduler.shutdownNow();
        textDocumentService.shutdown();
        documentManager.dispose();
        pendingClasspathUpdates.clear();
        projectsWithClasspath.clear();
        subprojectPathToEclipseName.clear();
        exitCode = 0; // clean shutdown
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        GroovyLanguageServerPlugin.logInfo("Exit requested. Code=" + exitCode);

        // Signal the application to terminate
        GroovyLanguageServerPlugin plugin = GroovyLanguageServerPlugin.getInstance();
        if (plugin != null) {
            try {
                plugin.getBundle().getBundleContext().getBundle(0).stop();
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Error stopping OSGi framework", e);
            }
        }
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    // ---- Custom requests ----

    /**
     * Handle {@code groovy/resolveSource} request from the client.
     * <p>
     * Called by the VS Code extension's {@code TextDocumentContentProvider}
     * to retrieve source content for {@code groovy-source://} virtual documents.
     *
     * @param params JSON object with a "uri" property
     * @return the source content string
     */
    @JsonRequest("groovy/resolveSource")
    public CompletableFuture<String> resolveSource(JsonObject params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uri = params.get("uri").getAsString();
                GroovyLanguageServerPlugin.logInfo("[resolveSource] Resolving: " + uri);
                String content = org.eclipse.groovy.ls.core.providers.SourceJarHelper
                        .resolveSourceContent(uri);
                if (content != null) {
                    return content;
                }
                return "// Source not available\n";
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("[resolveSource] Failed", e);
                return "// Error resolving source: " + e.getMessage() + "\n";
            }
        });
    }

    /**
     * Handle {@code groovy/classpathUpdate} notification from the client.
     * <p>
     * Called by the VS Code extension when the Red Hat Java extension provides
     * resolved classpath entries for a specific project. Each subproject sends
     * its own classpath to prevent version conflicts across projects.
     *
     * @param params JSON object with "projectUri" (optional), "projectPath" (optional),
     *               "hasJarEntries" (optional) and "entries" array
     */
    @JsonNotification("groovy/classpathUpdate")
    public void classpathUpdate(JsonObject params) {
        try {
            ClasspathUpdateRequest request = parseClasspathUpdateRequest(params);
            if (request == null) {
                GroovyLanguageServerPlugin.logInfo("[classpathUpdate] No entries received.");
                return;
            }

            GroovyLanguageServerPlugin.logInfo(
                "[classpathUpdate] Received " + request.entries.size() + " entries"
                    + (request.projectUri != null
                        ? " for projectUri: " + request.projectUri : " (no project URI)")
                    + (request.projectPath != null ? ", projectPath: " + request.projectPath : "")
                    + ", hasJarEntries=" + request.hasJarEntries);
            if (firstClasspathReceived.compareAndSet(false, true)) {
                startupTracker.mark(
                        "classpath.first-update",
                        request.projectPath != null ? request.projectPath : request.projectUri);
            }

            // Only send "Importing" status once (on the first classpath arrival),
            // not 50 times for 50 subprojects.
            if (!initialBuildDone.get()) {
                sendStatus(STATUS_IMPORTING, "Receiving classpaths...");
            }

            if (!workspaceProjectsReady.get()) {
                pendingClasspathUpdates.add(request);
                GroovyLanguageServerPlugin.logInfo(
                        "[classpathUpdate] Queued until workspace projects are ready: "
                                + (request.projectPath != null ? request.projectPath : request.projectUri));
                if (workspaceProjectsReady.get()) {
                    drainPendingClasspathUpdates();
                }
                return;
            }

            applyClasspathUpdate(request);

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[classpathUpdate] Failed to apply classpath", e);
            sendStatus("Error", "Classpath update failed: " + e.getMessage());
        }
    }

    private void applyClasspathUpdate(ClasspathUpdateRequest request) {
        try {
            IProject eclipseProject = findEclipseProjectFor(request.projectUri, request.projectPath);
            if (eclipseProject == null || !eclipseProject.isOpen()) {
                GroovyLanguageServerPlugin.logInfo(
                "[classpathUpdate] No matching Eclipse project for projectUri="
                    + request.projectUri + ", projectPath=" + request.projectPath);
                return;
            }

            IJavaProject javaProject = JavaCore.create(eclipseProject);
            if (javaProject == null) {
                GroovyLanguageServerPlugin.logInfo("[classpathUpdate] JavaCore.create returned null.");
                return;
            }

            String projName = eclipseProject.getName();
            GroovyLanguageServerPlugin.logInfo(
                    "[classpathUpdate] Updating Eclipse project '" + projName + "'");

            ClasspathComputation classpathComputation =
                    buildUpdatedClasspath(eclipseProject, javaProject.getRawClasspath(), request.entries);
            List<IClasspathEntry> newEntries = classpathComputation.entries;

            javaProject.setRawClasspath(
                    newEntries.toArray(new IClasspathEntry[0]),
                    eclipseProject.getFullPath().append("bin"),
                    new NullProgressMonitor());

            GroovyLanguageServerPlugin.logInfo(
                    "[classpathUpdate] Applied " + classpathComputation.appliedLibraries
                            + " libraries (" + classpathComputation.sourcesAttached
                            + " JARs with source, " + classpathComputation.directoriesAdded
                            + " directories) to '" + projName + "'. Total: "
                            + newEntries.size() + " entries.");

            diagnosticsEnabled = true;
            boolean classpathReady = request.hasJarEntries
                    || classpathComputation.appliedLibraries > 0
                    || projectsWithClasspath.contains(projName);
            if (classpathReady) {
                projectsWithClasspath.add(projName);
                GroovyLanguageServerPlugin.logInfo(
                        "[classpath-check] Added '" + projName + "' to projectsWithClasspath. "
                        + "All projects with classpath: " + projectsWithClasspath);

                publishDiagnosticsForProjectFilesIfStartupReady(projName);
            } else {
                GroovyLanguageServerPlugin.logInfo(
                        "[classpath-check] Deferred full diagnostics for '" + projName
                        + "' because the update contains output directories only.");
            }

            // Retry or re-reconcile working copies for open files in the
            // project whose classpath just became available so semantic tokens
            // can upgrade without waiting for an edit.
            textDocumentService.refreshOpenDocumentsSemanticState(projName);

            triggerBuildAfterClasspathUpdate();

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[classpathUpdate] Failed to apply classpath", e);
            sendStatus("Error", "Classpath update failed: " + e.getMessage());
        }
    }

    private ClasspathUpdateRequest parseClasspathUpdateRequest(JsonObject params) {
        String projectUri = getOptionalJsonString(params, "projectUri");
        String projectPath = getOptionalJsonString(params, "projectPath");

        com.google.gson.JsonArray entriesArray = params.getAsJsonArray("entries");
        if (entriesArray == null || entriesArray.size() == 0) {
            return null;
        }

        List<String> entries = new ArrayList<>();
        for (int i = 0; i < entriesArray.size(); i++) {
            entries.add(entriesArray.get(i).getAsString());
        }
        boolean hasJarEntries = getOptionalJsonBoolean(
                params,
                "hasJarEntries",
                entries.stream().anyMatch(entry -> entry != null
                        && entry.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")));
        return new ClasspathUpdateRequest(projectUri, projectPath, entries, hasJarEntries);
    }

    private String getOptionalJsonString(JsonObject params, String key) {
        if (!params.has(key)) {
            return null;
        }
        return params.get(key).getAsString();
    }

    private boolean getOptionalJsonBoolean(JsonObject params, String key, boolean defaultValue) {
        if (!params.has(key)) {
            return defaultValue;
        }
        try {
            return params.get(key).getAsBoolean();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private ClasspathComputation buildUpdatedClasspath(
            IProject eclipseProject,
            IClasspathEntry[] currentEntries,
            List<String> requestedEntries) {
        List<IClasspathEntry> newEntries = new ArrayList<>();
        recalculatePreservedClasspathEntries(eclipseProject, currentEntries, newEntries);

        ClasspathComputation computation = new ClasspathComputation(newEntries);
        appendLibraryEntries(requestedEntries, newEntries, computation);
        return computation;
    }

    /**
     * Re-detect source entries from the linked folder instead of blindly
     * preserving the ones computed during {@code initialize()}.  At
     * initialization time the Eclipse resource model may not yet have
     * indexed the linked folder's children, causing the fallback source
     * entry (the entire linked root) to be used.  By the time a
     * {@code classpathUpdate} arrives the folder is fully visible, so
     * re-detecting here self-heals any wrong initial source entries.
     * Container entries (JRE) are preserved as-is.
     */
    private void recalculatePreservedClasspathEntries(
            IProject eclipseProject,
            IClasspathEntry[] currentEntries,
            List<IClasspathEntry> target) {
        // Preserve container entries (JRE, etc.)
        for (IClasspathEntry entry : currentEntries) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                target.add(entry);
            }
        }

        // Re-detect source entries from the linked folder
        IFolder linkedRoot = eclipseProject.getFolder(LINKED_FOLDER_NAME);
        if (linkedRoot != null && linkedRoot.exists()) {
            List<IClasspathEntry> freshSourceEntries = createSourceEntries(
                    linkedRoot, DEFAULT_SOURCE_DIR_SUFFIXES,
                    "[classpathUpdate] Re-detected source folder: linked/");
            target.addAll(freshSourceEntries);
        } else {
            // Linked root not available — fall back to preserving existing source entries
            for (IClasspathEntry entry : currentEntries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    target.add(entry);
                }
            }
        }
    }

    private void appendLibraryEntries(
            List<String> requestedEntries,
            List<IClasspathEntry> target,
            ClasspathComputation computation) {
        String javaHomeNorm = normalizePath(System.getProperty("java.home"));
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        for (String entryPath : requestedEntries) {
            if (!seenPaths.add(entryPath)) {
                continue;
            }
            IClasspathEntry classpathEntry = toLibraryEntry(entryPath, javaHomeNorm, computation);
            if (classpathEntry != null) {
                target.add(classpathEntry);
            }
        }
    }

    private IClasspathEntry toLibraryEntry(
            String entryPath,
            String javaHomeNorm,
            ClasspathComputation computation) {
        java.io.File file = new java.io.File(entryPath);
        String entryNorm = normalizePath(entryPath);
        if (shouldSkipClasspathEntry(file, entryNorm, javaHomeNorm)) {
            return null;
        }

        boolean isJar = file.isFile();
        org.eclipse.core.runtime.IPath libraryPath =
                org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());

        org.eclipse.core.runtime.IPath sourceAttachment = null;
        if (isJar) {
            sourceAttachment = resolveSourceAttachment(file);
            if (sourceAttachment != null) {
                computation.sourcesAttached++;
            }
        } else {
            computation.directoriesAdded++;
        }

        computation.appliedLibraries++;
        return JavaCore.newLibraryEntry(libraryPath, sourceAttachment, null);
    }

    private boolean shouldSkipClasspathEntry(java.io.File file, String entryNorm, String javaHomeNorm) {
        if (entryNorm == null || !file.exists()) {
            return true;
        }
        if (javaHomeNorm != null && !javaHomeNorm.isEmpty() && entryNorm.startsWith(javaHomeNorm)) {
            return true;
        }
        boolean isJar = entryNorm.endsWith(".jar/");
        return !isJar && !file.isDirectory();
    }

    private org.eclipse.core.runtime.IPath resolveSourceAttachment(java.io.File binaryJar) {
        java.io.File sourcesJar = org.eclipse.groovy.ls.core.providers.SourceJarHelper
                .findSourcesJarForBinaryJar(binaryJar);
        if (sourcesJar == null) {
            return null;
        }
        return org.eclipse.core.runtime.Path.fromOSString(sourcesJar.getAbsolutePath());
    }

    private void triggerBuildAfterClasspathUpdate() {
        if (!initialBuildDone.get()) {
            initialBuildDone.set(true);
            if (initialBuildTimer != null) {
                initialBuildTimer.cancel(false);
            }
            GroovyLanguageServerPlugin.logInfo(
                    "[classpathUpdate] First classpath received. Will build after debounce.");
        }

        if (workspaceRoot != null) {
            // Debounce: multiple rapid classpath updates (one per subproject)
            // are coalesced into a single build.
            scheduleDebouncedBuild();
        }
    }

    /**
     * Handle {@code groovy/classpathBatchComplete} notification from the client.
     * Sent after all pre-fetched classpaths have been delivered, allowing us to
     * stop waiting for the debounce timer and build immediately.
     */
    @JsonNotification("groovy/classpathBatchComplete")
    public void classpathBatchComplete(JsonObject params) {
        startupTracker.mark("classpath.batch-complete");
        if (!workspaceProjectsReady.get()) {
            pendingClasspathBatchComplete.set(true);
            GroovyLanguageServerPlugin.logInfo(
                    "[classpathBatchComplete] Queued until workspace projects are ready.");
            if (workspaceProjectsReady.get()) {
                drainPendingClasspathUpdates();
            }
            return;
        }
        handleClasspathBatchComplete();
    }

    private void handleClasspathBatchComplete() {
        initialClasspathBatchCompleteReceived = true;
        GroovyLanguageServerPlugin.logInfo(
                "[classpathBatchComplete] All initial classpaths received. Triggering build now.");
        java.util.concurrent.ScheduledFuture<?> prev = debouncedBuildFuture.getAndSet(null);
        if (prev != null) {
            prev.cancel(false);
        }
        if (workspaceRoot != null) {
            triggerFullBuild();
        }
    }

    private void scheduleWorkspaceProjectInitialization() {
        if (workspaceRoot == null) {
            workspaceProjectsReady.set(true);
            return;
        }
        if (!workspaceProjectsInitializationScheduled.compareAndSet(false, true)) {
            return;
        }

        workspaceProjectsReady.set(false);
        startupTracker.mark("workspace.projects.initialize.scheduled");
        initialBuildScheduler.submit(() -> {
            long workspaceInitStartedAt = System.currentTimeMillis();
            try {
                initializeWorkspaceProject();
            } finally {
                startupTracker.duration(
                        "workspace.projects.initialize",
                        workspaceInitStartedAt,
                        subprojectPathToEclipseName.isEmpty()
                                ? "single-project workspace"
                                : subprojectPathToEclipseName.size() + " Eclipse project(s)");
                workspaceProjectsReady.set(true);
                workspaceProjectsInitializationScheduled.set(false);
                drainPendingClasspathUpdates();
            }
        });
    }

    private void drainPendingClasspathUpdates() {
        int drained = 0;
        ClasspathUpdateRequest queuedRequest;
        while ((queuedRequest = pendingClasspathUpdates.poll()) != null) {
            applyClasspathUpdate(queuedRequest);
            drained++;
        }
        if (drained > 0) {
            startupTracker.mark("classpath.queued-updates-drained", drained + " update(s)");
        }
        if (pendingClasspathBatchComplete.compareAndSet(true, false)) {
            startupTracker.mark("classpath.batch-complete.replayed");
            handleClasspathBatchComplete();
        }
    }

    private static final class ClasspathUpdateRequest {
        private final String projectUri;
        private final String projectPath;
        private final List<String> entries;
        private final boolean hasJarEntries;

        private ClasspathUpdateRequest(
                String projectUri,
                String projectPath,
                List<String> entries,
                boolean hasJarEntries) {
            this.projectUri = projectUri;
            this.projectPath = projectPath;
            this.entries = entries;
            this.hasJarEntries = hasJarEntries;
        }
    }

    private static final class ClasspathComputation {
        private final List<IClasspathEntry> entries;
        private int appliedLibraries;
        private int sourcesAttached;
        private int directoriesAdded;

        private ClasspathComputation(List<IClasspathEntry> entries) {
            this.entries = entries;
        }
    }

    /**
     * Find the Eclipse project that corresponds to a Java extension project URI.
     * <p>
     * Matches the path/URI to one of the per-subproject Eclipse projects created
     * during initialization, falling back to "GroovyProject" for single-project
     * workspaces.
     */
    private IProject findEclipseProjectFor(String projectUri, String projectPath) {
        if (subprojectPathToEclipseName.isEmpty()) {
            return fallbackGroovyProject();
        }

        IProject byPath = findEclipseProjectByPath(projectPath);
        if (byPath != null) {
            return byPath;
        }

        IProject byUri = resolveProjectFromUri(projectUri);
        if (byUri != null) {
            return byUri;
        }

        return fallbackGroovyProject();
    }

    private IProject resolveProjectFromUri(String projectUri) {
        java.io.File uriFile = toFile(projectUri);
        if (uriFile == null) {
            return null;
        }

        try {
            IProject byUriPath = findEclipseProjectByPath(uriFile.getAbsolutePath());
            if (byUriPath != null) {
                return byUriPath;
            }

            java.io.File projectDir = uriFile.isDirectory() ? uriFile : uriFile.getParentFile();
            if (projectDir == null) {
                return null;
            }

            IProject byName = findProjectByDirectoryName(projectDir);
            if (byName != null) {
                return byName;
            }

            return createProjectForDirectoryIfNeeded(projectDir);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[classpathUpdate] Failed to resolve project for projectUri="
                    + projectUri, e);
            return null;
        }
    }

    private IProject findProjectByDirectoryName(java.io.File projectDir) {
        String dirName = projectDir.getName().toLowerCase();
        for (var entry : subprojectPathToEclipseName.entrySet()) {
            if (!entry.getKey().endsWith("/" + dirName + "/")) {
                continue;
            }
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(entry.getValue());
            if (project.exists() && project.isOpen()) {
                return project;
            }
        }
        return null;
    }

    private IProject createProjectForDirectoryIfNeeded(java.io.File projectDir) throws CoreException {
        if (!projectDir.isDirectory()) {
            return null;
        }

        // Check if DocumentManager already imported this path as an ExtGroovy_ project.
        // If so, reuse it instead of creating a duplicate Groovy_ project.
        // This ensures the classpath is applied to the project that already holds
        // working copies for files in this directory.
        String dirName = projectDir.getName();
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        String extProjectName = "ExtGroovy_" + dirName;
        IProject extProject = workspace.getRoot().getProject(extProjectName);
        if (extProject.exists() && extProject.isOpen()) {
            // Verify it really points to the same directory
            IPath extLocation = extProject.getLocation();
            if (extLocation != null) {
                String extPath = normalizePath(extLocation.toOSString());
                String targetPath = normalizePath(projectDir.getAbsolutePath());
                if (extPath != null && extPath.equals(targetPath)) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[classpathUpdate] Reusing existing '" + extProjectName
                            + "' for: " + projectDir);
                    putSubprojectMapping(projectDir.getAbsolutePath(), extProjectName);
                    return extProject;
                }
            }
        }

        GroovyLanguageServerPlugin.logInfo(
                "[classpathUpdate] Creating on-the-fly project for: " + projectDir);
        createEclipseProjectFor(projectDir, DEFAULT_SOURCE_DIR_SUFFIXES);
        return findEclipseProjectByPath(projectDir.getAbsolutePath());
    }

    private IProject fallbackGroovyProject() {
        IProject groovy = ResourcesPlugin.getWorkspace().getRoot().getProject(GROOVY_PROJECT_NAME);
        return (groovy.exists() && groovy.isOpen()) ? groovy : null;
    }

    private IProject findEclipseProjectByPath(String filesystemPath) {
        if (filesystemPath == null || filesystemPath.isBlank()) {
            return null;
        }

        String normalizedPath = normalizePath(filesystemPath);
        if (normalizedPath == null) {
            return null;
        }

        // Exact path match first
        String directName = subprojectPathToEclipseName.get(normalizedPath);
        if (directName != null) {
            IProject direct = ResourcesPlugin.getWorkspace().getRoot().getProject(directName);
            if (direct.exists() && direct.isOpen()) {
                return direct;
            }
        }

        // Longest-prefix match (handles source-file paths under a project root)
        String bestProjectName = null;
        int bestPrefixLength = -1;
        for (var entry : subprojectPathToEclipseName.entrySet()) {
            String projectRoot = entry.getKey();
            if (normalizedPath.startsWith(projectRoot) && projectRoot.length() > bestPrefixLength) {
                bestPrefixLength = projectRoot.length();
                bestProjectName = entry.getValue();
            }
        }

        if (bestProjectName != null) {
            IProject best = ResourcesPlugin.getWorkspace().getRoot().getProject(bestProjectName);
            if (best.exists() && best.isOpen()) {
                return best;
            }
        }

        return null;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.replace('\\', '/').toLowerCase();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    /**
     * Returns the text document service cast to our implementation type.
     * Used internally by the workspace service for cross-service configuration.
     */
    GroovyTextDocumentService getGroovyTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        this.textDocumentService.connect(client);
        sendStatus(STATUS_STARTING, "Initializing...");
    }

    public LanguageClient getClient() {
        return client;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public boolean areDiagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    /**
     * Returns {@code true} while a workspace build is in progress.
     * Callers should avoid blocking JDT operations (reconcile, search)
     * during this time to prevent thread pool exhaustion.
     */
    public boolean isBuildInProgress() {
        return buildInProgress;
    }

    /**
     * Returns {@code true} once the first workspace build has completed
     * (meaning all classpath updates have had a chance to arrive and JDT
     * indexes are populated).  Before this point, missing-classpath
     * diagnostics are expected and should not be shown to the user.
     */
    public boolean isFirstBuildComplete() {
        return firstFullBuildComplete;
    }

    /**
     * Returns {@code true} once the first rooted-workspace build has actually
     * started. This stays {@code false} while startup is still waiting for the
     * initial classpath batch/debounce and flips to {@code true} even if that
     * first build later fails.
     */
    boolean isInitialBuildStarted() {
        return workspaceRoot != null && initialBuildStarted;
    }

    /**
     * Returns {@code true} once the first rooted-workspace build has completed
     * successfully. No-root sessions return {@code false} here and rely on
     * per-project classpath readiness instead.
     */
    boolean isInitialBuildSettled() {
        return workspaceRoot != null && initialBuildSettled;
    }

    boolean isDelegatedClasspathStartupExpected() {
        return workspaceRoot != null && delegatedClasspathStartupExpected;
    }

    /**
     * Read optional tuning parameters from the client's
     * {@code initializationOptions} JSON and reconfigure the LSP request
     * executor if custom values are provided.
     * <p>
     * Recognised keys (all optional):
     * <ul>
     *   <li>{@code lspRequestPoolSize} — max threads for interactive LSP handlers (default: {@code max(4, cpus)})</li>
     *   <li>{@code lspRequestQueueSize} — bounded queue for the fast pool (default: 64)</li>
     *   <li>{@code lspBackgroundPoolSize} — max threads for background/decorative handlers (default: {@code max(3, cpus-1)})</li>
     *   <li>{@code lspBackgroundQueueSize} — bounded queue for the background pool (default: 128)</li>
     * </ul>
     */
    private void applyInitializationOptions(Object initializationOptions) {
        if (initializationOptions == null) {
            return;
        }
        try {
            JsonObject opts;
            if (initializationOptions instanceof JsonObject) {
                opts = (JsonObject) initializationOptions;
            } else if (initializationOptions instanceof com.google.gson.JsonElement) {
                opts = ((com.google.gson.JsonElement) initializationOptions).getAsJsonObject();
            } else {
                return;
            }

            int poolSize = -1;
            int queueSize = -1;

            if (opts.has("lspRequestPoolSize") && opts.get("lspRequestPoolSize").isJsonPrimitive()) {
                poolSize = opts.get("lspRequestPoolSize").getAsInt();
            }
            if (opts.has("lspRequestQueueSize") && opts.get("lspRequestQueueSize").isJsonPrimitive()) {
                queueSize = opts.get("lspRequestQueueSize").getAsInt();
            }

            if (poolSize > 0 || queueSize > 0) {
                int cpus = Runtime.getRuntime().availableProcessors();
                int defaultPoolSize = Math.max(4, cpus);
                int effectivePool = poolSize > 0 ? poolSize : defaultPoolSize;
                int effectiveQueue = queueSize > 0 ? queueSize : 64;
                textDocumentService.configureRequestPool(effectivePool, effectiveQueue);
            }

            // Background pool configuration
            int bgPoolSize = -1;
            int bgQueueSize = -1;

            if (opts.has("lspBackgroundPoolSize") && opts.get("lspBackgroundPoolSize").isJsonPrimitive()) {
                bgPoolSize = opts.get("lspBackgroundPoolSize").getAsInt();
            }
            if (opts.has("lspBackgroundQueueSize") && opts.get("lspBackgroundQueueSize").isJsonPrimitive()) {
                bgQueueSize = opts.get("lspBackgroundQueueSize").getAsInt();
            }

            if (bgPoolSize > 0 || bgQueueSize > 0) {
                int cpus = Runtime.getRuntime().availableProcessors();
                int defaultBgPool = Math.max(3, cpus - 1);
                int effectiveBgPool = bgPoolSize > 0 ? bgPoolSize : defaultBgPool;
                int effectiveBgQueue = bgQueueSize > 0 ? bgQueueSize : 128;
                textDocumentService.configureBackgroundPool(effectiveBgPool, effectiveBgQueue);
            }

            if (opts.has("delegatedClasspathStartup")
                    && opts.get("delegatedClasspathStartup").isJsonPrimitive()) {
                delegatedClasspathStartupExpected = opts.get("delegatedClasspathStartup").getAsBoolean();
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to parse initializationOptions", e);
        }
    }

    private void settleInitialBuildIfReady() {
        if (initialBuildSettled || workspaceRoot == null || !firstFullBuildComplete) {
            return;
        }
        if (delegatedClasspathStartupExpected && !initialClasspathBatchCompleteReceived) {
            return;
        }
        initialBuildSettled = true;
    }

    private void sendPostBuildStartupStatus() {
        if (workspaceRoot != null
                && delegatedClasspathStartupExpected
                && !initialBuildSettled) {
            sendStatus(STATUS_IMPORTING, "Finalizing classpath...");
            return;
        }
        sendStatus(STATUS_READY, null);
    }

    /**
     * Immediately (debounced per-file) publish diagnostics for any currently-open
     * documents that belong to the given Eclipse project name. This provides
     * near-instant feedback when a project's classpath becomes available,
     * without waiting for the full workspace build.
     * <p>
     * As a safety net, files whose project could not be resolved are also
     * re-published — this covers edge cases where the URI→project mapping
     * fails (e.g., special characters, external projects, or race conditions
     * during initialization).
     */
    void publishDiagnosticsForProjectFilesIfStartupReady(String projectName) {
        if (workspaceRoot != null && !initialBuildSettled) {
            GroovyLanguageServerPlugin.logInfo(
                    "[eager-diag] Deferred eager diagnostics for " + projectName
                    + " until startup build settles.");
            return;
        }
        publishDiagnosticsForProjectFiles(projectName);
    }

    void publishDiagnosticsForProjectFiles(String projectName) {
        initialBuildScheduler.submit(() -> {
            try {
                for (String uri : documentManager.getOpenDocumentUris()) {
                    String ownerProject = getProjectNameForUri(
                            documentManager.getClientUri(uri));
                    if (projectName.equals(ownerProject) || ownerProject == null) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[eager-diag] Publishing diagnostics for " + uri
                                + " (project " + projectName
                                + ", resolved=" + ownerProject + ")");
                        textDocumentService.publishDiagnosticsIfEnabled(uri);
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                        "[eager-diag] Failed to publish early diagnostics for project "
                        + projectName, e);
            }
        });
    }

    /**
     * Returns {@code true} if the given Eclipse project has received at least
     * one classpath update from the client.
     */
    public boolean hasClasspathForProject(String projectName) {
        return projectName != null && projectsWithClasspath.contains(projectName);
    }

    /**
     * Returns the Eclipse project name that owns the given document URI,
     * or {@code null} if none is found.
     */
    public String getProjectNameForUri(String uri) {
        java.io.File file = toFile(uri);
        if (file == null) {
            return null;
        }
        try {
            String fsPath = file.getAbsolutePath();
            IProject project = findEclipseProjectByPath(fsPath);
            String name = project != null ? project.getName() : null;
            GroovyLanguageServerPlugin.logInfo(
                    "[classpath-check] getProjectNameForUri uri=" + uri
                    + " fsPath=" + fsPath
                    + " project=" + name
                    + " hasClasspath=" + (name != null && projectsWithClasspath.contains(name))
                    + " projectsWithClasspath=" + projectsWithClasspath);
            return name;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[classpath-check] getProjectNameForUri EXCEPTION for uri=" + uri
                    + " error=" + e.getMessage());
            return null;
        }
    }

    // ---- Private helpers ----

    /**
     * Initialize Eclipse workspace projects.
     * <p>
     * In a multi-project workspace (e.g., Gradle multi-module), this creates
     * <b>separate</b> Eclipse JDT projects for each subproject so that each
     * has its own isolated classpath — preventing conflicting library versions
     * from one subproject leaking into another.
     * <p>
     * For single-project workspaces, a single "GroovyProject" is created at
     * the workspace root (the original behaviour).
     */
    private void initializeWorkspaceProject() {
        try {
            cleanupStaleProjects();

            java.io.File workspaceDir = resolveWorkspaceDirectory();
            if (workspaceDir == null) {
                return;
            }

            List<java.io.File> subprojects =
                    findSubprojectsWithSources(workspaceDir, DEFAULT_SOURCE_DIR_SUFFIXES);
            if (subprojects.isEmpty()) {
                createSingleRootProject(workspaceDir, DEFAULT_SOURCE_DIR_SUFFIXES);
                return;
            }

            initializeMultiProjectWorkspace(workspaceDir, subprojects);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to initialize workspace project", e);
        }
    }

    private java.io.File resolveWorkspaceDirectory() {
        return toFile(workspaceRoot);
    }

    private java.io.File toFile(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equals(parsed.getScheme())) {
                return null;
            }
            return new java.io.File(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    private void initializeMultiProjectWorkspace(
            java.io.File workspaceDir,
            List<java.io.File> subprojects) {
        GroovyLanguageServerPlugin.logInfo(
                "[workspace] Found " + subprojects.size() + " subproject(s). "
                        + "Creating isolated Eclipse projects for classpath separation.");

        for (java.io.File subDir : subprojects) {
            createSubprojectSafely(subDir);
        }

        if (subprojectPathToEclipseName.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[workspace] Multi-project setup failed. Falling back to single root project.");
            createSingleRootProject(workspaceDir, DEFAULT_SOURCE_DIR_SUFFIXES);
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[workspace] Created " + subprojectPathToEclipseName.size()
                        + " Eclipse project(s): " + subprojectPathToEclipseName.values());
    }

    private void createSubprojectSafely(java.io.File subDir) {
        try {
            createEclipseProjectFor(subDir, DEFAULT_SOURCE_DIR_SUFFIXES);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[workspace] Skipped " + subDir.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Create a separate Eclipse JDT project for a subproject directory.
     * <p>
     * Uses <b>linked folders</b> instead of {@code setLocation()} to avoid
     * Eclipse's restriction against nested/overlapping project locations.
     * The project is created in the Eclipse metadata area ({@code -data} dir)
     * and a linked folder ({@code "linked"}) points to the actual subproject
     * directory on disk. Source entries reference paths under the linked folder
     * (e.g. {@code /Groovy_sample-project/linked/src/main/groovy}).
     * <p>
     * {@code findFilesForLocationURI()} resolves through linked resources,
     * so file lookups from document URIs work transparently.
     */
    private void createEclipseProjectFor(java.io.File subDir, String[] srcDirSuffixes)
            throws CoreException {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        String baseName = subDir.getName();
        String projectName = GROOVY_PROJECT_PREFIX + baseName;

        int counter = 1;
        while (workspace.getRoot().getProject(projectName).exists()) {
            projectName = GROOVY_PROJECT_PREFIX + baseName + "_" + counter++;
        }

        IProject project = workspace.getRoot().getProject(projectName);
        IProjectDescription description = workspace.newProjectDescription(projectName);
        description.setNatureIds(new String[]{
                JavaCore.NATURE_ID,
            GROOVY_NATURE_ID
        });

        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        IFolder linkedRoot = project.getFolder(LINKED_FOLDER_NAME);
        linkedRoot.createLink(
                org.eclipse.core.runtime.Path.fromOSString(subDir.getAbsolutePath()),
                IResource.ALLOW_MISSING_LOCAL,
                new NullProgressMonitor());
        applyLinkedResourceFilter(linkedRoot, "[" + projectName + "]");

        GroovyLanguageServerPlugin.logInfo(
                "[" + projectName + "] Linked folder → " + subDir.getAbsolutePath());

        // Ensure the resource model sees the linked folder's children
        // before we probe for source directories.  The createFilter() call
        // above uses BACKGROUND_REFRESH, so without this the nested folders
        // may not be visible yet.
        linkedRoot.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        // Configure source folders (relative to the linked root)
        IJavaProject javaProject = JavaCore.create(project);
        List<IClasspathEntry> entries = createSourceEntries(linkedRoot, srcDirSuffixes,
                "[" + projectName + "] Source folder: linked/");
        entries.add(JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path(JRE_CONTAINER_ID)));

        javaProject.setRawClasspath(
                entries.toArray(new IClasspathEntry[0]),
                project.getFullPath().append("bin"),
                new NullProgressMonitor());

        // Store mapping: normalized filesystem path → Eclipse project name
        putSubprojectMapping(subDir.getAbsolutePath(), projectName);

        GroovyLanguageServerPlugin.logInfo(
                "[workspace] Created '" + projectName + "' (linked folder) at "
                + subDir.getAbsolutePath() + " with " + entries.size() + " classpath entries.");
    }

    /**
     * Fall back to creating a single "GroovyProject" at the workspace root.
     * Used for single-project workspaces or when multi-project creation fails.
     */
    private void createSingleRootProject(java.io.File workspaceDir, String[] srcDirSuffixes) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = ensureGroovyRootProject(workspace);
            IFolder linkedRoot = ensureLinkedRoot(project, workspaceDir, "[GroovyProject]");
            GroovyLanguageServerPlugin.logInfo(
                    "[GroovyProject] Linked folder → " + workspaceDir.getAbsolutePath());

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                GroovyLanguageServerPlugin.logError("[classpath] JavaCore.create returned null", null);
                return;
            }

            // Synchronise the resource model before probing source dirs
            linkedRoot.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

            List<IClasspathEntry> entries = createSourceEntries(
                    linkedRoot, srcDirSuffixes, "[classpath] Added source folder: linked/");
            entries.add(JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path(JRE_CONTAINER_ID)));
            putSubprojectMapping(workspaceDir.getAbsolutePath(), GROOVY_PROJECT_NAME);

            GroovyLanguageServerPlugin.logInfo(
                    "[classpath] Initial classpath with " + entries.size()
                    + " entries (source folders + JRE). Waiting for classpath from Java extension...");

            javaProject.setRawClasspath(
                    entries.toArray(new IClasspathEntry[0]),
                    project.getFullPath().append("bin"),
                    new NullProgressMonitor());

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[classpath] Failed to configure classpath (non-fatal): "
                    + e.getMessage(), e);
        }
    }

    private IProject ensureGroovyRootProject(IWorkspace workspace) throws CoreException {
        IProject project = workspace.getRoot().getProject(GROOVY_PROJECT_NAME);
        if (!project.exists()) {
            IProjectDescription description = workspace.newProjectDescription(GROOVY_PROJECT_NAME);
            description.setNatureIds(new String[] { JavaCore.NATURE_ID, GROOVY_NATURE_ID });
            project.create(description, new NullProgressMonitor());
            project.open(new NullProgressMonitor());
            GroovyLanguageServerPlugin.logInfo("Created Eclipse project 'GroovyProject' (linked folder).");
            return project;
        }

        if (!project.isOpen()) {
            project.open(new NullProgressMonitor());
        }
        return project;
    }

    private IFolder ensureLinkedRoot(IProject project, java.io.File dir, String projectTag)
            throws CoreException {
        IFolder linkedRoot = project.getFolder(LINKED_FOLDER_NAME);
        if (!linkedRoot.exists()) {
            linkedRoot.createLink(
                    org.eclipse.core.runtime.Path.fromOSString(dir.getAbsolutePath()),
                    IResource.ALLOW_MISSING_LOCAL,
                    new NullProgressMonitor());
            applyLinkedResourceFilter(linkedRoot, projectTag);
        }
        return linkedRoot;
    }

    private void applyLinkedResourceFilter(IFolder linkedRoot, String projectTag) {
        try {
            linkedRoot.createFilter(
                    IResourceFilterDescription.EXCLUDE_ALL
                            | IResourceFilterDescription.FOLDERS
                            | IResourceFilterDescription.INHERITABLE,
                    new FileInfoMatcherDescription(
                            "org.eclipse.core.resources.regexFilterMatcher",
                            RESOURCE_FILTER_PATTERN),
                    IResource.BACKGROUND_REFRESH,
                    new NullProgressMonitor());
        } catch (CoreException e) {
            GroovyLanguageServerPlugin.logError(
                    projectTag + " Failed to set resource filter (non-fatal)", e);
        }
    }

    private List<IClasspathEntry> createSourceEntries(
            IFolder linkedRoot,
            String[] srcDirSuffixes,
            String logPrefix) {
        List<IClasspathEntry> entries = new ArrayList<>();
        boolean configuredSourceFound = addConfiguredSourceEntries(linkedRoot, srcDirSuffixes, logPrefix, entries);
        if (!configuredSourceFound) {
            addFallbackSourceEntry(linkedRoot, entries);
        }
        return entries;
    }

    private boolean addConfiguredSourceEntries(
            IFolder linkedRoot,
            String[] srcDirSuffixes,
            String logPrefix,
            List<IClasspathEntry> entries) {
        boolean found = false;
        for (String srcDir : srcDirSuffixes) {
            IFolder folder = linkedRoot.getFolder(srcDir);
            if (folder != null && existsOnFilesystem(folder)) {
                entries.add(JavaCore.newSourceEntry(folder.getFullPath()));
                GroovyLanguageServerPlugin.logInfo(logPrefix + srcDir);
                found = true;
            }
        }
        return found;
    }

    private void addFallbackSourceEntry(IFolder linkedRoot, List<IClasspathEntry> entries) {
        IFolder srcFolder = linkedRoot.getFolder("src");
        if (srcFolder != null && existsOnFilesystem(srcFolder)) {
            entries.add(JavaCore.newSourceEntry(srcFolder.getFullPath()));
            return;
        }
        entries.add(JavaCore.newSourceEntry(linkedRoot.getFullPath()));
    }

    /**
     * Check whether a folder exists on the real filesystem, bypassing the
     * Eclipse resource model.  Shortly after a linked folder is created the
     * resource model may not have discovered its children yet (especially
     * with {@code BACKGROUND_REFRESH}), so {@code folder.exists()} can
     * return {@code false} for directories that are actually present.
     * Falling back to the filesystem avoids misconfiguring source entries.
     */
    private static boolean existsOnFilesystem(IFolder folder) {
        org.eclipse.core.runtime.IPath location = folder.getLocation();
        return location != null && location.toFile().isDirectory();
    }

    private void putSubprojectMapping(String filesystemPath, String projectName) {
        String normalizedPath = normalizePath(filesystemPath);
        if (normalizedPath != null) {
            subprojectPathToEclipseName.put(normalizedPath, projectName);
        }
    }

    /**
     * Delete any stale Eclipse projects from previous runs.
     * Projects named "Groovy_*", "ExtGroovy_*", or "GroovyProject" are removed
     * so that fresh linked-folder projects can be created cleanly.
     */
    private void cleanupStaleProjects() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject p : projects) {
                String name = p.getName();
                if (name.startsWith(GROOVY_PROJECT_PREFIX)
                        || name.startsWith(EXTERNAL_GROOVY_PROJECT_PREFIX)
                        || name.equals(GROOVY_PROJECT_NAME)) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[workspace] Deleting stale project: " + name);
                    // IMPORTANT: first arg=false means do NOT delete disk contents.
                    // GroovyProject uses setLocation() pointing at the user's real folder,
                    // so delete(true,...) would wipe the user's files!
                    p.delete(false, true, new NullProgressMonitor());
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[workspace] Failed to clean up stale projects: " + e.getMessage(), e);
        }
    }

    /**
     * Find subdirectories that contain source folders (up to 5 levels deep).
     * Depth 5 covers patterns like {@code root/modules/group/subgroup/project}.
     */
    private List<java.io.File> findSubprojectsWithSources(java.io.File root, String[] srcDirSuffixes) {
        List<java.io.File> result = new ArrayList<>();
        scanForSubprojects(root, result, srcDirSuffixes, 0, 5);
        return result;
    }

    private void scanForSubprojects(java.io.File dir, List<java.io.File> result,
                                     String[] srcDirSuffixes, int depth, int maxDepth) {
        if (depth >= maxDepth || dir == null || !dir.isDirectory()) return;

        // Skip symbolic links to prevent infinite loops from circular symlinks
        try {
            if (java.nio.file.Files.isSymbolicLink(dir.toPath())) return;
        } catch (Exception ignored) {
            // If we can't check, skip to be safe
            return;
        }

        java.io.File[] children = dir.listFiles();
        if (children == null) return;

        for (java.io.File child : children) {
            String childName = child.getName();
            if (!child.isDirectory() || childName.startsWith(".")
                    || childName.equals("build") || childName.equals("target")
                    || childName.equals("node_modules") || childName.equals("dist")
                    || childName.equals("bin") || childName.equals("out")) {
                continue;
            }
            // Check if any source dir suffix exists
            for (String suffix : srcDirSuffixes) {
                if (new java.io.File(child, suffix).isDirectory()) {
                    result.add(child);
                    break;
                }
            }
            scanForSubprojects(child, result, srcDirSuffixes, depth + 1, maxDepth);
        }
    }
}
