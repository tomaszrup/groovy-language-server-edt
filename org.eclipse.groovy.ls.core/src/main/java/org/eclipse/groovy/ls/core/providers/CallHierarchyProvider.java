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
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
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

    private final DocumentManager documentManager;
    private final CallHierarchySupport support;

    public CallHierarchyProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
        this.support = new CallHierarchySupport(documentManager);
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
            IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
            if (elements == null || elements.length == 0) {
                return result;
            }

            IJavaElement element = documentManager.remapToWorkingCopyElement(elements[0]);
            if (element == null) {
                element = elements[0];
            }

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
            Map<String, CallHierarchySupport.IncomingCallData> callerMap = new HashMap<>();
            Map<String, String> contentCache = new HashMap<>();
            Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

            JdtSearchSupport.search(pattern,
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            support.processIncomingMatch(match, callerMap, contentCache, lineIndexCache);
                        }
                    },
                    null);

            // Convert grouped matches to CallHierarchyIncomingCall
            for (CallHierarchySupport.IncomingCallData data : callerMap.values()) {
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
        PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);

        try {
            Range selectionRange = item.getSelectionRange();
            int targetOffset = lineIndex.positionToOffset(selectionRange.getStart());

            // Find the method node that contains this offset
            MethodNode targetMethod = findMethodNodeAt(ast, targetOffset, lineIndex);
            if (targetMethod == null) {
                return result;
            }

            // Walk the method body for call expressions
            Map<String, CallHierarchySupport.OutgoingCallData> callMap = new HashMap<>();
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
            resolveOutgoingCalls(callMap, workingCopy, uri, result, lineIndex);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("getOutgoingCalls failed for " + uri, e);
        }

        return result;
    }

    // ---- Outgoing call resolution helpers ----

    private void resolveOutgoingCalls(Map<String, CallHierarchySupport.OutgoingCallData> callMap,
                                       ICompilationUnit workingCopy,
                                       String uri,
                                       List<CallHierarchyOutgoingCall> result,
                                       PositionUtils.LineIndex lineIndex) {
        for (CallHierarchySupport.OutgoingCallData data : callMap.values()) {
            CallHierarchyOutgoingCall outgoing = new CallHierarchyOutgoingCall();
            CallHierarchyItem toItem = resolveCallTarget(workingCopy, data, lineIndex);

            if (toItem == null) {
                toItem = buildStubCallItem(data, uri);
            }

            outgoing.setTo(toItem);
            outgoing.setFromRanges(data.ranges);
            result.add(outgoing);
        }
    }

    private CallHierarchyItem resolveCallTarget(ICompilationUnit workingCopy,
                                                 CallHierarchySupport.OutgoingCallData data,
                                                 PositionUtils.LineIndex lineIndex) {
        if (workingCopy == null || data.ranges.isEmpty()) {
            return null;
        }
        Range firstRange = data.ranges.get(0);
        int callOffset = lineIndex.positionToOffset(firstRange.getStart());
        try {
            IJavaElement[] resolved = documentManager.cachedCodeSelect(workingCopy, callOffset);
            if (resolved != null && resolved.length > 0) {
                IJavaElement element = documentManager.remapToWorkingCopyElement(resolved[0]);
                return buildCallHierarchyItem(element != null ? element : resolved[0]);
            }
        } catch (Exception e) {
            // codeSelect failed, create a stub item
        }
        return null;
    }

    private CallHierarchyItem buildStubCallItem(CallHierarchySupport.OutgoingCallData data, String uri) {
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

    // ---- Helpers ----

    void processIncomingMatch(SearchMatch match, Map<String, CallHierarchySupport.IncomingCallData> callerMap) {
        support.processIncomingMatch(match, callerMap, new HashMap<>(), new HashMap<>());
    }

    Range matchToRange(SearchMatch match) { return support.matchToRange(match, new HashMap<>(), new HashMap<>()); }

    private void recordOutgoingCall(String methodName, int startLine, int startCol,
                                     int endLine, int endCol,
                                     Map<String, CallHierarchySupport.OutgoingCallData> callMap) {
        if (methodName == null || methodName.isEmpty()) {
            return;
        }
        CallHierarchySupport.OutgoingCallData data = callMap.computeIfAbsent(methodName, CallHierarchySupport.OutgoingCallData::new);

        // AST lines are 1-based, LSP positions are 0-based
        Position start = new Position(Math.max(0, startLine - 1), Math.max(0, startCol - 1));
        Position end = new Position(Math.max(0, endLine - 1), Math.max(0, endCol - 1));
        data.ranges.add(new Range(start, end));
    }

    MethodNode findMethodNodeAt(ModuleNode ast, int offset, String content) {
        return findMethodNodeAt(ast, offset, PositionUtils.buildLineIndex(content));
    }

    private MethodNode findMethodNodeAt(ModuleNode ast, int offset, PositionUtils.LineIndex lineIndex) {
        for (ClassNode classNode : ast.getClasses()) {
            for (MethodNode method : classNode.getMethods()) {
                int methodStart = lineColToOffset(lineIndex, method.getLineNumber(), method.getColumnNumber());
                int methodEnd = lineColToOffset(lineIndex, method.getLastLineNumber(), method.getLastColumnNumber());
                if (offset >= methodStart && offset <= methodEnd) {
                    return method;
                }
            }
        }
        return null;
    }

    int lineColToOffset(String content, int line, int col) {
        return lineColToOffset(PositionUtils.buildLineIndex(content), line, col);
    }

    private int lineColToOffset(PositionUtils.LineIndex lineIndex, int line, int col) {
        if (line <= 0) {
            return 0;
        }
        return lineIndex.positionToOffset(
                new Position(Math.max(0, line - 1), Math.max(0, col - 1)));
    }

    /**
     * Build a CallHierarchyItem from a JDT element.
     */
    CallHierarchyItem buildCallHierarchyItem(IJavaElement element) {
        return support.buildCallHierarchyItem(element);
    }

    /**
     * Resolve an IJavaElement from a CallHierarchyItem using the stored handle identifier.
     */
    private IJavaElement resolveElementFromItem(CallHierarchyItem item) {
        return support.resolveElementFromItem(item);
    }

    Range toRange(String content, ISourceRange sourceRange) {
        return support.toRange(PositionUtils.buildLineIndex(content), sourceRange);
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
