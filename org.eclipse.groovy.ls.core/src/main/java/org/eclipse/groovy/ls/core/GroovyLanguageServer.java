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

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
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

    private LanguageClient client;
    private final GroovyTextDocumentService textDocumentService;
    private final GroovyWorkspaceService workspaceService;
    private final DocumentManager documentManager;

    private String workspaceRoot;
    private int exitCode = 1; // non-zero until clean shutdown
    private Endpoint remoteEndpoint;

    /**
     * Tracks whether the initial classpath has been received from the client.
     * The initial full build is deferred until the classpath arrives, to prevent
     * showing spurious errors while types are still unresolved.
     */
    private volatile boolean initialClasspathReceived = false;
    private volatile boolean initialBuildDone = false;
    private volatile boolean diagnosticsEnabled = false;
    private java.util.concurrent.ScheduledFuture<?> initialBuildTimer;

    /**
     * Maps normalized filesystem paths (lowercase, forward slashes, trailing slash)
     * to Eclipse project names. Used for per-subproject classpath isolation.
     */
    private final java.util.Map<String, String> subprojectPathToEclipseName
            = new java.util.LinkedHashMap<>();

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
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to send status notification", e);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return CompletableFuture.supplyAsync(() -> {
            GroovyLanguageServerPlugin.logInfo("Initializing Groovy Language Server...");

            // Capture workspace root
            if (params.getRootUri() != null) {
                workspaceRoot = params.getRootUri();
            } else if (params.getRootPath() != null) {
                workspaceRoot = URI.create("file:///" + params.getRootPath().replace("\\", "/")).toString();
            }

            if (workspaceRoot != null) {
                GroovyLanguageServerPlugin.logInfo("Workspace root: " + workspaceRoot);
                sendStatus("Importing", "Configuring workspace...");
                initializeWorkspaceProject();
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

            // Document symbols
            capabilities.setDocumentSymbolProvider(true);

            // Workspace symbols
            capabilities.setWorkspaceSymbolProvider(true);

            // Rename
            capabilities.setRenameProvider(true);

            // Code actions (quick fix, organize imports)
            CodeActionOptions codeActionOptions = new CodeActionOptions();
            codeActionOptions.setCodeActionKinds(java.util.Arrays.asList(
                    CodeActionKind.QuickFix,
                    CodeActionKind.Source,
                    CodeActionKind.SourceOrganizeImports,
                    org.eclipse.groovy.ls.core.providers.CodeActionProvider.SOURCE_KIND_ADD_MISSING_IMPORTS,
                    org.eclipse.groovy.ls.core.providers.CodeActionProvider.SOURCE_KIND_REMOVE_UNUSED_IMPORTS));
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

            // Document formatting (Eclipse JDT formatter with XML profile support)
            capabilities.setDocumentFormattingProvider(true);
            capabilities.setDocumentRangeFormattingProvider(true);

            GroovyLanguageServerPlugin.logInfo("Groovy Language Server initialized with capabilities.");

            return new InitializeResult(capabilities);
        });
    }

    @Override
    public void initialized(org.eclipse.lsp4j.InitializedParams params) {
        GroovyLanguageServerPlugin.logInfo("Client confirmed initialization. Server is fully operational.");

        // Defer the initial build to give the client time to send the classpathUpdate.
        // This prevents flashing errors while the classpath is still being resolved.
        // A fallback timer ensures the build happens even if no classpath arrives.
        if (workspaceRoot != null) {
            sendStatus("Importing", "Waiting for classpath...");
            try {
                java.util.concurrent.ScheduledExecutorService scheduler =
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread t = new Thread(r, "initial-build-timer");
                            t.setDaemon(true);
                            return t;
                        });
                initialBuildTimer = scheduler.schedule(() -> {
                    if (!initialBuildDone) {
                        GroovyLanguageServerPlugin.logInfo(
                                "Initial build timer fired (no classpath received). Building now.");
                        diagnosticsEnabled = true;
                        initialBuildDone = true;
                        sendStatus("Compiling", "Building workspace...");
                        textDocumentService.triggerFullBuild(this);
                    }
                }, 10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Fallback: build immediately if scheduling fails
                GroovyLanguageServerPlugin.logError("Failed to schedule deferred build", e);
                diagnosticsEnabled = true;
                sendStatus("Compiling", "Building workspace...");
                textDocumentService.triggerFullBuild(this);
                initialBuildDone = true;
            }
        } else {
            sendStatus("Ready", null);
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        GroovyLanguageServerPlugin.logInfo("Shutting down Groovy Language Server...");
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
     * @param params JSON object with "projectUri" (optional) and "entries" array
     */
    @JsonNotification("groovy/classpathUpdate")
    public void classpathUpdate(JsonObject params) {
        try {
            String projectUri = params.has("projectUri")
                    ? params.get("projectUri").getAsString() : null;

            com.google.gson.JsonArray entriesArray = params.getAsJsonArray("entries");
            if (entriesArray == null || entriesArray.size() == 0) {
                GroovyLanguageServerPlugin.logInfo("[classpathUpdate] No entries received.");
                return;
            }

            List<String> entries = new ArrayList<>();
            for (int i = 0; i < entriesArray.size(); i++) {
                entries.add(entriesArray.get(i).getAsString());
            }

            GroovyLanguageServerPlugin.logInfo(
                    "[classpathUpdate] Received " + entries.size() + " entries"
                    + (projectUri != null ? " for project: " + projectUri : " (no project URI)"));

            sendStatus("Importing", "Updating classpath...");

            // Find the matching Eclipse project for this projectUri
            IProject eclipseProject = findEclipseProjectForUri(projectUri);
            if (eclipseProject == null || !eclipseProject.isOpen()) {
                GroovyLanguageServerPlugin.logInfo(
                        "[classpathUpdate] No matching Eclipse project for: " + projectUri);
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

            // Preserve existing source entries and JRE container
            IClasspathEntry[] currentEntries = javaProject.getRawClasspath();
            List<IClasspathEntry> newEntries = new ArrayList<>();

            for (IClasspathEntry entry : currentEntries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                        || entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                    newEntries.add(entry);
                }
                // Skip existing library entries — we're replacing them
            }

            // Add the new library entries from the Java extension
            String javaHome = System.getProperty("java.home");
            String javaHomeNorm = javaHome != null
                    ? javaHome.replace('\\', '/').toLowerCase() : "";

            java.util.Set<String> seenPaths = new java.util.HashSet<>();
            int sourcesAttached = 0;
            int skipped = 0;
            for (String entryPath : entries) {
                if (!seenPaths.add(entryPath)) continue;

                if (!entryPath.endsWith(".jar")) {
                    skipped++;
                    continue;
                }

                String entryNorm = entryPath.replace('\\', '/').toLowerCase();
                if (!javaHomeNorm.isEmpty() && entryNorm.startsWith(javaHomeNorm)) {
                    skipped++;
                    continue;
                }

                java.io.File file = new java.io.File(entryPath);
                if (!file.exists()) continue;

                org.eclipse.core.runtime.IPath jarPath =
                        org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());

                org.eclipse.core.runtime.IPath sourceAttachment = null;
                java.io.File sourcesJar = org.eclipse.groovy.ls.core.providers
                        .SourceJarHelper.findSourcesJarForBinaryJar(file);
                if (sourcesJar != null) {
                    sourceAttachment = org.eclipse.core.runtime.Path
                            .fromOSString(sourcesJar.getAbsolutePath());
                    sourcesAttached++;
                }

                newEntries.add(JavaCore.newLibraryEntry(jarPath, sourceAttachment, null));
            }

            javaProject.setRawClasspath(
                    newEntries.toArray(new IClasspathEntry[0]),
                    eclipseProject.getFullPath().append("bin"),
                    new NullProgressMonitor());

            GroovyLanguageServerPlugin.logInfo(
                    "[classpathUpdate] Applied " + (seenPaths.size() - skipped) + " libraries ("
                    + sourcesAttached + " with source) to '" + projName + "'. Total: "
                    + newEntries.size() + " entries.");

            sendStatus("Ready", null);
            diagnosticsEnabled = true;

            // If this is the first classpath update, trigger the initial build
            // that was deferred from initialized(). Cancel the fallback timer.
            if (!initialBuildDone) {
                initialBuildDone = true;
                initialClasspathReceived = true;
                if (initialBuildTimer != null) {
                    initialBuildTimer.cancel(false);
                }
                GroovyLanguageServerPlugin.logInfo(
                        "[classpathUpdate] First classpath received. Triggering initial build.");
            }

            // Re-trigger diagnostics with new classpath
            if (workspaceRoot != null) {
                textDocumentService.triggerFullBuild(this);
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[classpathUpdate] Failed to apply classpath", e);
            sendStatus("Error", "Classpath update failed: " + e.getMessage());
        }
    }

    /**
     * Find the Eclipse project that corresponds to a Java extension project URI.
     * <p>
     * Matches the URI to one of the per-subproject Eclipse projects created during
     * initialization, falling back to "GroovyProject" for single-project workspaces.
     */
    private IProject findEclipseProjectForUri(String projectUri) {
        if (projectUri == null || subprojectPathToEclipseName.isEmpty()) {
            // Single-project mode or backward compat
            IProject groovy = ResourcesPlugin.getWorkspace().getRoot().getProject("GroovyProject");
            return (groovy.exists() && groovy.isOpen()) ? groovy : null;
        }

        try {
            java.io.File projDir = new java.io.File(URI.create(projectUri));
            String normPath = projDir.getAbsolutePath().replace('\\', '/').toLowerCase();
            if (!normPath.endsWith("/")) normPath += "/";

            // Exact path match
            String name = subprojectPathToEclipseName.get(normPath);
            if (name != null) {
                IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
                if (p.exists() && p.isOpen()) return p;
            }

            // Directory name match (handles different URI encodings)
            String dirName = projDir.getName().toLowerCase();
            for (var entry : subprojectPathToEclipseName.entrySet()) {
                if (entry.getKey().endsWith("/" + dirName + "/")) {
                    IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(entry.getValue());
                    if (p.exists() && p.isOpen()) return p;
                }
            }

            // On-the-fly creation for late-discovered projects
            if (projDir.isDirectory()) {
                GroovyLanguageServerPlugin.logInfo(
                        "[classpathUpdate] Creating on-the-fly project for: " + projectUri);
                String[] srcDirs = {
                    "src/main/java", "src/main/groovy",
                    "src/test/java", "src/test/groovy",
                    "src/main/resources", "src/test/resources",
                };
                createEclipseProjectFor(projDir, srcDirs);
                name = subprojectPathToEclipseName.get(normPath);
                if (name != null) {
                    return ResourcesPlugin.getWorkspace().getRoot().getProject(name);
                }
            }
        } catch (Throwable e) {
            GroovyLanguageServerPlugin.logError(
                    "[classpathUpdate] Failed to resolve project for: " + projectUri, e);
        }

        // Fallback: single-project mode
        IProject groovy = ResourcesPlugin.getWorkspace().getRoot().getProject("GroovyProject");
        return (groovy.exists() && groovy.isOpen()) ? groovy : null;
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
        sendStatus("Starting", "Initializing...");
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
            // Clean up stale projects from previous runs to avoid name collisions
            // and leftover setLocation() project descriptions.
            cleanupStaleProjects();

            java.io.File workspaceDir;
            try {
                workspaceDir = new java.io.File(URI.create(workspaceRoot));
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Failed to resolve workspace root URI", e);
                return;
            }

            String[] srcDirSuffixes = {
                "src/main/java", "src/main/groovy",
                "src/test/java", "src/test/groovy",
                "src/main/resources", "src/test/resources",
            };

            // Look for subprojects with source folders
            List<java.io.File> subprojects = findSubprojectsWithSources(workspaceDir, srcDirSuffixes);

            if (!subprojects.isEmpty()) {
                // ---- Multi-project workspace ----
                GroovyLanguageServerPlugin.logInfo(
                        "[workspace] Found " + subprojects.size() + " subproject(s). "
                        + "Creating isolated Eclipse projects for classpath separation.");

                for (java.io.File subDir : subprojects) {
                    try {
                        createEclipseProjectFor(subDir, srcDirSuffixes);
                    } catch (Throwable e) {
                        GroovyLanguageServerPlugin.logError(
                                "[workspace] Skipped " + subDir.getName() + ": " + e.getMessage(), e);
                    }
                }

                if (subprojectPathToEclipseName.isEmpty()) {
                    // All creations failed — fall back to single root project
                    GroovyLanguageServerPlugin.logInfo(
                            "[workspace] Multi-project setup failed. Falling back to single root project.");
                    createSingleRootProject(workspaceDir, srcDirSuffixes);
                } else {
                    GroovyLanguageServerPlugin.logInfo(
                            "[workspace] Created " + subprojectPathToEclipseName.size()
                            + " Eclipse project(s): " + subprojectPathToEclipseName.values());
                }
            } else {
                // ---- Single-project workspace ----
                createSingleRootProject(workspaceDir, srcDirSuffixes);
            }

        } catch (Throwable e) {
            GroovyLanguageServerPlugin.logError("Failed to initialize workspace project", e);
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
        String projectName = "Groovy_" + baseName;

        int counter = 1;
        while (workspace.getRoot().getProject(projectName).exists()) {
            projectName = "Groovy_" + baseName + "_" + counter++;
        }

        IProject project = workspace.getRoot().getProject(projectName);
        IProjectDescription description = workspace.newProjectDescription(projectName);
        // NO setLocation — project lives in Eclipse metadata area (flat, no nesting)
        description.setNatureIds(new String[]{
                JavaCore.NATURE_ID,
                "org.eclipse.jdt.groovy.core.groovyNature"
        });

        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        // Create a linked folder pointing to the subproject's actual filesystem location.
        // This lets Eclipse treat each subproject independently while all projects
        // live in the flat metadata area (no nesting issues).
        IFolder linkedRoot = project.getFolder("linked");
        linkedRoot.createLink(
                org.eclipse.core.runtime.Path.fromOSString(subDir.getAbsolutePath()),
                IResource.ALLOW_MISSING_LOCAL,
                new NullProgressMonitor());

        GroovyLanguageServerPlugin.logInfo(
                "[" + projectName + "] Linked folder → " + subDir.getAbsolutePath());

        // Configure source folders (relative to the linked root)
        IJavaProject javaProject = JavaCore.create(project);
        List<IClasspathEntry> entries = new ArrayList<>();
        boolean foundAny = false;

        for (String suffix : srcDirSuffixes) {
            IFolder srcFolder = linkedRoot.getFolder(suffix);
            if (srcFolder != null && srcFolder.exists()) {
                entries.add(JavaCore.newSourceEntry(srcFolder.getFullPath()));
                foundAny = true;
                GroovyLanguageServerPlugin.logInfo(
                        "[" + projectName + "] Source folder: linked/" + suffix);
            }
        }

        if (!foundAny) {
            IFolder srcFolder = linkedRoot.getFolder("src");
            if (srcFolder != null && srcFolder.exists()) {
                entries.add(JavaCore.newSourceEntry(srcFolder.getFullPath()));
            } else {
                entries.add(JavaCore.newSourceEntry(linkedRoot.getFullPath()));
            }
        }

        entries.add(JavaCore.newContainerEntry(
                new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        javaProject.setRawClasspath(
                entries.toArray(new IClasspathEntry[0]),
                project.getFullPath().append("bin"),
                new NullProgressMonitor());

        // Store mapping: normalized filesystem path → Eclipse project name
        String normalizedPath = subDir.getAbsolutePath().replace('\\', '/').toLowerCase();
        if (!normalizedPath.endsWith("/")) normalizedPath += "/";
        subprojectPathToEclipseName.put(normalizedPath, projectName);

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
            IProject project = workspace.getRoot().getProject("GroovyProject");

            if (!project.exists()) {
                IProjectDescription description = workspace.newProjectDescription("GroovyProject");
                // NO setLocation — project lives in Eclipse metadata area.
                // A linked folder will point to the real workspace dir.
                // This avoids creating .project / .classpath in the user's workspace.
                description.setNatureIds(new String[]{
                        JavaCore.NATURE_ID,
                        "org.eclipse.jdt.groovy.core.groovyNature"
                });
                project.create(description, new NullProgressMonitor());
                project.open(new NullProgressMonitor());
                GroovyLanguageServerPlugin.logInfo("Created Eclipse project 'GroovyProject' (linked folder).");
            } else if (!project.isOpen()) {
                project.open(new NullProgressMonitor());
            }

            // Create a linked folder pointing to the actual workspace directory.
            IFolder linkedRoot = project.getFolder("linked");
            if (!linkedRoot.exists()) {
                linkedRoot.createLink(
                        org.eclipse.core.runtime.Path.fromOSString(workspaceDir.getAbsolutePath()),
                        IResource.ALLOW_MISSING_LOCAL,
                        new NullProgressMonitor());
            }
            GroovyLanguageServerPlugin.logInfo(
                    "[GroovyProject] Linked folder → " + workspaceDir.getAbsolutePath());

            // Configure source folders (relative to the linked root)
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                GroovyLanguageServerPlugin.logError("[classpath] JavaCore.create returned null", null);
                return;
            }

            List<IClasspathEntry> entries = new ArrayList<>();
            boolean foundAny = false;

            for (String srcDir : srcDirSuffixes) {
                IFolder folder = linkedRoot.getFolder(srcDir);
                if (folder != null && folder.exists()) {
                    entries.add(JavaCore.newSourceEntry(folder.getFullPath()));
                    foundAny = true;
                    GroovyLanguageServerPlugin.logInfo("[classpath] Added source folder: linked/" + srcDir);
                }
            }

            if (!foundAny) {
                IFolder srcFolder = linkedRoot.getFolder("src");
                if (srcFolder != null && srcFolder.exists()) {
                    entries.add(JavaCore.newSourceEntry(srcFolder.getFullPath()));
                    foundAny = true;
                } else {
                    entries.add(JavaCore.newSourceEntry(linkedRoot.getFullPath()));
                }
            }

            entries.add(JavaCore.newContainerEntry(
                    new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

            // Store mapping so classpathUpdate can find this project
            String normalizedPath = workspaceDir.getAbsolutePath().replace('\\', '/').toLowerCase();
            if (!normalizedPath.endsWith("/")) normalizedPath += "/";
            subprojectPathToEclipseName.put(normalizedPath, "GroovyProject");

            GroovyLanguageServerPlugin.logInfo(
                    "[classpath] Initial classpath with " + entries.size()
                    + " entries (source folders + JRE). Waiting for classpath from Java extension...");

            javaProject.setRawClasspath(
                    entries.toArray(new IClasspathEntry[0]),
                    project.getFullPath().append("bin"),
                    new NullProgressMonitor());

        } catch (Throwable e) {
            GroovyLanguageServerPlugin.logError("[classpath] Failed to configure classpath (non-fatal): "
                    + e.getMessage(), e);
        }
    }

    /**
     * Delete any stale Eclipse projects from previous runs.
     * Projects named "Groovy_*" or "GroovyProject" are removed so that
     * fresh linked-folder projects can be created cleanly.
     */
    private void cleanupStaleProjects() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject p : projects) {
                String name = p.getName();
                if (name.startsWith("Groovy_") || name.equals("GroovyProject")) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[workspace] Deleting stale project: " + name);
                    // IMPORTANT: first arg=false means do NOT delete disk contents.
                    // GroovyProject uses setLocation() pointing at the user's real folder,
                    // so delete(true,...) would wipe the user's files!
                    p.delete(false, true, new NullProgressMonitor());
                }
            }
        } catch (Throwable e) {
            GroovyLanguageServerPlugin.logError(
                    "[workspace] Failed to clean up stale projects: " + e.getMessage(), e);
        }
    }

    /**
     * Find subdirectories that contain source folders (up to 3 levels deep).
     */
    private List<java.io.File> findSubprojectsWithSources(java.io.File root, String[] srcDirSuffixes) {
        List<java.io.File> result = new ArrayList<>();
        scanForSubprojects(root, result, srcDirSuffixes, 0, 3);
        return result;
    }

    private void scanForSubprojects(java.io.File dir, List<java.io.File> result,
                                     String[] srcDirSuffixes, int depth, int maxDepth) {
        if (depth >= maxDepth || dir == null || !dir.isDirectory()) return;

        java.io.File[] children = dir.listFiles();
        if (children == null) return;

        for (java.io.File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")
                    || child.getName().equals("build") || child.getName().equals("node_modules")
                    || child.getName().equals("bin") || child.getName().equals("out")) {
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
