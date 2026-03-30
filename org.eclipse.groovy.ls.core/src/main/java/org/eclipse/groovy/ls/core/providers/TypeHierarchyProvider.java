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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;

/**
 * Provides type hierarchy support for Groovy documents.
 * <p>
 * Uses JDT's {@link ITypeHierarchy} to compute supertypes and subtypes of
 * the selected type. The LSP type hierarchy protocol uses three requests:
 * <ol>
 *   <li>{@code prepareTypeHierarchy} — resolve the type at cursor</li>
 *   <li>{@code supertypes} — list direct supertypes</li>
 *   <li>{@code subtypes} — list direct subtypes</li>
 * </ol>
 */
public class TypeHierarchyProvider {

    private final DocumentManager documentManager;
    private final Map<String, IType> resolvedTypesByFqn = new ConcurrentHashMap<>();
    private final Set<String> missingTypesByFqn = ConcurrentHashMap.newKeySet();

    public TypeHierarchyProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    public void invalidateCache() {
        TypeHierarchyCache.clear();
        resolvedTypesByFqn.clear();
        missingTypesByFqn.clear();
    }

    /**
     * Prepare the type hierarchy by resolving the type at the cursor position.
     */
    public List<TypeHierarchyItem> prepareTypeHierarchy(TypeHierarchyPrepareParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();
        List<TypeHierarchyItem> result = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return result;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return result;
            }

            int offset = positionToOffset(content, position);
            IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
            if (elements == null || elements.length == 0) {
                return result;
            }

            IJavaElement element = documentManager.remapToWorkingCopyElement(elements[0]);
            if (element == null) {
                element = elements[0];
            }

            IType type = resolveToType(element);
            if (type == null) {
                return result;
            }

            TypeHierarchyItem item = buildTypeHierarchyItem(type, new HashMap<>(), new HashMap<>());
            if (item != null) {
                result.add(item);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("prepareTypeHierarchy failed for " + uri, e);
        }

