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

import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;

final class TypeHierarchySupport {

    private final DocumentManager documentManager;

    TypeHierarchySupport(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    String extractFqn(JsonElement dataElement) {
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

    IType findTypeInWorkspace(String fqn,
            Map<String, IType> resolvedTypesByFqn,
            Set<String> missingTypesByFqn) throws org.eclipse.core.runtime.CoreException {
        IType cachedType = resolvedTypesByFqn.get(fqn);
        if (cachedType != null && cachedType.exists()) {
            return cachedType;
        }
        if (missingTypesByFqn.contains(fqn)) {
            return null;
        }

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                IJavaProject javaProject = JavaCore.create(project);
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

    TypeHierarchyItem buildTypeHierarchyItem(IType type,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            IJavaElement remappedType = documentManager.remapToWorkingCopyElement(type);
            if (remappedType instanceof IType resolvedType) {
                type = resolvedType;
            }

            String uri = resolveTypeUri(type);
            if (uri == null) {
                return null;
            }

            String content = getContent(type, uri, contentCache);
            PositionUtils.LineIndex lineIndex = content != null ? lineIndexFor(uri, content, lineIndexCache) : null;
            Range range = getTypeRange(type, content, lineIndex);
            Range selectionRange = getTypeSelectionRange(type, range, content, lineIndex);

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

    SymbolKind getTypeKind(IType type) {
        try {
            if (type.isInterface()) {
                return SymbolKind.Interface;
            }
            if (type.isEnum()) {
                return SymbolKind.Enum;
            }
        } catch (Exception e) {
            // fall through
        }
        return SymbolKind.Class;
    }

        Range getTypeRange(IType type,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            ISourceRange sourceRange = type.getSourceRange();
            if (sourceRange != null && sourceRange.getOffset() >= 0 && content != null) {
                return toRange(lineIndex != null ? lineIndex : PositionUtils.buildLineIndex(content), sourceRange);
            }
        } catch (Exception e) {
            // fall through
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }

        Range getTypeSelectionRange(IType type,
            Range fallback,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            ISourceRange nameRange = type.getNameRange();
            if (nameRange != null && nameRange.getOffset() >= 0 && content != null) {
                return toRange(lineIndex != null ? lineIndex : PositionUtils.buildLineIndex(content), nameRange);
            }
        } catch (Exception e) {
            // fall through
        }
        return fallback;
    }

    String getContent(IType type, String uri, Map<String, String> contentCache) {
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

    Range toRange(PositionUtils.LineIndex lineIndex, ISourceRange sourceRange) {
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();
        return new Range(
                lineIndex.offsetToPosition(startOffset),
                lineIndex.offsetToPosition(endOffset));
    }

    private String resolveTypeUri(IType type) {
        try {
            return documentManager.resolveElementUri(type);
        } catch (Exception e) {
            return null;
        }
    }

    private PositionUtils.LineIndex lineIndexFor(String uri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        if (lineIndexCache == null) {
            return PositionUtils.buildLineIndex(content);
        }
        return lineIndexCache.computeIfAbsent(uri, ignored -> PositionUtils.buildLineIndex(content));
    }
}