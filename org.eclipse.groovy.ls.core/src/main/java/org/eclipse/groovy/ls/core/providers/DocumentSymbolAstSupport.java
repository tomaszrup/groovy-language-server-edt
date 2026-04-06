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
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

final class DocumentSymbolAstSupport {

    private final DocumentManager documentManager;

    DocumentSymbolAstSupport(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    List<ClassNode> getVisibleClasses(String uri) {
        List<ClassNode> result = new ArrayList<>();
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return result;
        }

        try {
            for (ClassNode classNode : ast.getClasses()) {
                if (isScriptClass(classNode)) {
                    continue;
                }
                result.add(classNode);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Document symbols from Groovy AST failed for " + uri, e);
        }

        return result;
    }

    DocumentSymbol toDocumentSymbolFromAST(ClassNode classNode) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(classNode.getNameWithoutPackage());
        symbol.setKind(getAstTypeKind(classNode));
        setAstSuperclassDetail(symbol, classNode);
        setRangesFromAST(symbol, classNode);
        symbol.setChildren(createAstChildren(classNode));
        return symbol;
    }

    void setRangesFromAST(DocumentSymbol symbol, ASTNode node) {
        if (node == null) {
            Range defaultRange = new Range(new Position(0, 0), new Position(0, 0));
            symbol.setRange(defaultRange);
            symbol.setSelectionRange(defaultRange);
            return;
        }

        int startLine = Math.max(0, node.getLineNumber() - 1);
        int startCol = Math.max(0, node.getColumnNumber() - 1);
        int endLine = Math.max(startLine, node.getLastLineNumber() - 1);
        int endCol = Math.max(0, node.getLastColumnNumber() - 1);
        if (endLine == startLine && endCol < startCol) {
            endCol = startCol;
        }

        Range fullRange = new Range(
                new Position(startLine, startCol),
                new Position(endLine, endCol));
        symbol.setRange(fullRange);

        Range nameRange = new Range(
                new Position(startLine, startCol),
                new Position(startLine, startCol + symbol.getName().length()));
        symbol.setSelectionRange(clampSelectionRange(nameRange, fullRange));
    }

    private boolean isScriptClass(ClassNode classNode) {
        return classNode.isScript()
                && classNode.getMethods().isEmpty()
                && classNode.getProperties().isEmpty();
    }

    private SymbolKind getAstTypeKind(ClassNode classNode) {
        if (GroovyTypeKindHelper.isTrait(classNode)) {
            return SymbolKind.Struct;
        }
        if (classNode.isInterface()) {
            return SymbolKind.Interface;
        }
        if (classNode.isEnum()) {
            return SymbolKind.Enum;
        }
        return SymbolKind.Class;
    }

    private void setAstSuperclassDetail(DocumentSymbol symbol, ClassNode classNode) {
        ClassNode superClass = classNode.getSuperClass();
        if (superClass != null
                && !"java.lang.Object".equals(superClass.getName())
                && !"groovy.lang.Script".equals(superClass.getName())) {
            symbol.setDetail("extends " + superClass.getNameWithoutPackage());
        }
    }

    private List<DocumentSymbol> createAstChildren(ClassNode classNode) {
        List<DocumentSymbol> children = new ArrayList<>();
        addAstPropertySymbols(classNode, children);
        addAstFieldSymbols(classNode, children);
        addAstMethodSymbols(classNode, children);
        addAstInnerTypeSymbols(classNode, children);
        return children;
    }

    private void addAstPropertySymbols(ClassNode classNode, List<DocumentSymbol> children) {
        for (PropertyNode prop : classNode.getProperties()) {
            DocumentSymbol propSymbol = new DocumentSymbol();
            propSymbol.setName(prop.getName());
            propSymbol.setKind(SymbolKind.Property);
            propSymbol.setDetail(prop.getType().getNameWithoutPackage());
            setRangesFromAST(propSymbol, prop.getField());
            children.add(propSymbol);
        }
    }

