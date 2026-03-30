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
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * Short-lived LRU cache for hover results.  Avoids redundant
     * {@code codeSelect()} calls when the user hovers the same position
     * repeatedly.  Key: {@code "uri#offset"}, Value: computed Hover.
     */
    private static final int HOVER_CACHE_SIZE = 200;
    private final Map<String, Hover> hoverCache =
            Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Hover> eldest) {
                    return size() > HOVER_CACHE_SIZE;
                }
            });

    /** Sentinel object representing a cached "no hover" result. */
    private static final Hover NO_HOVER = new Hover();

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

        Hover jdtHover = getHoverFromJdt(uri, position);
        if (jdtHover != null) {
            return jdtHover;
        }

        // Fallback: try hover from Groovy AST
        return getHoverFromGroovyAST(uri, position);
    }

    private Hover getHoverFromJdt(String uri, Position position) {
        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return null;
        }

        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return null;
            }

            PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);
            int offset = lineIndex.positionToOffset(position);
            String cacheKey = uri + "#" + offset;
            Hover cached = hoverCache.get(cacheKey);
            if (cached != null) {
                return cached == NO_HOVER ? null : cached;
            }

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            Hover hover = computeHoverForOffset(workingCopy, offset);
            if (hover == null) {
                hover = computeGeneratedAccessorHover(uri, content, lineIndex, workingCopy, offset);
            }
            if (hover != null) {
                hoverCache.put(cacheKey, hover);
                return hover;
            }

            hoverCache.put(cacheKey, NO_HOVER);
            return null;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Hover JDT failed for " + uri + ", falling back to AST", e);
            return null;
        }
    }

    private Hover computeHoverForOffset(ICompilationUnit workingCopy, int offset) throws JavaModelException {
        IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
        if (elements == null || elements.length == 0) {
            return null;
        }

        IJavaElement element = selectBestElement(elements);
        if (element == null) {
            return null;
        }

        String hoverContent = buildHoverContent(element);
        if (hoverContent == null || hoverContent.isEmpty()) {
            return null;
        }

        MarkupContent markup = new MarkupContent();
        markup.setKind(MarkupKind.MARKDOWN);
        markup.setValue(hoverContent);
        return new Hover(markup);
    }

    private IJavaElement selectBestElement(IJavaElement[] elements) throws JavaModelException {
        if (elements == null || elements.length == 0) {
            return null;
        }

        List<IMethod> methods = new ArrayList<>();
        IJavaElement fallback = null;
        for (IJavaElement originalElement : elements) {
            IJavaElement element = documentManager.remapToWorkingCopyElement(originalElement);
            if (element == null) {
                element = originalElement;
            }
            if (element == null) {
                continue;
            }
            if (fallback == null) {
                fallback = element;
            }
            if (element instanceof IMethod method) {
                methods.add(method);
            }
        }

        if (!methods.isEmpty()) {
            return choosePreferredMethod(methods);
        }
        return fallback;
    }

    private IMethod choosePreferredMethod(List<IMethod> methods) {
        IMethod best = null;
        for (IMethod method : methods) {
            if (isPreferredMethod(method, best)) {
                best = method;
            }
        }
        return best != null ? best : methods.get(0);
    }

    private boolean isPreferredMethod(IMethod candidate, IMethod currentBest) {
        if (candidate == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }

        if (isDeclaredInSubtype(candidate, currentBest)) {
            return true;
        }
        if (isDeclaredInSubtype(currentBest, candidate)) {
            return false;
        }

        if (isConcreteDeclaringType(candidate) && !isConcreteDeclaringType(currentBest)) {
            return true;
        }
        if (!isConcreteDeclaringType(candidate) && isConcreteDeclaringType(currentBest)) {
            return false;
        }

        if (isSourceBacked(candidate) && !isSourceBacked(currentBest)) {
            return true;
        }
        if (!isSourceBacked(candidate) && isSourceBacked(currentBest)) {
            return false;
        }

        return false;
    }

    private boolean isDeclaredInSubtype(IMethod candidate, IMethod currentBest) {
        try {
            IType candidateType = resolveComparisonType(candidate);
            IType currentType = resolveComparisonType(currentBest);
            if (candidateType == null || currentType == null) {
                return false;
            }

            String currentName = currentType.getFullyQualifiedName();
            if (currentName == null || currentName.isBlank()
                    || currentName.equals(candidateType.getFullyQualifiedName())) {
                return false;
            }

            ITypeHierarchy hierarchy = candidateType.newSupertypeHierarchy(null);
            if (hierarchy == null) {
                return false;
            }

            for (IType superType : hierarchy.getAllSupertypes(candidateType)) {
                if (currentName.equals(superType.getFullyQualifiedName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private IType resolveComparisonType(IMethod method) {
        try {
            IType declaringType = method != null ? method.getDeclaringType() : null;
            if (declaringType == null) {
                return null;
            }
            IType resolvedType = JavaBinaryMemberResolver.resolveMemberSource(declaringType);
            return resolvedType != null ? resolvedType : declaringType;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isConcreteDeclaringType(IMethod method) {
        try {
            IType declaringType = resolveComparisonType(method);
            return declaringType != null && !declaringType.isInterface();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSourceBacked(IMethod method) {
        try {
            if (method == null) {
                return false;
            }
            if (method.getCompilationUnit() != null) {
                return true;
            }
            IType declaringType = method.getDeclaringType();
            return declaringType != null && declaringType.getCompilationUnit() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Hover computeGeneratedAccessorHover(String uri, String content,
            ICompilationUnit workingCopy, int offset) throws JavaModelException {
        return computeGeneratedAccessorHover(
                uri, content, PositionUtils.buildLineIndex(content), workingCopy, offset);
    }

    private Hover computeGeneratedAccessorHover(String uri,
            String content,
            PositionUtils.LineIndex lineIndex,
            ICompilationUnit workingCopy,
            int offset) throws JavaModelException {
        String word = extractWordAt(content, offset);
        if (word == null || word.isBlank() || !isMemberReferenceAfterDot(content, offset)) {
            return null;
        }

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return null;
        }

        IJavaProject project = workingCopy.getJavaProject();
        if (project == null || !project.exists()) {
            return null;
        }

        IType receiverType = resolveReceiverTypeFromAst(ast, project, offset, word, content, lineIndex, uri);
        if (receiverType == null) {
            return null;
        }

        return buildGeneratedAccessorHover(receiverType, word);
    }

    private Hover buildGeneratedAccessorHover(IType receiverType, String methodName) throws JavaModelException {
        IMethod generatedMethod = GeneratedAccessorResolver.findMethod(receiverType, methodName);
        if (generatedMethod != null) {
            String hoverContent = buildHoverContent(generatedMethod);
            return buildASTHover(hoverContent, null);
        }

        JavaRecordSourceSupport.RecordComponentInfo component =
                GeneratedAccessorResolver.findRecordComponent(receiverType, methodName);
        if (component != null) {
            return buildASTHover(buildRecordAccessorHover(receiverType, component), null);
        }

        return null;
    }

    private String buildRecordAccessorHover(IType receiverType,
            JavaRecordSourceSupport.RecordComponentInfo component) throws JavaModelException {
        StringBuilder sb = new StringBuilder(GROOVY_FENCE_OPEN);
        sb.append(simplifyTypeName(component.type())).append(' ');
        sb.append(component.name()).append("()");
        sb.append(FENCE_CLOSE);

        String declaringType = receiverType.getFullyQualifiedName();
        if (declaringType != null && !declaringType.isBlank()) {
            sb.append("\n*Declared in* `").append(declaringType).append("`\n");
        }

        return sb.toString();
    }

    /**
     * Invalidate cached hover results for a document.
     * Called from {@code GroovyTextDocumentService.didChange()} when the
     * document content changes.
     */
    public void invalidateHoverCache(String uri) {
        hoverCache.entrySet().removeIf(e -> e.getKey().startsWith(uri + "#"));
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
        String[] paramNames = JdtParameterNameResolver.resolve(method);
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Signature.toString(paramTypes[i]));
            if (paramNames != null && i < paramNames.length) {
                String displayName = ParameterNameSupport.displayName(paramNames[i]);
                if (displayName != null) {
                    sb.append(' ').append(displayName);
                }
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
        String workspaceSource = loadWorkspaceSourceForJavadoc(type);
        if (workspaceSource != null && !workspaceSource.isEmpty()) {
            return workspaceSource;
        }

        String fqn = SourceJarHelper.binaryTypeFqn(type);
        if (type.getClassFile() == null) {
            return SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        }
        return loadSourceFromJarOrJdk(type, fqn);
    }

    private String loadWorkspaceSourceForJavadoc(IType type) {
        ICompilationUnit compilationUnit = resolveCompilationUnit(type);

        String openDocumentSource = loadOpenDocumentSource(type, compilationUnit);
        if (openDocumentSource != null && !openDocumentSource.isEmpty()) {
            return openDocumentSource;
        }

        if (compilationUnit == null) {
            return null;
        }

        try {
            return compilationUnit.getSource();
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("[hover] Failed to read workspace source for "
                    + type.getFullyQualifiedName(), e);
            return null;
        }
    }

    private ICompilationUnit resolveCompilationUnit(IType type) {
        ICompilationUnit compilationUnit = type.getCompilationUnit();
        if (compilationUnit != null) {
            return compilationUnit;
        }

        IJavaElement ancestor = type.getAncestor(IJavaElement.COMPILATION_UNIT);
        return ancestor instanceof ICompilationUnit cu ? cu : null;
    }

    private String loadOpenDocumentSource(IType type, ICompilationUnit compilationUnit) {
        String normalizedUri = documentManager.resolveElementUri(type);
        if ((normalizedUri == null || normalizedUri.isBlank()) && compilationUnit != null) {
            normalizedUri = documentManager.resolveElementUri(compilationUnit);
        }
        if (normalizedUri == null || normalizedUri.isBlank()) {
            return null;
        }

        return documentManager.getContent(normalizedUri);
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
        return PositionUtils.positionToOffset(content, position);
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

    private String simplifyTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }

        StringBuilder simplified = new StringBuilder(typeName.length());
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < typeName.length(); index++) {
            char current = typeName.charAt(index);
            if (Character.isJavaIdentifierPart(current) || current == '.' || current == '$') {
                token.append(current);
            } else {
                appendSimplifiedTypeToken(simplified, token);
                simplified.append(current);
            }
        }
        appendSimplifiedTypeToken(simplified, token);
        return simplified.toString();
    }

    private void appendSimplifiedTypeToken(StringBuilder target, StringBuilder token) {
        if (token.isEmpty()) {
            return;
        }

        String tokenText = token.toString();
        int splitIndex = Math.max(tokenText.lastIndexOf('.'), tokenText.lastIndexOf('$'));
        target.append(splitIndex >= 0 ? tokenText.substring(splitIndex + 1) : tokenText);
        token.setLength(0);
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
        PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);
        int offset = lineIndex.positionToOffset(position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }

        for (ClassNode classNode : ast.getClasses()) {
            Hover hover = findHoverInClass(classNode, ast, content, word, targetLine);
            if (hover != null) {
                return hover;
            }
        }

        return null;
    }

    /**
     * Search a single AST class for hover information matching the given word at the target line.
     */
    private Hover findHoverInClass(ClassNode classNode, ModuleNode ast,
                                   String content, String word, int targetLine) {
        // Check class name
        if (classNode.getNameWithoutPackage().equals(word) && isInRange(classNode, targetLine)) {
            return buildASTHover(buildClassHover(classNode),
                    extractClassDocumentation(content, classNode));
        }

        Hover memberHover = findMemberHoverInClass(classNode, content, word, targetLine);
        if (memberHover != null) {
            return memberHover;
        }

        // Check members inherited from traits/interfaces
        return resolveTraitMemberHover(classNode, ast, content, word, targetLine);
    }

    /**
     * Search direct members (methods, fields, properties) of a class for hover information.
     */
    private Hover findMemberHoverInClass(ClassNode classNode, String content,
                                         String word, int targetLine) {
        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().equals(word) && isInRange(method, targetLine)) {
                return buildASTHover(buildMethodHover(method),
                        extractMemberDocumentation(content, classNode, method.getName()));
            }
        }

        for (FieldNode field : classNode.getFields()) {
            if (field.getName().equals(word) && isInRange(field, targetLine)) {
                return buildASTHover(buildFieldHover(field),
                        extractMemberDocumentation(content, classNode, field.getName()));
            }
        }

        for (PropertyNode prop : classNode.getProperties()) {
            if (isPropertyMatch(prop, word, targetLine)) {
                return buildASTHover(buildPropertyHover(prop),
                        extractMemberDocumentation(content, classNode, prop.getName()));
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
                                           String content, String word, int targetLine) {
        // Only check if the cursor is inside this class
        if (classNode.getLineNumber() > 0 && classNode.getLastLineNumber() > 0
                && (targetLine < classNode.getLineNumber() || targetLine > classNode.getLastLineNumber())) {
            return null;
        }

        for (MethodNode method : TraitMemberResolver.collectTraitMethods(classNode, ast, documentManager)) {
            if (method.getName().equals(word)) {
                return buildASTHover(buildMethodHover(method),
                        extractTraitMemberDocumentation(content, method.getDeclaringClass(), method.getName()));
            }
        }

        for (FieldNode field : TraitMemberResolver.collectTraitFields(classNode, ast, documentManager)) {
            if (field.getName().equals(word)
                    || TraitMemberResolver.isTraitFieldMatch(field.getName(), word)) {
                return buildASTHover(buildFieldHover(field),
                        extractTraitMemberDocumentation(content, field.getDeclaringClass(), field.getName()));
            }
        }

        for (PropertyNode prop : TraitMemberResolver.collectTraitProperties(classNode, ast, documentManager)) {
            if (prop.getName().equals(word)) {
                return buildASTHover(buildPropertyHover(prop),
                        extractTraitMemberDocumentation(content, prop.getDeclaringClass(), prop.getName()));
            }
        }

        return null;
    }

    private Hover buildASTHover(String hoverText, String documentation) {
        String combined = appendDocumentation(hoverText, documentation);
        if (combined == null || combined.isEmpty()) {
            return null;
        }
        MarkupContent markup = new MarkupContent();
        markup.setKind(MarkupKind.MARKDOWN);
        markup.setValue(combined);
        return new Hover(markup);
    }

    private String appendDocumentation(String hoverText, String documentation) {
        if (hoverText == null || hoverText.isEmpty()) {
            return null;
        }
        if (documentation == null || documentation.isEmpty()) {
            return hoverText;
        }
        return hoverText + "\n---\n" + documentation;
    }

    private String extractClassDocumentation(String source, ClassNode classNode) {
        return SourceJarHelper.extractJavadoc(source, classNode.getNameWithoutPackage());
    }

    private String extractMemberDocumentation(String source, ClassNode owner, String memberName) {
        if (source == null || owner == null || memberName == null) {
            return null;
        }

        String classScopedSource = extractClassScopedSource(source, owner);
        if (classScopedSource == null || classScopedSource.isEmpty()) {
            return null;
        }
        return SourceJarHelper.extractMemberJavadoc(classScopedSource, memberName);
    }

    private String extractTraitMemberDocumentation(String source, ClassNode declaringClass, String memberName) {
        if (declaringClass == null) {
            return null;
        }
        return extractMemberDocumentation(source, declaringClass, memberName);
    }

    private String extractClassScopedSource(String source, ClassNode owner) {
        if (source == null || owner == null || owner.getLineNumber() <= 0 || owner.getLastLineNumber() <= 0) {
            return source;
        }

        int startOffset = lineToOffset(source, owner.getLineNumber());
        int endOffset = lineToOffset(source, owner.getLastLineNumber() + 1);
        if (startOffset < 0 || endOffset < startOffset) {
            return source;
        }

        return source.substring(startOffset, Math.min(endOffset, source.length()));
    }

    private int lineToOffset(String source, int oneBasedLine) {
        if (source == null || oneBasedLine <= 1) {
            return 0;
        }

        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (line == oneBasedLine) {
                return i;
            }
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return source.length();
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
            String displayName = ParameterNameSupport.displayName(params[i].getName());
            if (displayName != null) {
                sb.append(' ').append(displayName);
            }
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

    private boolean isMemberReferenceAfterDot(String content, int offset) {
        int wordStart = offset;
        while (wordStart > 0 && Character.isJavaIdentifierPart(content.charAt(wordStart - 1))) {
            wordStart--;
        }
        return wordStart > 0 && content.charAt(wordStart - 1) == '.';
    }

    private IType resolveReceiverTypeFromAst(ModuleNode ast, IJavaProject project,
            int offset, String methodName, String content) {
        return resolveReceiverTypeFromAst(
            ast, project, offset, methodName, content, PositionUtils.buildLineIndex(content), null);
    }

    private IType resolveReceiverTypeFromAst(ModuleNode ast,
            IJavaProject project,
            int offset,
            String methodName,
            String content,
            PositionUtils.LineIndex lineIndex) {
        return resolveReceiverTypeFromAst(ast, project, offset, methodName, content, lineIndex, null);
    }

    private IType resolveReceiverTypeFromAst(ModuleNode ast,
            IJavaProject project,
            int offset,
            String methodName,
            String content,
            PositionUtils.LineIndex lineIndex,
            String sourceUri) {
        MethodCallExpression found = findMethodCallAtOffset(ast, offset, methodName, content, lineIndex);
        if (found == null) {
            return null;
        }

        Expression objectExpr = found.getObjectExpression();
        ClassNode receiverClassNode = resolveObjectExpressionType(objectExpr, ast);
        if (receiverClassNode == null || "java.lang.Object".equals(receiverClassNode.getName())) {
            return null;
        }

        return resolveClassNodeToIType(receiverClassNode, ast, project, sourceUri);
    }

    private MethodCallExpression findMethodCallAtOffset(ModuleNode module, int offset,
            String methodName, String content) {
        return findMethodCallAtOffset(
                module, offset, methodName, content, PositionUtils.buildLineIndex(content));
    }

    private MethodCallExpression findMethodCallAtOffset(ModuleNode module,
            int offset,
            String methodName,
            String content,
            PositionUtils.LineIndex lineIndex) {
        Position pos = lineIndex.offsetToPosition(offset);
        int targetLine = pos.getLine() + 1;
        int targetCol = pos.getCharacter() + 1;

        final MethodCallExpression[] result = new MethodCallExpression[1];

        ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (result[0] != null) {
                    return;
                }
                String name = call.getMethodAsString();
                if (methodName.equals(name)) {
                    Expression methodExpr = call.getMethod();
                    int mLine = methodExpr.getLineNumber();
                    int mCol = methodExpr.getColumnNumber();
                    int mLastCol = methodExpr.getLastColumnNumber();
                    if (mLine == targetLine && targetCol >= mCol && targetCol <= mLastCol) {
                        result[0] = call;
                        return;
                    }
                }
                super.visitMethodCallExpression(call);
            }
        };

        for (ClassNode classNode : module.getClasses()) {
            if (result[0] != null) {
                break;
            }
            visitor.visitClass(classNode);
        }

        if (result[0] == null) {
            BlockStatement stmtBlock = module.getStatementBlock();
            if (stmtBlock != null) {
                for (Statement stmt : stmtBlock.getStatements()) {
                    if (result[0] != null) {
                        break;
                    }
                    stmt.visit(visitor);
                }
            }
        }

        return result[0];
    }

    private ClassNode resolveObjectExpressionType(Expression objectExpr, ModuleNode ast) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof VariableExpression varExpr) {
            String varName = varExpr.getName();
            if ("this".equals(varName)) {
                return null;
            }
            for (ClassNode classNode : ast.getClasses()) {
                if (classNode.getLineNumber() < 0) {
                    continue;
                }
                for (MethodNode method : classNode.getMethods()) {
                    ClassNode type = resolveLocalVarTypeInBlock(getBlock(method), varName, ast);
                    if (type != null) {
                        return type;
                    }
                }
            }
            BlockStatement stmtBlock = ast.getStatementBlock();
            if (stmtBlock != null) {
                ClassNode type = resolveLocalVarTypeInBlock(stmtBlock, varName, ast);
                if (type != null) {
                    return type;
                }
            }
            ClassNode exprType = varExpr.getType();
            if (exprType != null && !"java.lang.Object".equals(exprType.getName())) {
                return exprType;
            }
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnType(nestedCall, ast);
        }
        return null;
    }

    private ClassNode resolveMethodCallReturnType(MethodCallExpression methodCall, ModuleNode module) {
        Expression objectExpr = methodCall.getObjectExpression();
        String methodName = methodCall.getMethodAsString();
        if (methodName == null) {
            return null;
        }

        ClassNode receiverClassNode = null;
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            receiverClassNode = ctorCall.getType();
        } else if (objectExpr instanceof MethodCallExpression nestedCall) {
            receiverClassNode = resolveMethodCallReturnType(nestedCall, module);
        } else if (objectExpr instanceof VariableExpression varExpr) {
            String receiverVarName = varExpr.getName();
            if (!"this".equals(receiverVarName)) {
                receiverClassNode = resolveLocalVarTypeInBlock(module.getStatementBlock(), receiverVarName, module);
                if (receiverClassNode == null) {
                    receiverClassNode = varExpr.getType();
                }
            }
        }

        if (receiverClassNode == null || "java.lang.Object".equals(receiverClassNode.getName())) {
            return null;
        }

        for (MethodNode method : receiverClassNode.getMethods()) {
            if (methodName.equals(method.getName())) {
                ClassNode returnType = method.getReturnType();
                if (returnType != null && !"java.lang.Object".equals(returnType.getName())) {
                    return returnType;
                }
            }
        }

        return null;
    }

    private BlockStatement getBlock(MethodNode method) {
        Statement code = method.getCode();
        return code instanceof BlockStatement block ? block : null;
    }

    private ClassNode resolveLocalVarTypeInBlock(BlockStatement block, String varName, ModuleNode module) {
        if (block == null) {
            return null;
        }
        for (Statement stmt : block.getStatements()) {
            if (!(stmt instanceof ExpressionStatement exprStmt)) {
                continue;
            }
            if (!(exprStmt.getExpression() instanceof org.codehaus.groovy.ast.expr.DeclarationExpression decl)) {
                continue;
            }
            Expression left = decl.getLeftExpression();
            if (!(left instanceof VariableExpression varExpr) || !varName.equals(varExpr.getName())) {
                continue;
            }

            Expression init = decl.getRightExpression();
            if (init instanceof ConstructorCallExpression ctorCall) {
                return ctorCall.getType();
            }
            if (init instanceof MethodCallExpression methodCall) {
                return resolveMethodCallReturnType(methodCall, module);
            }

            ClassNode originType = varExpr.getOriginType();
            if (originType != null && !"java.lang.Object".equals(originType.getName())) {
                return originType;
            }

            ClassNode initType = init.getType();
            if (initType != null && !"java.lang.Object".equals(initType.getName())) {
                return initType;
            }
        }
        return null;
    }

    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module, IJavaProject project) {
        return resolveClassNodeToIType(typeNode, module, project, null);
    }

    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module, IJavaProject project,
            String sourceUri) {
        if (typeNode == null || project == null) {
            return null;
        }
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            if (typeName.contains(".")) {
                IType type = ScopedTypeLookupSupport.findType(project, typeName, sourceUri);
                if (type != null) {
                    return type;
                }
            }

            for (ImportNode imp : module.getImports()) {
                ClassNode impType = imp.getType();
                if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                    IType type = ScopedTypeLookupSupport.findType(project, impType.getName(), sourceUri);
                    if (type != null) {
                        return type;
                    }
                }
            }

            for (ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType type = ScopedTypeLookupSupport.findType(project, pkgName + typeName, sourceUri);
                    if (type != null) {
                        return type;
                    }
                }
            }

            String pkg = module.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                if (pkg.endsWith(".")) {
                    pkg = pkg.substring(0, pkg.length() - 1);
                }
                IType type = ScopedTypeLookupSupport.findType(project, pkg + "." + typeName, sourceUri);
                if (type != null) {
                    return type;
                }
            }

            String[] autoPackages = {"java.lang.", "java.util.", "java.io.",
                    "groovy.lang.", "groovy.util.", "java.math."};
            for (String autoPkg : autoPackages) {
                IType type = ScopedTypeLookupSupport.findType(project, autoPkg + typeName, sourceUri);
                if (type != null) {
                    return type;
                }
            }
        } catch (JavaModelException e) {
            return null;
        }
        return null;
    }

    private Position offsetToPosition(String content, int offset) {
        return PositionUtils.offsetToPosition(content, offset);
    }
}
