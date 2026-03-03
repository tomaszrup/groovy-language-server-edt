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

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

/**
 * Manages open document state and JDT working copies.
 * <p>
 * When a document is opened in the editor, we create a JDT working copy
 * so that groovy-eclipse's compiler and type resolver have access to the
 * latest unsaved content. On close, the working copy is discarded.
 */
public class DocumentManager {

    private static final String CLASSES_LOG_SEGMENT = " classes=";

    /**
     * Tracks open documents: URI → current source text.
     */
    private final Map<String, StringBuilder> openDocuments = new ConcurrentHashMap<>();

    /**
     * Maps normalized URIs to the latest client-supplied URI form.
     * This preserves the URI style used by the editor for diagnostics publishing.
     */
    private final Map<String, String> clientUris = new ConcurrentHashMap<>();

    /**
     * Tracks JDT working copies for open documents.
     */
    private final Map<String, ICompilationUnit> workingCopies = new ConcurrentHashMap<>();

    /**
     * Standalone Groovy compiler service used as a fallback when JDT
     * working copies cannot be created (e.g., file outside Eclipse project).
     */
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    /**
     * Tracks project root directories that have already been imported into
     * the Eclipse workspace, to avoid re-importing the same project.
     */
    private final Set<String> importedProjectRoots = ConcurrentHashMap.newKeySet();

    // ---- URI normalization ----

