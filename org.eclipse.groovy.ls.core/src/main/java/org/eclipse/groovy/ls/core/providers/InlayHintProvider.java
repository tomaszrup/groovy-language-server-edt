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
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Provides LSP inlay hints for Groovy documents.
 */
public class InlayHintProvider {

    private static final int HIERARCHY_CACHE_MAX = 100;

    private record ReturnTypeInfo(String displayName, IType resolvedType) {
    }

    private final DocumentManager documentManager;
    private final java.util.concurrent.atomic.AtomicReference<InlayHintSettings> currentSettings =
            new java.util.concurrent.atomic.AtomicReference<>(InlayHintSettings.defaults());

    /**
     * Instance-level LRU cache for JDT type hierarchies, shared across inlay
     * hint requests.  Building a supertype hierarchy is expensive (especially
     * with deep inheritance chains); caching them avoids redundant work when
     * the user scrolls or edits within the same file.
     * <p>
     * Invalidated per-URI on {@code didChange} via {@link #invalidateCache}.
     */
    @SuppressWarnings("serial")
    private final Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache =
            Collections.synchronizedMap(new java.util.LinkedHashMap<String, org.eclipse.jdt.core.ITypeHierarchy>(
                    HIERARCHY_CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, org.eclipse.jdt.core.ITypeHierarchy> eldest) {
                    return size() > HIERARCHY_CACHE_MAX;
                }
            });

    public InlayHintProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Invalidate cached state for a URI (call on didChange / didClose).
     */
    public void invalidateCache(String uri) {
        // The hierarchy cache is keyed by FQN, not URI, so a targeted
        // per-URI eviction isn't practical.  A full clear is cheap and
        // correct — hierarchies are rebuilt on the next request.
        hierarchyCache.clear();
    }

    /**
     * Update the inlay hint settings.
     */
    public void updateSettings(InlayHintSettings settings) {
        if (settings != null) {
            this.currentSettings.set(settings);
        }
    }

    /**
     * Update settings from a generic Object (avoids coupling callers to InlayHintSettings).
     */
    public void updateSettingsFromObject(Object settings) {
        if (settings instanceof InlayHintSettings s) {
            updateSettings(s);
        }
    }

    /**
     * Compute inlay hints for the given document range using the current settings.
     */
    public List<InlayHint> getInlayHints(InlayHintParams params) {
        return getInlayHints(params, currentSettings.get());
    }

    /**
     * Compute inlay hints for the given document range.
     */
    public List<InlayHint> getInlayHints(InlayHintParams params, InlayHintSettings settings) {
        String uri = params.getTextDocument().getUri();
        String content = documentManager.getContent(uri);
        if (content == null) {
            return Collections.emptyList();
        }

        // Use cached AST only — never trigger on-demand compilation.
        // Inlay hints are decorative; returning empty when no AST is
        // cached yet is acceptable and avoids burning the 15 s timeout
        // budget on a full Groovy compilation that may never finish in
        // large workspaces.
        ModuleNode module = documentManager.getCachedGroovyAST(uri);
        if (module == null) {
            return Collections.emptyList();
        }

        Range requestedRange = params.getRange();

        try {
            InlayHintSettings effectiveSettings = settings != null ? settings : InlayHintSettings.defaults();
            List<InlayHint> mergedHints = new ArrayList<>();

            InlayHintSettings nonParameterSettings = new InlayHintSettings(
                    effectiveSettings.isVariableTypesEnabled(),
                    false,
                    effectiveSettings.isClosureParameterTypesEnabled(),
                    effectiveSettings.isMethodReturnTypesEnabled());
            InlayHintVisitor nonParameterVisitor = new InlayHintVisitor(content, requestedRange, nonParameterSettings);
            nonParameterVisitor.visitModule(module);
            mergedHints.addAll(nonParameterVisitor.getHints());

            // JDT-aware variable type hints for method call chains
            // (e.g., def value = new JavaClass().method() — the AST alone returns Object)
            if (effectiveSettings.isVariableTypesEnabled()) {
                ICompilationUnit varWorkingCopy = documentManager.getWorkingCopy(uri);
                if (varWorkingCopy != null) {
                    List<InlayHint> jdtVarHints = computeJdtVariableTypeHints(
                            module, content, requestedRange, varWorkingCopy);
                    mergedHints.addAll(jdtVarHints);
                }
            }

            if (effectiveSettings.isParameterNamesEnabled()) {
                ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
                List<InlayHint> parameterHints = new ArrayList<>();

                if (workingCopy != null) {
                    ParameterHintCollector jdtCollector =
                            new ParameterHintCollector(content, requestedRange, workingCopy,
                                    documentManager);
                    jdtCollector.visitModule(module);
                    parameterHints.addAll(jdtCollector.getHints());
                }

                InlayHintVisitor fallbackParameterVisitor = new InlayHintVisitor(
                        content,
                        requestedRange,
                        new InlayHintSettings(false, true, false, false));
                fallbackParameterVisitor.visitModule(module);
                parameterHints.addAll(fallbackParameterVisitor.getHints());

                mergedHints.addAll(parameterHints);
            }

            return dedupeAndSort(mergedHints);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Inlay hints computation failed for " + uri, e);
            return Collections.emptyList();
        }
    }

    private List<InlayHint> dedupeAndSort(List<InlayHint> hints) {
        List<InlayHint> sorted = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (InlayHint hint : hints) {
            if (hint == null || hint.getPosition() == null || hint.getLabel() == null) {
                continue;
            }
            String label = hint.getLabel().isLeft()
                    ? hint.getLabel().getLeft()
                    : String.valueOf(hint.getLabel().getRight());
            String key = hint.getPosition().getLine()
                    + ":"
                    + hint.getPosition().getCharacter()
                    + ":"
                    + label;
            if (seen.add(key)) {
                sorted.add(hint);
            }
        }

        sorted.sort(Comparator
                .comparing((InlayHint hint) -> hint.getPosition().getLine())
                .thenComparing(hint -> hint.getPosition().getCharacter()));

        return sorted;
    }

    // =========================================================================
    // JDT-aware variable type hints for method call chains
    // =========================================================================

    /**
     * Compute variable type inlay hints for declarations whose right-hand side is a method
     * call chain (e.g., {@code def value = new JavaClass().method()}). The AST visitor can't
     * resolve these because the Groovy compiler at CONVERSION phase doesn't know the method's
     * return type. We use JDT to look it up.
     */
    private List<InlayHint> computeJdtVariableTypeHints(ModuleNode module, String content,
                                                         Range requestedRange,
                                                         ICompilationUnit workingCopy) {
        List<InlayHint> hints = new ArrayList<>();
        try {
            IJavaProject project = workingCopy.getJavaProject();
            if (project == null || !project.exists()) return hints;

            // Use the shared instance-level hierarchy cache (LRU, max 100
            // entries) so that hierarchies computed for one request survive
            // across subsequent inlay-hint refreshes.

            MethodCallDeclCollector collector = new MethodCallDeclCollector();
            for (ClassNode classNode : module.getClasses()) {
                collector.visitClass(classNode);
            }
            BlockStatement stmtBlock = module.getStatementBlock();
            if (stmtBlock != null) {
                stmtBlock.visit(collector);
            }

            for (MethodCallDeclCollector.DeclInfo info : collector.getDeclarations()) {
                if (Thread.currentThread().isInterrupted()) break;
                if (requestedRange != null && !isInRange(info.line, requestedRange)) {
                    continue;
                }
                ReturnTypeInfo returnType = resolveMethodCallChainInfo(info.methodCall, module, project, hierarchyCache);
                if (returnType != null && returnType.displayName() != null && !returnType.displayName().isBlank()) {
                    String typeName = returnType.displayName();
                    Position hintPos = new Position(info.line, info.column + info.varName.length());
                    InlayHint hint = new InlayHint(hintPos, Either.forLeft(": " + typeName));
                    hint.setKind(InlayHintKind.Type);
                    hint.setPaddingLeft(false);
                    hint.setPaddingRight(true);
                    hints.add(hint);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("JDT variable type hint computation failed", e);
        }
        return hints;
    }

    private boolean isInRange(int zeroBasedLine, Range range) {
        return zeroBasedLine >= range.getStart().getLine()
                && zeroBasedLine <= range.getEnd().getLine();
    }

    /**
     * Resolve a method call expression chain to a JDT IType.
     */
    private IType resolveMethodCallChainType(MethodCallExpression methodCall,
                                              ModuleNode module, IJavaProject project,
                                              Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache) {
        ReturnTypeInfo returnType = resolveMethodCallChainInfo(methodCall, module, project, hierarchyCache);
        return returnType != null ? returnType.resolvedType() : null;
    }

    private ReturnTypeInfo resolveMethodCallChainInfo(MethodCallExpression methodCall,
            ModuleNode module, IJavaProject project,
            Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache) {
        try {
            Expression objectExpr = methodCall.getObjectExpression();
            String methodName = methodCall.getMethodAsString();
            if (methodName == null) return null;

            IType receiverType = null;

            if (objectExpr instanceof ConstructorCallExpression ctorCall) {
                ClassNode ctorType = ctorCall.getType();
                receiverType = resolveClassNodeToType(ctorType, module, project);
            } else if (objectExpr instanceof MethodCallExpression nestedCall) {
                ReturnTypeInfo nestedReturnType = resolveMethodCallChainInfo(nestedCall, module, project, hierarchyCache);
                receiverType = nestedReturnType != null ? nestedReturnType.resolvedType() : null;
            } else if (objectExpr instanceof VariableExpression varExpr) {
                String receiverVarName = varExpr.getName();
                if (!"this".equals(receiverVarName)) {
                    // Try to resolve the variable's initializer type
                    ClassNode varType = resolveLocalVarType(module, receiverVarName);
                    if (varType != null && !"java.lang.Object".equals(varType.getName())) {
                        receiverType = resolveClassNodeToType(varType, module, project);
                    }
                }
            }

            if (receiverType == null) return null;

            // Find the method return type via JDT, with per-request hierarchy cache
            return findMethodReturnInfo(receiverType, methodName, project, hierarchyCache);
        } catch (Exception e) {
            return null;
        }
    }

    private IType findMethodReturnType(IType receiverType, String methodName, IJavaProject project)
            throws JavaModelException {
        ReturnTypeInfo returnType = findMethodReturnInfo(receiverType, methodName, project, null);
        return returnType != null ? returnType.resolvedType() : null;
    }

    /**
     * Find the return type of a method on the given receiver type, searching
     * supertypes if needed.  An optional per-request hierarchy cache avoids
     * re-computing the same expensive type hierarchy for the same receiver
     * multiple times within a single inlay-hints request.
     */
    private IType findMethodReturnType(IType receiverType, String methodName,
            IJavaProject project,
            Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache)
            throws JavaModelException {
        ReturnTypeInfo returnType = findMethodReturnInfo(receiverType, methodName, project, hierarchyCache);
        return returnType != null ? returnType.resolvedType() : null;
    }

    private ReturnTypeInfo findMethodReturnInfo(IType receiverType, String methodName,
            IJavaProject project,
            Map<String, org.eclipse.jdt.core.ITypeHierarchy> hierarchyCache)
            throws JavaModelException {
        IType memberSource = JavaBinaryMemberResolver.resolveMemberSource(receiverType);
        if (memberSource == null) {
            return null;
        }

        // Search in the type itself first
        ReturnTypeInfo returnType = findMethodReturnInfo(memberSource.getMethods(), methodName, memberSource, project);
        if (returnType != null) {
            return returnType;
        }

        // Check supertypes — cache the hierarchy per receiver FQN
        String cacheKey = memberSource.getFullyQualifiedName();
        org.eclipse.jdt.core.ITypeHierarchy hierarchy = null;
        if (hierarchyCache != null) {
            hierarchy = hierarchyCache.get(cacheKey);
        }
        if (hierarchy == null) {
            hierarchy = memberSource.newSupertypeHierarchy(null);
            if (hierarchyCache != null && hierarchy != null) {
                hierarchyCache.put(cacheKey, hierarchy);
            }
        }

        if (hierarchy != null) {
            for (IType superType : hierarchy.getAllSupertypes(memberSource)) {
                returnType = findMethodReturnInfo(superType.getMethods(), methodName, superType, project);
                if (returnType != null) {
                    return returnType;
                }
            }
        }

        return findRecordComponentReturnInfo(receiverType, methodName, project);
    }

    private ReturnTypeInfo findMethodReturnInfo(IMethod[] methods, String methodName,
            IType context, IJavaProject project) throws JavaModelException {
        for (IMethod method : methods) {
            if (methodName.equals(method.getElementName())) {
                ReturnTypeInfo returnType = createReturnTypeInfoFromSignature(method.getReturnType(), context, project);
                if (returnType != null) {
                    return returnType;
                }
            }
        }
        return null;
    }

    private ReturnTypeInfo findRecordComponentReturnInfo(IType receiverType, String methodName,
            IJavaProject project) throws JavaModelException {
        for (JavaRecordSourceSupport.RecordComponentInfo component : JavaRecordSourceSupport.getRecordComponents(receiverType)) {
            if (methodName.equals(component.name())) {
                return createReturnTypeInfo(component.type(), receiverType, project);
            }
        }
        return null;
    }

    private ReturnTypeInfo createReturnTypeInfoFromSignature(String returnSig, IType context,
            IJavaProject project) throws JavaModelException {
        if (returnSig == null || returnSig.isBlank()) {
            return null;
        }
        return createReturnTypeInfo(org.eclipse.jdt.core.Signature.toString(returnSig), context, project);
    }

    private ReturnTypeInfo createReturnTypeInfo(String displayName, IType context,
            IJavaProject project) throws JavaModelException {
        String resolvedTypeName = normalizeDisplayTypeName(displayName);
        if (resolvedTypeName == null) {
            return null;
        }
        IType resolvedType = resolveTypeByName(resolvedTypeName, context, project);
        return new ReturnTypeInfo(simplifyDisplayTypeName(resolvedTypeName), resolvedType);
    }

    private String normalizeDisplayTypeName(String typeName) {
        if (typeName == null) {
            return null;
        }
        String normalized = typeName.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String simplifyDisplayTypeName(String typeName) {
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

    private IType resolveTypeByName(String typeName, IType context, IJavaProject project)
            throws JavaModelException {
        String lookupTypeName = normalizeLookupTypeName(typeName);
        if (lookupTypeName == null) {
            return null;
        }

        IType type = project.findType(typeName);
        if (type != null) return type;

        if (!lookupTypeName.equals(typeName)) {
            type = project.findType(lookupTypeName);
            if (type != null) return type;
        }

        if (context != null) {
            String[][] resolved = context.resolveType(typeName);
            if ((resolved == null || resolved.length == 0) && !lookupTypeName.equals(typeName)) {
                resolved = context.resolveType(lookupTypeName);
            }
            if (resolved != null && resolved.length > 0) {
                String fqn = resolved[0][0].isEmpty()
                        ? resolved[0][1]
                        : resolved[0][0] + "." + resolved[0][1];
                type = project.findType(fqn);
                if (type != null) return type;
            }
        }

        // Try java.lang
        type = project.findType("java.lang." + lookupTypeName);
        return type;
    }

    private String normalizeLookupTypeName(String typeName) {
        if (typeName == null) {
            return null;
        }

        String normalized = typeName.trim();
        if (normalized.isEmpty() || "?".equals(normalized)) {
            return null;
        }

        if (normalized.startsWith("? extends ")) {
            normalized = normalized.substring("? extends ".length()).trim();
        } else if (normalized.startsWith("? super ")) {
            normalized = normalized.substring("? super ".length()).trim();
        }

        int angleBracket = normalized.indexOf('<');
        if (angleBracket >= 0) {
            normalized = normalized.substring(0, angleBracket);
        }

        int bracket = normalized.indexOf('[');
        if (bracket >= 0) {
            normalized = normalized.substring(0, bracket);
        }

        normalized = normalized.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private IType resolveClassNodeToType(ClassNode typeNode, ModuleNode module, IJavaProject project)
            throws JavaModelException {
        if (typeNode == null || project == null) return null;
        String typeName = typeNode.getName();
        if (typeName == null || typeName.isEmpty()) return null;

        if (typeName.contains(".")) {
            IType type = project.findType(typeName);
            if (type != null) return type;
        }

        // Check imports
        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                IType type = project.findType(impType.getName());
                if (type != null) return type;
            }
        }

        // Star imports
        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType type = project.findType(pkgName + typeName);
                if (type != null) return type;
            }
        }

        // Module package
        String pkg = module.getPackageName();
        if (pkg != null && !pkg.isEmpty()) {
            if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
            IType type = project.findType(pkg + "." + typeName);
            if (type != null) return type;
        }

        // Auto-import packages
        String[] autoPackages = {"java.lang.", "java.util.", "java.io.", "groovy.lang.", "groovy.util.", "java.math."};
        for (String autoPkg : autoPackages) {
            IType type = project.findType(autoPkg + typeName);
            if (type != null) return type;
        }

        return null;
    }

    /**
     * Resolve a local variable's type from the AST by finding its declaration.
     */
    private ClassNode resolveLocalVarType(ModuleNode module, String varName) {
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            for (MethodNode method : classNode.getMethods()) {
                ClassNode type = resolveVarInBlock(getBlock(method), varName);
                if (type != null) return type;
            }
        }
        BlockStatement stmtBlock = module.getStatementBlock();
        if (stmtBlock != null) {
            ClassNode type = resolveVarInBlock(stmtBlock, varName);
            if (type != null) return type;
        }
        return null;
    }

    private BlockStatement getBlock(MethodNode method) {
        org.codehaus.groovy.ast.stmt.Statement code = method.getCode();
        return (code instanceof BlockStatement block) ? block : null;
    }

    private ClassNode resolveVarInBlock(BlockStatement block, String varName) {
        if (block == null) return null;
        for (org.codehaus.groovy.ast.stmt.Statement stmt : block.getStatements()) {
            if (!(stmt instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement exprStmt)) continue;
            if (!(exprStmt.getExpression() instanceof DeclarationExpression decl)) continue;
            Expression left = decl.getLeftExpression();
            if (!(left instanceof VariableExpression varExpr)) continue;
            if (!varName.equals(varExpr.getName())) continue;

            Expression init = decl.getRightExpression();
            if (init instanceof ConstructorCallExpression ctorCall) {
                return ctorCall.getType();
            }
            ClassNode originType = varExpr.getOriginType();
            if (originType != null && !"java.lang.Object".equals(originType.getName())) {
                return originType;
            }
        }
        return null;
    }

    /**
     * Collects DeclarationExpressions where the RHS is a MethodCallExpression
     * and the variable is declared with 'def' style (untyped).
     */
    private static final class MethodCallDeclCollector extends ClassCodeVisitorSupport {
        static final class DeclInfo {
            final String varName;
            final int line;
            final int column;
            final MethodCallExpression methodCall;

            DeclInfo(String varName, int line, int column, MethodCallExpression methodCall) {
                this.varName = varName;
                this.line = line;
                this.column = column;
                this.methodCall = methodCall;
            }
        }

        private final List<DeclInfo> declarations = new ArrayList<>();

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        List<DeclInfo> getDeclarations() {
            return declarations;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expr) {
            if (expr == null || expr.getLineNumber() < 1) {
                super.visitDeclarationExpression(expr);
                return;
            }
            Expression left = expr.getLeftExpression();
            if (!(left instanceof VariableExpression variable)) {
                super.visitDeclarationExpression(expr);
                return;
            }
            String variableName = variable.getName();
            if (variableName == null || variableName.isEmpty()) {
                super.visitDeclarationExpression(expr);
                return;
            }

            // Check if it's a def-style declaration (no explicit type)
            if (!isDynamic(variable)) {
                super.visitDeclarationExpression(expr);
                return;
            }

            Expression rightExpr = expr.getRightExpression();
            if (rightExpr instanceof MethodCallExpression methodCall) {
                // Only collect when the AST type is unhelpful
                ClassNode inferredType = rightExpr.getType();
                if (inferredType == null
                        || "java.lang.Object".equals(inferredType.getName())
                        || "void".equals(inferredType.getName())) {
                    int line = Math.max(0, variable.getLineNumber() - 1);
                    int col = Math.max(0, variable.getColumnNumber() - 1);
                    declarations.add(new DeclInfo(variableName, line, col, methodCall));
                }
            }
            super.visitDeclarationExpression(expr);
        }

        private boolean isDynamic(VariableExpression var) {
            return var.isDynamicTyped()
                    || "java.lang.Object".equals(var.getOriginType().getName());
        }
    }

    private static final class ParameterHintCollector extends ClassCodeVisitorSupport {

        private final String source;
        private final Range requestedRange;
        private final ICompilationUnit workingCopy;
        private final DocumentManager documentManager;
        private final List<InlayHint> hints = new ArrayList<>();
        private final Set<String> emitted = new HashSet<>();
        private SourceUnit sourceUnit;

        ParameterHintCollector(String source, Range requestedRange, ICompilationUnit workingCopy,
                               DocumentManager documentManager) {
            this.source = source;
            this.requestedRange = requestedRange;
            this.workingCopy = workingCopy;
            this.documentManager = documentManager;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        List<InlayHint> getHints() {
            return hints;
        }

        void visitModule(ModuleNode module) {
            this.sourceUnit = module.getContext();

            for (ClassNode classNode : module.getClasses()) {
                if (Thread.currentThread().isInterrupted()) return;
                visitModuleClass(classNode);
            }

            BlockStatement statementBlock = module.getStatementBlock();
            if (statementBlock != null && !Thread.currentThread().isInterrupted()) {
                statementBlock.visit(this);
            }
        }

        private void visitModuleClass(ClassNode classNode) {
            if (isSyntheticScriptClass(classNode)) {
                visitSyntheticScriptMembers(classNode);
                return;
            }
            visitClass(classNode);
        }

        private boolean isSyntheticScriptClass(ClassNode classNode) {
            return classNode.isScript() && classNode.getLineNumber() < 1;
        }

        private void visitSyntheticScriptMembers(ClassNode classNode) {
            MethodNode runMethod = classNode.getMethod("run", Parameter.EMPTY_ARRAY);
            if (runMethod != null && runMethod.getCode() != null) {
                runMethod.getCode().visit(this);
            }

            for (MethodNode method : classNode.getMethods()) {
                if (isUserDefinedScriptMethod(method)) {
                    visitMethod(method);
                }
            }
        }

        private boolean isUserDefinedScriptMethod(MethodNode method) {
            return !"run".equals(method.getName())
                    && !"main".equals(method.getName())
                    && method.getLineNumber() > 0;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            if (Thread.currentThread().isInterrupted()) return;
            addMethodCallHints(call);
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (Thread.currentThread().isInterrupted()) return;
            addConstructorCallHints(call);
            super.visitConstructorCallExpression(call);
        }

        private void addMethodCallHints(MethodCallExpression call) {
            if (call == null || call.getLineNumber() < 1) {
                return;
            }

            String methodName = call.getMethodAsString();
            if (methodName == null || methodName.isBlank()) {
                return;
            }

            List<Expression> arguments = toArgumentExpressions(call.getArguments());
            if (arguments.isEmpty()) {
                return;
            }

            int methodOffset = resolveMethodNameOffset(call, methodName);
            if (methodOffset < 0) {
                return;
            }

            IMethod targetMethod = resolveBestMethodTarget(methodOffset, methodName, arguments.size());
            if (targetMethod == null) {
                return;
            }

            String[] parameterNames = readParameterNames(targetMethod);
            addArgumentHints(arguments, parameterNames);
        }

        private void addConstructorCallHints(ConstructorCallExpression call) {
            if (call == null || call.getLineNumber() < 1 || call.getType() == null) {
                return;
            }

            List<Expression> arguments = toArgumentExpressions(call.getArguments());
            if (arguments.isEmpty()) {
                return;
            }

            String typeName = call.getType().getNameWithoutPackage();
            if (typeName == null || typeName.isBlank()) {
                typeName = call.getType().getName();
            }
            if (typeName == null || typeName.isBlank()) {
                return;
            }

            int typeOffset = resolveConstructorTypeOffset(call, typeName);
            if (typeOffset < 0) {
                return;
            }

            IMethod constructor = resolveBestConstructorTarget(typeOffset, arguments.size());
            if (constructor == null) {
                return;
            }

            String[] parameterNames = readParameterNames(constructor);
            addArgumentHints(arguments, parameterNames);
        }

        private void addArgumentHints(List<Expression> arguments, String[] parameterNames) {
            int max = Math.min(arguments.size(), parameterNames.length);
            for (int index = 0; index < max; index++) {
                Expression argument = arguments.get(index);
                String parameterName = parameterNames[index];
                if (isHintableArgument(argument, parameterName) && parameterName != null && !parameterName.isBlank()) {
                    Position position = new Position(
                            Math.max(0, argument.getLineNumber() - 1),
                            Math.max(0, argument.getColumnNumber() - 1));
                    addParameterHint(position, parameterName + ":");
                }
            }
        }

        private boolean isHintableArgument(Expression argument, String parameterName) {
            return argument != null
                    && argument.getLineNumber() >= 1
                    && !isNamedArgument(argument)
                    && !isArgumentNameMatchingParameter(argument, parameterName);
        }

        private boolean isArgumentNameMatchingParameter(Expression arg, String parameterName) {
            if (arg instanceof VariableExpression variable) {
                String argName = variable.getName();
                return argName != null && argName.equals(parameterName);
            }
            return false;
        }

        private IMethod resolveBestMethodTarget(int offset, String methodName, int argumentCount) {
            try {
                IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
                List<IMethod> methods = new ArrayList<>();
                if (elements != null) {
                    for (IJavaElement element : elements) {
                        if (element instanceof IMethod method
                                && !method.isConstructor()
                                && methodName.equals(method.getElementName())) {
                            methods.add(method);
                        }
                    }
                }
                return chooseBestMethod(methods, argumentCount);
            } catch (Exception ignored) {
                return null;
            }
        }

        private IMethod resolveBestConstructorTarget(int offset, int argumentCount) {
            try {
                IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
                List<IMethod> constructors = collectConstructorCandidates(elements);
                return chooseBestMethod(constructors, argumentCount);
            } catch (Exception ignored) {
                return null;
            }
        }

        private List<IMethod> collectConstructorCandidates(IJavaElement[] elements)
                throws JavaModelException {
            List<IMethod> constructors = new ArrayList<>();
            if (elements == null) {
                return constructors;
            }

            for (IJavaElement element : elements) {
                addConstructorCandidates(element, constructors);
            }
            return constructors;
        }

        private void addConstructorCandidates(IJavaElement element, List<IMethod> constructors)
                throws JavaModelException {
            if (element instanceof IMethod method) {
                if (method.isConstructor()) {
                    constructors.add(remapConstructorToResolved(method));
                }
                return;
            }

            if (element instanceof IType type) {
                IType resolvedType = JavaBinaryMemberResolver.resolveMemberSource(type);
                if (resolvedType == null) {
                    resolvedType = type;
                }
                for (IMethod method : resolvedType.getMethods()) {
                    if (method.isConstructor()) {
                        constructors.add(method);
                    }
                }
            }
        }

        private IMethod remapConstructorToResolved(IMethod sourceMethod) throws JavaModelException {
            if (sourceMethod == null || !sourceMethod.isConstructor()) {
                return sourceMethod;
            }

            IType declaringType = sourceMethod.getDeclaringType();
            if (declaringType == null) {
                return sourceMethod;
            }

            IType resolvedType = JavaBinaryMemberResolver.resolveMemberSource(declaringType);
            if (resolvedType == null || resolvedType == declaringType) {
                return sourceMethod;
            }

            String[] sourceParameterTypes = sourceMethod.getParameterTypes();
            for (IMethod candidate : resolvedType.getMethods()) {
                if (candidate.isConstructor() && hasMatchingParameterTypes(sourceParameterTypes, candidate)) {
                    return candidate;
                }
            }

            return sourceMethod;
        }

        private boolean hasMatchingParameterTypes(String[] sourceParameterTypes, IMethod candidate) {
            try {
                String[] candidateParameterTypes = candidate.getParameterTypes();
                if (sourceParameterTypes == null
                        || candidateParameterTypes == null
                        || sourceParameterTypes.length != candidateParameterTypes.length) {
                    return false;
                }

                for (int index = 0; index < sourceParameterTypes.length; index++) {
                    if (!sourceParameterTypes[index].equals(candidateParameterTypes[index])) {
                        return false;
                    }
                }

                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        private IMethod chooseBestMethod(List<IMethod> methods, int argumentCount) {
            if (methods == null || methods.isEmpty()) {
                return null;
            }

            IMethod best = null;
            int bestScore = Integer.MAX_VALUE;

            for (IMethod method : methods) {
                int paramCount = getParameterCount(method);
                boolean varargs = isVarargs(method);

                int score = compatibilityScore(paramCount, varargs, argumentCount);
                if (score < bestScore) {
                    bestScore = score;
                    best = method;
                }
            }

            return best != null ? best : methods.get(0);
        }

        private int compatibilityScore(int parameterCount, boolean varargs, int argumentCount) {
            if (varargs) {
                int required = Math.max(0, parameterCount - 1);
                if (argumentCount < required) {
                    return 10_000 + (required - argumentCount);
                }
                return argumentCount - required;
            }

            if (argumentCount == parameterCount) {
                return 0;
            }
            if (argumentCount < parameterCount) {
                return parameterCount - argumentCount;
            }
            return 10_000 + (argumentCount - parameterCount);
        }

        private int getParameterCount(IMethod method) {
            try {
                return method.getParameterTypes().length;
            } catch (Exception ignored) {
                return Integer.MAX_VALUE / 2;
            }
        }

        private boolean isVarargs(IMethod method) {
            try {
                return Flags.isVarargs(method.getFlags());
            } catch (JavaModelException e) {
                return false;
            }
        }

        private String[] readParameterNames(IMethod method) {
            return JdtParameterNameResolver.resolve(method);
        }

        private int resolveMethodNameOffset(MethodCallExpression call, String methodName) {
            Expression methodExpression = call.getMethod();
            int line = methodExpression != null && methodExpression.getLineNumber() > 0
                    ? methodExpression.getLineNumber() - 1
                    : call.getLineNumber() - 1;
            int startCol = methodExpression != null && methodExpression.getColumnNumber() > 0
                    ? methodExpression.getColumnNumber() - 1
                    : Math.max(0, call.getColumnNumber() - 1);

            int nameColumn = findNameInLine(line, startCol, methodName);
            if (nameColumn < 0) {
                return -1;
            }
            return lineToOffset(line, nameColumn);
        }

        private int resolveConstructorTypeOffset(ConstructorCallExpression call, String typeName) {
            ClassNode type = call.getType();
            int line = type != null && type.getLineNumber() > 0
                    ? type.getLineNumber() - 1
                    : call.getLineNumber() - 1;
            int startCol = type != null && type.getColumnNumber() > 0
                    ? type.getColumnNumber() - 1
                    : Math.max(0, call.getColumnNumber() - 1);

            int nameColumn = findNameInLine(line, startCol, typeName);
            if (nameColumn < 0) {
                return -1;
            }
            return lineToOffset(line, nameColumn);
        }

        private List<Expression> toArgumentExpressions(Expression argsExpression) {
            if (argsExpression instanceof ArgumentListExpression argumentListExpression) {
                return argumentListExpression.getExpressions();
            }
            if (argsExpression instanceof TupleExpression tupleExpression) {
                return tupleExpression.getExpressions();
            }
            return Collections.emptyList();
        }

        private boolean isNamedArgument(Expression expression) {
            return expression instanceof NamedArgumentListExpression || expression instanceof MapExpression;
        }

        private void addParameterHint(Position position, String label) {
            if (position == null || label == null || label.isBlank() || !isInRequestedRange(position)) {
                return;
            }

            String key = position.getLine() + ":" + position.getCharacter() + ":" + label;
            if (!emitted.add(key)) {
                return;
            }

            InlayHint hint = new InlayHint();
            hint.setPosition(position);
            hint.setKind(InlayHintKind.Parameter);
            hint.setLabel(Either.forLeft(label));
            hint.setPaddingRight(true);
            hints.add(hint);
        }

        private boolean isInRequestedRange(Position position) {
            if (requestedRange == null) {
                return true;
            }
            return comparePosition(position, requestedRange.getStart()) >= 0
                    && comparePosition(position, requestedRange.getEnd()) <= 0;
        }

        private int comparePosition(Position left, Position right) {
            if (left.getLine() != right.getLine()) {
                return Integer.compare(left.getLine(), right.getLine());
            }
            return Integer.compare(left.getCharacter(), right.getCharacter());
        }

        private int lineToOffset(int targetLine, int targetColumn) {
            if (targetLine < 0 || targetColumn < 0) {
                return -1;
            }

            int line = 0;
            int index = 0;
            while (index < source.length() && line < targetLine) {
                if (source.charAt(index) == '\n') {
                    line++;
                }
                index++;
            }

            if (line != targetLine) {
                return -1;
            }

            return Math.min(index + targetColumn, source.length());
        }

        private int findNameInLine(int line, int fromColumn, String name) {
            String lineText = getLineText(line);
            if (lineText == null || name == null || name.isBlank()) {
                return -1;
            }

            int safeStart = Math.max(0, Math.min(fromColumn, lineText.length()));
            int index = lineText.indexOf(name, safeStart);
            if (index >= 0 && hasIdentifierBoundaries(lineText, index, index + name.length())) {
                return index;
            }

            index = lineText.indexOf(name);
            if (index >= 0 && hasIdentifierBoundaries(lineText, index, index + name.length())) {
                return index;
            }

            return -1;
        }

        private boolean hasIdentifierBoundaries(String lineText, int start, int end) {
            boolean leftBoundary = start <= 0 || !Character.isJavaIdentifierPart(lineText.charAt(start - 1));
            boolean rightBoundary = end >= lineText.length() || !Character.isJavaIdentifierPart(lineText.charAt(end));
            return leftBoundary && rightBoundary;
        }

        private String getLineText(int targetLine) {
            if (targetLine < 0 || source == null || source.isEmpty()) {
                return null;
            }

            int line = 0;
            int start = 0;
            for (int index = 0; index < source.length(); index++) {
                if (line == targetLine) {
                    break;
                }
                if (source.charAt(index) == '\n') {
                    line++;
                    start = index + 1;
                }
            }

            if (line != targetLine) {
                return null;
            }

            int end = source.indexOf('\n', start);
            if (end < 0) {
                end = source.length();
            }
            return source.substring(start, end);
        }
    }
}