        return result;
    }

    /**
     * Compute the direct supertypes of the given type hierarchy item.
     */
    public List<TypeHierarchyItem> getSupertypes(TypeHierarchySupertypesParams params) {
        List<TypeHierarchyItem> result = new ArrayList<>();

        IType type = resolveTypeFromItem(params.getItem());
        if (type == null) {
            return result;
        }

        try {
            ITypeHierarchy hierarchy = TypeHierarchyCache.getSupertypeHierarchy(type);
            if (hierarchy == null) {
                return result;
            }
            Map<String, String> contentCache = new HashMap<>();
            Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
            IType[] supertypes = hierarchy.getSupertypes(type);
            for (IType supertype : supertypes) {
                TypeHierarchyItem item = buildTypeHierarchyItem(supertype, contentCache, lineIndexCache);
                if (item != null) {
                    result.add(item);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("getSupertypes failed", e);
        }

        return result;
    }

    /**
     * Compute the direct subtypes of the given type hierarchy item.
     */
    public List<TypeHierarchyItem> getSubtypes(TypeHierarchySubtypesParams params) {
        List<TypeHierarchyItem> result = new ArrayList<>();

        IType type = resolveTypeFromItem(params.getItem());
        if (type == null) {
            return result;
        }

        try {
            ITypeHierarchy hierarchy = TypeHierarchyCache.getTypeHierarchy(type);
            if (hierarchy == null) {
                return result;
            }
            Map<String, String> contentCache = new HashMap<>();
            Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
            IType[] subtypes = hierarchy.getSubtypes(type);
            for (IType subtype : subtypes) {
                TypeHierarchyItem item = buildTypeHierarchyItem(subtype, contentCache, lineIndexCache);
                if (item != null) {
                    result.add(item);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("getSubtypes failed", e);
        }

        return result;
    }

    // ---- Helpers ----

    /**
     * Resolve an IJavaElement to an IType. If the element is a method or field,
     * return its declaring type.
     */
    private IType resolveToType(IJavaElement element) {
        if (element instanceof IType type) {
            return type;
        }
        if (element instanceof IMethod method) {
            return method.getDeclaringType();
        }
        if (element instanceof IField field) {
            return field.getDeclaringType();
        }
        return null;
    }

    /**
     * Resolve an IType from a TypeHierarchyItem by looking up the FQN stored in data.
     */
    private IType resolveTypeFromItem(TypeHierarchyItem item) {
        if (item == null || item.getData() == null) {
            return null;
        }

        try {
            JsonElement dataElement = (JsonElement) item.getData();
            String fqn = extractFqn(dataElement);

            if (fqn == null || fqn.isEmpty()) {
                return null;
            }

            return findTypeInWorkspace(fqn);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve type from hierarchy item", e);
        }

        return null;
    }

    private String extractFqn(JsonElement dataElement) {
        if (dataElement.isJsonObject()) {
            JsonObject obj = dataElement.getAsJsonObject();
            if (obj.has("fqn")) {
                return obj.get("fqn").getAsString();
            }
        } else if (dataElement.isJsonPrimitive()) {
            return dataElement.getAsString();
        }
        return null;
    }

    private IType findTypeInWorkspace(String fqn) throws org.eclipse.core.runtime.CoreException {
        IType cachedType = resolvedTypesByFqn.get(fqn);
        if (cachedType != null && cachedType.exists()) {
            return cachedType;
        }
        if (missingTypesByFqn.contains(fqn)) {
            return null;
        }

        for (org.eclipse.core.resources.IProject project :
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && project.hasNature(org.eclipse.jdt.core.JavaCore.NATURE_ID)) {
                IJavaProject javaProject = org.eclipse.jdt.core.JavaCore.create(project);
                IType type = javaProject.findType(fqn);
                if (type != null && type.exists()) {
                    resolvedTypesByFqn.put(fqn, type);
                    missingTypesByFqn.remove(fqn);
                    return type;
                }
            }
        }
        missingTypesByFqn.add(fqn);
        return null;
    }

    /**
     * Build a TypeHierarchyItem from an IType.
     */
    private TypeHierarchyItem buildTypeHierarchyItem(IType type) {
        return buildTypeHierarchyItem(type, new HashMap<>(), new HashMap<>());
    }

    private TypeHierarchyItem buildTypeHierarchyItem(IType type,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            IJavaElement remappedType = documentManager.remapToWorkingCopyElement(type);
            if (remappedType instanceof IType resolvedType) {
                type = resolvedType;
            }

            // Determine the URI first — skip if not resolvable
            String uri = resolveTypeUri(type);
            if (uri == null) {
                return null;
            }

            String content = getContent(type, uri, contentCache);
            PositionUtils.LineIndex lineIndex = content != null
                    ? lineIndexFor(uri, content, lineIndexCache)
                    : null;
            Range range = getTypeRange(type, uri, content, lineIndex);
            Range selectionRange = getTypeSelectionRange(type, uri, range, content, lineIndex);

            // Use the required 5-arg constructor
            TypeHierarchyItem item = new TypeHierarchyItem(
                    type.getElementName(),
                    getTypeKind(type),
                    uri,
                    range,
                    selectionRange);

            String fqn = type.getFullyQualifiedName();
            String simpleName = type.getElementName();
            if (!fqn.equals(simpleName)) {
                int lastDot = fqn.lastIndexOf('.');
                if (lastDot > 0) {
                    item.setDetail(fqn.substring(0, lastDot));
                }
            }

            // Store FQN in data for later resolution
            JsonObject data = new JsonObject();
            data.addProperty("fqn", type.getFullyQualifiedName());
            item.setData(data);

            return item;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to build TypeHierarchyItem for " + type.getElementName(), e);
            return null;
        }
    }

    private SymbolKind getTypeKind(IType type) {
        try {
            if (type.isInterface()) return SymbolKind.Interface;
            if (type.isEnum()) return SymbolKind.Enum;
        } catch (Exception e) {
            // fall through
        }
        return SymbolKind.Class;
    }

    private String resolveTypeUri(IType type) {
        try {
            return documentManager.resolveElementUri(type);
        } catch (Exception e) {
            return null;
        }
    }

    private Range getTypeRange(IType type, String uri) {
        return getTypeRange(type, uri, getContent(type, uri), null);
    }

    private Range getTypeRange(IType type,
            String uri,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            ISourceRange sourceRange = type.getSourceRange();
            if (sourceRange != null && sourceRange.getOffset() >= 0) {
                if (content != null) {
                    return toRange(lineIndex != null ? lineIndex : PositionUtils.buildLineIndex(content),
                            sourceRange);
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }

    private Range getTypeSelectionRange(IType type, String uri, Range fallback) {
        return getTypeSelectionRange(type, uri, fallback, getContent(type, uri), null);
    }

    private Range getTypeSelectionRange(IType type,
            String uri,
            Range fallback,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            ISourceRange nameRange = type.getNameRange();
            if (nameRange != null && nameRange.getOffset() >= 0) {
                if (content != null) {
                    return toRange(lineIndex != null ? lineIndex : PositionUtils.buildLineIndex(content),
                            nameRange);
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return fallback;
    }

    private String getContent(IType type, String uri) {
        return getContent(type, uri, new HashMap<>());
    }

    private String getContent(IType type, String uri, Map<String, String> contentCache) {
        try {
            org.eclipse.core.resources.IResource resource = type.getResource();
            if (resource == null) {
                ICompilationUnit compilationUnit = type.getCompilationUnit();
                if (compilationUnit != null) {
                    resource = compilationUnit.getResource();
                }
            }
            return JdtSearchSupport.readContent(documentManager, uri, resource, contentCache);
        } catch (Exception e) {
            contentCache.put(uri, null);
            return null;
        }
    }

    private Range toRange(String content, ISourceRange sourceRange) {
        return toRange(PositionUtils.buildLineIndex(content), sourceRange);
    }

    private Range toRange(PositionUtils.LineIndex lineIndex, ISourceRange sourceRange) {
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();
        return new Range(
                lineIndex.offsetToPosition(startOffset),
                lineIndex.offsetToPosition(endOffset));
    }

    private PositionUtils.LineIndex lineIndexFor(String uri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        if (lineIndexCache == null) {
            return PositionUtils.buildLineIndex(content);
        }
        return lineIndexCache.computeIfAbsent(uri, ignored -> PositionUtils.buildLineIndex(content));
    }

    Position offsetToPosition(String content, int offset) {
        int line = 0;
        int col = 0;
        int safeOffset = Math.min(offset, content.length());
        for (int i = 0; i < safeOffset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    int positionToOffset(String content, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < content.length() && line < position.getLine()) {
            if (content.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), content.length());
    }
}
