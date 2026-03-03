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
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TypeDefinitionParams;

/**
 * Provides go-to-type-definition for Groovy documents.
 * <p>
 * Resolves the element at the cursor via JDT codeSelect, determines its type,
 * and navigates to the type's declaration. This is distinct from go-to-definition:
 * for a variable {@code String name}, definition goes to the variable declaration,
 * while type-definition goes to the {@code String} class.
 */
public class TypeDefinitionProvider {

    private final DocumentManager documentManager;

    public TypeDefinitionProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute the type definition location(s) for the element at the cursor.
     */
    public List<Location> getTypeDefinition(TypeDefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        List<Location> jdtLocations = resolveViaJdt(uri, position);
        if (!jdtLocations.isEmpty()) {
            return jdtLocations;
        }

        return resolveFromGroovyAST(uri, position);
    }

    private List<Location> resolveViaJdt(String uri, Position position) {
        List<Location> locations = new ArrayList<>();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return locations;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return locations;
            }

            int offset = positionToOffset(content, position);
            IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
            if (elements == null || elements.length == 0) {
                return locations;
            }

            IJavaElement element = elements[0];
            IType type = resolveType(element);
            if (type == null) {
                return locations;
            }

            Location location = toLocation(type);
            if (location != null) {
                locations.add(location);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Type definition JDT failed for " + uri + ", falling back to AST", e);
        }

        return locations;
    }

    /**
     * Resolve the type of a JDT element.
     * <ul>
     *   <li>IType → itself</li>
     *   <li>IField → its declared type</li>
     *   <li>IMethod → its return type</li>
     *   <li>ILocalVariable → its declared type</li>
     * </ul>
     */
    IType resolveType(IJavaElement element) throws JavaModelException {
        if (element instanceof IType itype) {
            return itype;
        }

        if (element instanceof IField field) {
            String typeSig = field.getTypeSignature();
            return resolveTypeFromSignature(typeSig, field.getDeclaringType());
        }

        if (element instanceof IMethod method) {
            String returnSig = method.getReturnType();
            return resolveTypeFromSignature(returnSig, method.getDeclaringType());
        }

        if (element instanceof ILocalVariable local) {
            String typeSig = local.getTypeSignature();
            IJavaElement parent = local.getParent();
            IType declaringType = null;
            if (parent instanceof IMethod m) {
                declaringType = m.getDeclaringType();
            } else {
                IJavaElement ancestor = local.getAncestor(IJavaElement.TYPE);
                if (ancestor instanceof IType t) {
                    declaringType = t;
                }
            }
            if (declaringType != null) {
                return resolveTypeFromSignature(typeSig, declaringType);
            }
        }

        return null;
    }

    private IType resolveTypeFromSignature(String typeSig, IType declaringType)
            throws JavaModelException {
        if (typeSig == null || declaringType == null) {
            return null;
        }
        String typeName = Signature.toString(typeSig);
        // Strip array brackets and generics
        typeName = stripArrayAndGenerics(typeName);

        // Try resolve as simple name within the declaring type's context
        String[][] resolvedNames = declaringType.resolveType(typeName);
        if (resolvedNames != null && resolvedNames.length > 0) {
            String fqn = resolvedNames[0][0].isEmpty()
                    ? resolvedNames[0][1]
                    : resolvedNames[0][0] + "." + resolvedNames[0][1];
            return declaringType.getJavaProject().findType(fqn);
        }

        // Try direct lookup as FQN
        return declaringType.getJavaProject().findType(typeName);
    }

    String stripArrayAndGenerics(String typeName) {
        // Remove generics: List<String> → List
        int angleBracket = typeName.indexOf('<');
        if (angleBracket >= 0) {
            typeName = typeName.substring(0, angleBracket);
        }
        // Remove array: String[] → String
        int bracket = typeName.indexOf('[');
        if (bracket >= 0) {
            typeName = typeName.substring(0, bracket);
        }
        return typeName.trim();
    }

    private Location toLocation(IType type) {
        try {
            // Try workspace resource first
            org.eclipse.core.resources.IResource resource = type.getResource();
            if (resource == null) {
                ICompilationUnit cu = type.getCompilationUnit();
                if (cu != null) {
                    resource = cu.getResource();
                }
            }

            if (resource != null && resource.getLocationURI() != null) {
                String targetUri = resource.getLocationURI().toString();
                Range range = new Range(new Position(0, 0), new Position(0, 0));
                ISourceRange nameRange = type.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0) {
                    range = offsetRangeToLspRange(targetUri, resource, nameRange);
                }
                return new Location(targetUri, range);
            }

            // Binary type: use SourceJarHelper
            java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
            if (sourcesJar != null) {
                String fqn = type.getFullyQualifiedName();
                String source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
                if (source != null && !source.isEmpty()) {
                    String virtualUri = SourceJarHelper.buildGroovySourceUri(
                            fqn, ".java", sourcesJar.getAbsolutePath(), false, source);
                    return new Location(virtualUri,
                            new Range(new Position(0, 0), new Position(0, 0)));
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to resolve type location for " + type.getElementName(), e);
        }
        return null;
    }

    // ---- Groovy AST fallback ----

    private List<Location> resolveFromGroovyAST(String uri, Position position) {
        List<Location> locations = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) {
            return locations;
        }

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return locations;
        }

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return locations;
        }

        // Check if the word matches a class declared in the same file
        for (ClassNode classNode : ast.getClasses()) {
            // Check fields for the word — their type might be a class in this file
            for (FieldNode field : classNode.getFields()) {
                if (word.equals(field.getName())) {
                    String typeName = field.getType().getNameWithoutPackage();
                    Location loc = findClassLocationInFile(uri, ast, typeName);
                    if (loc != null) {
                        locations.add(loc);
                        return locations;
                    }
                }
            }
        }

        return locations;
    }

    private Location findClassLocationInFile(String uri, ModuleNode ast,
            String typeName) {
        for (ClassNode classNode : ast.getClasses()) {
            if (typeName.equals(classNode.getNameWithoutPackage())
                    && classNode.getLineNumber() >= 1) {
                int line = classNode.getLineNumber() - 1;
                int col = classNode.getColumnNumber() - 1;
                Position start = new Position(line, Math.max(col, 0));
                Position end = new Position(line, Math.max(col, 0) + typeName.length());
                return new Location(uri, new Range(start, end));
            }
        }
        return null;
    }

    // ---- Helpers ----

    private Range offsetRangeToLspRange(String uri, org.eclipse.core.resources.IResource resource,
            ISourceRange sourceRange) {
        try {
            String content = documentManager.getContent(uri);
            if (content == null && resource instanceof org.eclipse.core.resources.IFile ifile) {
                try (java.io.InputStream is = ifile.getContents()) {
                    content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            if (content == null) {
                return new Range(new Position(0, 0), new Position(0, 0));
            }
            Position start = offsetToPosition(content, sourceRange.getOffset());
            Position end = offsetToPosition(content, sourceRange.getOffset() + sourceRange.getLength());
            return new Range(start, end);
        } catch (Exception e) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
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

    String extractWordAt(String content, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = offset;
        while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
            end++;
        }
        if (start == end) return null;
        return content.substring(start, end);
    }
}
