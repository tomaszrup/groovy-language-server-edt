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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

final class CallHierarchySupport {

    private static final String HANDLE_ID_KEY = "handleId";

    private final DocumentManager documentManager;

    CallHierarchySupport(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    void processIncomingMatch(SearchMatch match,
            Map<String, IncomingCallData> callerMap,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            Object matchElement = match.getElement();
            if (!(matchElement instanceof IJavaElement javaElement)) {
                return;
            }

            IJavaElement enclosing = javaElement;
            while (enclosing != null
                    && !(enclosing instanceof IMethod)
                    && !(enclosing instanceof IType)) {
                enclosing = enclosing.getParent();
            }
            if (enclosing == null) {
                return;
            }

            String key = enclosing.getHandleIdentifier();
            IncomingCallData data = callerMap.get(key);
            if (data == null) {
                data = new IncomingCallData();
                data.item = buildCallHierarchyItem(enclosing);
                callerMap.put(key, data);
            }

            Range callRange = matchToRange(match, contentCache, lineIndexCache);
            if (callRange != null) {
                data.ranges.add(callRange);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to process incoming call match", e);
        }
    }

    Range matchToRange(SearchMatch match,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            IResource resource = match.getResource();
            String targetUri = JdtSearchSupport.resolveResourceUri(documentManager, resource);
            if (targetUri == null) {
                return null;
            }
            String content = getContent(targetUri, resource, contentCache);
            if (content == null) {
                return null;
            }

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();
            PositionUtils.LineIndex lineIndex = lineIndexFor(targetUri, content, lineIndexCache);
            return new Range(
                    lineIndex.offsetToPosition(startOffset),
                    lineIndex.offsetToPosition(endOffset));
        } catch (Exception e) {
            return null;
        }
    }

    CallHierarchyItem buildCallHierarchyItem(IJavaElement element) {
        try {
            IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
            if (remappedElement != null) {
                element = remappedElement;
            }

            CallHierarchyItem item = new CallHierarchyItem();
            item.setName(element.getElementName());

            switch (element) {
                case IMethod method -> {
                    item.setKind(method.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method);
                    IType declaringType = method.getDeclaringType();
                    if (declaringType != null) {
                        item.setDetail(declaringType.getFullyQualifiedName());
                    }
                }
                case IType type -> {
                    item.setKind(SymbolKind.Class);
                    item.setDetail(type.getFullyQualifiedName());
                }
                default -> item.setKind(SymbolKind.Function);
            }

            String uri = documentManager.resolveElementUri(element);
            if (uri == null) {
                return null;
            }
            item.setUri(uri);

            Range range = getElementRange(element, uri);
            item.setRange(range);
            item.setSelectionRange(getElementSelectionRange(element, uri, range));

            JsonObject data = new JsonObject();
            data.addProperty(HANDLE_ID_KEY, element.getHandleIdentifier());
            item.setData(data);
            return item;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to build CallHierarchyItem for " + element.getElementName(), e);
            return null;
        }
    }

    IJavaElement resolveElementFromItem(CallHierarchyItem item) {
        if (item == null || item.getData() == null) {
            return null;
        }

        try {
            JsonElement dataElement = (JsonElement) item.getData();
            if (dataElement.isJsonObject()) {
                JsonObject obj = dataElement.getAsJsonObject();
                if (obj.has(HANDLE_ID_KEY)) {
                    return JavaCore.create(obj.get(HANDLE_ID_KEY).getAsString());
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve element from call hierarchy item", e);
        }

        return null;
    }

    Range toRange(PositionUtils.LineIndex lineIndex, ISourceRange sourceRange) {
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();
        return new Range(
                lineIndex.offsetToPosition(startOffset),
                lineIndex.offsetToPosition(endOffset));
    }

    private Range getElementRange(IJavaElement element, String uri) {
        try {
            if (element instanceof ISourceReference sourceRef) {
                ISourceRange sourceRange = sourceRef.getSourceRange();
                if (sourceRange != null && sourceRange.getOffset() >= 0) {
                    String content = getContent(uri, element.getResource(), null);
                    if (content != null) {
                        return toRange(PositionUtils.buildLineIndex(content), sourceRange);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }

    private Range getElementSelectionRange(IJavaElement element, String uri, Range fallback) {
        try {
            if (element instanceof ISourceReference sourceRef) {
                ISourceRange nameRange = sourceRef.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0) {
                    String content = getContent(uri, element.getResource(), null);
                    if (content != null) {
                        return toRange(PositionUtils.buildLineIndex(content), nameRange);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return fallback;
    }

    private String getContent(String uri, IResource resource, Map<String, String> contentCache) {
        if (contentCache != null && contentCache.containsKey(uri)) {
            return contentCache.get(uri);
        }

        String content = documentManager.getContent(uri);
        if (content != null) {
            if (contentCache != null) {
                contentCache.put(uri, content);
            }
            return content;
        }

        if (resource instanceof IFile file) {
            try (InputStream inputStream = file.getContents()) {
                content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                if (contentCache != null) {
                    contentCache.put(uri, content);
                }
                return content;
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    private PositionUtils.LineIndex lineIndexFor(String targetUri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        PositionUtils.LineIndex cached = lineIndexCache.get(targetUri);
        if (cached != null) {
            return cached;
        }
        PositionUtils.LineIndex built = PositionUtils.buildLineIndex(content);
        lineIndexCache.put(targetUri, built);
        return built;
    }

    static final class IncomingCallData {
        CallHierarchyItem item;
        List<Range> ranges = new ArrayList<>();
    }

    static final class OutgoingCallData {
        String name;
        List<Range> ranges = new ArrayList<>();

        OutgoingCallData(String name) {
            this.name = name;
        }
    }
}