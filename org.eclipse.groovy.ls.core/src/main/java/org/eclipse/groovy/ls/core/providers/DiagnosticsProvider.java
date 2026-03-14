/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Provides diagnostics (errors/warnings) for Groovy documents.
 * <p>
 * Uses JDT's compilation infrastructure. When groovy-eclipse's patched JDT compiles
 * a {@code .groovy} file, it reports problems as {@link IProblem} instances.
 * We convert these to LSP {@link Diagnostic} objects and publish them to the client.
 */
public class DiagnosticsProvider {

    private static final int TYPE_COLLISION_PROBLEM_ID = 16777539;
    private static final int GROOVY_RUN_SCRIPT_PROBLEM_ID = 67108964;
    /** IProblem.PackageIsNotExpectedPackage — false positive when AST has zero classes. */
    private static final int PACKAGE_IS_NOT_EXPECTED_PROBLEM_ID = 536871240;
    /**
     * IProblem.IsClassPathCorrect (16777540) — "The type X cannot be resolved.
     * It is indirectly referenced from required .class files".  Groovy-compiled
     * classes always reference internal Groovy runtime types (GroovyObject,
     * MetaClass, etc.) that may not be on the JDT classpath yet (or at all,
     * when the Groovy plugin provides them implicitly).
     */
    private static final int IS_CLASSPATH_CORRECT_PROBLEM_ID = 16777540;
    /**
     * Groovy runtime type prefixes whose "indirectly referenced" errors are
     * always suppressed.  These are compiler-internal references injected by
     * the Groovy compiler into every .class file — users cannot act on them
     * regardless of which Groovy version they use.
     */
    private static final String[] GROOVY_RUNTIME_TYPE_PREFIXES = {
        "groovy.lang.GroovyObject",
        "groovy.lang.MetaClass",
        "groovy.lang.GroovyCallable",
        "groovy.lang.Closure",
        "groovy.lang.GString",
        "groovy.lang.Range",
        "groovy.lang.Script",
        "groovy.lang.MetaObjectProtocol",
        "groovy.transform.Generated",
        "groovy.transform.Internal",
        "groovy.transform.stc.",
        "org.codehaus.groovy.",
    };
    private static final String GENERAL_CONVERSION_ERROR_PREFIX =
            "Groovy:General error during conversion:";
    private static final String NO_SUCH_CLASS_PREFIX = "No such class: ";
    private static final String TRANSFORM_LOADER_FRAGMENT =
            "JDTClassNode.getTypeClass() cannot locate it using transform loader";
    private static final String DIAGNOSTIC_SOURCE_GROOVY = "groovy";
    private static final String[] DEFAULT_AUTO_IMPORTED_PACKAGES = {
        "java.lang.",
        "java.util.",
        "java.io.",
        "java.net.",
        "java.math.",
        "groovy.lang.",
        "groovy.util."
    };
    private static final Pattern UNABLE_TO_RESOLVE_CLASS_PATTERN =
            Pattern.compile("(?i)unable to resolve class\\s+([\\w.$]+)");

