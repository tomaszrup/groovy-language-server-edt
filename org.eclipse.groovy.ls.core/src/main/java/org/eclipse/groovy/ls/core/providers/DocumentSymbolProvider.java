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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Provides document symbols (outline) for Groovy documents.
 * <p>
 * Walks the JDT model tree ({@link IType}, {@link IMethod}, {@link IField}) to
 * build a hierarchical {@link DocumentSymbol} tree representing the document's structure.
 */
public class DocumentSymbolProvider {

    private final DocumentManager documentManager;

    public DocumentSymbolProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Get the document symbols (outline) for a document.
     */
    public List<Either<SymbolInformation, DocumentSymbol>> getDocumentSymbols(
            DocumentSymbolParams params) {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();

        String uri = params.getTextDocument().getUri();
        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);

        String content = documentManager.getContent(uri);
        if (content == null) {
            return result;
        }

        if (workingCopy == null) {
            // Fallback: use Groovy AST for document symbols
            return getDocumentSymbolsFromGroovyAST(uri);
        }

        try {
            PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);
            // Walk all types declared in this compilation unit
            IType[] types = workingCopy.getTypes();
            for (IType type : types) {
                DocumentSymbol typeSymbol = toDocumentSymbol(type, content, lineIndex);
                if (typeSymbol != null) {
                    result.add(Either.forRight(typeSymbol));
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Document symbols failed for " + uri, e);
        }

        return result;
    }

    /**
     * Convert an {@link IType} to a {@link DocumentSymbol} with nested children.
     */
    private DocumentSymbol toDocumentSymbol(IType type, String content) {
        return toDocumentSymbol(type, content, PositionUtils.buildLineIndex(content));
    }

