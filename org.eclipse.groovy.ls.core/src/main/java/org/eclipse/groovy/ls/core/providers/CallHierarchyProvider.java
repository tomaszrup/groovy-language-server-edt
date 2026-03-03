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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/**
 * Provides call hierarchy support for Groovy documents.
 * <p>
 * Uses JDT's {@link SearchEngine} for incoming calls (who calls this method)
 * and Groovy AST walking for outgoing calls (what does this method call).
 * <p>
 * The LSP call hierarchy protocol uses three requests:
 * <ol>
 *   <li>{@code prepareCallHierarchy} — resolve the method at cursor</li>
 *   <li>{@code incomingCalls} — find all callers</li>
 *   <li>{@code outgoingCalls} — find all callees</li>
 * </ol>
 */
public class CallHierarchyProvider {

    private static final String HANDLE_ID_KEY = "handleId";

    private final DocumentManager documentManager;

    public CallHierarchyProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Prepare the call hierarchy by resolving the element at the cursor.
     */
    public List<CallHierarchyItem> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();
        List<CallHierarchyItem> result = new ArrayList<>();

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
            IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
            if (elements == null || elements.length == 0) {
                return result;
            }

            IJavaElement element = elements[0];

            // Accept methods and types (for constructor calls)
            if (element instanceof IMethod || element instanceof IType) {
                CallHierarchyItem item = buildCallHierarchyItem(element);
                if (item != null) {
                    result.add(item);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("prepareCallHierarchy failed for " + uri, e);
        }

        return result;
    }

    /**
     * Find all incoming calls to the given method/type.
     */
    public List<CallHierarchyIncomingCall> getIncomingCalls(
            CallHierarchyIncomingCallsParams params) {
        List<CallHierarchyIncomingCall> result = new ArrayList<>();

        IJavaElement element = resolveElementFromItem(params.getItem());
        if (element == null) {
            return result;
        }

        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return result;
            }

            // Group search matches by enclosing method
            Map<String, IncomingCallData> callerMap = new HashMap<>();
            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
            SearchEngine engine = new SearchEngine();

