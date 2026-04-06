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

import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;

final class CodeLensSpockSupport {

    private final DocumentManager documentManager;
    private final Set<String> lifecycleMethods;
    private final Set<String> spockLabels;

    CodeLensSpockSupport(DocumentManager documentManager, Set<String> lifecycleMethods, Set<String> spockLabels) {
        this.documentManager = documentManager;
        this.lifecycleMethods = lifecycleMethods;
        this.spockLabels = spockLabels;
    }

    boolean shouldSkipMethodCodeLens(IMethod method, String uri) {
        return shouldSkipMethodCodeLens(method, documentManager.getContent(uri), uri, documentManager.getCachedGroovyAST(uri));
    }

    boolean shouldSkipMethodCodeLens(IMethod method, String content, String uri, ModuleNode cachedAst) {
        try {
            if (method == null || uri == null || !isSpockSpecification(method.getDeclaringType())) {
                return false;
            }

            String methodName = method.getElementName();
            if (isSpockLifecycleMethod(methodName) || !isValidIdentifier(methodName)) {
                return true;
            }

            return isIdentifierNamedSpockFeatureMethod(method, content, cachedAst);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isSpockSpecification(IType type) {
        try {
            if (type == null) {
                return false;
            }
            String superclassName = type.getSuperclassName();
            return "Specification".equals(superclassName)
                    || "spock.lang.Specification".equals(superclassName);
        } catch (Exception e) {
            return false;
        }
    }

    boolean isSpockLifecycleMethod(String methodName) {
        return methodName != null && lifecycleMethods.contains(methodName);
    }

    boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            if (!Character.isJavaIdentifierPart(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isIdentifierNamedSpockFeatureMethod(IMethod method, String content, ModuleNode cachedAst) {
        if (cachedAst == null || content == null) {
            return false;
        }

        MethodNode astMethod = findMatchingAstMethod(cachedAst, method, content);
        return astMethod != null && astMethod.getCode() != null && containsSpockLabels(astMethod, cachedAst);
    }

    private MethodNode findMatchingAstMethod(ModuleNode module, IMethod method, String content) {
        if (module == null || method == null || content == null) {
            return null;
        }

        ClassNode declaringClass = findMatchingClassNode(module, safeTypeName(method));
        if (declaringClass == null) {
            return null;
        }

        int targetLine = methodNameLine(method, content);
        if (targetLine < 0) {
            return null;
        }

        List<MethodNode> candidates = declaringClass.getDeclaredMethods(method.getElementName());
        for (MethodNode candidate : candidates) {
            if (candidate.getLineNumber() == targetLine) {
                return candidate;
            }
        }
        return null;
    }

    private ClassNode findMatchingClassNode(ModuleNode module, String typeName) {
        if (module == null || typeName == null || typeName.isBlank()) {
            return null;
        }
        for (ClassNode classNode : module.getClasses()) {
            ClassNode match = findMatchingClassNode(classNode, typeName);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private ClassNode findMatchingClassNode(ClassNode classNode, String typeName) {
        if (classNode == null) {
            return null;
        }
        if (matchesTypeName(classNode, typeName)) {
            return classNode;
        }

        java.util.Iterator<InnerClassNode> innerIter = classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode match = findMatchingClassNode(innerIter.next(), typeName);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean matchesTypeName(ClassNode classNode, String typeName) {
        String astName = classNode.getName();
        if (typeName.equals(astName)) {
            return true;
        }

        String astNameWithoutPackage = classNode.getNameWithoutPackage();
        if (typeName.equals(astNameWithoutPackage)) {
            return true;
        }

        int dollarIndex = astNameWithoutPackage.lastIndexOf('$');
        return dollarIndex >= 0
                && dollarIndex < astNameWithoutPackage.length() - 1
                && typeName.equals(astNameWithoutPackage.substring(dollarIndex + 1));
    }

    private String safeTypeName(IMethod method) {
        try {
            IType declaringType = method.getDeclaringType();
            if (declaringType == null) {
                return null;
            }

            String fullName = declaringType.getFullyQualifiedName('$');
            return fullName != null && !fullName.isBlank() ? fullName : declaringType.getElementName();
        } catch (Exception e) {
            return null;
        }
    }

    private int methodNameLine(IMethod method, String content) {
        try {
            ISourceRange nameRange = method.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return -1;
            }
            return PositionUtils.offsetToPosition(content, nameRange.getOffset()).getLine() + 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean containsSpockLabels(MethodNode methodNode, ModuleNode module) {
        boolean[] found = {false};
        methodNode.getCode().visit(new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module != null ? module.getContext() : null;
            }

            @Override
            public void visitBlockStatement(BlockStatement block) {
                if (found[0] || block == null) {
                    return;
                }

                for (Statement stmt : block.getStatements()) {
                    if (hasSpockLabel(stmt.getStatementLabels())) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitBlockStatement(block);
            }
        });
        return found[0];
    }

    private boolean hasSpockLabel(List<String> labels) {
        if (labels == null) {
            return false;
        }
        for (String label : labels) {
            if (spockLabels.contains(label)) {
                return true;
            }
        }
        return false;
    }
}