    /**
     * Normalize a file URI to a canonical form so that different encodings
     * of the same file path (e.g., from the LSP client vs. JDT) map to
     * the same key.
     * <p>
     * Handles:
     * <ul>
     *   <li>{@code file:///c%3A/path%20%E2%80%94} (VS Code, fully percent-encoded)</li>
     *   <li>{@code file:/C:/path%20\u2014} (JDT, partially encoded with raw Unicode)</li>
     * </ul>
     *
     * @param uri a file URI string
     * @return a canonical URI string, or the input unchanged if normalization fails
     */
    public static String normalizeUri(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return uri;
        }
        try {
            String filePath = uriToFilePath(uri);
            if (filePath != null) {
                File f = new File(filePath);
                // File.toURI() produces consistent percent-encoding
                String result = f.toURI().toString();
                // Remove trailing slash for files (File.toURI adds one for dirs)
                if (result.endsWith("/") && !f.isDirectory()) {
                    result = result.substring(0, result.length() - 1);
                }
                return result;
            }
        } catch (Exception e) {
            // fall through
        }
        return uri;
    }

    /**
     * Convert a file URI (any encoding variant) to a local file path.
     */
    private static String uriToFilePath(String uri) {
        // Try standard URI parsing first (works for properly encoded URIs)
        try {
            URI parsed = new URI(uri);
            if ("file".equals(parsed.getScheme())) {
                File file = new File(parsed);
                String path = file.getAbsolutePath();
                // Normalize drive letter to lowercase on Windows
                if (path.length() >= 2 && path.charAt(1) == ':') {
                    path = Character.toLowerCase(path.charAt(0)) + path.substring(1);
                }
                return path;
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            // URI might contain raw non-ASCII chars (from JDT URI.toString())
            // Fall through to manual parsing
        }

        // Manual fallback: strip scheme and decode
        try {
            String path = uri;
            if (path.startsWith("file:///")) {
                path = path.substring(8);
            } else if (path.startsWith("file://")) {
                path = path.substring(7);
            } else if (path.startsWith("file:/")) {
                path = path.substring(6);
            }
            // Decode percent-encoded characters
            path = URLDecoder.decode(path, "UTF-8");
            String absPath = new File(path).getAbsolutePath();
            // Normalize drive letter
            if (absPath.length() >= 2 && absPath.charAt(1) == ':') {
                absPath = Character.toLowerCase(absPath.charAt(0)) + absPath.substring(1);
            }
            return absPath;
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Build marker file names used to detect a project root directory.
     */
    private static final String[] BUILD_MARKERS = {
        "build.gradle", "build.gradle.kts", "pom.xml",
        "settings.gradle", "settings.gradle.kts",
        ".project", "gradlew", "mvnw"
    };

    /**
     * Our working copy owner — used by JDT to distinguish our working copies.
     */
    private final WorkingCopyOwner workingCopyOwner = new WorkingCopyOwner() {
        @Override
        public String findSource(String typeName, String packageName) {
            // Could be extended to provide in-memory source for types
            return null;
        }
    };

    /**
     * Called when a document is opened. Stores the content and creates a JDT working copy.
     */
    public void didOpen(String uri, String text) {
        String clientUri = uri;
        uri = normalizeUri(uri);
        openDocuments.put(uri, new StringBuilder(text));
        if (clientUri != null) {
            clientUris.put(uri, clientUri);
        }

        // Create JDT working copy
        ICompilationUnit cu = findCompilationUnit(uri);
        if (cu != null) {
            try {
                ICompilationUnit workingCopy = cu.getWorkingCopy(workingCopyOwner, null);
                workingCopy.getBuffer().setContents(text);
                workingCopy.reconcile(ICompilationUnit.NO_AST, true, true, workingCopyOwner, null);
                workingCopies.put(uri, workingCopy);
                GroovyLanguageServerPlugin.logInfo("JDT working copy created for " + uri
                        + " (type: " + workingCopy.getClass().getName() + ")");
                return;
            } catch (JavaModelException e) {
                GroovyLanguageServerPlugin.logError("Failed to create working copy for " + uri, e);
            }
        }

        // Fallback: parse with standalone Groovy compiler when JDT is unavailable
        GroovyLanguageServerPlugin.logInfo(
                "No JDT working copy for " + uri + "; using Groovy compiler fallback.");
        compilerService.parse(uri, text);
    }

    /**
     * Called when a document is changed. Applies incremental edits.
     */
    public void didChange(String uri, java.util.List<TextDocumentContentChangeEvent> changes) {
        String clientUri = uri;
        uri = normalizeUri(uri);
        if (clientUri != null) {
            clientUris.put(uri, clientUri);
        }
        StringBuilder content = openDocuments.get(uri);
        if (content == null) {
            return;
        }

        for (TextDocumentContentChangeEvent change : changes) {
            if (change.getRange() == null) {
                // Full document replacement
                content.setLength(0);
                content.append(change.getText());
            } else {
                // Incremental change — convert LSP range to offsets
                int startOffset = positionToOffset(content, change.getRange().getStart());
                int endOffset = positionToOffset(content, change.getRange().getEnd());
                if (startOffset >= 0 && endOffset >= startOffset && endOffset <= content.length()) {
                    content.replace(startOffset, endOffset, change.getText());
                }
            }
        }

        // Update the JDT working copy
        ICompilationUnit workingCopy = workingCopies.get(uri);
        if (workingCopy != null) {
            try {
                workingCopy.getBuffer().setContents(content.toString());
                workingCopy.reconcile(ICompilationUnit.NO_AST, true, true, workingCopyOwner, null);
            } catch (JavaModelException e) {
                GroovyLanguageServerPlugin.logError("Failed to reconcile working copy for " + uri, e);
            }
        } else {
            // Fallback: re-parse with Groovy compiler
            compilerService.parse(uri, content.toString());
        }
    }

    /**
     * Called when a document is closed. Discards the content and working copy.
     */
    public void didClose(String uri) {
        uri = normalizeUri(uri);
        openDocuments.remove(uri);
        clientUris.remove(uri);
        compilerService.invalidate(uri);

        ICompilationUnit workingCopy = workingCopies.remove(uri);
        if (workingCopy != null) {
            try {
                workingCopy.discardWorkingCopy();
            } catch (JavaModelException e) {
                GroovyLanguageServerPlugin.logError("Failed to discard working copy for " + uri, e);
            }
        }
    }

    /**
     * Returns the current content of an open document.
     */
    public String getContent(String uri) {
        StringBuilder content = openDocuments.get(normalizeUri(uri));
        return (content != null) ? content.toString() : null;
    }

    /**
     * Returns the JDT working copy for an open document.
     */
    public ICompilationUnit getWorkingCopy(String uri) {
        return workingCopies.get(normalizeUri(uri));
    }

    /**
     * Returns all currently open document URIs.
     */
    public java.util.Set<String> getOpenDocumentUris() {
        return openDocuments.keySet();
    }

    /**
     * Return the most recent client URI form for a document.
     * Falls back to the normalized URI when no client form is known.
     */
    public String getClientUri(String uri) {
        String normalized = normalizeUri(uri);
        return clientUris.getOrDefault(normalized, normalized);
    }

    public WorkingCopyOwner getWorkingCopyOwner() {
        return workingCopyOwner;
    }

    /**
     * Temporarily set the buffer contents of a working copy and reconcile.
     * This is useful for patching the source before operations like codeSelect
     * that require a parseable AST.
     */
    public void reconcileWithContent(ICompilationUnit workingCopy, String content)
            throws JavaModelException {
        workingCopy.getBuffer().setContents(content);
        workingCopy.reconcile(ICompilationUnit.NO_AST, true, true, workingCopyOwner, null);
    }

    /**
     * Returns the Groovy AST for a document, either from the JDT working copy
     * (if available) or from the standalone Groovy compiler fallback.
     * Returns {@code null} if no AST is available.
     */
    public ModuleNode getGroovyAST(String uri) {
        uri = normalizeUri(uri);
        ICompilationUnit workingCopy = workingCopies.get(uri);
        if (workingCopy != null) {
            ModuleNode jdtModule = extractModuleNodeFromWorkingCopy(workingCopy, uri);
            if (jdtModule != null) {
                return jdtModule;
            }
        } else {
            GroovyLanguageServerPlugin.logInfo("[ast] No working copy for " + uri);
        }

        GroovyCompilerService.ParseResult cached = compilerService.getCachedResult(uri);
        if (cached != null && cached.hasAST()) {
            ModuleNode module = cached.getModuleNode();
            GroovyLanguageServerPlugin.logInfo("[ast] Standalone cache for " + uri
                    + CLASSES_LOG_SEGMENT + classCount(module));
            return module;
        }

        String content = getContent(uri);
        if (content != null) {
            GroovyCompilerService.ParseResult result = compilerService.parse(uri, content);
            if (result.hasAST()) {
                ModuleNode module = result.getModuleNode();
                GroovyLanguageServerPlugin.logInfo("[ast] Parsed on-demand for " + uri
                        + CLASSES_LOG_SEGMENT + classCount(module));
                return module;
            }
            GroovyLanguageServerPlugin.logInfo("[ast] Parse failed for " + uri);
        }

        GroovyLanguageServerPlugin.logInfo("[ast] No AST available for " + uri);
        return null;
    }

    private ModuleNode extractModuleNodeFromWorkingCopy(ICompilationUnit workingCopy, String uri) {
        try {
            java.lang.reflect.Method getModuleNode = workingCopy.getClass().getMethod("getModuleNode");
            Object result = getModuleNode.invoke(workingCopy);
            if (result instanceof ModuleNode module) {
                GroovyLanguageServerPlugin.logInfo("[ast] JDT module for " + uri
                        + CLASSES_LOG_SEGMENT + classCount(module));
                return module;
            }
            GroovyLanguageServerPlugin.logInfo("[ast] JDT getModuleNode() returned "
                    + (result != null ? result.getClass().getName() : "null") + " for " + uri);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logInfo("[ast] JDT reflection failed for " + uri + ": " + e.getMessage());
        }
        return null;
    }

    private int classCount(ModuleNode module) {
        return module.getClasses() != null ? module.getClasses().size() : 0;
    }

    /**
     * Returns the Groovy compiler service for direct access to parse results.
     */
    public GroovyCompilerService getCompilerService() {
        return compilerService;
    }

    /**
     * Returns {@code true} if a JDT working copy exists for this document,
     * {@code false} if only the Groovy fallback parser is available.
     */
    public boolean hasJdtWorkingCopy(String uri) {
        return workingCopies.containsKey(normalizeUri(uri));
    }

    // ---- Private helpers ----

    /**
     * Find the JDT {@link ICompilationUnit} corresponding to a document URI.
     * <p>
     * If the file is not found in any existing Eclipse project, this method
     * attempts to detect the file's project root (by walking up the directory
     * tree looking for build markers like {@code build.gradle} or {@code pom.xml})
     * and dynamically imports it as an Eclipse JDT project so that JDT
     * features (completion, navigation, diagnostics) become available.
     */
    private ICompilationUnit findCompilationUnit(String uriString) {
        try {
            URI uri = URI.create(uriString);
            java.io.File file = new java.io.File(uri);

            // First, try to find the file in an existing Eclipse project
            ICompilationUnit cu = lookupCompilationUnit(uri);
            if (cu != null) {
                return cu;
            }

            // Not found — the file is probably outside the primary workspace.
            // Detect the project root and dynamically import it.
            java.io.File projectRoot = detectProjectRoot(file);
            if (projectRoot != null && importedProjectRoots.add(projectRoot.getAbsolutePath())) {
                GroovyLanguageServerPlugin.logInfo(
                        "File " + file.getName() + " is outside known projects. "
                        + "Importing external project from: " + projectRoot.getAbsolutePath());
                importExternalProject(projectRoot);

                // Retry lookup after import
                cu = lookupCompilationUnit(uri);
                if (cu != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "Successfully created JDT compilation unit after importing external project.");
                    return cu;
                }
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to find compilation unit for " + uriString, e);
        }
        return null;
    }

    /**
     * Look up an {@link ICompilationUnit} in the Eclipse workspace for a URI.
     * <p>
     * When multiple Eclipse projects map to the same filesystem path (e.g. a
     * subproject's linked folder AND an external whole-directory project), we
     * prefer the most specific project — the one whose effective content root
     * is the longest prefix of the file's path.
     * This avoids creating working copies under an external "catch-all" project
     * with a broken or incomplete classpath.
     */
    private ICompilationUnit lookupCompilationUnit(URI uri) {
        try {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                    .findFilesForLocationURI(uri);
            if (files.length == 0) {
                return null;
            }

            // If only one match, use it directly
            if (files.length == 1) {
                org.eclipse.jdt.core.IJavaElement element = JavaCore.create(files[0]);
                if (element instanceof ICompilationUnit compilationUnit) {
                    return compilationUnit;
                }
                return null;
            }

            // Multiple matches: prefer the file whose project's effective
            // content root is the longest (most specific).  This is consistent
            // with GroovyLanguageServer.findEclipseProjectByPath().
            ICompilationUnit bestCu = null;
            int bestContentRootLength = -1;

            for (IFile file : files) {
                org.eclipse.jdt.core.IJavaElement element = JavaCore.create(file);
                if (element instanceof ICompilationUnit cu) {
                    int contentRootLen = getProjectContentRootLength(file.getProject());

                    if (contentRootLen > bestContentRootLength) {
                        bestContentRootLength = contentRootLen;
                        bestCu = cu;
                    }
                }
            }

            return bestCu;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("lookupCompilationUnit failed for " + uri, e);
        }
        return null;
    }

    /**
     * Determine the effective filesystem content root length for a project.
     * <p>
     * For linked-folder projects (created by
     * {@code GroovyLanguageServer.createEclipseProjectFor}), the content root
     * is the linked folder's target directory (e.g.,
     * {@code c:\sample\consumer}).
     * <p>
     * For external projects (created by {@code importExternalProject} with an
     * explicit location), the content root is the project's filesystem
     * location (e.g., {@code c:\sample}).
     * <p>
     * A longer content root means a more specific project.
     */
    private int getProjectContentRootLength(IProject project) {
        // 1. Check for a "linked" folder — this is the primary mechanism
        //    used by on-the-fly subproject setup
        IResource linkedRoot = project.findMember("linked");
        if (linkedRoot != null && linkedRoot.isLinked()) {
            org.eclipse.core.runtime.IPath linkedLocation =
                    linkedRoot.getLocation();
            if (linkedLocation != null) {
                return linkedLocation.toOSString().length();
            }
        }

        // 2. For external projects with an explicit filesystem location,
        //    use the project's location URI.
        //    (Workspace-internal projects have no explicit locationURI and
        //    their getLocation() returns a workspace metadata path, which
        //    is NOT the filesystem content root.)
        try {
            java.net.URI locationUri = project.getDescription().getLocationURI();
            if (locationUri != null) {
                // Explicit location → external project
                org.eclipse.core.runtime.IPath projLoc = project.getLocation();
                if (projLoc != null) {
                    return projLoc.toOSString().length();
                }
            }
        } catch (org.eclipse.core.runtime.CoreException e) {
            // Ignore — fall through to default
        }

        // 3. Fallback: workspace-internal project without linked folder.
        //    Return 0 so it loses to any project with a real content root.
        return 0;
    }

    /**
     * Detect the project root for a file by walking up the directory tree
     * looking for build markers (build.gradle, pom.xml, settings.gradle, etc.).
     * Returns the deepest directory that contains a build marker, or
     * {@code null} if no marker is found up to the filesystem root.
     */
    private java.io.File detectProjectRoot(java.io.File file) {
        java.io.File dir = file.isDirectory() ? file : file.getParentFile();
        java.io.File bestCandidate = null;

        while (dir != null) {
            for (String marker : BUILD_MARKERS) {
                if (new java.io.File(dir, marker).exists()) {
                    bestCandidate = dir;
                    // Don't break — keep walking up in case this is a subproject
                    // and there's a root project above (e.g., settings.gradle)
                }
            }
            // If we found a settings.gradle at this level, this is the root
            if (bestCandidate != null) {
                java.io.File settingsGradle = new java.io.File(dir, "settings.gradle");
                java.io.File settingsGradleKts = new java.io.File(dir, "settings.gradle.kts");
                if (settingsGradle.exists() || settingsGradleKts.exists()) {
                    return dir; // This is the root project
                }
            }
            dir = dir.getParentFile();
        }

        return bestCandidate; // May be null if no markers found
    }

    /**
     * Import an external project directory as an Eclipse JDT project.
     * Creates an Eclipse project linked to the external directory, adds the
     * Java and Groovy natures, and configures source folders.
     */
    private void importExternalProject(java.io.File projectRoot) {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();

            // Generate a unique project name based on the directory name
            String baseName = projectRoot.getName();
            String projectName = "ExtGroovy_" + baseName;

            // Ensure unique name if a project with this name already exists
            int counter = 1;
            while (workspace.getRoot().getProject(projectName).exists()) {
                projectName = "ExtGroovy_" + baseName + "_" + counter++;
            }

            IProject project = workspace.getRoot().getProject(projectName);
            IProjectDescription description = workspace.newProjectDescription(projectName);

            // Point the project to the external directory
            IPath locationPath = Path.fromOSString(projectRoot.getAbsolutePath());
            description.setLocation(locationPath);

            // Add Java and Groovy natures
            description.setNatureIds(new String[]{
                    JavaCore.NATURE_ID,
                    "org.eclipse.jdt.groovy.core.groovyNature"
            });

            project.create(description, new NullProgressMonitor());
            project.open(new NullProgressMonitor());

            GroovyLanguageServerPlugin.logInfo(
                    "Created Eclipse project '" + projectName + "' at " + projectRoot.getAbsolutePath());

            // Configure the classpath with detected source folders
            configureExternalProjectClasspath(project);

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to import external project from " + projectRoot.getAbsolutePath(), e);
        }
    }

    /**
     * Configure the JDT classpath for an imported external project.
     * Detects standard source directories (Maven/Gradle conventions)
     * and sets up the build path. Library dependencies are provided
     * asynchronously by the Red Hat Java extension via the
     * {@code groovy/classpathUpdate} notification.
     */
    private void configureExternalProjectClasspath(IProject project) {
        try {
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                return;
            }

            String[] candidateSrcDirs = {
                "src/main/java",
                "src/main/groovy",
                "src/test/java",
                "src/test/groovy",
                "src/main/resources",
                "src/test/resources",
                "src",
            };

            List<IClasspathEntry> entries = new ArrayList<>();
            boolean foundAny = false;
            Set<String> addedSrcDirs = new java.util.LinkedHashSet<>();

            for (String srcDir : candidateSrcDirs) {
                IFolder folder = project.getFolder(srcDir);
                if (folder == null || !folder.exists() || hasNestedConflict(srcDir, addedSrcDirs)) {
                    if (folder != null && folder.exists()) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[ext] Skipping nested source folder: " + srcDir);
                    }
                    continue;
                }
                entries.add(JavaCore.newSourceEntry(folder.getFullPath()));
                addedSrcDirs.add(srcDir);
                foundAny = true;
                GroovyLanguageServerPlugin.logInfo(
                        "[ext] Added source folder: " + srcDir);
            }

            if (!foundAny) {
                entries.add(JavaCore.newSourceEntry(project.getFullPath()));
                GroovyLanguageServerPlugin.logInfo(
                        "[ext] No standard source folders found; using project root.");
            }

            // Add JRE system library
            entries.add(JavaCore.newContainerEntry(
                    new Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

            // NOTE: Library dependencies are added via groovy/classpathUpdate
            // when the Red Hat Java extension provides resolved classpath entries.

            javaProject.setRawClasspath(
                    entries.toArray(new IClasspathEntry[0]),
                    project.getFullPath().append("bin"),
                    new NullProgressMonitor());

            GroovyLanguageServerPlugin.logInfo(
                    "[ext] Configured classpath with " + entries.size()
                    + " entries (source folders + JRE).");

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to configure classpath for external project "
                    + project.getName() + " (non-fatal): " + e.getMessage(), e);
        }
    }

    /**
     * Check if {@code srcDir} conflicts with any already-added source directory.
     * JDT does not allow nesting source folders.
     */
    private boolean hasNestedConflict(String srcDir, Set<String> addedSrcDirs) {
        for (String existing : addedSrcDirs) {
            if (existing.startsWith(srcDir + "/") || srcDir.startsWith(existing + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert an LSP {@link org.eclipse.lsp4j.Position} to a character offset.
     */
    private int positionToOffset(CharSequence content, org.eclipse.lsp4j.Position position) {
        int line = 0;
        int offset = 0;

        while (offset < content.length() && line < position.getLine()) {
            if (content.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }

        return offset + position.getCharacter();
    }
}
