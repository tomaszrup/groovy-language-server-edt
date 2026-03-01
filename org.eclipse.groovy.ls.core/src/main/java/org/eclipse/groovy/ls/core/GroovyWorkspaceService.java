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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
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
            }
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        GroovyLanguageServerPlugin.logInfo("Watched files changed: " + params.getChanges().size() + " changes");

        // Trigger incremental build when files change on disk
        CompletableFuture.runAsync(() -> {
            try {
                for (FileEvent event : params.getChanges()) {
                    String uri = event.getUri();
                    if (uri.endsWith(".groovy") || uri.endsWith(".java")) {
                        // Refresh the workspace to pick up external changes
                        ResourcesPlugin.getWorkspace().getRoot().refreshLocal(
                                org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                                new org.eclipse.core.runtime.NullProgressMonitor());
                        break; // One refresh is enough
                    }
                }
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Failed to handle file changes", e);
            }
        });
    }

    // ---- Private helpers ----

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
}