    private DocumentSymbol toDocumentSymbol(IType type,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            DocumentSymbol symbol = createTypeSymbol(type, content, lineIndex);
            symbol.setChildren(createTypeChildren(type, content, lineIndex));
            return symbol;

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to build document symbol for " + type.getElementName(), e);
            return null;
        }
    }

    private DocumentSymbol createTypeSymbol(IType type,
            String content,
            PositionUtils.LineIndex lineIndex) throws JavaModelException {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(type.getElementName());
        symbol.setKind(getTypeKind(type));

        String superclass = type.getSuperclassName();
        if (superclass != null && !"Object".equals(superclass)) {
            symbol.setDetail("extends " + superclass);
        }

        setRanges(symbol, type, content, lineIndex);
        return symbol;
    }

    private List<DocumentSymbol> createTypeChildren(IType type,
            String content,
            PositionUtils.LineIndex lineIndex) throws JavaModelException {
        List<DocumentSymbol> children = new ArrayList<>();
        addFieldSymbols(type, content, lineIndex, children);
        addMethodSymbols(type, content, lineIndex, children);
        addInnerTypeSymbols(type, content, lineIndex, children);
        return children;
    }

    private void addFieldSymbols(IType type,
            String content,
            PositionUtils.LineIndex lineIndex,
            List<DocumentSymbol> children)
            throws JavaModelException {
        for (IField field : type.getFields()) {
            DocumentSymbol fieldSymbol = new DocumentSymbol();
            fieldSymbol.setName(field.getElementName());
            fieldSymbol.setKind(org.eclipse.jdt.core.Flags.isEnum(field.getFlags())
                    ? SymbolKind.EnumMember : SymbolKind.Field);
            fieldSymbol.setDetail(resolveFieldDetail(field));
            setRanges(fieldSymbol, field, content, lineIndex);
            children.add(fieldSymbol);
        }
    }

    private String resolveFieldDetail(IField field) {
        try {
            return Signature.toString(field.getTypeSignature());
        } catch (Exception e) {
            return null;
        }
    }

    private void addMethodSymbols(IType type,
            String content,
            PositionUtils.LineIndex lineIndex,
            List<DocumentSymbol> children)
            throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            DocumentSymbol methodSymbol = new DocumentSymbol();
            methodSymbol.setName(method.getElementName());
            methodSymbol.setKind(method.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method);
            methodSymbol.setDetail(resolveMethodDetail(method));
            setRanges(methodSymbol, method, content, lineIndex);
            children.add(methodSymbol);
        }
    }

    private String resolveMethodDetail(IMethod method) throws JavaModelException {
        StringBuilder detail = new StringBuilder("(");
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = JdtParameterNameResolver.resolve(method);

        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                detail.append(", ");
            }
            detail.append(Signature.toString(paramTypes[i]));
            if (paramNames != null && i < paramNames.length) {
                detail.append(' ').append(paramNames[i]);
            }
        }

        detail.append(')');
        if (!method.isConstructor()) {
            detail.append(": ").append(Signature.toString(method.getReturnType()));
        }
        return detail.toString();
    }

    private void addInnerTypeSymbols(IType type,
            String content,
            PositionUtils.LineIndex lineIndex,
            List<DocumentSymbol> children)
            throws JavaModelException {
        for (IType innerType : type.getTypes()) {
            DocumentSymbol innerSymbol = toDocumentSymbol(innerType, content, lineIndex);
            if (innerSymbol != null) {
                children.add(innerSymbol);
            }
        }
    }

    /**
     * Set the range and selectionRange on a DocumentSymbol from a JDT source element.
     */
    private void setRanges(DocumentSymbol symbol, org.eclipse.jdt.core.IJavaElement element, String content) {
        setRanges(symbol, element, content, PositionUtils.buildLineIndex(content));
    }

    private void setRanges(DocumentSymbol symbol,
            org.eclipse.jdt.core.IJavaElement element,
            String content,
            PositionUtils.LineIndex lineIndex) {
        try {
            if (element instanceof ISourceReference sourceRef) {

                // Full source range
                ISourceRange sourceRange = sourceRef.getSourceRange();
                Range fullRange;
                if (sourceRange != null && sourceRange.getOffset() >= 0
                        && sourceRange.getLength() >= 0) {
                    fullRange = normalizeRange(toRange(lineIndex, sourceRange));
                } else {
                    fullRange = new Range(new Position(0, 0), new Position(0, 0));
                }
                symbol.setRange(fullRange);

                // Name range (selection range)
                ISourceRange nameRange = sourceRef.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0) {
                    symbol.setSelectionRange(clampSelectionRange(
                            normalizeRange(toRange(lineIndex, nameRange)), fullRange));
                } else {
                    symbol.setSelectionRange(fullRange);
                }
            } else {
                Range defaultRange = new Range(new Position(0, 0), new Position(0, 0));
                symbol.setRange(defaultRange);
                symbol.setSelectionRange(defaultRange);
            }
        } catch (Exception e) {
            Range defaultRange = new Range(new Position(0, 0), new Position(0, 0));
            symbol.setRange(defaultRange);
            symbol.setSelectionRange(defaultRange);
        }
    }

    /**
     * Convert a JDT source range to an LSP range.
     */
    private Range toRange(String content, ISourceRange sourceRange) {
        return toRange(PositionUtils.buildLineIndex(content), sourceRange);
    }

    private Range toRange(PositionUtils.LineIndex lineIndex, ISourceRange sourceRange) {
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();

        Position start = lineIndex.offsetToPosition(startOffset);
        Position end = lineIndex.offsetToPosition(endOffset);

        return new Range(start, end);
    }

    private SymbolKind getTypeKind(IType type) {
        try {
            if (isTrait(type)) return SymbolKind.Struct;
            if (type.isInterface()) return SymbolKind.Interface;
            if (type.isEnum()) return SymbolKind.Enum;
        } catch (Exception e) {
            // fall through
        }
        return SymbolKind.Class;
    }

    private boolean isTrait(IType type) {
        try {
            for (IAnnotation ann : type.getAnnotations()) {
                String name = ann.getElementName();
                if ("Trait".equals(name) || name.endsWith(".Trait")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private Position offsetToPosition(String content, int offset) {
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

    // ---- Groovy AST fallback for document symbols ----

    /**
     * Build document symbols from the Groovy AST when JDT is not available.
     */
        private List<Either<SymbolInformation, DocumentSymbol>> getDocumentSymbolsFromGroovyAST(
            String uri) {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return result;
        }

        try {
            for (ClassNode classNode : ast.getClasses()) {
                // Skip script class (auto-generated for scripts)
                if (classNode.isScript() && classNode.getMethods().isEmpty()
                        && classNode.getProperties().isEmpty()) {
                    continue;
                }

                result.add(Either.forRight(toDocumentSymbolFromAST(classNode)));
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Document symbols from Groovy AST failed for " + uri, e);
        }

        return result;
    }

    /**
     * Convert a Groovy {@link ClassNode} to a {@link DocumentSymbol} with children.
     */
    private DocumentSymbol toDocumentSymbolFromAST(ClassNode classNode) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(classNode.getNameWithoutPackage());
        symbol.setKind(getAstTypeKind(classNode));
        setAstSuperclassDetail(symbol, classNode);
        setRangesFromAST(symbol, classNode);
        symbol.setChildren(createAstChildren(classNode));
        return symbol;
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
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                detail.append(", ");
            }
            detail.append(params[i].getType().getNameWithoutPackage());
            String displayName = ParameterNameSupport.displayName(params[i].getName());
            if (displayName != null) {
                detail.append(' ').append(displayName);
            }
        }
        detail.append("): ").append(method.getReturnType().getNameWithoutPackage());
        return detail.toString();
    }

    private void addAstInnerTypeSymbols(ClassNode classNode, List<DocumentSymbol> children) {
        java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter =
                classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode inner = innerIter.next();
            DocumentSymbol innerSymbol = toDocumentSymbolFromAST(inner);
            if (innerSymbol != null) {
                children.add(innerSymbol);
            }
        }
    }

    /**
     * Set ranges from Groovy AST node line/column information.
     */
    private void setRangesFromAST(DocumentSymbol symbol,
            org.codehaus.groovy.ast.ASTNode node) {
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

        // Guard against invalid ranges where end is before start
        if (endLine == startLine && endCol < startCol) {
            endCol = startCol;
        }

        Range fullRange = new Range(
                new Position(startLine, startCol),
                new Position(endLine, endCol));
        symbol.setRange(fullRange);

        // Selection range: just the name area (approximate: start of the node)
        Range nameRange = new Range(
                new Position(startLine, startCol),
                new Position(startLine, startCol + symbol.getName().length()));
        symbol.setSelectionRange(clampSelectionRange(nameRange, fullRange));
    }

    /**
     * Ensure a range has end >= start. If the range is inverted, collapse it
     * to a zero-width range at the start position.
     */
    private Range normalizeRange(Range range) {
        if (comparePositions(range.getEnd(), range.getStart()) < 0) {
            return new Range(range.getStart(), range.getStart());
        }
        return range;
    }

    /**
     * Clamp a selectionRange so that it is fully contained within the fullRange.
     * The LSP spec requires selectionRange to be within the enclosing range.
     */
    private Range clampSelectionRange(Range selection, Range full) {
        Position fullStart = full.getStart();
        Position fullEnd = full.getEnd();

        // Clamp both endpoints into [fullStart, fullEnd]
        Position clampedStart = minPosition(maxPosition(selection.getStart(), fullStart), fullEnd);
        Position clampedEnd = minPosition(maxPosition(selection.getEnd(), fullStart), fullEnd);

        // Defensive: if start somehow still after end, collapse to start
        if (comparePositions(clampedStart, clampedEnd) > 0) {
            clampedEnd = clampedStart;
        }

        return new Range(clampedStart, clampedEnd);
    }

    private static Position maxPosition(Position a, Position b) {
        return comparePositions(a, b) >= 0 ? a : b;
    }

    private static Position minPosition(Position a, Position b) {
        return comparePositions(a, b) <= 0 ? a : b;
    }

    private static int comparePositions(Position a, Position b) {
        if (a.getLine() != b.getLine()) {
            return Integer.compare(a.getLine(), b.getLine());
        }
        return Integer.compare(a.getCharacter(), b.getCharacter());
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