            engine.search(pattern,
                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            processIncomingMatch(match, callerMap);
                        }
                    },
                    null);

            // Convert grouped matches to CallHierarchyIncomingCall
            for (IncomingCallData data : callerMap.values()) {
                CallHierarchyItem fromItem = data.item;
                if (fromItem != null) {
                    CallHierarchyIncomingCall call = new CallHierarchyIncomingCall();
                    call.setFrom(fromItem);
                    call.setFromRanges(data.ranges);
                    result.add(call);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("getIncomingCalls failed", e);
        }

        return result;
    }

    /**
     * Find all outgoing calls from the given method.
     * Uses Groovy AST to walk the method body and find call expressions.
     */
    public List<CallHierarchyOutgoingCall> getOutgoingCalls(
            CallHierarchyOutgoingCallsParams params) {
        List<CallHierarchyOutgoingCall> result = new ArrayList<>();

        CallHierarchyItem item = params.getItem();
        if (item == null || item.getUri() == null) {
            return result;
        }

        String uri = item.getUri();

        // Try to find outgoing calls via the Groovy AST
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return result;
        }

        String content = documentManager.getContent(uri);
        if (content == null) {
            return result;
        }

        try {
            Range selectionRange = item.getSelectionRange();
            int targetOffset = positionToOffset(content, selectionRange.getStart());

            // Find the method node that contains this offset
            MethodNode targetMethod = findMethodNodeAt(ast, targetOffset, content);
            if (targetMethod == null) {
                return result;
            }

            // Walk the method body for call expressions
            Map<String, OutgoingCallData> callMap = new HashMap<>();
            targetMethod.getCode().visit(new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return ast.getContext();
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    recordOutgoingCall(call.getMethodAsString(),
                            call.getLineNumber(), call.getColumnNumber(),
                            call.getLastLineNumber(), call.getLastColumnNumber(),
                            callMap);
                    super.visitMethodCallExpression(call);
                }

                @Override
                public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                    recordOutgoingCall(call.getMethodAsString(),
                            call.getLineNumber(), call.getColumnNumber(),
                            call.getLastLineNumber(), call.getLastColumnNumber(),
                            callMap);
                    super.visitStaticMethodCallExpression(call);
                }

                @Override
                public void visitConstructorCallExpression(ConstructorCallExpression call) {
                    String typeName = call.getType().getNameWithoutPackage();
                    recordOutgoingCall(typeName,
                            call.getLineNumber(), call.getColumnNumber(),
                            call.getLastLineNumber(), call.getLastColumnNumber(),
                            callMap);
                    super.visitConstructorCallExpression(call);
                }
            });

            // Try to resolve each outgoing call to a JDT element
            ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
            resolveOutgoingCalls(callMap, workingCopy, content, uri, result);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("getOutgoingCalls failed for " + uri, e);
        }

        return result;
    }

    // ---- Outgoing call resolution helpers ----

    private void resolveOutgoingCalls(Map<String, OutgoingCallData> callMap,
                                       ICompilationUnit workingCopy, String content,
                                       String uri, List<CallHierarchyOutgoingCall> result) {
        for (OutgoingCallData data : callMap.values()) {
            CallHierarchyOutgoingCall outgoing = new CallHierarchyOutgoingCall();
            CallHierarchyItem toItem = resolveCallTarget(workingCopy, content, data);

            if (toItem == null) {
                toItem = buildStubCallItem(data, uri);
            }

            outgoing.setTo(toItem);
            outgoing.setFromRanges(data.ranges);
            result.add(outgoing);
        }
    }

    private CallHierarchyItem resolveCallTarget(ICompilationUnit workingCopy,
                                                 String content, OutgoingCallData data) {
        if (workingCopy == null || data.ranges.isEmpty()) {
            return null;
        }
        Range firstRange = data.ranges.get(0);
        int callOffset = positionToOffset(content, firstRange.getStart());
        try {
            IJavaElement[] resolved = workingCopy.codeSelect(callOffset, 0);
            if (resolved != null && resolved.length > 0) {
                return buildCallHierarchyItem(resolved[0]);
            }
        } catch (Exception e) {
            // codeSelect failed, create a stub item
        }
        return null;
    }

    private CallHierarchyItem buildStubCallItem(OutgoingCallData data, String uri) {
        CallHierarchyItem toItem = new CallHierarchyItem();
        toItem.setName(data.name);
        toItem.setKind(SymbolKind.Method);
        toItem.setUri(uri);
        Range stubRange = data.ranges.isEmpty()
                ? new Range(new Position(0, 0), new Position(0, 0))
                : data.ranges.get(0);
        toItem.setRange(stubRange);
        toItem.setSelectionRange(stubRange);
        return toItem;
    }

    // ---- Internal data classes ----

    private static class IncomingCallData {
        CallHierarchyItem item;
        List<Range> ranges = new ArrayList<>();
    }

    private static class OutgoingCallData {
        String name;
        List<Range> ranges = new ArrayList<>();

        OutgoingCallData(String name) {
            this.name = name;
        }
    }

    // ---- Helpers ----

    private void processIncomingMatch(SearchMatch match, Map<String, IncomingCallData> callerMap) {
        try {
            Object matchElement = match.getElement();
            if (!(matchElement instanceof IJavaElement javaElement)) {
                return;
            }

            // Walk up to the enclosing method or type
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

            // Add the call site range
            Range callRange = matchToRange(match);
            if (callRange != null) {
                data.ranges.add(callRange);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to process incoming call match", e);
        }
    }

    private Range matchToRange(SearchMatch match) {
        try {
            org.eclipse.core.resources.IResource resource = match.getResource();
            if (resource == null || resource.getLocationURI() == null) {
                return null;
            }

            String targetUri = resource.getLocationURI().toString();
            String content = getContent(targetUri, resource);
            if (content == null) {
                return null;
            }

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();
            return new Range(
                    offsetToPosition(content, startOffset),
                    offsetToPosition(content, endOffset));
        } catch (Exception e) {
            return null;
        }
    }

    private void recordOutgoingCall(String methodName, int startLine, int startCol,
                                     int endLine, int endCol,
                                     Map<String, OutgoingCallData> callMap) {
        if (methodName == null || methodName.isEmpty()) {
            return;
        }

        OutgoingCallData data = callMap.computeIfAbsent(methodName, OutgoingCallData::new);

        // AST lines are 1-based, LSP positions are 0-based
        Position start = new Position(Math.max(0, startLine - 1), Math.max(0, startCol - 1));
        Position end = new Position(Math.max(0, endLine - 1), Math.max(0, endCol - 1));
        data.ranges.add(new Range(start, end));
    }

    private MethodNode findMethodNodeAt(ModuleNode ast, int offset, String content) {
        for (ClassNode classNode : ast.getClasses()) {
            for (MethodNode method : classNode.getMethods()) {
                int methodStart = lineColToOffset(content, method.getLineNumber(), method.getColumnNumber());
                int methodEnd = lineColToOffset(content, method.getLastLineNumber(), method.getLastColumnNumber());
                if (offset >= methodStart && offset <= methodEnd) {
                    return method;
                }
            }
        }
        return null;
    }

    private int lineColToOffset(String content, int line, int col) {
        if (line <= 0) return 0;
        int currentLine = 1;
        int offset = 0;
        while (offset < content.length() && currentLine < line) {
            if (content.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return Math.min(offset + Math.max(0, col - 1), content.length());
    }

    /**
     * Build a CallHierarchyItem from a JDT element.
     */
    private CallHierarchyItem buildCallHierarchyItem(IJavaElement element) {
        try {
            CallHierarchyItem item = new CallHierarchyItem();
            item.setName(element.getElementName());

            if (element instanceof IMethod method) {
                item.setKind(method.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method);
                IType declaringType = method.getDeclaringType();
                if (declaringType != null) {
                    item.setDetail(declaringType.getFullyQualifiedName());
                }
            } else if (element instanceof IType type) {
                item.setKind(SymbolKind.Class);
                item.setDetail(type.getFullyQualifiedName());
            } else {
                item.setKind(SymbolKind.Function);
            }

            String uri = resolveElementUri(element);
            if (uri == null) {
                return null;
            }
            item.setUri(uri);

            // Ranges
            Range range = getElementRange(element, uri);
            item.setRange(range);
            item.setSelectionRange(getElementSelectionRange(element, uri, range));

            // Store handle identifier for later resolution
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

    /**
     * Resolve an IJavaElement from a CallHierarchyItem using the stored handle identifier.
     */
    private IJavaElement resolveElementFromItem(CallHierarchyItem item) {
        if (item == null || item.getData() == null) {
            return null;
        }

        try {
            JsonElement dataElement = (JsonElement) item.getData();
            String handleId = null;
            if (dataElement.isJsonObject()) {
                JsonObject obj = dataElement.getAsJsonObject();
                if (obj.has(HANDLE_ID_KEY)) {
                    handleId = obj.get(HANDLE_ID_KEY).getAsString();
                }
            }

            if (handleId != null) {
                return JavaCore.create(handleId);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve element from call hierarchy item", e);
        }

        return null;
    }

    private String resolveElementUri(IJavaElement element) {
        try {
            org.eclipse.core.resources.IResource resource = element.getResource();
            if (resource != null && resource.getLocationURI() != null) {
                return resource.getLocationURI().toString();
            }

            // Try the compilation unit
            if (element instanceof IMember member) {
                ICompilationUnit cu = member.getCompilationUnit();
                if (cu != null) {
                    resource = cu.getResource();
                    if (resource != null && resource.getLocationURI() != null) {
                        return resource.getLocationURI().toString();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private Range getElementRange(IJavaElement element, String uri) {
        try {
            if (element instanceof ISourceReference sourceRef) {
                ISourceRange sourceRange = sourceRef.getSourceRange();
                if (sourceRange != null && sourceRange.getOffset() >= 0) {
                    String content = getContent(uri, element.getResource());
                    if (content != null) {
                        return toRange(content, sourceRange);
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
                    String content = getContent(uri, element.getResource());
                    if (content != null) {
                        return toRange(content, nameRange);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return fallback;
    }

    private String getContent(String uri, org.eclipse.core.resources.IResource resource) {
        String content = documentManager.getContent(uri);
        if (content != null) {
            return content;
        }
        if (resource instanceof org.eclipse.core.resources.IFile file) {
            try (java.io.InputStream is = file.getContents()) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Range toRange(String content, ISourceRange sourceRange) {
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();
        return new Range(
                offsetToPosition(content, startOffset),
                offsetToPosition(content, endOffset));
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
