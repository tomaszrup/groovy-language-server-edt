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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.providers.InlayHintSettings;
import org.eclipse.groovy.ls.core.providers.JdtSearchSupport;
import org.eclipse.groovy.ls.core.providers.ReferenceSearchHelper;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Implements the LSP {@link WorkspaceService} for workspace-level operations.
 * <p>
 * Handles workspace symbol search, configuration changes, and watched file events.
 */
public class GroovyWorkspaceService implements WorkspaceService {

    private static final String JSON_GROOVY = "groovy";
    private static final String JSON_ENABLED = "enabled";
    private static final String LOG_NEW_URI = " new=";
    private static final String LOG_EDITS = " edits=";
    private static final int MAX_WORKSPACE_SYMBOL_RESULTS = 200;
    private static final long WORKSPACE_GROOVY_FILES_CACHE_TTL_MS = 30_000;
    private static final String IMPORT_PREFIX = "import ";
    private static final Pattern PACKAGE_DECLARATION_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([\\w.]+)\\s*;?\\s*$");

    private final GroovyLanguageServer server;
    private final DocumentManager documentManager;
    private volatile List<Path> cachedWorkspaceGroovyFiles = List.of();
    private volatile long workspaceGroovyFilesCacheTimestampMs;
    private final Object workspaceGroovyFilesCacheLock = new Object();

