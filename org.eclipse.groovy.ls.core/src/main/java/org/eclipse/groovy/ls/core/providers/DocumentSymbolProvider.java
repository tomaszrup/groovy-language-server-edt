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
            return getDocumentSymbolsFromGroovyAST(uri, content);
        }

        try {
            // Walk all types declared in this compilation unit
            IType[] types = workingCopy.getTypes();
            for (IType type : types) {
                DocumentSymbol typeSymbol = toDocumentSymbol(type, content);
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
        try {
            DocumentSymbol symbol = new DocumentSymbol();
            symbol.setName(type.getElementName());
            symbol.setKind(getTypeKind(type));

            // Detail: superclass info
            String superclass = type.getSuperclassName();
            if (superclass != null && !"Object".equals(superclass)) {
                symbol.setDetail("extends " + superclass);
            }

            // Ranges
            setRanges(symbol, type, content);

            // Children: methods, fields, inner types
            List<DocumentSymbol> children = new ArrayList<>();

            // Fields
            for (IField field : type.getFields()) {
                DocumentSymbol fieldSymbol = new DocumentSymbol();
                fieldSymbol.setName(field.getElementName());
                fieldSymbol.setKind(org.eclipse.jdt.core.Flags.isEnum(field.getFlags())
                        ? SymbolKind.EnumMember : SymbolKind.Field);

                try {
                    fieldSymbol.setDetail(Signature.toString(field.getTypeSignature()));
                } catch (Exception e) {
                    // ignore
                }

                setRanges(fieldSymbol, field, content);
                children.add(fieldSymbol);
            }

            // Methods
            for (IMethod method : type.getMethods()) {
                DocumentSymbol methodSymbol = new DocumentSymbol();
                methodSymbol.setName(method.getElementName());
                methodSymbol.setKind(method.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method);

                // Detail: parameter signature
                StringBuilder detail = new StringBuilder("(");
                String[] paramTypes = method.getParameterTypes();
                String[] paramNames = method.getParameterNames();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (i > 0) detail.append(", ");
                    detail.append(Signature.toString(paramTypes[i]));
                    if (paramNames != null && i < paramNames.length) {
                        detail.append(' ').append(paramNames[i]);
                    }
                }
                detail.append(')');

                if (!method.isConstructor()) {
                    detail.append(": ").append(Signature.toString(method.getReturnType()));
                }

                methodSymbol.setDetail(detail.toString());
                setRanges(methodSymbol, method, content);
                children.add(methodSymbol);
            }

            // Inner types (recursive)
            for (IType innerType : type.getTypes()) {
                DocumentSymbol innerSymbol = toDocumentSymbol(innerType, content);
                if (innerSymbol != null) {
                    children.add(innerSymbol);
                }
            }

            symbol.setChildren(children);
            return symbol;

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to build document symbol for " + type.getElementName(), e);
            return null;
        }
    }

    /**
     * Set the range and selectionRange on a DocumentSymbol from a JDT source element.
     */
    private void setRanges(DocumentSymbol symbol, org.eclipse.jdt.core.IJavaElement element, String content) {
        try {
            if (element instanceof ISourceReference) {
                ISourceReference sourceRef = (ISourceReference) element;

                // Full source range
                ISourceRange sourceRange = sourceRef.getSourceRange();
                if (sourceRange != null && sourceRange.getOffset() >= 0) {
                    symbol.setRange(toRange(content, sourceRange));
                } else {
                    symbol.setRange(new Range(new Position(0, 0), new Position(0, 0)));
                }

                // Name range (selection range)
                ISourceRange nameRange = sourceRef.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0) {
                    symbol.setSelectionRange(toRange(content, nameRange));
                } else {
                    symbol.setSelectionRange(symbol.getRange());
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
        int startOffset = sourceRange.getOffset();
        int endOffset = startOffset + sourceRange.getLength();

        Position start = offsetToPosition(content, startOffset);
        Position end = offsetToPosition(content, endOffset);

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
            String uri, String content) {
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

                DocumentSymbol classSymbol = toDocumentSymbolFromAST(classNode, content);
                if (classSymbol != null) {
                    result.add(Either.forRight(classSymbol));
                }
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
    private DocumentSymbol toDocumentSymbolFromAST(ClassNode classNode, String content) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(classNode.getNameWithoutPackage());

        // Determine kind
        if (GroovyTypeKindHelper.isTrait(classNode)) {
            symbol.setKind(SymbolKind.Struct);
        } else if (classNode.isInterface()) {
            symbol.setKind(SymbolKind.Interface);
        } else if (classNode.isEnum()) {
            symbol.setKind(SymbolKind.Enum);
        } else {
            symbol.setKind(SymbolKind.Class);
        }

        // Superclass detail
        ClassNode superClass = classNode.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getName())
                && !"groovy.lang.Script".equals(superClass.getName())) {
            symbol.setDetail("extends " + superClass.getNameWithoutPackage());
        }

        // Set ranges from AST line/column info
        setRangesFromAST(symbol, classNode, content);

        // Children
        List<DocumentSymbol> children = new ArrayList<>();

        // Properties
        for (PropertyNode prop : classNode.getProperties()) {
            DocumentSymbol propSymbol = new DocumentSymbol();
            propSymbol.setName(prop.getName());
            propSymbol.setKind(SymbolKind.Property);
            propSymbol.setDetail(prop.getType().getNameWithoutPackage());
            setRangesFromAST(propSymbol, prop.getField(), content);
            children.add(propSymbol);
        }

        // Fields (excluding property-backing fields)
        for (FieldNode field : classNode.getFields()) {
            if (field.getName().startsWith("$") || field.getName().startsWith("__")) {
                continue;
            }
            // Check if this field backs a property
            boolean isPropertyField = false;
            for (PropertyNode prop : classNode.getProperties()) {
                if (prop.getName().equals(field.getName())) {
                    isPropertyField = true;
                    break;
                }
            }
            if (isPropertyField) continue;

            DocumentSymbol fieldSymbol = new DocumentSymbol();
            fieldSymbol.setName(field.getName());
            fieldSymbol.setKind(classNode.isEnum() && field.isEnum()
                    ? SymbolKind.EnumMember : SymbolKind.Field);
            fieldSymbol.setDetail(field.getType().getNameWithoutPackage());
            setRangesFromAST(fieldSymbol, field, content);
            children.add(fieldSymbol);
        }

        // Methods
        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().startsWith("<") || method.isSynthetic()) {
                continue;
            }

            DocumentSymbol methodSymbol = new DocumentSymbol();
            methodSymbol.setName(method.getName());
            methodSymbol.setKind(method.getName().equals("<init>")
                    ? SymbolKind.Constructor : SymbolKind.Method);

            // Detail: parameters and return type
            StringBuilder detail = new StringBuilder("(");
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) detail.append(", ");
                detail.append(params[i].getType().getNameWithoutPackage());
                detail.append(' ').append(params[i].getName());
            }
            detail.append("): ").append(method.getReturnType().getNameWithoutPackage());
            methodSymbol.setDetail(detail.toString());

            setRangesFromAST(methodSymbol, method, content);
            children.add(methodSymbol);
        }

        // Inner classes
        java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter =
                classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode inner = innerIter.next();
            DocumentSymbol innerSymbol = toDocumentSymbolFromAST(inner, content);
            if (innerSymbol != null) {
                children.add(innerSymbol);
            }
        }

        symbol.setChildren(children);
        return symbol;
    }

    /**
     * Set ranges from Groovy AST node line/column information.
     */
    private void setRangesFromAST(DocumentSymbol symbol,
            org.codehaus.groovy.ast.ASTNode node, String content) {
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

        Range fullRange = new Range(
                new Position(startLine, startCol),
                new Position(endLine, endCol));
        symbol.setRange(fullRange);

        // Selection range: just the name area (approximate: start of the node)
        Range nameRange = new Range(
                new Position(startLine, startCol),
                new Position(startLine, startCol + symbol.getName().length()));
        symbol.setSelectionRange(nameRange);
    }
}