    private final DocumentManager documentManager;
    private LanguageClient client;
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.Predicate<String>> classpathAvailableForUri =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> buildInProgressSupplier =
            new java.util.concurrent.atomic.AtomicReference<>(() -> false);
    /**
     * Supplier that returns {@code true} once the very first workspace build
     * has completed.  Before that point, missing-classpath is expected
     * (classpaths arrive asynchronously) and the "no classpath" warning
     * should be suppressed to avoid false alarms for files that were
     * already open when the server started.
     */
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> initializationCompleteSupplier =
            new java.util.concurrent.atomic.AtomicReference<>(() -> true);
    private final java.util.Map<String, List<Diagnostic>> latestDiagnosticsByUri =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Simple debounce: track last scheduled publish per URI
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pendingPublish =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Per-URI version counter: incremented on every debounced request.
    // A running task compares the version it captured against the current
    // value — if they differ, a newer edit has been made and the task's
    // results would be stale, so it skips the expensive reconcile.
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> diagnosticVersions =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Per-URI "in-flight" flag: prevents overlapping reconcile tasks for the
    // same file.  At most ONE diagnostics task can be running per URI.  If a
    // new task fires while one is already running, it marks "re-run needed"
    // and returns immediately, letting the in-flight task handle the re-run
    // when it finishes.
    private final java.util.Set<String> diagnosticsInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Use a pool of 4 threads so that one reconcile blocked on the workspace
    // lock doesn't stall diagnostics for every other open file.
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "groovy-ls-diagnostics");
                t.setDaemon(true);
                return t;
            });
    // Limit concurrent JDT reconciles. reconcile(getJLSLatest()) is expensive
    // (~1-5s per file in large workspaces). If all threads are doing reconcile,
    // no more diagnostics can be scheduled. The semaphore lets us fall back to
    // fast syntax-only diagnostics when the limit is reached.
    // Scaled to available processors (minimum 2, maximum 6) so that large
    // workspaces with many open files can reconcile more in parallel.
    // On a 4-CPU container this yields 3 permits, leaving headroom for other work.
    private final java.util.concurrent.Semaphore reconcileSemaphore =
            new java.util.concurrent.Semaphore(
                    Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)));

    public DiagnosticsProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    public void connect(LanguageClient client) {
        this.client = client;
    }

    /**
     * Set a predicate that checks whether a classpath has been configured
     * for the project owning a given URI. When the predicate returns {@code false},
     * only syntax diagnostics are reported and a warning is added at the
     * package/first line.
     */
    public void setClasspathChecker(java.util.function.Predicate<String> checker) {
        this.classpathAvailableForUri.set(checker);
    }

    /**
     * Set a supplier that returns {@code true} while a workspace build is
     * in progress.  When true, diagnostics will skip JDT reconcile (which
     * blocks on the workspace lock) and use the standalone Groovy compiler
     * for fast, non-blocking syntax diagnostics instead.
     */
    /**
     * Set a supplier that returns {@code true} once the first workspace build
     * has completed.  Before that point, the "Classpath is not configured"
     * warning is suppressed — classpath updates are still arriving and the
     * missing classpath is transient.
     */
    public void setInitializationCompleteSupplier(java.util.function.BooleanSupplier supplier) {
        this.initializationCompleteSupplier.set(supplier);
    }

    public void setBuildInProgressSupplier(java.util.function.BooleanSupplier supplier) {
        this.buildInProgressSupplier.set(supplier);
    }

    /**
     * Publish diagnostics for a document in two phases:
     * <ol>
     *   <li><b>Fast pass</b> — JDT reconcile + unused imports (published immediately)</li>
     *   <li><b>Deferred pass</b> — unused declaration detection via SearchEngine
     *       (scheduled 1 s later to avoid blocking the fast diagnostics)</li>
     * </ol>
     */
    public void publishDiagnostics(String uri) {
        String normalizedUri = DocumentManager.normalizeUri(uri);
        if (client == null) {
            GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnostics skipped (no client) uri=" + uri);
            return;
        }

        // Skip if the document was closed between scheduling and execution
        if (documentManager.getContent(uri) == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] publishDiagnostics skipped (document closed) uri=" + uri);
            return;
        }

        // Phase 1: fast diagnostics (JDT reconcile + unused imports)
        List<Diagnostic> diagnostics = collectDiagnostics(uri);
        latestDiagnosticsByUri.put(normalizedUri, new ArrayList<>(diagnostics));
        GroovyLanguageServerPlugin.logInfo(
                "[diag-trace] publishDiagnostics (fast) uri=" + uri + " count=" + diagnostics.size());
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>(diagnostics)));

        // Phase 2: deferred unused-declaration detection — runs after a short
        // delay so the user sees JDT errors immediately without waiting for
        // the expensive SearchEngine scans.
        scheduler.schedule(() -> {
            try {
                if (documentManager.getContent(uri) == null) return;
                List<Diagnostic> unusedDeclarations =
                        UnusedDeclarationDetector.detectUnusedDeclarations(uri, documentManager);
                if (!unusedDeclarations.isEmpty()) {
                    List<Diagnostic> merged = new ArrayList<>(diagnostics);
                    merged.addAll(unusedDeclarations);
                    latestDiagnosticsByUri.put(normalizedUri, merged);
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, merged));
                    GroovyLanguageServerPlugin.logInfo(
                            "[diag-trace] publishDiagnostics (deferred) uri=" + uri
                            + " +unused=" + unusedDeclarations.size());
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                        "Deferred unused-declaration detection failed for " + uri, e);
            }
        }, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Get the latest cached diagnostics for a URI.
     */
    public List<Diagnostic> getLatestDiagnostics(String uri) {
        List<Diagnostic> diagnostics = latestDiagnosticsByUri.get(DocumentManager.normalizeUri(uri));
        return diagnostics == null ? new ArrayList<>() : new ArrayList<>(diagnostics);
    }

    /**
     * Collect diagnostics on demand (for code actions) and refresh cache.
     */
    public List<Diagnostic> collectDiagnosticsForCodeActions(String uri) {
        List<Diagnostic> diagnostics = collectDiagnostics(uri);
        latestDiagnosticsByUri.put(DocumentManager.normalizeUri(uri), diagnostics);
        return diagnostics;
    }

    /**
     * Clear cached diagnostics for a URI and cancel any pending debounced
     * diagnostic task.  Called when a document is closed.
     */
    public void clearDiagnostics(String uri) {
        String normalizedUri = DocumentManager.normalizeUri(uri);
        latestDiagnosticsByUri.remove(normalizedUri);
        diagnosticsInFlight.remove(normalizedUri);
        diagnosticVersions.remove(normalizedUri);

        // Cancel any scheduled but not-yet-started diagnostic task for this URI.
        // Without this, a debounced task from didOpen can fire after didClose,
        // triggering a reconcile for a file that's no longer open.
        java.util.concurrent.ScheduledFuture<?> pending = pendingPublish.remove(normalizedUri);
        if (pending != null) {
            pending.cancel(false);
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] cancelled pending diagnostic task on close for " + uri);
        }
    }

    /**
     * Publish diagnostics immediately (no debounce delay).
     * <p>
     * Use this for {@code didOpen} where there is nothing to coalesce —
     * the file content is already complete and no rapid-fire edits follow.
     * The task still runs asynchronously on the scheduler thread and
     * respects the same version / in-flight guards as the debounced path.
     */
    public void publishDiagnosticsImmediate(String uri) {
        GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnosticsImmediate uri=" + uri);
        String normalizedUri = DocumentManager.normalizeUri(uri);

        java.util.concurrent.atomic.AtomicLong version =
                diagnosticVersions.computeIfAbsent(normalizedUri,
                        k -> new java.util.concurrent.atomic.AtomicLong(0));
        long myVersion = version.incrementAndGet();

        // Cancel any pending debounced publish for the same URI
        java.util.concurrent.ScheduledFuture<?> existing = pendingPublish.remove(normalizedUri);
        if (existing != null) {
            existing.cancel(false);
        }

        // Submit with zero delay — runs on the scheduler thread without
        // blocking the LSP dispatch thread.
        java.util.concurrent.ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (version.get() != myVersion) {
                return;
            }
            if (!diagnosticsInFlight.add(normalizedUri)) {
                return;
            }
            try {
                publishDiagnostics(uri);
            } finally {
                diagnosticsInFlight.remove(normalizedUri);
                if (version.get() != myVersion && documentManager.getContent(uri) != null) {
                    publishDiagnosticsDebounced(uri);
                }
            }
        }, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
        pendingPublish.put(normalizedUri, future);
    }

    /**
     * Publish diagnostics after a short delay (debounced).
     * <p>
     * Subsequent calls for the same URI cancel the previous scheduled publish
     * and increment a per-URI version counter.  The scheduled task checks the
     * version before and after doing expensive work (reconcile) — if the version
     * has changed, a newer edit superseded this one and the results are discarded.
     * <p>
     * Additionally, only ONE diagnostics task per URI can be in-flight at a time.
     * If the previous task is still running when the debounce timer fires, the
     * new task skips work entirely — the in-flight task will re-schedule
     * automatically if the version has moved forward while it was running.
     */
    public void publishDiagnosticsDebounced(String uri) {
        GroovyLanguageServerPlugin.logInfo("[diag-trace] publishDiagnosticsDebounced schedule uri=" + uri);
        String normalizedUri = DocumentManager.normalizeUri(uri);

        // Increment version counter — any in-flight or scheduled task for an
        // older version will see the mismatch and skip its work.
        java.util.concurrent.atomic.AtomicLong version =
                diagnosticVersions.computeIfAbsent(normalizedUri,
                        k -> new java.util.concurrent.atomic.AtomicLong(0));
        long myVersion = version.incrementAndGet();

        java.util.concurrent.ScheduledFuture<?> existing = pendingPublish.remove(normalizedUri);
        if (existing != null) {
            existing.cancel(false);
        }

        java.util.concurrent.ScheduledFuture<?> future = scheduler.schedule(() -> {
            // Check 1: skip if version has already moved forward
            if (version.get() != myVersion) {
                GroovyLanguageServerPlugin.logInfo(
                        "[diag-trace] skipping stale diagnostic task (version moved) uri=" + uri);
                return;
            }

            // Check 2: skip if another task for this URI is already running.
            // That task will re-schedule when it finishes if the version changed.
            if (!diagnosticsInFlight.add(normalizedUri)) {
                GroovyLanguageServerPlugin.logInfo(
                        "[diag-trace] skipping diagnostic task (another in-flight) uri=" + uri);
                return;
            }

            try {
                publishDiagnostics(uri);
            } finally {
                diagnosticsInFlight.remove(normalizedUri);

                // If the version moved while we were running, a newer edit
                // came in.  Re-schedule so the latest content gets diagnosed.
                if (version.get() != myVersion && documentManager.getContent(uri) != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[diag-trace] re-scheduling diagnostic task (version changed while running) uri=" + uri);
                    publishDiagnosticsDebounced(uri);
                }
            }
        }, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
        pendingPublish.put(normalizedUri, future);
    }

    /**
     * Collect diagnostics from JDT compilation problems.
     */
    private List<Diagnostic> collectDiagnostics(String uri) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String content = documentManager.getContent(uri);

        // If the document is no longer open (was closed while the debounced
        // task was waiting), skip all expensive work immediately.
        if (content == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] collectDiagnostics skipped (document closed) uri=" + uri);
            return diagnostics;
        }

        // Pre-compute line-start offsets once. This turns every subsequent
        // offset→position conversion from O(fileLength) to O(log lines).
        PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);

        // When no classpath is available for this project, report only syntax
        // errors (from the standalone Groovy compiler) and append a warning at
        // the top of the file so the user knows why full diagnostics are missing.
        java.util.function.Predicate<String> checker = classpathAvailableForUri.get();
        boolean hasClasspath = checker == null || checker.test(uri);
        GroovyLanguageServerPlugin.logInfo(
                "[classpath-check] uri=" + uri
                + " checkerPresent=" + (checker != null)
                + " hasClasspath=" + hasClasspath);

        if (!hasClasspath) {
            GroovyLanguageServerPlugin.logInfo(
                    "[classpath-check] No classpath for " + uri + " → syntax-only mode");
            try {
                collectFromGroovyCompiler(uri, diagnostics);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                    "Failed to collect syntax diagnostics (no classpath) for " + uri, e);
            }
            // Only show the "Classpath is not configured" warning after the
            // first workspace build has completed.  Before that point the
            // missing classpath is expected — classpath updates arrive
            // asynchronously during the initialization window and the
            // warning would be a false alarm for files that were already
            // open when the server started.  Once the build finishes and
            // full diagnostics are published, the warning is legitimate.
            boolean initComplete = initializationCompleteSupplier.get().getAsBoolean();
            if (initComplete) {
                diagnostics.add(createNoClasspathWarning(content));
            } else {
                GroovyLanguageServerPlugin.logInfo(
                        "[classpath-check] Suppressed noClasspath warning (initialization in progress) for " + uri);
            }
            return diagnostics;
        }

        // When a build is in progress, the workspace lock is held and any
        // JDT reconcile() call would block this thread indefinitely.  Use
        // fast syntax-only diagnostics from the standalone Groovy compiler
        // instead.  Full JDT diagnostics are published automatically after
        // the build completes.
        boolean buildRunning = buildInProgressSupplier.get().getAsBoolean();
        if (buildRunning) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] Build in progress → syntax-only diagnostics for " + uri);
            try {
                collectFromGroovyCompiler(uri, diagnostics);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                    "Failed to collect syntax diagnostics (build in progress) for " + uri, e);
            }
            return diagnostics;
        }

        try {
            // Approach 1: Get problems from the working copy reconciliation.
            // Use a semaphore to prevent all threads from blocking in reconcile()
            // simultaneously — excess requests fall through to the faster
            // standalone Groovy compiler instead of queueing.
            ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
            if (workingCopy != null) {
                boolean gotPermit = reconcileSemaphore.tryAcquire();
                if (gotPermit) {
                    try {
                        collectFromWorkingCopy(workingCopy, diagnostics, content, lineIndex);

                        // Also get problems from resource markers (for saved files)
                        IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                                .findFilesForLocationURI(URI.create(uri));
                        if (files.length > 0) {
                            collectFromMarkers(files[0], diagnostics, content, lineIndex);
                        }
                    } finally {
                        reconcileSemaphore.release();
                    }
                } else {
                    // All reconcile slots are busy — use fast syntax-only diagnostics.
                    // This file will get full JDT diagnostics on the next change or
                    // when publishDiagnosticsForOpenDocuments() runs after a build.
                    GroovyLanguageServerPlugin.logInfo(
                            "[diag-trace] Reconcile slots busy, syntax-only for " + uri);
                    collectFromGroovyCompiler(uri, diagnostics);
                }
            } else {
                // Fallback: use standalone Groovy compiler for syntax diagnostics
                collectFromGroovyCompiler(uri, diagnostics);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to collect diagnostics for " + uri, e);
        }

        // Always check for unused imports via AST analysis
        try {
            ModuleNode ast = documentManager.getGroovyAST(uri);
            if (ast != null && content != null) {
                List<Diagnostic> unusedImports = UnusedImportDetector.detectUnusedImports(ast, content);
                diagnostics.addAll(unusedImports);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Unused import detection failed for " + uri, e);
        }

        // NOTE: Unused declaration detection (UnusedDeclarationDetector) is
        // intentionally NOT run here — it is deferred to a secondary pass in
        // publishDiagnostics() so that JDT diagnostics + unused imports are
        // published immediately without waiting for the expensive SearchEngine
        // scans.  See publishDiagnostics() Phase 2.

        return diagnostics;
    }

    /**
     * Create a warning diagnostic placed at the first line (package declaration)
     * informing the user that no classpath is configured for this file's project.
     */
    private Diagnostic createNoClasspathWarning(String content) {
        int firstLineLength = 0;
        if (content != null) {
            int idx = content.indexOf('\n');
            firstLineLength = idx >= 0 ? idx : content.length();
            if (firstLineLength > 0 && content.charAt(firstLineLength - 1) == '\r') {
                firstLineLength--;
            }
        }
        Diagnostic warning = new Diagnostic();
        warning.setRange(new Range(new Position(0, 0), new Position(0, firstLineLength)));
        warning.setSeverity(DiagnosticSeverity.Warning);
        warning.setSource(DIAGNOSTIC_SOURCE_GROOVY);
        warning.setCode("groovy.noClasspath");
        warning.setMessage(
            "Classpath is not configured for this project. "
            + "Only syntax errors are reported. "
            + "Install the 'Language Support for Java' extension and ensure "
            + "this project is recognized as a valid Gradle/Maven project.");
        return warning;
    }

    /**
     * Collect problems from a JDT working copy that has been reconciled.
     */
    private void collectFromWorkingCopy(ICompilationUnit workingCopy, List<Diagnostic> diagnostics,
            String content, PositionUtils.LineIndex lineIndex) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            // Reconcile to get fresh problems
            org.eclipse.jdt.core.dom.CompilationUnit ast = workingCopy.reconcile(
                    org.eclipse.jdt.core.dom.AST.getJLSLatest(),
                    true, // force problem detection
                    true, // enable statements recovery
                    documentManager.getWorkingCopyOwner(),
                    null);

            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (ast != null) {
                // Check whether the Groovy AST has zero classes. When the code
                // is mid-edit and syntactically broken, the parser can produce
                // an empty module. JDT then reports false positives like
                // "declared package does not match" because it sees no package
                // declaration in the empty AST.
                boolean astHasNoClasses = isGroovyASTEmpty(workingCopy);

                IProblem[] problems = ast.getProblems();
                IJavaProject javaProject = workingCopy.getJavaProject();
                for (IProblem problem : problems) {
                    if (shouldSkipDiagnostic(
                            problem.getID(),
                            problem.getMessage(),
                            javaProject,
                            workingCopy,
                            astHasNoClasses)) {
                        continue;
                    }
                    diagnostics.add(toDiagnostic(problem, lineIndex));
                }
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("Failed to reconcile working copy", e);
        }
    }

    /**
     * Check if the Groovy ModuleNode for a working copy has zero classes,
     * indicating a broken parse that produces false-positive diagnostics.
     */
    private boolean isGroovyASTEmpty(ICompilationUnit workingCopy) {
        try {
            ModuleNode module = ReflectionCache.getModuleNode(workingCopy);
            if (module != null) {
                return module.getClasses() == null || module.getClasses().isEmpty();
            }
        } catch (Exception e) {
            // Not a GroovyCompilationUnit or reflection failed — assume not empty
        }
        return false;
    }

    /**
     * Collect problems from Eclipse resource markers.
     */
    private void collectFromMarkers(IFile file, List<Diagnostic> diagnostics,
            String content, PositionUtils.LineIndex lineIndex) {
        try {
            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
            IJavaProject javaProject = JavaCore.create(file.getProject());
            ICompilationUnit contextUnit = null;
            org.eclipse.jdt.core.IJavaElement element = JavaCore.create(file);
            if (element instanceof ICompilationUnit compilationUnit) {
                contextUnit = compilationUnit;
            }
            for (IMarker marker : markers) {
                // Filter out the same Groovy-Eclipse false positive from markers.
                // Extract the JDT problem ID when available so that ID-based
                // filters (e.g. PACKAGE_IS_NOT_EXPECTED_PROBLEM_ID) also work
                // for marker-sourced diagnostics.
                String msg = marker.getAttribute(IMarker.MESSAGE, "");
                int markerId = marker.getAttribute("id", -1);
                if (shouldSkipDiagnostic(markerId, msg, javaProject, contextUnit)) {
                    continue;
                }
                Diagnostic diagnostic = toDiagnostic(marker, lineIndex);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to collect markers", e);
        }
    }

    private boolean shouldSkipDiagnostic(
            int problemId,
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        return shouldSkipDiagnostic(problemId, message, javaProject, contextUnit, false);
    }

    private boolean shouldSkipDiagnostic(
            int problemId,
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit,
            boolean astHasNoClasses) {
        if (problemId == GROOVY_RUN_SCRIPT_PROBLEM_ID) {
            return true;
        }

        // Filter out Groovy-Eclipse false positive: "The type X is already defined"
        // (IProblem.TypeCollision = 16777539). When a .groovy file has errors,
        // the Groovy compiler wraps the file body in a synthetic script class
        // with the same name as the declared class, causing a spurious duplicate.
        if (problemId == TYPE_COLLISION_PROBLEM_ID
                || (message != null
                        && message.startsWith("The type ")
                        && message.endsWith(" is already defined"))) {
            return true;
        }

        // Filter "General error during conversion: ..." messages. These are noisy
        // wrappers around the underlying parse errors and provide no actionable
        // information beyond what the actual syntax errors already report.
        if (message != null && message.startsWith(GENERAL_CONVERSION_ERROR_PREFIX)) {
            return true;
        }

        // When the Groovy AST has zero classes (code is mid-edit/broken), JDT
        // sees no package declaration and reports a spurious package mismatch.
        if (astHasNoClasses && problemId == PACKAGE_IS_NOT_EXPECTED_PROBLEM_ID) {
            return true;
        }

        // Suppress "The declared package X does not match the expected package
        // ''" — an expected package of "" (empty) is always a symptom of a
        // misconfigured source root (e.g. when the linked folder's children
        // were not yet visible during project creation).  A real source file
        // with a package declaration should never be expected to have no
        // package, so this is always a false positive.
        if (problemId == PACKAGE_IS_NOT_EXPECTED_PROBLEM_ID
                && message != null
                && message.endsWith(" \"\"")) {
            return true;
        }

        // Suppress "indirectly referenced from required .class files" for
        // Groovy runtime types.  Every compiled Groovy class references
        // GroovyObject, MetaClass, Generated, Internal, etc.  If the Groovy
        // runtime JAR hasn't been sent via classpathUpdate yet (timing) or
        // is provided implicitly by the build tool's Groovy plugin, these
        // errors are false positives the user cannot fix.  The check is
        // version-agnostic — it matches type prefixes, not specific JARs.
        if (problemId == IS_CLASSPATH_CORRECT_PROBLEM_ID
                && message != null
                && isGroovyRuntimeIndirectReference(message)) {
            return true;
        }

        // Filter a known transient Groovy-Eclipse issue: a source type exists in the
        // project but JDTClassNode.getTypeClass() cannot load it through transform loader.
        if (isExistingTypeTransformLoaderFailure(message, javaProject, contextUnit)) {
            return true;
        }

        // Filter transient unresolved-class diagnostics when the target type
        // is actually present in the project and resolvable from context.
        return isResolvableUnableToResolveClassFailure(message, javaProject, contextUnit);
    }

    /**
     * Test whether an "indirectly referenced" error message refers to a
     * well-known Groovy runtime type.  The message format is:
     * <pre>
     * The type groovy.lang.GroovyObject cannot be resolved.
     * It is indirectly referenced from required .class files
     * </pre>
     * We extract the fully-qualified type name and check it against
     * {@link #GROOVY_RUNTIME_TYPE_PREFIXES}.
     */
    private static boolean isGroovyRuntimeIndirectReference(String message) {
        // Fast pre-check: avoid regex for messages that clearly don't match.
        if (!message.contains("groovy.") && !message.contains("org.codehaus.groovy.")) {
            return false;
        }
        // Extract the type name between "The type " and " cannot be resolved"
        int start = message.indexOf("The type ");
        if (start < 0) {
            return false;
        }
        start += "The type ".length();
        int end = message.indexOf(" cannot be resolved", start);
        if (end < 0) {
            return false;
        }
        String typeName = message.substring(start, end).trim();
        for (String prefix : GROOVY_RUNTIME_TYPE_PREFIXES) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExistingTypeTransformLoaderFailure(
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        if (message == null || javaProject == null || !message.contains(TRANSFORM_LOADER_FRAGMENT)) {
            return false;
        }

        String missingType = extractMissingTypeName(message);
        if (missingType == null || missingType.isBlank()) {
            return false;
        }

        return typeExistsInProjectContext(missingType, javaProject, contextUnit);
    }

    private boolean isResolvableUnableToResolveClassFailure(
            String message,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        if (message == null || javaProject == null) {
            return false;
        }

        Matcher matcher = UNABLE_TO_RESOLVE_CLASS_PATTERN.matcher(message);
        if (!matcher.find()) {
            return false;
        }

        String missingType = matcher.group(1);
        if (missingType == null || missingType.isBlank()) {
            return false;
        }

        return typeExistsInProjectContext(missingType, javaProject, contextUnit);
    }

    private boolean typeExistsInProjectContext(
            String missingType,
            IJavaProject javaProject,
            ICompilationUnit contextUnit) {
        String normalizedMissingType = missingType.trim();
        Set<String> candidates = collectTypeCandidates(normalizedMissingType, contextUnit);

        try {
            return anyCandidateTypeExists(candidates, javaProject);
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diagnostics] Failed to validate missing type '" + normalizedMissingType
                    + "' with candidates=" + candidates + ": " + e.getMessage());
            return false;
        }
    }

    private Set<String> collectTypeCandidates(String missingType, ICompilationUnit contextUnit) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(missingType);

        if (missingType.contains(".")) {
            return candidates;
        }

        String simpleName = extractSimpleTypeName(missingType);
        addPackageCandidates(candidates, simpleName, contextUnit);
        addImportCandidates(candidates, simpleName, contextUnit);
        for (String pkg : DEFAULT_AUTO_IMPORTED_PACKAGES) {
            candidates.add(pkg + simpleName);
        }

        return candidates;
    }

    private String extractSimpleTypeName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < typeName.length() - 1) {
            return typeName.substring(lastDot + 1);
        }
        return typeName;
    }

    private void addPackageCandidates(Set<String> candidates,
            String simpleName,
            ICompilationUnit contextUnit) {
        if (contextUnit == null) {
            return;
        }

        try {
            for (var pkg : contextUnit.getPackageDeclarations()) {
                String packageName = pkg.getElementName();
                if (packageName != null && !packageName.isBlank()) {
                    candidates.add(packageName + "." + simpleName);
                }
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diagnostics] Failed to read package declarations: " + e.getMessage());
        }
    }

    private void addImportCandidates(Set<String> candidates,
            String simpleName,
            ICompilationUnit contextUnit) {
        if (contextUnit == null) {
            return;
        }

        try {
            for (IImportDeclaration imp : contextUnit.getImports()) {
                String importedName = imp.getElementName();
                if (importedName == null || importedName.isBlank()) {
                    continue;
                }
                if (imp.isOnDemand()) {
                    candidates.add(importedName + "." + simpleName);
                } else if (importedName.endsWith("." + simpleName)) {
                    candidates.add(importedName);
                }
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[diagnostics] Failed to read import declarations: " + e.getMessage());
        }
    }

    private boolean anyCandidateTypeExists(Set<String> candidates, IJavaProject javaProject)
            throws JavaModelException {
        for (String candidate : candidates) {
            if (!isValidTypeCandidate(candidate)) {
                continue;
            }
            IType type = javaProject.findType(candidate);
            if (type != null && type.exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidTypeCandidate(String candidate) {
        return candidate != null && !candidate.isBlank();
    }

    private String extractMissingTypeName(String message) {
        if (message.isBlank()) {
            return null;
        }

        int start = message.indexOf(NO_SUCH_CLASS_PREFIX);
        if (start >= 0) {
            start += NO_SUCH_CLASS_PREFIX.length();

            int end = message.indexOf(" --", start);
            if (end < 0) {
                end = message.length();
            }

            return message.substring(start, end).trim();
        }

        Matcher matcher = UNABLE_TO_RESOLVE_CLASS_PATTERN.matcher(message);
        if (matcher.find()) {
            String extracted = matcher.group(1);
            return extracted == null ? null : extracted.trim();
        }

        return null;
    }

    /**
     * Collect diagnostics from the standalone Groovy compiler (fallback).
     * Used when no JDT working copy is available.
     */
    private void collectFromGroovyCompiler(String uri, List<Diagnostic> diagnostics) {
        String content = documentManager.getContent(uri);
        if (content == null) {
            return;
        }

        GroovyCompilerService compilerService = documentManager.getCompilerService();
        GroovyCompilerService.ParseResult result = compilerService.getCachedResult(uri);
        if (result == null) {
            result = compilerService.parse(uri, content);
        }

        for (org.codehaus.groovy.syntax.SyntaxException error : result.getErrors()) {
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setSeverity(DiagnosticSeverity.Error);

            int startLine = Math.max(0, error.getStartLine() - 1);
            int startCol = Math.max(0, error.getStartColumn() - 1);
            int endLine = Math.max(startLine, error.getEndLine() - 1);
            int endCol = Math.max(startCol, error.getEndColumn() - 1);

            diagnostic.setRange(new Range(
                    new Position(startLine, startCol),
                    new Position(endLine, endCol)));
            diagnostic.setMessage(error.getMessage());
                diagnostic.setSource(DIAGNOSTIC_SOURCE_GROOVY);

            diagnostics.add(diagnostic);
        }
    }

    /**
     * Convert a JDT {@link IProblem} to an LSP {@link Diagnostic}.
     * Uses a pre-computed {@link PositionUtils.LineIndex} for O(log n)
     * offset→position conversion instead of scanning the full content.
     *
     * @param problem   the JDT problem
     * @param lineIndex pre-computed line index for the document
     */
    private Diagnostic toDiagnostic(IProblem problem, PositionUtils.LineIndex lineIndex) {
        Diagnostic diagnostic = new Diagnostic();

        // Map severity
        if (problem.isError()) {
            diagnostic.setSeverity(DiagnosticSeverity.Error);
        } else if (problem.isWarning()) {
            diagnostic.setSeverity(DiagnosticSeverity.Warning);
        } else {
            diagnostic.setSeverity(DiagnosticSeverity.Information);
        }

        Range range;
        int sourceStart = problem.getSourceStart();
        int sourceEnd = problem.getSourceEnd();

        if (sourceStart >= 0 && sourceEnd >= 0 && lineIndex != null) {
            // O(log n) offset→position via pre-computed line index
            Position start = lineIndex.offsetToPosition(sourceStart);
            Position end = lineIndex.offsetToPosition(sourceEnd + 1);
            range = new Range(start, end);
        } else {
            // Fallback: use the line number (1-based from JDT, 0-based for LSP)
            int line = Math.max(0, problem.getSourceLineNumber() - 1);
            range = new Range(
                    new Position(line, 0),
                    new Position(line, Integer.MAX_VALUE));
        }

        diagnostic.setRange(range);
        diagnostic.setMessage(problem.getMessage());
        diagnostic.setSource(DIAGNOSTIC_SOURCE_GROOVY);
        diagnostic.setCode(String.valueOf(problem.getID()));

        return diagnostic;
    }

    /**
     * Backward-compatible overload: converts String content to LineIndex and delegates.
     */
    private Diagnostic toDiagnostic(IProblem problem, String content) {
        PositionUtils.LineIndex idx = content != null ? PositionUtils.buildLineIndex(content) : null;
        return toDiagnostic(problem, idx);
    }

    /**
     * Convert an absolute character offset range to an LSP {@link Range}.
     * Kept for backward compatibility with callers that don't have a LineIndex.
     *
     * @param content the full document text
     * @param start   the start offset (inclusive)
     * @param end     the end offset (exclusive)
     * @return the corresponding LSP range with correct line/column positions
     */
    private Range offsetRangeToLspRange(String content, int start, int end) {
        PositionUtils.LineIndex idx = PositionUtils.buildLineIndex(content);
        return new Range(idx.offsetToPosition(start), idx.offsetToPosition(end));
    }

    /**
     * Backward-compatible overload: converts String content to LineIndex and delegates.
     */
    private Diagnostic toDiagnostic(IMarker marker, String content) {
        PositionUtils.LineIndex idx = content != null ? PositionUtils.buildLineIndex(content) : null;
        return toDiagnostic(marker, idx);
    }

    /**
     * Convert an Eclipse {@link IMarker} to an LSP {@link Diagnostic}.
     */
    private Diagnostic toDiagnostic(IMarker marker, PositionUtils.LineIndex lineIndex) {
        try {
            Diagnostic diagnostic = new Diagnostic();

            // Severity
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            switch (severity) {
                case IMarker.SEVERITY_ERROR:
                    diagnostic.setSeverity(DiagnosticSeverity.Error);
                    break;
                case IMarker.SEVERITY_WARNING:
                    diagnostic.setSeverity(DiagnosticSeverity.Warning);
                    break;
                default:
                    diagnostic.setSeverity(DiagnosticSeverity.Information);
            }

            // Range — convert absolute character offsets to line:column
            int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
            int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);

            Range range;
            if (charStart >= 0 && charEnd >= 0 && lineIndex != null) {
                range = new Range(
                        lineIndex.offsetToPosition(charStart),
                        lineIndex.offsetToPosition(charEnd));
            } else {
                int line = Math.max(0, marker.getAttribute(IMarker.LINE_NUMBER, 1) - 1);
                range = new Range(
                        new Position(line, 0),
                        new Position(line, Integer.MAX_VALUE));
            }

            diagnostic.setRange(range);
            diagnostic.setMessage(marker.getAttribute(IMarker.MESSAGE, "Unknown error"));
            diagnostic.setSource(DIAGNOSTIC_SOURCE_GROOVY);

            return diagnostic;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to convert marker to diagnostic", e);
            return null;
        }
    }

    /**
     * Shut down the diagnostics scheduler. Called during server shutdown.
     */
    public void shutdown() {
        scheduler.shutdownNow();
        pendingPublish.values().forEach(f -> f.cancel(true));
        pendingPublish.clear();
        diagnosticsInFlight.clear();
    }
}