    public GroovyWorkspaceService(GroovyLanguageServer server, DocumentManager documentManager) {
        this.server = server;
        this.documentManager = documentManager;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            List<SymbolInformation> symbols = new ArrayList<>();
            Set<String> seenHandles = new HashSet<>();
            String query = params.getQuery();

            if (query == null || query.isEmpty()) {
                return Either.forLeft(symbols);
            }

            try {
                IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

                org.eclipse.core.runtime.IProgressMonitor stopAfterLimit =
                        new org.eclipse.core.runtime.NullProgressMonitor() {
                            @Override
                            public boolean isCanceled() {
                                return symbols.size() >= MAX_WORKSPACE_SYMBOL_RESULTS;
                            }
                        };

                JdtSearchSupport.searchAllTypeNames(
                        null,
                        SearchPattern.R_PATTERN_MATCH,
                        query.toCharArray(),
                        SearchPattern.R_CAMELCASE_MATCH | SearchPattern.R_PREFIX_MATCH,
                        IJavaSearchConstants.TYPE,
                        scope,
                        new TypeNameMatchRequestor() {
                            @Override
                            public void acceptTypeNameMatch(TypeNameMatch match) {
                                IType type = match.getType();
                                if (type == null) {
                                    return;
                                }

                                String handle = type.getHandleIdentifier();
                                if (handle != null && !seenHandles.add(handle)) {
                                    return;
                                }

                                SymbolInformation symbol = toSymbolInformation(type);
                                if (symbol != null) {
                                    symbols.add(symbol);
                                }
                            }
                        },
                        IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH,
                        stopAfterLimit);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Workspace symbol search failed", e);
            }

            return Either.forLeft(symbols);
        });
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        GroovyLanguageServerPlugin.logInfo("Configuration changed: " + params.getSettings());

        Object settings = params.getSettings();
        if (!(settings instanceof com.google.gson.JsonObject json)) {
            return;
        }

        com.google.gson.JsonObject groovy = getChildJson(json, JSON_GROOVY);
        if (groovy == null) {
            return;
        }

        applyLogLevelSetting(groovy);
        applyFormatterSettings(groovy);
        com.google.gson.JsonObject inlayHints = getChildJson(groovy, "inlayHints");
        server.getGroovyTextDocumentService().updateInlayHintSettings(parseInlayHintSettings(inlayHints));
    }

    private void applyLogLevelSetting(com.google.gson.JsonObject groovy) {
        com.google.gson.JsonObject ls = getChildJson(groovy, "ls");
        if (ls == null || !ls.has("logLevel")) {
            return;
        }
        com.google.gson.JsonElement levelElem = ls.get("logLevel");
        String level = (levelElem != null && !levelElem.isJsonNull()) ? levelElem.getAsString() : null;
        GroovyLanguageServerPlugin.setLogLevelFromString(level);
        GroovyLanguageServerPlugin.logInfo("Log level set to: " + level);
    }

    private void applyFormatterSettings(com.google.gson.JsonObject groovy) {
        com.google.gson.JsonObject format = getChildJson(groovy, "format");
        if (format == null || !format.has("settingsUrl")) {
            return;
        }

        com.google.gson.JsonElement urlElem = format.get("settingsUrl");
        String profilePath = (urlElem != null && !urlElem.isJsonNull())
                ? urlElem.getAsString()
                : null;
        server.getGroovyTextDocumentService().updateFormatterProfile(profilePath);
    }

    private com.google.gson.JsonObject getChildJson(com.google.gson.JsonObject root, String key) {
        if (root == null || !root.has(key)) {
            return null;
        }
        com.google.gson.JsonElement child = root.get(key);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }

    private InlayHintSettings parseInlayHintSettings(com.google.gson.JsonObject inlayHints) {
        InlayHintSettings defaults = InlayHintSettings.defaults();
        if (inlayHints == null) {
            return defaults;
        }

        boolean variableTypesEnabled = readNestedBoolean(
                inlayHints,
                "variableTypes",
            JSON_ENABLED,
                defaults.isVariableTypesEnabled());
        boolean parameterNamesEnabled = readNestedBoolean(
                inlayHints,
                "parameterNames",
            JSON_ENABLED,
                defaults.isParameterNamesEnabled());
        boolean closureParameterTypesEnabled = readNestedBoolean(
                inlayHints,
                "closureParameterTypes",
            JSON_ENABLED,
                defaults.isClosureParameterTypesEnabled());
        boolean methodReturnTypesEnabled = readNestedBoolean(
                inlayHints,
                "methodReturnTypes",
            JSON_ENABLED,
                defaults.isMethodReturnTypesEnabled());

        return new InlayHintSettings(
                variableTypesEnabled,
                parameterNamesEnabled,
                closureParameterTypesEnabled,
                methodReturnTypesEnabled);
    }

    private boolean readNestedBoolean(
            com.google.gson.JsonObject root,
            String objectName,
            String propertyName,
            boolean defaultValue) {
        if (root == null || !root.has(objectName) || !root.get(objectName).isJsonObject()) {
            return defaultValue;
        }

        com.google.gson.JsonObject obj = root.getAsJsonObject(objectName);
        if (!obj.has(propertyName)) {
            return defaultValue;
        }

        com.google.gson.JsonElement value = obj.get(propertyName);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        try {
            return value.getAsBoolean();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        GroovyLanguageServerPlugin.logInfo("Watched files changed: " + params.getChanges().size() + " changes");
        CompletableFuture.runAsync(() -> handleWatchedFileChanges(params))
                .exceptionally(e -> {
                    GroovyLanguageServerPlugin.logError("Watched file changes handler failed", (Exception) e);
                    return null;
                });
    }

    private void handleWatchedFileChanges(DidChangeWatchedFilesParams params) {
        try {
            SourceChangeSummary summary = refreshChangedSourceFiles(params);
            if (!summary.hasSourceChange) {
                return;
            }

            // Only refresh the projects that own the changed files,
            // not the entire workspace.
            refreshAffectedProjects(params);
            GroovyLanguageServerPlugin.logInfo(
                    "[diag-trace] didChangeWatchedFiles sourceChanged=true javaChanged="
                            + summary.hasJavaSourceChange + " -> refresh diagnostics");
            if (summary.hasJavaSourceChange) {
                // Java source changed — need a workspace build so that Groovy
                // files that reference the changed Java types get updated.
                server.scheduleDebouncedBuild();
            } else {
                // Only Groovy/resource files changed — publish diagnostics for
                // ALL open files, not just the changed ones.  Changes in one
                // file may affect unused-declaration fading in other open files.
                GroovyTextDocumentService tds = server.getGroovyTextDocumentService();
                tds.publishDiagnosticsForOpenDocuments();
            }

            // Source files changed externally — reference counts shown in
            // code lenses of *other* open files may now be stale.
            server.getGroovyTextDocumentService().refreshCodeLenses();
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to handle file changes", e);
        }
    }

    private SourceChangeSummary refreshChangedSourceFiles(DidChangeWatchedFilesParams params) {
        boolean hasSourceChange = false;
        boolean hasJavaSourceChange = false;
        boolean hasGroovySourceChange = false;
        for (FileEvent event : params.getChanges()) {
            String uri = event.getUri();
            if (!isSourceFileUri(uri)) {
                continue;
            }

            hasSourceChange = true;
            if (isJavaFileUri(uri)) {
                hasJavaSourceChange = true;
            } else if (isGroovyFileUri(uri)) {
                hasGroovySourceChange = true;
            }
            refreshWorkspaceFile(uri);
        }
        if (hasGroovySourceChange) {
            ReferenceSearchHelper.clearCaches();
            invalidateWorkspaceGroovyFilesCache();
        }
        return new SourceChangeSummary(hasSourceChange, hasJavaSourceChange);
    }

    private void refreshWorkspaceFile(String uri) {
        try {
            URI fileUri = toFileLocationUri(uri);
            if (fileUri == null) {
                return;
            }
            org.eclipse.core.resources.IFile[] files =
                    ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(fileUri);
            for (org.eclipse.core.resources.IFile file : files) {
                file.refreshLocal(
                        org.eclipse.core.resources.IResource.DEPTH_ZERO,
                        new org.eclipse.core.runtime.NullProgressMonitor());
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to refresh file: " + uri, e);
        }
    }

    private void refreshOpenProjects() throws org.eclipse.core.runtime.CoreException {
        for (org.eclipse.core.resources.IProject project
                : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                project.refreshLocal(
                        org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                        new org.eclipse.core.runtime.NullProgressMonitor());
            }
        }
    }

    /**
     * Refresh only the Eclipse projects that own the changed files,
     * using DEPTH_ZERO on each file instead of DEPTH_INFINITE on every project.
     */
    private void refreshAffectedProjects(DidChangeWatchedFilesParams params) {
        java.util.Set<org.eclipse.core.resources.IProject> refreshed = new java.util.HashSet<>();
        for (FileEvent event : params.getChanges()) {
            try {
                URI fileUri = toFileLocationUri(event.getUri());
                if (fileUri == null) {
                    continue;
                }
                org.eclipse.core.resources.IFile[] files =
                        ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(fileUri);
                for (org.eclipse.core.resources.IFile file : files) {
                    org.eclipse.core.resources.IProject project = file.getProject();
                    if (project != null && project.isOpen() && refreshed.add(project)) {
                        project.refreshLocal(
                                org.eclipse.core.resources.IResource.DEPTH_ONE,
                                new org.eclipse.core.runtime.NullProgressMonitor());
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                        "Failed to refresh affected project for: " + event.getUri(), e);
            }
        }
    }

    @Override
    public CompletableFuture<WorkspaceEdit> willRenameFiles(RenameFilesParams params) {
        return CompletableFuture.supplyAsync(() -> buildWillRenameWorkspaceEdit(params));
    }

    private WorkspaceEdit buildWillRenameWorkspaceEdit(RenameFilesParams params) {
        if (params == null || params.getFiles() == null || params.getFiles().isEmpty()) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] willRenameFiles: no files");
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] willRenameFiles: files=" + params.getFiles().size());

        Map<String, List<TextEdit>> changes = new HashMap<>();
        for (FileRename fileRename : params.getFiles()) {
            applyWillRenameForFile(fileRename, changes);
        }

        if (changes.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] willRenameFiles: returning null (no edits)");
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] willRenameFiles: returning changes uris=" + changes.size()
                        + LOG_EDITS + countTextEdits(changes));

        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(changes);
        return edit;
    }

    private void applyWillRenameForFile(FileRename fileRename, Map<String, List<TextEdit>> changes) {
        if (fileRename == null) {
            return;
        }

        String oldUri = DocumentManager.normalizeUri(fileRename.getOldUri());
        String newUri = DocumentManager.normalizeUri(fileRename.getNewUri());
        if (!isSourceFileUri(oldUri) || !isSourceFileUri(newUri)) {
            return;
        }

        String oldExtension = fileExtensionFromUri(oldUri);
        String newExtension = fileExtensionFromUri(newUri);
        if (!sameExtension(oldExtension, newExtension)) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: skip ext mismatch old="
                            + oldUri + LOG_NEW_URI + newUri);
            return;
        }

        String oldTypeName = baseNameFromUri(oldUri);
        String newTypeName = baseNameFromUri(newUri);
        if (oldTypeName == null || newTypeName == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: invalid type names old="
                            + oldTypeName + LOG_NEW_URI + newTypeName + " uri=" + oldUri);
            return;
        }

        TypeMoveContext moveContext = resolveTypeMoveContext(oldUri, newUri, oldTypeName, newTypeName);
        if (moveContext == null) {
            applyGroovyOnlyFallbackEdits(oldUri, newUri, oldTypeName, newTypeName, changes);
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: used groovy-only fallback for " + oldUri);
            return;
        }

        applyResolvedMoveContextEdits(moveContext, oldExtension, oldTypeName, newTypeName, changes);
    }

    private boolean sameExtension(String oldExtension, String newExtension) {
        return oldExtension != null && newExtension != null && oldExtension.equals(newExtension);
    }

    private void addGroovyFallbackPackageEdit(
            String oldUri,
            String newUri,
            Map<String, List<TextEdit>> changes) {
        if (!isGroovyFileUri(oldUri)) {
            return;
        }
        TextEdit fallbackPackageEdit = findGroovyPackageMoveFallbackEdit(oldUri, newUri);
        if (fallbackPackageEdit != null) {
            changes.computeIfAbsent(oldUri, key -> new ArrayList<>()).add(fallbackPackageEdit);
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: groovy package fallback edit added for " + oldUri);
        }
    }

    private void applyResolvedMoveContextEdits(
            TypeMoveContext moveContext,
            String extension,
            String oldTypeName,
            String newTypeName,
            Map<String, List<TextEdit>> changes) {
        String oldUri = moveContext.oldUri;
        boolean javaRename = "java".equals(extension);
        boolean typeNameChanged = !oldTypeName.equals(newTypeName);
        boolean packageChanged = moveContext.isPackageChanged();

        if (!typeNameChanged && !packageChanged) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: no type/package change for " + oldUri);
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] willRenameFiles: candidate old=" + oldTypeName
                        + LOG_NEW_URI + newTypeName
                        + " oldFqn=" + moveContext.oldQualifiedName
                        + LOG_NEW_URI + moveContext.newQualifiedName
                        + " packageChanged=" + packageChanged
                        + " ext=" + extension
                        + " oldUri=" + oldUri + LOG_NEW_URI + moveContext.newUri);

        Map<String, List<TextEdit>> workspaceRenameEdits = typeNameChanged
                ? buildWorkspaceTypeRenameEdits(moveContext.targetType, newTypeName, javaRename)
                : Map.of();
        if (!workspaceRenameEdits.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: workspace rename edits uris="
                            + workspaceRenameEdits.size() + LOG_EDITS
                            + countTextEdits(workspaceRenameEdits));
            mergeEdits(changes, workspaceRenameEdits);
        }

        if (packageChanged) {
            addPackageMoveEdits(moveContext, changes);
        }

        if (typeNameChanged) {
            addDeclarationFallbackEdit(
                    moveContext.oldUri,
                    oldTypeName,
                    newTypeName,
                    javaRename,
                    workspaceRenameEdits,
                    changes);
        }
    }

    private void addPackageMoveEdits(TypeMoveContext moveContext, Map<String, List<TextEdit>> changes) {
        Map<String, List<TextEdit>> moveEdits = buildGroovyTypeMoveEdits(moveContext);
        if (!moveEdits.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: groovy move edits uris="
                            + moveEdits.size() + LOG_EDITS + countTextEdits(moveEdits));
            mergeEdits(changes, moveEdits);
        }

        if (!isGroovyFileUri(moveContext.oldUri)) {
            return;
        }

        String source = getSourceText(moveContext.oldUri);
        TextEdit packageEdit = findGroovyPackageDeclarationMoveEdit(source, moveContext.newPackageName);
        if (packageEdit != null) {
            changes.computeIfAbsent(moveContext.oldUri, k -> new ArrayList<>()).add(packageEdit);
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: groovy package declaration edit added for "
                            + moveContext.oldUri);
        }
    }

    private void addDeclarationFallbackEdit(
            String oldUri,
            String oldTypeName,
            String newTypeName,
            boolean javaRename,
            Map<String, List<TextEdit>> workspaceRenameEdits,
            Map<String, List<TextEdit>> changes) {
        if (javaRename || !workspaceRenameEdits.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: skip declaration fallback for " + oldUri);
            return;
        }

        String source = getSourceText(oldUri);
        if (source == null || source.isBlank()) {
            return;
        }

        TextEdit declarationEdit = findTypeDeclarationRenameEdit(source, oldTypeName, newTypeName);
        if (declarationEdit == null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: no declaration fallback edit for " + oldUri);
            return;
        }

        changes.computeIfAbsent(oldUri, k -> new ArrayList<>()).add(declarationEdit);
        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] willRenameFiles: declaration fallback edit added for " + oldUri);
    }

    // ---- Groovy-only fallback (when JDT type resolution unavailable) ----

    private void applyGroovyOnlyFallbackEdits(
            String oldUri, String newUri,
            String oldTypeName, String newTypeName,
            Map<String, List<TextEdit>> changes) {
        if (!isGroovyFileUri(oldUri)) {
            return;
        }

        String source = getSourceText(oldUri);
        if (source == null || source.isBlank()) {
            return;
        }

        boolean typeNameChanged = !oldTypeName.equals(newTypeName);
        String oldPackageName = extractPackageName(source);
        String newPackageName = inferPackageFromPath(newUri, oldPackageName);
        boolean packageChanged = !Objects.equals(oldPackageName, newPackageName);

        if (!typeNameChanged && !packageChanged) {
            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] groovy fallback: no type/package change for " + oldUri);
            return;
        }

        String oldFqn = buildFqn(oldPackageName, oldTypeName);
        String newFqn = buildFqn(newPackageName, newTypeName);

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] groovy fallback: oldFqn=" + oldFqn + " newFqn=" + newFqn
                        + " typeChanged=" + typeNameChanged + " pkgChanged=" + packageChanged);

        // 1. Rename type declaration in the moved/renamed file
        if (typeNameChanged) {
            TextEdit declEdit = findTypeDeclarationRenameEdit(source, oldTypeName, newTypeName);
            if (declEdit != null) {
                changes.computeIfAbsent(oldUri, k -> new ArrayList<>()).add(declEdit);
            }
        }

        // 2. Update package declaration in the moved file
        if (packageChanged) {
            TextEdit pkgEdit = findGroovyPackageDeclarationMoveEdit(source, newPackageName);
            if (pkgEdit != null) {
                changes.computeIfAbsent(oldUri, k -> new ArrayList<>()).add(pkgEdit);
            }
        }

        // 3. Update references and imports in other workspace files
        updateWorkspaceReferencesWithoutJdt(
                oldUri, oldTypeName, newTypeName,
                oldPackageName, newPackageName,
                oldFqn, newFqn,
                typeNameChanged, packageChanged,
                changes);
    }

    private void updateWorkspaceReferencesWithoutJdt(
            String oldUri,
            String oldTypeName, String newTypeName,
            String oldPackageName, String newPackageName,
            String oldFqn, String newFqn,
            boolean typeNameChanged, boolean packageChanged,
            Map<String, List<TextEdit>> changes) {
        List<Path> workspaceGroovyFiles = collectWorkspaceGroovyFiles();

        int fileCount = 0;
        for (Path filePath : workspaceGroovyFiles) {
            String fileUri = DocumentManager.normalizeUri(filePath.toUri().toString());
            if (Objects.equals(fileUri, oldUri)) {
                continue;
            }

            String content = getSourceText(fileUri);
            if (content == null || content.isBlank()) {
                continue;
            }

            List<TextEdit> fileEdits = buildFallbackFileEdits(
                    content,
                    oldTypeName, newTypeName,
                    oldPackageName, newPackageName,
                    oldFqn, newFqn,
                    typeNameChanged, packageChanged);

            if (!fileEdits.isEmpty()) {
                changes.computeIfAbsent(fileUri, k -> new ArrayList<>()).addAll(fileEdits);
                fileCount++;
            }
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] groovy fallback: scanned " + workspaceGroovyFiles.size()
                        + " files, edited " + fileCount);
    }

    List<TextEdit> buildFallbackFileEdits(
            String content,
            String oldTypeName, String newTypeName,
            String oldPackageName, String newPackageName,
            String oldFqn, String newFqn,
            boolean typeNameChanged, boolean packageChanged) {
        List<TextEdit> edits = new ArrayList<>();

        String filePackage = extractPackageName(content);
        boolean wasInSamePackage = Objects.equals(filePackage, oldPackageName);
        boolean nowInSamePackage = Objects.equals(filePackage, newPackageName);

        boolean hasOldImport = hasExactImport(content, oldFqn);
        boolean hasStarImportForOldPkg = !oldPackageName.isEmpty()
                && hasStarImport(content, oldPackageName);

        // Determine if this file currently has access to the type via simple name
        boolean canAccessBySimpleName = hasOldImport || wasInSamePackage || hasStarImportForOldPkg;

        // Check for conflicting imports (different type with same simple name)
        if (!hasOldImport && hasConflictingImport(content, oldTypeName, oldFqn)) {
            canAccessBySimpleName = false;
        }

        boolean usesSimpleName = canAccessBySimpleName && content.contains(oldTypeName);
        boolean usesFqn = content.contains(oldFqn);

        if (!usesSimpleName && !usesFqn && !hasOldImport) {
            return edits;
        }

        // 1. Handle import changes
        if (hasOldImport) {
            if (nowInSamePackage && !typeNameChanged) {
                TextEdit removeEdit = createImportRemovalEdit(content, oldFqn);
                if (removeEdit != null) {
                    edits.add(removeEdit);
                }
            } else {
                TextEdit replaceEdit = createImportReplaceEdit(content, oldFqn, newFqn);
                if (replaceEdit != null) {
                    edits.add(replaceEdit);
                }
            }
        } else if (usesSimpleName && packageChanged && !nowInSamePackage) {
            if (!hasExactImport(content, newFqn)) {
                edits.add(createImportInsertEdit(content, newFqn));
            }
        }

        // 2. Rename simple name references (only when type name changes)
        if (typeNameChanged && usesSimpleName) {
            addSimpleNameReplacements(content, oldTypeName, newTypeName, edits);
        }

        // 3. Update fully-qualified name references
        if (usesFqn && !Objects.equals(oldFqn, newFqn)) {
            addFqnReplacements(content, oldFqn, newFqn, edits);
        }

        return edits;
    }

    private List<Path> collectWorkspaceGroovyFiles() {
        long now = System.currentTimeMillis();
        if (workspaceGroovyFilesCacheTimestampMs > 0
                && now - workspaceGroovyFilesCacheTimestampMs <= WORKSPACE_GROOVY_FILES_CACHE_TTL_MS) {
            return cachedWorkspaceGroovyFiles;
        }

        synchronized (workspaceGroovyFilesCacheLock) {
            if (workspaceGroovyFilesCacheTimestampMs > 0
                    && now - workspaceGroovyFilesCacheTimestampMs <= WORKSPACE_GROOVY_FILES_CACHE_TTL_MS) {
                return cachedWorkspaceGroovyFiles;
            }

            List<Path> groovyFiles = new ArrayList<>();

            try {
                ResourcesPlugin.getWorkspace().getRoot().accept(resource -> {
                    if (resource.getType() == IResource.FILE
                            && "groovy".equals(resource.getFileExtension())
                            && resource.getLocationURI() != null) {
                        try {
                            groovyFiles.add(Paths.get(resource.getLocationURI()));
                        } catch (Exception ignored) {
                            // Skip malformed URIs
                        }
                    }
                    return true;
                });
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError(
                        "Failed to scan workspace via Eclipse resources", e);
            }

            if (groovyFiles.isEmpty()) {
                collectGroovyFilesFromFileSystem(groovyFiles);
            }

            cachedWorkspaceGroovyFiles = List.copyOf(groovyFiles);
            workspaceGroovyFilesCacheTimestampMs = System.currentTimeMillis();
            return cachedWorkspaceGroovyFiles;
        }
    }

    private void invalidateWorkspaceGroovyFilesCache() {
        cachedWorkspaceGroovyFiles = List.of();
        workspaceGroovyFilesCacheTimestampMs = 0L;
    }

    private void collectGroovyFilesFromFileSystem(List<Path> groovyFiles) {
        String workspaceRoot = server.getWorkspaceRoot();
        if (workspaceRoot == null) {
            return;
        }
        Path rootPath = toFilePath(workspaceRoot);
        if (rootPath == null) {
            return;
        }
        try {
            Files.walk(rootPath, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".groovy"))
                    .filter(p -> !isInsideBuildOrOutputDir(p))
                    .limit(5000)
                    .forEach(groovyFiles::add);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to walk workspace for Groovy files", e);
        }
    }

    private void addSimpleNameReplacements(
            String content, String oldName, String newName, List<TextEdit> edits) {
        Pattern pattern = Pattern.compile("(?<![.\\w])" + Pattern.quote(oldName) + "(?![\\w])");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int start = matcher.start();
            if (isInsideImportLine(content, start) || isInsidePackageLine(content, start)) {
                continue;
            }
            Position startPos = offsetToPosition(content, start);
            Position endPos = offsetToPosition(content, start + oldName.length());
            edits.add(new TextEdit(new Range(startPos, endPos), newName));
        }
    }

    private void addFqnReplacements(
            String content, String oldFqn, String newFqn, List<TextEdit> edits) {
        Pattern pattern = Pattern.compile("(?<![\\w.])" + Pattern.quote(oldFqn) + "(?![\\w])");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int start = matcher.start();
            if (isInsideImportLine(content, start)) {
                continue;
            }
            Position startPos = offsetToPosition(content, start);
            Position endPos = offsetToPosition(content, start + oldFqn.length());
            edits.add(new TextEdit(new Range(startPos, endPos), newFqn));
        }
    }

    String inferPackageFromPath(String uri, String fallbackPackage) {
        Path filePath = toFilePath(uri);
        if (filePath == null) {
            return fallbackPackage;
        }
        try {
            Path parent = filePath.getParent();
            if (parent == null) {
                return fallbackPackage;
            }

            String normalizedParent = parent.toString().replace('\\', '/');

            String[] sourceRoots = {
                    "/src/main/groovy/", "/src/main/java/",
                    "/src/test/groovy/", "/src/test/java/",
                    "/src/groovy/", "/src/java/",
                    "/src/"
            };

            for (String srcRoot : sourceRoots) {
                int idx = normalizedParent.indexOf(srcRoot);
                if (idx >= 0) {
                    String afterRoot = normalizedParent.substring(idx + srcRoot.length());
                    if (afterRoot.isEmpty()) {
                        return "";
                    }
                    return afterRoot.replace('/', '.');
                }
                String withoutTrailing = srcRoot.substring(0, srcRoot.length() - 1);
                if (normalizedParent.endsWith(withoutTrailing)) {
                    return "";
                }
            }

            return fallbackPackage;
        } catch (Exception e) {
            return fallbackPackage;
        }
    }

    private String buildFqn(String packageName, String typeName) {
        return (packageName == null || packageName.isEmpty())
                ? typeName
                : packageName + "." + typeName;
    }

    boolean hasStarImport(String content, String packageName) {
        Pattern starPattern = Pattern.compile(
                "(?m)^\\s*import\\s+" + Pattern.quote(packageName) + "\\.\\*(?=\\s|;|$)");
        return starPattern.matcher(content).find();
    }

    boolean hasConflictingImport(String content, String simpleName, String excludeFqn) {
        Pattern importPattern = Pattern.compile(
                "(?m)^\\s*import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")(?=\\s|;|$)");
        Matcher matcher = importPattern.matcher(content);
        while (matcher.find()) {
            String importedFqn = matcher.group(1);
            if (!importedFqn.equals(excludeFqn)) {
                return true;
            }
        }
        return false;
    }

    boolean isInsidePackageLine(String content, int offset) {
        if (offset < 0 || offset > content.length()) {
            return false;
        }
        int lineStart = content.lastIndexOf('\n', Math.max(0, offset - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int lineEnd = content.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }
        String line = content.substring(lineStart, lineEnd).trim();
        return line.startsWith("package ");
    }

    static boolean isInsideBuildOrOutputDir(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/build/")
                || normalized.contains("/.gradle/")
                || normalized.contains("/node_modules/")
                || normalized.contains("/.git/")
                || normalized.contains("/bin/")
                || normalized.contains("/out/");
    }

    @Override
    public void didRenameFiles(RenameFilesParams params) {
        if (params == null || params.getFiles() == null || params.getFiles().isEmpty()) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: no files");
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] didRenameFiles: files=" + params.getFiles().size());

        RenameSummary summary = summarizeRenames(params.getFiles());

        if (!summary.hasSourceRename) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: no source rename detected");
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] didRenameFiles: hasSourceRename=true hasJavaRename=" + summary.hasJavaRename);

        ReferenceSearchHelper.clearCaches();
        invalidateWorkspaceGroovyFilesCache();

        final boolean javaRename = summary.hasJavaRename;
        CompletableFuture.runAsync(() -> {
            try {
                if (javaRename) {
                    GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: triggerFullBuild");
                    server.triggerFullBuild();
                } else {
                    GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: publish open diagnostics");
                    server.getGroovyTextDocumentService().publishDiagnosticsForOpenDocuments();
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Failed to refresh diagnostics after file rename", e);
            }
        }).exceptionally(e -> {
            GroovyLanguageServerPlugin.logError("File rename handler failed", (Exception) e);
            return null;
        });
    }

    private RenameSummary summarizeRenames(List<FileRename> renames) {
        boolean hasSourceRename = false;
        boolean hasJavaRename = false;
        for (FileRename rename : renames) {
            if (rename != null) {
                String oldUri = DocumentManager.normalizeUri(rename.getOldUri());
                String newUri = DocumentManager.normalizeUri(rename.getNewUri());
                boolean sourceRename = isSourceFileUri(oldUri) || isSourceFileUri(newUri);
                if (sourceRename) {
                    hasSourceRename = true;
                    if (isJavaFileUri(oldUri) || isJavaFileUri(newUri)) {
                        hasJavaRename = true;
                    }
                    break;
                }
            }
        }
        return new RenameSummary(hasSourceRename, hasJavaRename);
    }

    // ---- Private helpers ----

    private boolean isSourceFileUri(String uri) {
        return isGroovyFileUri(uri) || isJavaFileUri(uri);
    }

    private boolean isGroovyFileUri(String uri) {
        return JSON_GROOVY.equals(fileExtensionFromUri(uri));
    }

    private boolean isJavaFileUri(String uri) {
        return "java".equals(fileExtensionFromUri(uri));
    }

    private String baseNameFromUri(String uri) {
        String fileName = fileNameFromUri(uri);
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return fileName.substring(0, dot);
    }

    private String fileNameFromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        Path path = toFilePath(uri);
        if (path == null) {
            return null;
        }
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : null;
    }

    private String fileExtensionFromUri(String uri) {
        String fileName = fileNameFromUri(uri);
        if (fileName == null) {
            return null;
        }

        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String getSourceText(String uri) {
        String openContent = documentManager.getContent(uri);
        if (openContent != null) {
            return openContent;
        }

        Path path = toFilePath(uri);
        if (path == null) {
            return null;
        }

        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to read source for file rename: " + uri, e);
        }

        return null;
    }

    private TypeMoveContext resolveTypeMoveContext(
            String oldUri,
            String newUri,
            String oldTypeName,
            String newTypeName) {
        try {
            IType targetType = findTypeForFileRename(oldUri, oldTypeName);
            if (targetType == null) {
                return null;
            }

            String oldQualifiedName = targetType.getFullyQualifiedName('.');
            if (oldQualifiedName == null || oldQualifiedName.isBlank()) {
                oldQualifiedName = oldTypeName;
            }

            String oldPackageName = targetType.getPackageFragment() != null
                    ? targetType.getPackageFragment().getElementName()
                    : packageFromQualifiedName(oldQualifiedName);
            if (oldPackageName == null) {
                oldPackageName = "";
            }

            String newPackageName = resolveMovedFilePackage(targetType, newUri, oldPackageName);
            String newQualifiedName = newPackageName.isBlank()
                    ? newTypeName
                    : newPackageName + "." + newTypeName;

            return new TypeMoveContext(
                    oldUri,
                    newUri,
                    targetType,
                    oldPackageName,
                    newPackageName,
                    oldQualifiedName,
                    newQualifiedName);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve move context for " + oldUri, e);
            return null;
        }
    }

    private String packageFromQualifiedName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return qualifiedName.substring(0, lastDot);
    }

    private String resolveMovedFilePackage(IType targetType, String newUri, String fallbackPackage) {
        IJavaProject javaProject = targetType != null ? targetType.getJavaProject() : null;
        return resolveMovedFilePackage(javaProject, newUri, fallbackPackage);
    }

    private String resolveMovedFilePackage(IJavaProject javaProject, String newUri, String fallbackPackage) {
        if (javaProject == null) {
            return fallbackPackage;
        }
        Path newPath = toFilePath(newUri);
        if (newPath == null) {
            return fallbackPackage;
        }
        try {
            Path newParent = newPath.getParent();
            if (newParent == null) {
                return fallbackPackage;
            }

            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                String resolvedPackage = resolvePackageForRoot(root, newParent);
                if (resolvedPackage != null) {
                    return resolvedPackage;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve target package for moved file: " + newUri, e);
        }

        return fallbackPackage;
    }

    private String resolvePackageForRoot(IPackageFragmentRoot root, Path newParent)
            throws JavaModelException {
        if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
            return null;
        }

        IResource resource = root.getResource();
        if (resource == null || resource.getLocationURI() == null) {
            return null;
        }

        Path rootPath = Paths.get(resource.getLocationURI()).normalize();
        Path normalizedParent = newParent.normalize();
        if (!normalizedParent.startsWith(rootPath)) {
            return null;
        }

        Path relative = rootPath.relativize(normalizedParent);
        return packageNameForPath(relative);
    }

    private TextEdit findGroovyPackageMoveFallbackEdit(String oldUri, String newUri) {
        try {
            String source = getSourceText(oldUri);
            if (source == null) {
                return null;
            }

            String oldPackageName = extractPackageName(source);
            IJavaProject javaProject = findJavaProjectForUri(oldUri);
            String newPackageName = resolveMovedFilePackage(javaProject, newUri, oldPackageName);
            // If JDT couldn't resolve (returned same package), try path-based inference
            if (Objects.equals(oldPackageName, newPackageName)) {
                newPackageName = inferPackageFromPath(newUri, oldPackageName);
            }
            if (Objects.equals(oldPackageName, newPackageName)) {
                return null;
            }

            return findGroovyPackageDeclarationMoveEdit(source, newPackageName);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to create Groovy package fallback edit for move: " + oldUri,
                    e);
            return null;
        }
    }

    private IJavaProject findJavaProjectForUri(String uri) {
        try {
            URI location = toFileLocationUri(uri);
            if (location == null) {
                return null;
            }
            org.eclipse.core.resources.IFile[] files =
                    ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(location);
            for (org.eclipse.core.resources.IFile file : files) {
                IJavaElement element = JavaCore.create(file);
                if (element == null || element.getJavaProject() == null) {
                    continue;
                }
                IJavaProject javaProject = element.getJavaProject();
                if (javaProject.exists()) {
                    return javaProject;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve Java project for URI: " + uri, e);
        }
        return null;
    }

    private URI toFileLocationUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            return "file".equals(parsed.getScheme()) ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Path toFilePath(String uri) {
        URI fileUri = toFileLocationUri(uri);
        if (fileUri == null) {
            return null;
        }
        try {
            return Paths.get(fileUri);
        } catch (Exception e) {
            return null;
        }
    }

    private String packageNameForPath(Path relativePath) {
        if (relativePath == null || relativePath.getNameCount() == 0) {
            return "";
        }

        StringBuilder packageName = new StringBuilder();
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            if (i > 0) {
                packageName.append('.');
            }
            packageName.append(relativePath.getName(i).toString());
        }
        return packageName.toString();
    }

    private Map<String, List<TextEdit>> buildGroovyTypeMoveEdits(TypeMoveContext moveContext) {
        if (moveContext == null || !moveContext.isPackageChanged()) {
            return Map.of();
        }

        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    moveContext.targetType,
                    IJavaSearchConstants.ALL_OCCURRENCES);
            if (pattern == null) {
                return Map.of();
            }

            Map<String, List<SearchMatch>> matchesByUri = collectGroovyMoveMatches(pattern);
            return buildMoveEditsFromMatches(matchesByUri, moveContext);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Workspace file move search failed for " + moveContext.oldUri,
                    e);
            return Map.of();
        }
    }

    private Map<String, List<SearchMatch>> collectGroovyMoveMatches(SearchPattern pattern)
            {
        Map<String, List<SearchMatch>> matchesByUri = new HashMap<>();
        try {
            JdtSearchSupport.search(pattern,
                    SearchEngine.createWorkspaceScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            collectGroovyMoveMatch(matchesByUri, match);
                        }
                    },
                    null);
        } catch (org.eclipse.core.runtime.CoreException e) {
            GroovyLanguageServerPlugin.logError("Failed to collect Groovy move matches", e);
        }
        return matchesByUri;
    }

    private void collectGroovyMoveMatch(Map<String, List<SearchMatch>> matchesByUri, SearchMatch match) {
        try {
            IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return;
            }

            String targetUri = DocumentManager.normalizeUri(resource.getLocationURI().toString());
            if (!isGroovyFileUri(targetUri)) {
                return;
            }

            matchesByUri.computeIfAbsent(targetUri, key -> new ArrayList<>()).add(match);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to collect move match", e);
        }
    }

    private Map<String, List<TextEdit>> buildMoveEditsFromMatches(
            Map<String, List<SearchMatch>> matchesByUri,
            TypeMoveContext moveContext) {
        Map<String, List<TextEdit>> editsByUri = new HashMap<>();
        for (Map.Entry<String, List<SearchMatch>> entry : matchesByUri.entrySet()) {
            String uri = entry.getKey();
            String content = getSourceText(uri);
            if (content == null || content.isBlank()) {
                continue;
            }

            List<TextEdit> fileEdits = buildGroovyFileMoveEdits(uri, content, moveContext, entry.getValue());
            if (!fileEdits.isEmpty()) {
                editsByUri.put(uri, fileEdits);
            }
        }
        return editsByUri;
    }

    private List<TextEdit> buildGroovyFileMoveEdits(
            String uri,
            String content,
            TypeMoveContext moveContext,
            List<SearchMatch> matches) {
        List<TextEdit> edits = new ArrayList<>();
        if (matches == null || matches.isEmpty()) {
            return edits;
        }

        boolean sameAsMovedTypeFile = Objects.equals(uri, moveContext.oldUri);
        if (!sameAsMovedTypeFile) {
            addImportMoveEdits(content, moveContext, matches, edits);
        }

        addQualifiedTypeReferenceUpdates(content, matches, moveContext, edits);
        return edits;
    }

    private void addImportMoveEdits(
            String content,
            TypeMoveContext moveContext,
            List<SearchMatch> matches,
            List<TextEdit> edits) {
        String documentPackage = extractPackageName(content);
        boolean sameAsTargetPackage = Objects.equals(documentPackage, moveContext.newPackageName);
        boolean hasOldImport = hasExactImport(content, moveContext.oldQualifiedName);
        boolean hasNewImport = hasExactImport(content, moveContext.newQualifiedName);
        boolean usesSimpleName = hasSimpleTypeUsage(content, matches, moveContext.oldTypeSimpleName());

        if (hasOldImport) {
            TextEdit importEdit = sameAsTargetPackage
                    ? createImportRemovalEdit(content, moveContext.oldQualifiedName)
                    : createImportReplaceEdit(content, moveContext.oldQualifiedName, moveContext.newQualifiedName);
            if (importEdit != null) {
                edits.add(importEdit);
            }
            return;
        }

        if (usesSimpleName && !sameAsTargetPackage && !hasNewImport) {
            edits.add(createImportInsertEdit(content, moveContext.newQualifiedName));
        }
    }

    private void addQualifiedTypeReferenceUpdates(
            String content,
            List<SearchMatch> matches,
            TypeMoveContext moveContext,
            List<TextEdit> edits) {
        Set<Integer> seenOffsets = new HashSet<>();
        for (SearchMatch match : matches) {
            int startOffset = match.getOffset();
            int length = match.getLength();
            int endOffset = startOffset + length;
            if (shouldReplaceQualifiedReference(
                    content,
                    seenOffsets,
                    startOffset,
                    length,
                    endOffset,
                    moveContext.oldQualifiedName)) {
                Position start = offsetToPosition(content, startOffset);
                Position end = offsetToPosition(content, endOffset);
                edits.add(new TextEdit(new Range(start, end), moveContext.newQualifiedName));
            }
        }
    }

    private boolean shouldReplaceQualifiedReference(
            String content,
            Set<Integer> seenOffsets,
            int startOffset,
            int length,
            int endOffset,
            String expectedToken) {
        if (startOffset < 0 || length <= 0 || !seenOffsets.add(startOffset)) {
            return false;
        }
        if (endOffset > content.length()) {
            return false;
        }
        String token = content.substring(startOffset, endOffset);
        return expectedToken.equals(token) && !isInsideImportLine(content, startOffset);
    }

    private boolean hasSimpleTypeUsage(String content, List<SearchMatch> matches, String simpleTypeName) {
        for (SearchMatch match : matches) {
            int startOffset = match.getOffset();
            int length = match.getLength();
            int endOffset = startOffset + length;
            if (isSimpleTypeMatch(content, simpleTypeName, startOffset, length, endOffset)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSimpleTypeMatch(
            String content,
            String simpleTypeName,
            int startOffset,
            int length,
            int endOffset) {
        if (startOffset < 0 || length <= 0 || endOffset > content.length()) {
            return false;
        }
        if (isInsideImportLine(content, startOffset)) {
            return false;
        }
        String token = content.substring(startOffset, endOffset);
        return simpleTypeName.equals(token);
    }

    private boolean isInsideImportLine(String content, int offset) {
        if (offset < 0 || offset > content.length()) {
            return false;
        }
        int lineStart = content.lastIndexOf('\n', Math.max(0, offset - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int lineEnd = content.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }
        String line = content.substring(lineStart, lineEnd).trim();
        return line.startsWith(IMPORT_PREFIX) || line.startsWith("import static ");
    }

    private String extractPackageName(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        Matcher matcher = PACKAGE_DECLARATION_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private boolean hasExactImport(String content, String qualifiedTypeName) {
        Pattern importPattern = Pattern.compile(
                "(?m)^\\s*import\\s+" + Pattern.quote(qualifiedTypeName) + "(?=\\s|;|$)");
        return importPattern.matcher(content).find();
    }

    private TextEdit createImportReplaceEdit(String content, String oldQualifiedName, String newQualifiedName) {
        Pattern importPattern = Pattern.compile(
                "(?m)^\\s*import\\s+(" + Pattern.quote(oldQualifiedName) + ")(?=\\s|;|$)");
        Matcher matcher = importPattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }

        Position start = offsetToPosition(content, matcher.start(1));
        Position end = offsetToPosition(content, matcher.end(1));
        return new TextEdit(new Range(start, end), newQualifiedName);
    }

    private TextEdit createImportRemovalEdit(String content, String qualifiedName) {
        Pattern importPattern = Pattern.compile(
                "(?m)^\\s*import\\s+" + Pattern.quote(qualifiedName) + "(?=\\s|;|$)");
        Matcher matcher = importPattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }

        int tokenStart = matcher.start();
        int lineStart = content.lastIndexOf('\n', Math.max(0, tokenStart - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int lineEnd = content.indexOf('\n', matcher.end());
        if (lineEnd >= 0) {
            lineEnd += 1;
        } else {
            lineEnd = content.length();
        }

        Position start = offsetToPosition(content, lineStart);
        Position end = offsetToPosition(content, lineEnd);
        return new TextEdit(new Range(start, end), "");
    }

    private TextEdit createImportInsertEdit(String content, String qualifiedName) {
        int insertLine = findImportInsertLine(content);
        String importLine = IMPORT_PREFIX + qualifiedName + "\n";
        Position insert = new Position(insertLine, 0);
        return new TextEdit(new Range(insert, insert), importLine);
    }

    private int findImportInsertLine(String content) {
        String[] lines = content.split("\\n", -1);
        int lastImportLine = -1;
        int packageLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("package ")) {
                packageLine = i;
            }
            if (trimmed.startsWith(IMPORT_PREFIX)) {
                lastImportLine = i;
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("interface ")
                    || trimmed.startsWith("enum ") || trimmed.startsWith("trait ")
                    || trimmed.startsWith("@") || trimmed.startsWith("def ")
                    || trimmed.startsWith("public ") || trimmed.startsWith("abstract ")
                    || trimmed.startsWith("final ")) {
                break;
            }
        }

        if (lastImportLine >= 0) {
            return lastImportLine + 1;
        }
        if (packageLine >= 0) {
            return packageLine + 2;
        }
        return 0;
    }

    private TextEdit findGroovyPackageDeclarationMoveEdit(String source, String newPackageName) {
        if (source == null) {
            return null;
        }

        Matcher matcher = PACKAGE_DECLARATION_PATTERN.matcher(source);
        if (matcher.find()) {
            if (newPackageName == null || newPackageName.isBlank()) {
                int lineStart = source.lastIndexOf('\n', Math.max(0, matcher.start() - 1));
                lineStart = lineStart < 0 ? 0 : lineStart + 1;
                int lineEnd = source.indexOf('\n', matcher.end());
                if (lineEnd >= 0) {
                    lineEnd += 1;
                } else {
                    lineEnd = source.length();
                }

                Position start = offsetToPosition(source, lineStart);
                Position end = offsetToPosition(source, lineEnd);
                return new TextEdit(new Range(start, end), "");
            }

            Position start = offsetToPosition(source, matcher.start(1));
            Position end = offsetToPosition(source, matcher.end(1));
            return new TextEdit(new Range(start, end), newPackageName);
        }

        if (newPackageName == null || newPackageName.isBlank()) {
            return null;
        }

        Position start = new Position(0, 0);
        return new TextEdit(new Range(start, start), "package " + newPackageName + "\n\n");
    }

    private Map<String, List<TextEdit>> buildWorkspaceTypeRenameEdits(
            IType targetType, String newTypeName, boolean groovyTargetsOnly) {
        try {
            if (targetType == null) {
                return Map.of();
            }

            SearchPattern pattern = SearchPattern.createPattern(
                    targetType,
                    IJavaSearchConstants.ALL_OCCURRENCES);
            if (pattern == null) {
                return Map.of();
            }

            Map<String, List<TextEdit>> editsByUri = new HashMap<>();
            Map<String, String> sourceCache = new HashMap<>();
            JdtSearchSupport.search(pattern,
                    SearchEngine.createWorkspaceScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            addWorkspaceRenameEdit(match, newTypeName, editsByUri, groovyTargetsOnly, sourceCache);
                        }
                    },
                    null);

            return editsByUri;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Workspace file rename search failed for " + targetType.getFullyQualifiedName(), e);
            return Map.of();
        }
    }

    private IType findTypeForFileRename(String oldUri, String oldTypeName) {
        try {
            URI location = toFileLocationUri(oldUri);
            if (location == null) {
                return null;
            }
            org.eclipse.core.resources.IFile[] files =
                    ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(location);

            for (org.eclipse.core.resources.IFile file : files) {
                IJavaElement element = JavaCore.create(file);
                if (!(element instanceof ICompilationUnit)) {
                    continue;
                }

                ICompilationUnit compilationUnit = (ICompilationUnit) element;
                for (IType type : compilationUnit.getTypes()) {
                    if (oldTypeName.equals(type.getElementName())) {
                        return type;
                    }
                }

                IType direct = compilationUnit.getType(oldTypeName);
                if (direct != null && direct.exists()) {
                    return direct;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve type for file rename: " + oldUri, e);
        }

        return null;
    }

    @SuppressWarnings("unused")
    private void addWorkspaceRenameEdit(
            SearchMatch match,
            String newTypeName,
            Map<String, List<TextEdit>> editsByUri,
            boolean groovyTargetsOnly) {
        addWorkspaceRenameEdit(match, newTypeName, editsByUri, groovyTargetsOnly, new HashMap<>());
    }

    private void addWorkspaceRenameEdit(
            SearchMatch match,
            String newTypeName,
            Map<String, List<TextEdit>> editsByUri,
            boolean groovyTargetsOnly,
            Map<String, String> sourceCache) {
        try {
            org.eclipse.core.resources.IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return;
            }

            int startOffset = match.getOffset();
            int length = match.getLength();
            if (startOffset < 0 || length <= 0) {
                return;
            }

            String targetUri = DocumentManager.normalizeUri(resource.getLocationURI().toString());
            if (groovyTargetsOnly && !isGroovyFileUri(targetUri)) {
                return;
            }
            String content = sourceCache.computeIfAbsent(targetUri, this::getSourceText);
            if (content == null) {
                return;
            }

            int endOffset = startOffset + length;
            if (startOffset > content.length() || endOffset > content.length()) {
                return;
            }

            Position start = offsetToPosition(content, startOffset);
            Position end = offsetToPosition(content, endOffset);
            TextEdit textEdit = new TextEdit(new Range(start, end), newTypeName);
            editsByUri.computeIfAbsent(targetUri, k -> new ArrayList<>()).add(textEdit);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to add workspace rename edit", e);
        }
    }

    private void mergeEdits(Map<String, List<TextEdit>> target, Map<String, List<TextEdit>> source) {
        for (Map.Entry<String, List<TextEdit>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }
    }

    private int countTextEdits(Map<String, List<TextEdit>> editsByUri) {
        int count = 0;
        for (List<TextEdit> edits : editsByUri.values()) {
            if (edits != null) {
                count += edits.size();
            }
        }
        return count;
    }

    private TextEdit findTypeDeclarationRenameEdit(String source, String oldTypeName, String newTypeName) {
        Pattern declarationPattern = Pattern.compile(
            "\\b(?:class|interface|trait|enum|record|@interface)\\s+("
                + Pattern.quote(oldTypeName) + ")\\b");
        Matcher matcher = declarationPattern.matcher(source);
        if (!matcher.find()) {
            return null;
        }

        Position start = offsetToPosition(source, matcher.start(1));
        Position end = offsetToPosition(source, matcher.end(1));
        return new TextEdit(new Range(start, end), newTypeName);
    }

    private Position offsetToPosition(String content, int offset) {
        int line = 0;
        int column = 0;
        int safeOffset = Math.max(0, Math.min(offset, content.length()));
        for (int i = 0; i < safeOffset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    /**
     * Convert a JDT {@link IJavaElement} to an LSP {@link SymbolInformation}.
     */
    private SymbolInformation toSymbolInformation(IJavaElement element) {
        try {
            String name = element.getElementName();
            SymbolKind kind = toSymbolKind(element);

            // Get the source location
            org.eclipse.core.resources.IResource resource = element.getResource();
            if (resource == null) {
                return null;
            }

            Location location = new Location();
            location.setUri(resource.getLocationURI().toString());

            // We need the document content to convert offset to line/column.
            // For workspace symbols, we provide a basic range.
            Range range = new Range(new Position(0, 0), new Position(0, 0));

            if (element instanceof org.eclipse.jdt.core.ISourceReference sourceRef) {
                org.eclipse.jdt.core.ISourceRange sourceRange = sourceRef.getNameRange();
                if (sourceRange != null) {
                    // We'd need to convert offset to line/col — simplified for now
                    range = new Range(new Position(0, sourceRange.getOffset()),
                            new Position(0, sourceRange.getOffset() + sourceRange.getLength()));
                }
            }

            location.setRange(range);

            // Container name (enclosing type or package)
            String containerName = "";
            IJavaElement parent = element.getParent();
            if (parent != null) {
                containerName = parent.getElementName();
            }

            SymbolInformation info = new SymbolInformation();
            info.setName(name);
            info.setKind(kind);
            info.setLocation(location);
            info.setContainerName(containerName);

            return info;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to convert element to symbol", e);
            return null;
        }
    }

    /**
     * Map a JDT element type to an LSP {@link SymbolKind}.
     */
    private SymbolKind toSymbolKind(IJavaElement element) {
        switch (element.getElementType()) {
            case IJavaElement.TYPE:
                try {
                    if (element instanceof IType type) {
                        if (type.isInterface()) return SymbolKind.Interface;
                        if (type.isEnum()) return SymbolKind.Enum;
                    }
                } catch (JavaModelException e) {
                    // fall through
                }
                return SymbolKind.Class;
            case IJavaElement.METHOD:
                return SymbolKind.Method;
            case IJavaElement.FIELD:
                return SymbolKind.Field;
            case IJavaElement.LOCAL_VARIABLE:
                return SymbolKind.Variable;
            case IJavaElement.PACKAGE_FRAGMENT:
                return SymbolKind.Package;
            default:
                return SymbolKind.Class;
        }
    }

    private static final class TypeMoveContext {
        private final String oldUri;
        private final String newUri;
        private final IType targetType;
        private final String oldPackageName;
        private final String newPackageName;
        private final String oldQualifiedName;
        private final String newQualifiedName;

        private TypeMoveContext(
                String oldUri,
                String newUri,
                IType targetType,
                String oldPackageName,
                String newPackageName,
                String oldQualifiedName,
                String newQualifiedName) {
            this.oldUri = oldUri;
            this.newUri = newUri;
            this.targetType = targetType;
            this.oldPackageName = oldPackageName;
            this.newPackageName = newPackageName;
            this.oldQualifiedName = oldQualifiedName;
            this.newQualifiedName = newQualifiedName;
        }

        private String oldTypeSimpleName() {
            int lastDot = oldQualifiedName.lastIndexOf('.');
            if (lastDot < 0 || lastDot == oldQualifiedName.length() - 1) {
                return oldQualifiedName;
            }
            return oldQualifiedName.substring(lastDot + 1);
        }

        private boolean isPackageChanged() {
            return !Objects.equals(oldPackageName, newPackageName);
        }
    }

    private static final class SourceChangeSummary {
        private final boolean hasSourceChange;
        private final boolean hasJavaSourceChange;

        private SourceChangeSummary(boolean hasSourceChange, boolean hasJavaSourceChange) {
            this.hasSourceChange = hasSourceChange;
            this.hasJavaSourceChange = hasJavaSourceChange;
        }
    }

    private static final class RenameSummary {
        private final boolean hasSourceRename;
        private final boolean hasJavaRename;

        private RenameSummary(boolean hasSourceRename, boolean hasJavaRename) {
            this.hasSourceRename = hasSourceRename;
            this.hasJavaRename = hasJavaRename;
        }
    }
}
