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

    private static final String GROOVY_FENCE_OPEN = "```groovy\n";
    private static final String FENCE_CLOSE = "\n```\n";
    private static final String EXTENDS_KW = " extends ";
    private static final String FENCE_CLOSE_BARE = "\n```";

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
            } catch (Exception e) {
                GroovyLanguageServerPlugin.logError("Hover JDT failed for " + uri + ", falling back to AST", e);
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
        sb.append(buildSignatureContent(element));

        // Try to get Javadoc / Groovydoc
        if (element instanceof org.eclipse.jdt.core.IMember member) {
            String javadoc = getJavadocForElement(member);
            if (javadoc != null && !javadoc.isEmpty()) {
                sb.append("\n---\n").append(javadoc);
            }
        }

        return sb.toString();
    }

    private String buildSignatureContent(IJavaElement element) throws JavaModelException {
        switch (element.getElementType()) {
            case IJavaElement.TYPE:
                return buildTypeSignature((IType) element);
            case IJavaElement.METHOD:
                return buildMethodSignature((IMethod) element);
            case IJavaElement.FIELD:
                return buildFieldSignature((IField) element);
            case IJavaElement.LOCAL_VARIABLE:
                return buildLocalVarSignature((ILocalVariable) element);
            default:
                return "`" + element.getElementName() + "`";
        }
    }

    private String buildTypeSignature(IType type) throws JavaModelException {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);

        String modifiers = org.eclipse.jdt.core.Flags.toString(type.getFlags());
        if (!modifiers.isEmpty()) {
            sb.append(modifiers).append(' ');
        }

        appendTypeKind(sb, type);
        sb.append(type.getElementName());
        appendSuperclass(sb, type.getSuperclassName());
        appendInterfaces(sb, type);

        sb.append(FENCE_CLOSE);

        String pkg = type.getFullyQualifiedName();
        int lastDot = pkg.lastIndexOf('.');
        if (lastDot > 0) {
            sb.append("\n*Package:* `").append(pkg.substring(0, lastDot)).append("`\n");
        }
        return sb.toString();
    }

    private void appendTypeKind(StringBuilder sb, IType type) throws JavaModelException {
        if (isTrait(type)) {
            sb.append("trait ");
        } else if (type.isInterface()) {
            sb.append("interface ");
        } else if (type.isEnum()) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
    }

    private void appendSuperclass(StringBuilder sb, String superclass) {
        if (superclass != null && !"Object".equals(superclass)
                && !"java.lang.Object".equals(superclass)) {
            sb.append(EXTENDS_KW).append(simpleName(superclass));
        }
    }

    private void appendInterfaces(StringBuilder sb, IType type) throws JavaModelException {
        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces != null && interfaces.length > 0) {
            sb.append(type.isInterface() ? EXTENDS_KW : " implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleName(interfaces[i]));
            }
        }
    }

    private String buildMethodSignature(IMethod method) throws JavaModelException {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);

        String modifiers = org.eclipse.jdt.core.Flags.toString(method.getFlags());
        if (!modifiers.isEmpty()) {
            sb.append(modifiers).append(' ');
        }

        if (!method.isConstructor()) {
            String returnType = Signature.toString(method.getReturnType());
            sb.append(returnType).append(' ');
        }

        sb.append(method.getElementName());
        appendMethodParameters(sb, method);
        appendExceptions(sb, method);

        sb.append(FENCE_CLOSE);

        IType declaringType = method.getDeclaringType();
        if (declaringType != null) {
            sb.append("\n*Declared in* `").append(declaringType.getFullyQualifiedName()).append("`\n");
        }
        return sb.toString();
    }

    private void appendMethodParameters(StringBuilder sb, IMethod method) throws JavaModelException {
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
    }

    private void appendExceptions(StringBuilder sb, IMethod method) throws JavaModelException {
        String[] exceptions = method.getExceptionTypes();
        if (exceptions != null && exceptions.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < exceptions.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(Signature.toString(exceptions[i]));
            }
        }
    }

    private String buildFieldSignature(IField field) throws JavaModelException {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);

        String modifiers = org.eclipse.jdt.core.Flags.toString(field.getFlags());
        if (!modifiers.isEmpty()) {
            sb.append(modifiers).append(' ');
        }

        sb.append(Signature.toString(field.getTypeSignature()));
        sb.append(' ').append(field.getElementName());

        Object constant = field.getConstant();
        if (constant != null) {
            sb.append(" = ").append(constant);
        }

        sb.append(FENCE_CLOSE);

        IType declaringType = field.getDeclaringType();
        if (declaringType != null) {
            sb.append("\n*Declared in* `").append(declaringType.getFullyQualifiedName()).append("`\n");
        }
        return sb.toString();
    }

    private String buildLocalVarSignature(ILocalVariable local) {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
        sb.append(Signature.toString(local.getTypeSignature()));
        sb.append(' ').append(local.getElementName());
        sb.append(FENCE_CLOSE);
        sb.append("\n*(local variable)*\n");
        return sb.toString();
    }

    /**
     * Get Javadoc/Groovydoc for a JDT element by reading directly from the sources JAR.
     * Falls back to JDT's getAttachedJavadoc if source reading fails.
     */
    private String getJavadocForElement(IJavaElement element) {
        try {
            String attachedDoc = getJavadocFromAttachment(element);
            if (attachedDoc != null) {
                return attachedDoc;
            }

            IType type = resolveOwnerType(element);
            if (type == null) {
                return null;
            }

            String source = loadSourceForJavadoc(type);
            if (source == null || source.isEmpty()) {
                return null;
            }

            return extractJavadocFromSource(source, element, type);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[hover] Failed to get Javadoc for " + element.getElementName(), e);
            return null;
        }
    }

    private String getJavadocFromAttachment(IJavaElement element) {
        if (element instanceof org.eclipse.jdt.core.IMember member) {
            try {
                String jdtDoc = member.getAttachedJavadoc(null);
                if (jdtDoc != null && !jdtDoc.isEmpty()) {
                    return jdtDoc;
                }
            } catch (JavaModelException e) {
                // continue to source JAR approach
            }
        }
        return null;
    }

    private IType resolveOwnerType(IJavaElement element) {
        if (element instanceof IType type) {
            return type;
        }
        IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
        return ancestor instanceof IType type ? type : null;
    }

    private String loadSourceForJavadoc(IType type) {
        String fqn = type.getFullyQualifiedName();
        if (type.getClassFile() == null) {
            return SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        }
        return loadSourceFromJarOrJdk(type, fqn);
    }

    private String loadSourceFromJarOrJdk(IType type, String fqn) {
        java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
        String source = null;

        if (sourcesJar != null) {
            source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
        }

        if (source == null || source.isEmpty()) {
            source = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        }
        return source;
    }

    private String extractJavadocFromSource(String source, IJavaElement element, IType type) {
        if (element instanceof IType) {
            return SourceJarHelper.extractJavadoc(source, type.getElementName());
        }
        return SourceJarHelper.extractMemberJavadoc(source, element.getElementName());
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

        // Extract the word under cursor for matching
        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }

        for (ClassNode classNode : ast.getClasses()) {
            Hover hover = findHoverInClass(classNode, ast, word, targetLine);
            if (hover != null) {
                return hover;
            }
        }

        return null;
    }

    /**
     * Search a single AST class for hover information matching the given word at the target line.
     */
    private Hover findHoverInClass(ClassNode classNode, ModuleNode ast, String word, int targetLine) {
        // Check class name
        if (classNode.getNameWithoutPackage().equals(word) && isInRange(classNode, targetLine)) {
            return buildASTHover(buildClassHover(classNode));
        }

        Hover memberHover = findMemberHoverInClass(classNode, word, targetLine);
        if (memberHover != null) {
            return memberHover;
        }

        // Check members inherited from traits/interfaces
        return resolveTraitMemberHover(classNode, ast, word, targetLine);
    }

    /**
     * Search direct members (methods, fields, properties) of a class for hover information.
     */
    private Hover findMemberHoverInClass(ClassNode classNode, String word, int targetLine) {
        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().equals(word) && isInRange(method, targetLine)) {
                return buildASTHover(buildMethodHover(method));
            }
        }

        for (FieldNode field : classNode.getFields()) {
            if (field.getName().equals(word) && isInRange(field, targetLine)) {
                return buildASTHover(buildFieldHover(field));
            }
        }

        for (PropertyNode prop : classNode.getProperties()) {
            if (isPropertyMatch(prop, word, targetLine)) {
                return buildASTHover(buildPropertyHover(prop));
            }
        }

        return null;
    }

    /**
     * Check if a property node matches the target word and line.
     */
    private boolean isPropertyMatch(PropertyNode prop, String word, int targetLine) {
        if (!prop.getName().equals(word)) {
            return false;
        }
        if (prop.getField() != null && isInRange(prop.getField(), targetLine)) {
            return true;
        }
        return isInRange(prop, targetLine);
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
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
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
            sb.append(EXTENDS_KW).append(superClass.getNameWithoutPackage());
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getNameWithoutPackage());
            }
        }
        sb.append(FENCE_CLOSE_BARE);
        return sb.toString();
    }

    private String buildMethodHover(MethodNode method) {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
        sb.append(method.getReturnType().getNameWithoutPackage()).append(' ');
        sb.append(method.getName()).append('(');
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getNameWithoutPackage());
            sb.append(' ').append(params[i].getName());
        }
        sb.append(')');
        sb.append(FENCE_CLOSE_BARE);
        return sb.toString();
    }

    private String buildFieldHover(FieldNode field) {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
        sb.append(field.getType().getNameWithoutPackage());
        sb.append(' ').append(field.getName());
        sb.append(FENCE_CLOSE_BARE);
        return sb.toString();
    }

    private String buildPropertyHover(PropertyNode prop) {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
        sb.append(prop.getType().getNameWithoutPackage());
        sb.append(' ').append(prop.getName());
        sb.append(FENCE_CLOSE_BARE).append("\n\n*(property)*");
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
