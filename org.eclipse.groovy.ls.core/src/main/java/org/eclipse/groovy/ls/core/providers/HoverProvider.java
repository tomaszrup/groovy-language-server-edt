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
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;

/**
 * Provides hover information for Groovy documents.
 * <p>
 * Uses JDT's {@link ICompilationUnit#codeSelect(int, int)} to resolve the element
 * under the cursor, then formats type signatures, documentation, and source info
 * as Markdown content for the hover popup.
 */
public class HoverProvider {

    private final DocumentManager documentManager;

    public HoverProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute hover information at the cursor position.
     */
    public Hover getHover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);

                    // codeSelect resolves the element at the given offset
                    IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
                    if (elements != null && elements.length > 0) {
                        IJavaElement element = elements[0];
                        String hoverContent = buildHoverContent(element);

                        if (hoverContent != null && !hoverContent.isEmpty()) {
                            MarkupContent markup = new MarkupContent();
                            markup.setKind(MarkupKind.MARKDOWN);
                            markup.setValue(hoverContent);
                            return new Hover(markup);
                        }
                    }
                }
            } catch (Throwable t) {
                GroovyLanguageServerPlugin.logError("Hover JDT failed for " + uri + ", falling back to AST", t);
            }
        }

        // Fallback: try hover from Groovy AST
        return getHoverFromGroovyAST(uri, position);
    }

    /**
     * Build a Markdown hover string for a JDT element.
     */
    private String buildHoverContent(IJavaElement element) throws JavaModelException {
        StringBuilder sb = new StringBuilder();

        switch (element.getElementType()) {
            case IJavaElement.TYPE: {
                IType type = (IType) element;
                sb.append("```groovy\n");

                // Modifiers
                String modifiers = org.eclipse.jdt.core.Flags.toString(type.getFlags());
                if (!modifiers.isEmpty()) {
                    sb.append(modifiers).append(' ');
                }

                // Kind
                if (isTrait(type)) {
                    sb.append("trait ");
                } else if (type.isInterface()) {
                    sb.append("interface ");
                } else if (type.isEnum()) {
                    sb.append("enum ");
                } else {
                    sb.append("class ");
                }

                sb.append(type.getElementName());

                // Superclass
                String superclass = type.getSuperclassName();
                if (superclass != null && !"Object".equals(superclass)
                        && !"java.lang.Object".equals(superclass)) {
                    sb.append(" extends ").append(simpleName(superclass));
                }

                // Interfaces
                String[] interfaces = type.getSuperInterfaceNames();
                if (interfaces != null && interfaces.length > 0) {
                    sb.append(type.isInterface() ? " extends " : " implements ");
                    for (int i = 0; i < interfaces.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(simpleName(interfaces[i]));
                    }
                }

                sb.append("\n```\n");

                // Package info
                String pkg = type.getFullyQualifiedName();
                int lastDot = pkg.lastIndexOf('.');
                if (lastDot > 0) {
                    sb.append("\n*Package:* `").append(pkg.substring(0, lastDot)).append("`\n");
                }
                break;
            }

            case IJavaElement.METHOD: {
                IMethod method = (IMethod) element;
                sb.append("```groovy\n");

                // Modifiers
                String modifiers = org.eclipse.jdt.core.Flags.toString(method.getFlags());
                if (!modifiers.isEmpty()) {
                    sb.append(modifiers).append(' ');
                }

                // Return type
                if (!method.isConstructor()) {
                    String returnType = Signature.toString(method.getReturnType());
                    sb.append(returnType).append(' ');
                }

                // Method name
                sb.append(method.getElementName());

                // Parameters
                sb.append('(');
                String[] paramTypes = method.getParameterTypes();
                String[] paramNames = method.getParameterNames();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(Signature.toString(paramTypes[i]));
                    if (paramNames != null && i < paramNames.length) {
                        sb.append(' ').append(paramNames[i]);
                    }
                }
                sb.append(')');

                // Exceptions
                String[] exceptions = method.getExceptionTypes();
                if (exceptions != null && exceptions.length > 0) {
                    sb.append(" throws ");
                    for (int i = 0; i < exceptions.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(Signature.toString(exceptions[i]));
                    }
                }

                sb.append("\n```\n");

                // Declaring type
                IType declaringType = method.getDeclaringType();
                if (declaringType != null) {
                    sb.append("\n*Declared in* `").append(declaringType.getFullyQualifiedName()).append("`\n");
                }
                break;
            }

            case IJavaElement.FIELD: {
                IField field = (IField) element;
                sb.append("```groovy\n");

                // Modifiers
                String modifiers = org.eclipse.jdt.core.Flags.toString(field.getFlags());
                if (!modifiers.isEmpty()) {
                    sb.append(modifiers).append(' ');
                }

                // Type
                sb.append(Signature.toString(field.getTypeSignature()));
                sb.append(' ').append(field.getElementName());

                // Constant value
                Object constant = field.getConstant();
                if (constant != null) {
                    sb.append(" = ").append(constant);
                }

                sb.append("\n```\n");

                // Declaring type
                IType declaringType = field.getDeclaringType();
                if (declaringType != null) {
                    sb.append("\n*Declared in* `").append(declaringType.getFullyQualifiedName()).append("`\n");
                }
                break;
            }

            case IJavaElement.LOCAL_VARIABLE: {
                ILocalVariable local = (ILocalVariable) element;
                sb.append("```groovy\n");
                sb.append(Signature.toString(local.getTypeSignature()));
                sb.append(' ').append(local.getElementName());
                sb.append("\n```\n");
                sb.append("\n*(local variable)*\n");
                break;
            }

            default:
                sb.append("`").append(element.getElementName()).append("`");
                break;
        }

        // Try to get Javadoc / Groovydoc
        if (element instanceof org.eclipse.jdt.core.IMember) {
            String javadoc = getJavadocForElement(element);
            if (javadoc != null && !javadoc.isEmpty()) {
                sb.append("\n---\n").append(javadoc);
            }
        }

        return sb.toString();
    }

    /**
     * Get Javadoc/Groovydoc for a JDT element by reading directly from the sources JAR.
     * Falls back to JDT's getAttachedJavadoc if source reading fails.
     */
    private String getJavadocForElement(IJavaElement element) {
        try {
            // First try JDT's built-in Javadoc
            if (element instanceof org.eclipse.jdt.core.IMember) {
                try {
                    String jdtDoc = ((org.eclipse.jdt.core.IMember) element).getAttachedJavadoc(null);
                    if (jdtDoc != null && !jdtDoc.isEmpty()) {
                        return jdtDoc;
                    }
                } catch (JavaModelException e) {
                    // continue to source JAR approach
                }
            }

            // Get the type that owns this element
            IType type = null;
            if (element instanceof IType) {
                type = (IType) element;
            } else {
                IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
                if (ancestor instanceof IType) {
                    type = (IType) ancestor;
                }
            }

            if (type == null || type.getClassFile() == null) {
                // Might be a JDK type (no classFile, source is in src.zip)
                if (type != null) {
                    String fqn = type.getFullyQualifiedName();
                    String jdkSource = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
                    if (jdkSource != null) {
                        if (element instanceof IType) {
                            return SourceJarHelper.extractJavadoc(jdkSource, type.getElementName());
                        } else {
                            return SourceJarHelper.extractMemberJavadoc(jdkSource, element.getElementName());
                        }
                    }
                }
                return null;
            }

            // Find and read from sources JAR
            java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
            String fqn = type.getFullyQualifiedName();
            String source = null;

            if (sourcesJar != null) {
                source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
            }

            // Fallback: try JDK src.zip
            if (source == null || source.isEmpty()) {
                source = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
            }

            if (source == null || source.isEmpty()) return null;

            // Extract the appropriate Javadoc
            if (element instanceof IType) {
                return SourceJarHelper.extractJavadoc(source, type.getElementName());
            } else {
                // For methods/fields, extract member doc
                String memberName = element.getElementName();
                String memberDoc = SourceJarHelper.extractMemberJavadoc(source, memberName);
                return memberDoc;
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[hover] Failed to get Javadoc for " + element.getElementName(), e);
            return null;
        }
    }

    private int positionToOffset(String content, Position position) {
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

    /**
     * Extract the simple class name from a potentially fully-qualified name.
     * "java.lang.String" → "String", "String" → "String"
     */
    private String simpleName(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    // ---- Groovy AST fallback hover ----

    /**
     * Provide hover information from the Groovy AST when JDT is not available.
     * Finds the AST node at the cursor position and shows its type signature.
     */
    private Hover getHoverFromGroovyAST(String uri, Position position) {
        String content = documentManager.getContent(uri);
        if (content == null) {
            return null;
        }

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return null;
        }

        // LSP positions are 0-based; Groovy AST is 1-based
        int targetLine = position.getLine() + 1;
        int targetCol = position.getCharacter() + 1;

        // Extract the word under cursor for matching
        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }

        for (ClassNode classNode : ast.getClasses()) {
            // Check class name
            if (classNode.getNameWithoutPackage().equals(word)
                    && isInRange(classNode, targetLine)) {
                return buildASTHover(buildClassHover(classNode));
            }

            // Check methods
            for (MethodNode method : classNode.getMethods()) {
                if (method.getName().equals(word)
                        && isInRange(method, targetLine)) {
                    return buildASTHover(buildMethodHover(method));
                }
            }

            // Check fields
            for (FieldNode field : classNode.getFields()) {
                if (field.getName().equals(word)
                        && isInRange(field, targetLine)) {
                    return buildASTHover(buildFieldHover(field));
                }
            }

            // Check properties
            for (PropertyNode prop : classNode.getProperties()) {
                if (prop.getName().equals(word)
                        && ((prop.getField() != null && isInRange(prop.getField(), targetLine))
                                || isInRange(prop, targetLine))) {
                    return buildASTHover(buildPropertyHover(prop));
                }
            }

            // Check members inherited from traits/interfaces
            Hover traitHover = resolveTraitMemberHover(classNode, ast, word, targetLine);
            if (traitHover != null) {
                return traitHover;
            }
        }

        return null;
    }

    /**
     * Look up a member in the traits/interfaces implemented by the given class.
     */
    private Hover resolveTraitMemberHover(ClassNode classNode, ModuleNode ast,
                                           String word, int targetLine) {
        // Only check if the cursor is inside this class
        if (classNode.getLineNumber() > 0 && classNode.getLastLineNumber() > 0
                && (targetLine < classNode.getLineNumber() || targetLine > classNode.getLastLineNumber())) {
            return null;
        }

        for (MethodNode method : TraitMemberResolver.collectTraitMethods(classNode, ast, documentManager)) {
            if (method.getName().equals(word)) {
                return buildASTHover(buildMethodHover(method));
            }
        }

        for (FieldNode field : TraitMemberResolver.collectTraitFields(classNode, ast, documentManager)) {
            if (field.getName().equals(word)
                    || TraitMemberResolver.isTraitFieldMatch(field.getName(), word)) {
                return buildASTHover(buildFieldHover(field));
            }
        }

        for (PropertyNode prop : TraitMemberResolver.collectTraitProperties(classNode, ast, documentManager)) {
            if (prop.getName().equals(word)) {
                return buildASTHover(buildPropertyHover(prop));
            }
        }

        return null;
    }

    private Hover buildASTHover(String hoverText) {
        if (hoverText == null || hoverText.isEmpty()) {
            return null;
        }
        MarkupContent markup = new MarkupContent();
        markup.setKind(MarkupKind.MARKDOWN);
        markup.setValue(hoverText);
        return new Hover(markup);
    }

    private String buildClassHover(ClassNode classNode) {
        StringBuilder sb = new StringBuilder("```groovy\n");
        if (GroovyTypeKindHelper.isTrait(classNode)) {
            sb.append("trait ");
        } else if (classNode.isInterface()) {
            sb.append("interface ");
        } else if (classNode.isEnum()) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
        sb.append(classNode.getName());
        ClassNode superClass = classNode.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getName())) {
            sb.append(" extends ").append(superClass.getNameWithoutPackage());
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getNameWithoutPackage());
            }
        }
        sb.append("\n```");
        return sb.toString();
    }

    private String buildMethodHover(MethodNode method) {
        StringBuilder sb = new StringBuilder("```groovy\n");
        sb.append(method.getReturnType().getNameWithoutPackage()).append(' ');
        sb.append(method.getName()).append('(');
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getNameWithoutPackage());
            sb.append(' ').append(params[i].getName());
        }
        sb.append(')');
        sb.append("\n```");
        return sb.toString();
    }

    private String buildFieldHover(FieldNode field) {
        StringBuilder sb = new StringBuilder("```groovy\n");
        sb.append(field.getType().getNameWithoutPackage());
        sb.append(' ').append(field.getName());
        sb.append("\n```");
        return sb.toString();
    }

    private String buildPropertyHover(PropertyNode prop) {
        StringBuilder sb = new StringBuilder("```groovy\n");
        sb.append(prop.getType().getNameWithoutPackage());
        sb.append(' ').append(prop.getName());
        sb.append("\n```\n\n*(property)*");
        return sb.toString();
    }

    private boolean isInRange(org.codehaus.groovy.ast.ASTNode node, int line) {
        return node.getLineNumber() <= line && node.getLastLineNumber() >= line;
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

    private String extractWordAt(String content, int offset) {
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