    private void addAstFieldSymbols(ClassNode classNode, List<DocumentSymbol> children) {
        for (FieldNode field : classNode.getFields()) {
            if (isVisibleNonPropertyField(classNode, field)) {
                DocumentSymbol fieldSymbol = new DocumentSymbol();
                fieldSymbol.setName(field.getName());
                fieldSymbol.setKind(classNode.isEnum() && field.isEnum()
                        ? SymbolKind.EnumMember : SymbolKind.Field);
                fieldSymbol.setDetail(field.getType().getNameWithoutPackage());
                setRangesFromAST(fieldSymbol, field);
                children.add(fieldSymbol);
            }
        }
    }

    private void addAstMethodSymbols(ClassNode classNode, List<DocumentSymbol> children) {
        for (MethodNode method : classNode.getMethods()) {
            if (isVisibleAstMethod(method)) {
                DocumentSymbol methodSymbol = createAstMethodSymbol(method);
                setRangesFromAST(methodSymbol, method);
                children.add(methodSymbol);
            }
        }
    }

    private boolean isVisibleAstMethod(MethodNode method) {
        return !method.getName().startsWith("<") && !method.isSynthetic();
    }

    private DocumentSymbol createAstMethodSymbol(MethodNode method) {
        DocumentSymbol methodSymbol = new DocumentSymbol();
        methodSymbol.setName(method.getName());
        methodSymbol.setKind(method.getName().equals("<init>")
                ? SymbolKind.Constructor : SymbolKind.Method);
        methodSymbol.setDetail(buildAstMethodDetail(method));
        return methodSymbol;
    }

    private String buildAstMethodDetail(MethodNode method) {
        StringBuilder detail = new StringBuilder("(");
        Parameter[] params = method.getParameters();
        for (int index = 0; index < params.length; index++) {
            if (index > 0) {
                detail.append(", ");
            }
            detail.append(params[index].getType().getNameWithoutPackage());
            String displayName = ParameterNameSupport.displayName(params[index].getName());
            if (displayName != null) {
                detail.append(' ').append(displayName);
            }
        }
        detail.append("): ").append(method.getReturnType().getNameWithoutPackage());
        return detail.toString();
    }

    private void addAstInnerTypeSymbols(ClassNode classNode, List<DocumentSymbol> children) {
        java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter = classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode inner = innerIter.next();
            DocumentSymbol innerSymbol = toDocumentSymbolFromAST(inner);
            if (innerSymbol != null) {
                children.add(innerSymbol);
            }
        }
    }

    private Range clampSelectionRange(Range selection, Range full) {
        Position fullStart = full.getStart();
        Position fullEnd = full.getEnd();
        Position clampedStart = minPosition(maxPosition(selection.getStart(), fullStart), fullEnd);
        Position clampedEnd = minPosition(maxPosition(selection.getEnd(), fullStart), fullEnd);
        if (comparePositions(clampedStart, clampedEnd) > 0) {
            clampedEnd = clampedStart;
        }
        return new Range(clampedStart, clampedEnd);
    }

    private Position maxPosition(Position left, Position right) {
        return comparePositions(left, right) >= 0 ? left : right;
    }

    private Position minPosition(Position left, Position right) {
        return comparePositions(left, right) <= 0 ? left : right;
    }

    private int comparePositions(Position left, Position right) {
        if (left.getLine() != right.getLine()) {
            return Integer.compare(left.getLine(), right.getLine());
        }
        return Integer.compare(left.getCharacter(), right.getCharacter());
    }

    private boolean isVisibleNonPropertyField(ClassNode classNode, FieldNode field) {
        String fieldName = field.getName();
        if (fieldName.startsWith("$") || fieldName.startsWith("__")) {
            return false;
        }

        for (PropertyNode prop : classNode.getProperties()) {
            if (fieldName.equals(prop.getName())) {
                return false;
            }
        }
        return true;
    }
}