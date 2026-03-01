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
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Implements the LSP {@link WorkspaceService} for workspace-level operations.
 * <p>
 * Handles workspace symbol search, configuration changes, and watched file events.
 */
public class GroovyWorkspaceService implements WorkspaceService {

    private final GroovyLanguageServer server;
    private final DocumentManager documentManager;

    public GroovyWorkspaceService(GroovyLanguageServer server, DocumentManager documentManager) {
        this.server = server;
        this.documentManager = documentManager;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            List<SymbolInformation> symbols = new ArrayList<>();
            String query = params.getQuery();

            if (query == null || query.isEmpty()) {
                return Either.forLeft(symbols);
            }

            try {
                SearchEngine searchEngine = new SearchEngine();
                SearchPattern pattern = SearchPattern.createPattern(
                        query,
                        IJavaSearchConstants.TYPE,
                        IJavaSearchConstants.DECLARATIONS,
                        SearchPattern.R_CAMELCASE_MATCH | SearchPattern.R_PREFIX_MATCH);

                if (pattern == null) {
                    return Either.forLeft(symbols);
                }

                // Search across all Java projects in the workspace
                IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

                searchEngine.search(pattern,
                        new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                        scope,
                        new SearchRequestor() {
                            @Override
                            public void acceptSearchMatch(SearchMatch match) {
                                Object element = match.getElement();
                                if (element instanceof IJavaElement) {
                                    IJavaElement javaElement = (IJavaElement) element;
                                    SymbolInformation symbol = toSymbolInformation(javaElement, match);
                                    if (symbol != null) {
                                        symbols.add(symbol);
                                    }
                                }
                            }
                        },
                        null);
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Workspace symbol search failed", e);
            }

            return Either.forLeft(symbols);
        });
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        GroovyLanguageServerPlugin.logInfo("Configuration changed: " + params.getSettings());

        // Extract formatter settings from the configuration
        Object settings = params.getSettings();
        if (settings instanceof com.google.gson.JsonObject) {
            com.google.gson.JsonObject json = (com.google.gson.JsonObject) settings;

            // Navigate: { "groovy": { "format": { "settingsUrl": "..." } } }
            com.google.gson.JsonObject groovy = json.has("groovy")
                    ? json.getAsJsonObject("groovy") : null;
            if (groovy != null) {
                com.google.gson.JsonObject format = groovy.has("format")
                        ? groovy.getAsJsonObject("format") : null;
                if (format != null && format.has("settingsUrl")) {
                    com.google.gson.JsonElement urlElem = format.get("settingsUrl");
                    String profilePath = (urlElem != null && !urlElem.isJsonNull())
                            ? urlElem.getAsString() : null;
                    server.getGroovyTextDocumentService().updateFormatterProfile(profilePath);
                }

                com.google.gson.JsonObject inlayHints = groovy.has("inlayHints")
                        ? groovy.getAsJsonObject("inlayHints") : null;
                server.getGroovyTextDocumentService().updateInlayHintSettings(
                        parseInlayHintSettings(inlayHints));
            }
        }
    }

    private InlayHintSettings parseInlayHintSettings(com.google.gson.JsonObject inlayHints) {
        InlayHintSettings defaults = InlayHintSettings.defaults();
        if (inlayHints == null) {
            return defaults;
        }

        boolean variableTypesEnabled = readNestedBoolean(
                inlayHints,
                "variableTypes",
                "enabled",
                defaults.isVariableTypesEnabled());
        boolean parameterNamesEnabled = readNestedBoolean(
                inlayHints,
                "parameterNames",
                "enabled",
                defaults.isParameterNamesEnabled());
        boolean closureParameterTypesEnabled = readNestedBoolean(
                inlayHints,
                "closureParameterTypes",
                "enabled",
                defaults.isClosureParameterTypesEnabled());
        boolean methodReturnTypesEnabled = readNestedBoolean(
                inlayHints,
                "methodReturnTypes",
                "enabled",
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

        // Trigger incremental build when files change on disk.
        // IMPORTANT: Refresh only the individual files that changed instead of
        // doing DEPTH_INFINITE on the workspace root.  A full recursive refresh
        // re-traverses every linked folder target, generating heavy I/O inside
        // VS Code's workspaceStorage area that can break other extensions'
        // persisted state (e.g. Copilot chat history).
        CompletableFuture.runAsync(() -> {
            try {
                boolean hasSourceChange = false;
                boolean hasJavaSourceChange = false;
                for (FileEvent event : params.getChanges()) {
                    String uri = event.getUri();
                    if (uri.endsWith(".groovy") || uri.endsWith(".java")) {
                        hasSourceChange = true;
                        if (uri.endsWith(".java")) {
                            hasJavaSourceChange = true;
                        }
                        try {
                            java.net.URI fileUri = java.net.URI.create(uri);
                            org.eclipse.core.resources.IFile[] files =
                                    ResourcesPlugin.getWorkspace().getRoot()
                                            .findFilesForLocationURI(fileUri);
                            for (org.eclipse.core.resources.IFile file : files) {
                                file.refreshLocal(
                                        org.eclipse.core.resources.IResource.DEPTH_ZERO,
                                        new org.eclipse.core.runtime.NullProgressMonitor());
                            }
                        } catch (Exception e) {
                            GroovyLanguageServerPlugin.logError(
                                    "Failed to refresh file: " + uri, e);
                        }
                    }
                }
                // If no individual files could be resolved (e.g. new file not yet
                // known to Eclipse), fall back to a project-level refresh rather
                // than a full workspace-root refresh.
                if (hasSourceChange) {
                    for (org.eclipse.core.resources.IProject p
                            : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                        if (p.isOpen()) {
                            p.refreshLocal(
                                    org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                                    new org.eclipse.core.runtime.NullProgressMonitor());
                        }
                    }

                    GroovyLanguageServerPlugin.logInfo(
                            "[diag-trace] didChangeWatchedFiles sourceChanged=true javaChanged="
                            + hasJavaSourceChange + " -> refresh diagnostics");
                    if (hasJavaSourceChange) {
                        server.getGroovyTextDocumentService().triggerFullBuild(server);
                    } else {
                        server.getGroovyTextDocumentService().publishDiagnosticsForOpenDocuments();
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Failed to handle file changes", e);
            }
        });
    }

    @Override
    public CompletableFuture<WorkspaceEdit> willRenameFiles(RenameFilesParams params) {
        return CompletableFuture.supplyAsync(() -> {
            if (params == null || params.getFiles() == null || params.getFiles().isEmpty()) {
                GroovyLanguageServerPlugin.logInfo("[rename-trace] willRenameFiles: no files");
                return null;
            }

            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: files=" + params.getFiles().size());

            Map<String, List<TextEdit>> changes = new HashMap<>();

            for (FileRename fileRename : params.getFiles()) {
                if (fileRename == null) {
                    continue;
                }

                String oldUri = DocumentManager.normalizeUri(fileRename.getOldUri());
                String newUri = DocumentManager.normalizeUri(fileRename.getNewUri());

                if (!isSourceFileUri(oldUri) || !isSourceFileUri(newUri)) {
                    continue;
                }

                String oldExtension = fileExtensionFromUri(oldUri);
                String newExtension = fileExtensionFromUri(newUri);
                if (oldExtension == null || newExtension == null || !oldExtension.equals(newExtension)) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[rename-trace] willRenameFiles: skip ext mismatch old="
                            + oldUri + " new=" + newUri);
                    continue;
                }

                boolean javaRename = "java".equals(oldExtension);

                String oldTypeName = baseNameFromUri(oldUri);
                String newTypeName = baseNameFromUri(newUri);
                if (oldTypeName == null || newTypeName == null) {
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: invalid type names old="
                            + oldTypeName + " new=" + newTypeName + " uri=" + oldUri);
                    continue;
                }

                TypeMoveContext moveContext =
                    resolveTypeMoveContext(oldUri, newUri, oldTypeName, newTypeName);
                if (moveContext == null) {
                    if (isGroovyFileUri(oldUri)) {
                        TextEdit fallbackPackageEdit = findGroovyPackageMoveFallbackEdit(oldUri, newUri);
                        if (fallbackPackageEdit != null) {
                            changes.computeIfAbsent(oldUri, key -> new ArrayList<>()).add(fallbackPackageEdit);
                            GroovyLanguageServerPlugin.logInfo(
                                    "[rename-trace] willRenameFiles: groovy package fallback edit added for "
                                    + oldUri);
                        }
                    }
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: unable to resolve move context for " + oldUri);
                    continue;
                }

                boolean typeNameChanged = !oldTypeName.equals(newTypeName);
                boolean packageChanged = moveContext.isPackageChanged();
                if (!typeNameChanged && !packageChanged) {
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: no type/package change for " + oldUri);
                    continue;
                }

                GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: candidate old=" + oldTypeName
                    + " new=" + newTypeName
                    + " oldFqn=" + moveContext.oldQualifiedName
                    + " newFqn=" + moveContext.newQualifiedName
                    + " packageChanged=" + packageChanged
                    + " ext=" + oldExtension
                    + " oldUri=" + oldUri + " newUri=" + newUri);

                Map<String, List<TextEdit>> workspaceRenameEdits = Map.of();
                if (typeNameChanged) {
                    workspaceRenameEdits =
                        buildWorkspaceTypeRenameEdits(oldUri, oldTypeName, newTypeName, javaRename);
                    if (!workspaceRenameEdits.isEmpty()) {
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: workspace rename edits uris="
                        + workspaceRenameEdits.size() + " edits="
                        + countTextEdits(workspaceRenameEdits));
                    mergeEdits(changes, workspaceRenameEdits);
                    }
                }

                if (packageChanged) {
                    Map<String, List<TextEdit>> moveEdits = buildGroovyTypeMoveEdits(moveContext);
                    if (!moveEdits.isEmpty()) {
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: groovy move edits uris="
                        + moveEdits.size() + " edits=" + countTextEdits(moveEdits));
                    mergeEdits(changes, moveEdits);
                    }

                    if (isGroovyFileUri(oldUri)) {
                    String source = getSourceText(oldUri);
                    TextEdit packageEdit = findGroovyPackageDeclarationMoveEdit(
                        source,
                        moveContext.newPackageName);
                    if (packageEdit != null) {
                        changes.computeIfAbsent(oldUri, k -> new ArrayList<>()).add(packageEdit);
                        GroovyLanguageServerPlugin.logInfo(
                            "[rename-trace] willRenameFiles: groovy package declaration edit added for "
                            + oldUri);
                    }
                    }
                }

                if (!typeNameChanged) {
                    continue;
                }

                // For Java file renames, avoid touching Java files ourselves so
                // the Red Hat Java extension remains the sole owner of Java edits.
                if (javaRename || !workspaceRenameEdits.isEmpty()) {
                    GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: skip declaration fallback for "
                            + oldUri);
                    continue;
                }

                String source = getSourceText(oldUri);
                if (source == null || source.isBlank()) {
                    continue;
                }

                TextEdit declarationEdit = findTypeDeclarationRenameEdit(source, oldTypeName, newTypeName);
                if (declarationEdit == null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[rename-trace] willRenameFiles: no declaration fallback edit for " + oldUri);
                    continue;
                }

                changes.computeIfAbsent(oldUri, k -> new ArrayList<>()).add(declarationEdit);
                GroovyLanguageServerPlugin.logInfo(
                        "[rename-trace] willRenameFiles: declaration fallback edit added for " + oldUri);
            }

            if (changes.isEmpty()) {
                GroovyLanguageServerPlugin.logInfo("[rename-trace] willRenameFiles: returning null (no edits)");
                return null;
            }

            GroovyLanguageServerPlugin.logInfo(
                    "[rename-trace] willRenameFiles: returning changes uris=" + changes.size()
                    + " edits=" + countTextEdits(changes));

            WorkspaceEdit edit = new WorkspaceEdit();
            edit.setChanges(changes);
            return edit;
        });
    }

    @Override
    public void didRenameFiles(RenameFilesParams params) {
        if (params == null || params.getFiles() == null || params.getFiles().isEmpty()) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: no files");
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] didRenameFiles: files=" + params.getFiles().size());

        boolean hasSourceRename = false;
        boolean hasJavaRename = false;
        for (FileRename rename : params.getFiles()) {
            if (rename == null) {
                continue;
            }
            String oldUri = DocumentManager.normalizeUri(rename.getOldUri());
            String newUri = DocumentManager.normalizeUri(rename.getNewUri());
            if (isSourceFileUri(oldUri) || isSourceFileUri(newUri)) {
                hasSourceRename = true;
                if (isJavaFileUri(oldUri) || isJavaFileUri(newUri)) {
                    hasJavaRename = true;
                }
                break;
            }
        }

        if (!hasSourceRename) {
            GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: no source rename detected");
            return;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[rename-trace] didRenameFiles: hasSourceRename=true hasJavaRename=" + hasJavaRename);

        final boolean javaRename = hasJavaRename;
        CompletableFuture.runAsync(() -> {
            try {
                if (javaRename) {
                    GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: triggerFullBuild");
                    server.getGroovyTextDocumentService().triggerFullBuild(server);
                } else {
                    GroovyLanguageServerPlugin.logInfo("[rename-trace] didRenameFiles: publish open diagnostics");
                    server.getGroovyTextDocumentService().publishDiagnosticsForOpenDocuments();
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Failed to refresh diagnostics after file rename", e);
            }
        });
    }

    // ---- Private helpers ----

    private boolean isSourceFileUri(String uri) {
        return isGroovyFileUri(uri) || isJavaFileUri(uri);
    }

    private boolean isGroovyFileUri(String uri) {
        return "groovy".equals(fileExtensionFromUri(uri));
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
        try {
            Path path = Paths.get(URI.create(uri));
            Path fileName = path.getFileName();
            return fileName != null ? fileName.toString() : null;
        } catch (Exception e) {
            return null;
        }
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

        try {
            Path path = Paths.get(URI.create(uri));
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
                    targetType,
                    oldTypeName,
                    newTypeName,
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
        try {
            Path newPath = Paths.get(URI.create(newUri));
            Path newParent = newPath.getParent();
            if (newParent == null) {
                return fallbackPackage;
            }

            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }

                IResource resource = root.getResource();
                if (resource == null || resource.getLocationURI() == null) {
                    continue;
                }

                Path rootPath = Paths.get(resource.getLocationURI()).normalize();
                Path normalizedParent = newParent.normalize();
                if (!normalizedParent.startsWith(rootPath)) {
                    continue;
                }

                Path relative = rootPath.relativize(normalizedParent);
                return packageNameForPath(relative);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve target package for moved file: " + newUri, e);
        }

        return fallbackPackage;
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
            URI location = URI.create(uri);
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

            Map<String, List<SearchMatch>> matchesByUri = new HashMap<>();
            SearchEngine engine = new SearchEngine();
            engine.search(pattern,
                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                    SearchEngine.createWorkspaceScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            try {
                                IResource resource = match.getResource();
                                if (resource == null || resource.getLocationURI() == null) {
                                    return;
                                }

                                String targetUri =
                                        DocumentManager.normalizeUri(resource.getLocationURI().toString());
                                if (!isGroovyFileUri(targetUri)) {
                                    return;
                                }

                                matchesByUri.computeIfAbsent(targetUri, key -> new ArrayList<>()).add(match);
                            } catch (Exception e) {
                                GroovyLanguageServerPlugin.logError("Failed to collect move match", e);
                            }
                        }
                    },
                    null);

            Map<String, List<TextEdit>> editsByUri = new HashMap<>();
            for (Map.Entry<String, List<SearchMatch>> entry : matchesByUri.entrySet()) {
                String uri = entry.getKey();
                String content = getSourceText(uri);
                if (content == null || content.isBlank()) {
                    continue;
                }

                List<TextEdit> fileEdits = buildGroovyFileMoveEdits(
                        uri,
                        content,
                        moveContext,
                        entry.getValue());
                if (!fileEdits.isEmpty()) {
                    editsByUri.put(uri, fileEdits);
                }
            }

            return editsByUri;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Workspace file move search failed for " + moveContext.oldUri,
                    e);
            return Map.of();
        }
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
            String documentPackage = extractPackageName(content);
            boolean sameAsTargetPackage = Objects.equals(documentPackage, moveContext.newPackageName);
            boolean hasOldImport = hasExactImport(content, moveContext.oldQualifiedName);
            boolean hasNewImport = hasExactImport(content, moveContext.newQualifiedName);
            boolean usesSimpleName = hasSimpleTypeUsage(content, matches, moveContext.oldTypeName);

            if (hasOldImport) {
                TextEdit importEdit = sameAsTargetPackage
                        ? createImportRemovalEdit(content, moveContext.oldQualifiedName)
                        : createImportReplaceEdit(
                                content,
                                moveContext.oldQualifiedName,
                                moveContext.newQualifiedName);
                if (importEdit != null) {
                    edits.add(importEdit);
                }
            } else if (usesSimpleName && !sameAsTargetPackage && !hasNewImport) {
                TextEdit insertImport = createImportInsertEdit(content, moveContext.newQualifiedName);
                if (insertImport != null) {
                    edits.add(insertImport);
                }
            }
        }

        addQualifiedTypeReferenceUpdates(content, matches, moveContext, edits);
        return edits;
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
            if (startOffset < 0 || length <= 0 || !seenOffsets.add(startOffset)) {
                continue;
            }

            int endOffset = startOffset + length;
            if (endOffset > content.length()) {
                continue;
            }

            String token = content.substring(startOffset, endOffset);
            if (!moveContext.oldQualifiedName.equals(token) || isInsideImportLine(content, startOffset)) {
                continue;
            }

            Position start = offsetToPosition(content, startOffset);
            Position end = offsetToPosition(content, endOffset);
            edits.add(new TextEdit(new Range(start, end), moveContext.newQualifiedName));
        }
    }

    private boolean hasSimpleTypeUsage(String content, List<SearchMatch> matches, String simpleTypeName) {
        for (SearchMatch match : matches) {
            int startOffset = match.getOffset();
            int length = match.getLength();
            if (startOffset < 0 || length <= 0) {
                continue;
            }
            int endOffset = startOffset + length;
            if (endOffset > content.length()) {
                continue;
            }

            if (isInsideImportLine(content, startOffset)) {
                continue;
            }

            String token = content.substring(startOffset, endOffset);
            if (simpleTypeName.equals(token)) {
                return true;
            }
        }
        return false;
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
        return line.startsWith("import ") || line.startsWith("import static ");
    }

    private String extractPackageName(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        Pattern packagePattern = Pattern.compile(
                "(?m)^\\s*package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)*)\\s*;?\\s*$");
        Matcher matcher = packagePattern.matcher(content);
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
        String importLine = "import " + qualifiedName + "\n";
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
            if (trimmed.startsWith("import ")) {
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

        Pattern packagePattern = Pattern.compile(
                "(?m)^\\s*package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)*)\\s*;?\\s*$");
        Matcher matcher = packagePattern.matcher(source);
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
            String oldUri, String oldTypeName, String newTypeName, boolean groovyTargetsOnly) {
        try {
            IType targetType = findTypeForFileRename(oldUri, oldTypeName);
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
            SearchEngine engine = new SearchEngine();
            engine.search(pattern,
                    new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                    SearchEngine.createWorkspaceScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            addWorkspaceRenameEdit(match, newTypeName, editsByUri, groovyTargetsOnly);
                        }
                    },
                    null);

            return editsByUri;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Workspace file rename search failed for " + oldUri, e);
            return Map.of();
        }
    }

    private IType findTypeForFileRename(String oldUri, String oldTypeName) {
        try {
            URI location = URI.create(oldUri);
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

    private void addWorkspaceRenameEdit(
            SearchMatch match,
            String newTypeName,
            Map<String, List<TextEdit>> editsByUri,
            boolean groovyTargetsOnly) {
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
            String content = getSourceText(targetUri);
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
    private SymbolInformation toSymbolInformation(IJavaElement element, SearchMatch match) {
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

            // Approximate range from the search match offset/length
            int offset = match.getOffset();
            int length = match.getLength();

            // We need the document content to convert offset to line/column.
            // For workspace symbols, we provide a basic range.
            Range range = new Range(new Position(0, 0), new Position(0, 0));

            if (element instanceof org.eclipse.jdt.core.ISourceReference) {
                org.eclipse.jdt.core.ISourceReference sourceRef = (org.eclipse.jdt.core.ISourceReference) element;
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
                    if (element instanceof IType) {
                        IType type = (IType) element;
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
        private final IType targetType;
        private final String oldTypeName;
        private final String newTypeName;
        private final String oldPackageName;
        private final String newPackageName;
        private final String oldQualifiedName;
        private final String newQualifiedName;

        private TypeMoveContext(
                String oldUri,
                IType targetType,
                String oldTypeName,
                String newTypeName,
                String oldPackageName,
                String newPackageName,
                String oldQualifiedName,
                String newQualifiedName) {
            this.oldUri = oldUri;
            this.targetType = targetType;
            this.oldTypeName = oldTypeName;
            this.newTypeName = newTypeName;
            this.oldPackageName = oldPackageName;
            this.newPackageName = newPackageName;
            this.oldQualifiedName = oldQualifiedName;
            this.newQualifiedName = newQualifiedName;
        }

        private boolean isPackageChanged() {
            return !Objects.equals(oldPackageName, newPackageName);
        }
    }
}
