package org.eclipse.groovy.ls.core.providers;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.lsp4j.Position;

final class HoverAstSupport {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String[] AUTO_IMPORTED_PACKAGES = {
        "java.lang.", "java.util.", "java.io.", "groovy.lang.", "groovy.util.", "java.math."
    };

    private HoverAstSupport() {}

    static ConstructorCallExpression findConstructorCallAtOffset(
            ModuleNode module, int offset, String typeName, PositionUtils.LineIndex lineIndex) {
        Position pos = lineIndex.offsetToPosition(offset);
        int targetLine = pos.getLine() + 1;
        int targetCol = pos.getCharacter() + 1;

        final ConstructorCallExpression[] result = new ConstructorCallExpression[1];
        ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitConstructorCallExpression(ConstructorCallExpression call) {
                if (result[0] != null || call.getType() == null) {
                    return;
                }
                if (!typeName.equals(call.getType().getNameWithoutPackage())) {
                    super.visitConstructorCallExpression(call);
                    return;
                }

                if (isWithinRange(targetLine, targetCol,
                        call.getLineNumber(), call.getColumnNumber(),
                        call.getLastLineNumber(), call.getLastColumnNumber())) {
                    result[0] = call;
                    return;
                }
                super.visitConstructorCallExpression(call);
            }
        };

        visitClassesAndStatements(module, visitor, result);
        return result[0];
    }

    static IType resolveReceiverTypeFromAst(
            ModuleNode ast,
            IJavaProject project,
            int offset,
            String methodName,
            PositionUtils.LineIndex lineIndex,
            String sourceUri) {
        MethodCallExpression found = findMethodCallAtOffset(ast, offset, methodName, lineIndex);
        if (found == null) {
            return null;
        }

        ClassNode receiverClassNode = resolveObjectExpressionType(found.getObjectExpression(), ast);
        if (!isUsefulType(receiverClassNode)) {
            return null;
        }

        return resolveClassNodeToIType(receiverClassNode, ast, project, sourceUri);
    }

    static MethodCallExpression findMethodCallAtOffset(
            ModuleNode module,
            int offset,
            String methodName,
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
                if (matchesMethodCall(call, methodName, targetLine, targetCol)) {
                    result[0] = call;
                    return;
                }
                super.visitMethodCallExpression(call);
            }
        };

        visitClassesAndStatements(module, visitor, result);
        return result[0];
    }

    static IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module, IJavaProject project,
            String sourceUri) {
        if (typeNode == null || project == null) {
            return null;
        }
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            IType resolved = lookupFullyQualifiedType(project, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            resolved = lookupImportedType(module, project, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            resolved = lookupStarImportedType(module, project, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            resolved = lookupPackageType(module, project, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            return lookupAutoImportedType(project, typeName, sourceUri);
        } catch (JavaModelException e) {
            return null;
        }
    }

    private static void visitClassesAndStatements(
            ModuleNode module,
            ClassCodeVisitorSupport visitor,
            Object[] resultHolder) {
        for (ClassNode classNode : module.getClasses()) {
            if (resultHolder[0] != null) {
                return;
            }
            visitor.visitClass(classNode);
        }

        BlockStatement stmtBlock = module.getStatementBlock();
        if (stmtBlock == null || resultHolder[0] != null) {
            return;
        }

        for (Statement stmt : stmtBlock.getStatements()) {
            if (resultHolder[0] != null) {
                return;
            }
            stmt.visit(visitor);
        }
    }

    private static boolean matchesMethodCall(
            MethodCallExpression call,
            String methodName,
            int targetLine,
            int targetCol) {
        if (!methodName.equals(call.getMethodAsString())) {
            return false;
        }
        Expression methodExpr = call.getMethod();
        int methodLine = methodExpr.getLineNumber();
        int methodColumn = methodExpr.getColumnNumber();
        int methodLastColumn = methodExpr.getLastColumnNumber();
        return methodLine == targetLine
                && targetCol >= methodColumn
                && targetCol <= methodLastColumn;
    }

    private static ClassNode resolveObjectExpressionType(Expression objectExpr, ModuleNode ast) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof VariableExpression variableExpression) {
            return resolveVariableExpressionType(variableExpression, ast);
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnType(nestedCall, ast);
        }
        return null;
    }

    private static ClassNode resolveVariableExpressionType(VariableExpression variableExpression, ModuleNode ast) {
        String variableName = variableExpression.getName();
        if ("this".equals(variableName)) {
            return null;
        }

        ClassNode declaredType = findVariableTypeInClasses(ast, variableName);
        if (isUsefulType(declaredType)) {
            return declaredType;
        }

        return isUsefulType(variableExpression.getType()) ? variableExpression.getType() : null;
    }

    private static ClassNode findVariableTypeInClasses(ModuleNode ast, String variableName) {
        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) {
                continue;
            }
            for (MethodNode method : classNode.getMethods()) {
                ClassNode methodType = resolveLocalVarTypeInBlock(blockFor(method), variableName, ast);
                if (methodType != null) {
                    return methodType;
                }
            }
        }
        BlockStatement stmtBlock = ast.getStatementBlock();
        return stmtBlock != null ? resolveLocalVarTypeInBlock(stmtBlock, variableName, ast) : null;
    }

    private static ClassNode resolveMethodCallReturnType(MethodCallExpression methodCall, ModuleNode module) {
        String methodName = methodCall.getMethodAsString();
        if (methodName == null) {
            return null;
        }

        ClassNode receiverClassNode = resolveReceiverClassNode(methodCall.getObjectExpression(), module);
        if (!isUsefulType(receiverClassNode)) {
            return null;
        }
        for (MethodNode method : receiverClassNode.getMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            ClassNode returnType = method.getReturnType();
            if (isUsefulType(returnType)) {
                return returnType;
            }
        }
        return null;
    }

    private static ClassNode resolveReceiverClassNode(Expression objectExpr, ModuleNode module) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnType(nestedCall, module);
        }
        if (objectExpr instanceof VariableExpression variableExpression) {
            String receiverName = variableExpression.getName();
            if (!"this".equals(receiverName)) {
                ClassNode localType = resolveLocalVarTypeInBlock(module.getStatementBlock(), receiverName, module);
                if (localType != null) {
                    return localType;
                }
                return variableExpression.getType();
            }
        }
        return null;
    }

    private static BlockStatement blockFor(MethodNode method) {
        Statement code = method.getCode();
        return code instanceof BlockStatement block ? block : null;
    }

    private static ClassNode resolveLocalVarTypeInBlock(BlockStatement block, String varName, ModuleNode module) {
        if (block == null) {
            return null;
        }
        for (Statement stmt : block.getStatements()) {
            ClassNode declaredType = resolveDeclaredVariableType(stmt, varName, module);
            if (declaredType != null) {
                return declaredType;
            }
        }
        return null;
    }

    private static ClassNode resolveDeclaredVariableType(Statement stmt, String varName, ModuleNode module) {
        if (!(stmt instanceof ExpressionStatement expressionStatement)) {
            return null;
        }
        if (!(expressionStatement.getExpression() instanceof org.codehaus.groovy.ast.expr.DeclarationExpression declaration)) {
            return null;
        }
        Expression left = declaration.getLeftExpression();
        if (!(left instanceof VariableExpression variableExpression) || !varName.equals(variableExpression.getName())) {
            return null;
        }
        Expression init = declaration.getRightExpression();
        if (init instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (init instanceof MethodCallExpression methodCall) {
            return resolveMethodCallReturnType(methodCall, module);
        }

        ClassNode originType = variableExpression.getOriginType();
        if (isUsefulType(originType)) {
            return originType;
        }
        return isUsefulType(init.getType()) ? init.getType() : null;
    }

    private static IType lookupFullyQualifiedType(IJavaProject project, String typeName, String sourceUri)
            throws JavaModelException {
        return typeName.contains(".") ? ScopedTypeLookupSupport.findType(project, typeName, sourceUri) : null;
    }

    private static IType lookupImportedType(ModuleNode module, IJavaProject project, String typeName,
            String sourceUri) throws JavaModelException {
        for (ImportNode imp : module.getImports()) {
            ClassNode importType = imp.getType();
            if (importType != null && typeName.equals(importType.getNameWithoutPackage())) {
                IType resolved = ScopedTypeLookupSupport.findType(project, importType.getName(), sourceUri);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private static IType lookupStarImportedType(ModuleNode module, IJavaProject project, String typeName,
            String sourceUri) throws JavaModelException {
        for (ImportNode starImport : module.getStarImports()) {
            String packageName = starImport.getPackageName();
            if (packageName == null) {
                continue;
            }
            IType resolved = ScopedTypeLookupSupport.findType(project, packageName + typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static IType lookupPackageType(ModuleNode module, IJavaProject project, String typeName,
            String sourceUri) throws JavaModelException {
        String packageName = module.getPackageName();
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        String normalizedPackage = packageName.endsWith(".")
                ? packageName.substring(0, packageName.length() - 1)
                : packageName;
        return ScopedTypeLookupSupport.findType(project, normalizedPackage + "." + typeName, sourceUri);
    }

    private static IType lookupAutoImportedType(IJavaProject project, String typeName, String sourceUri)
            throws JavaModelException {
        for (String autoPackage : AUTO_IMPORTED_PACKAGES) {
            IType resolved = ScopedTypeLookupSupport.findType(project, autoPackage + typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static boolean isUsefulType(ClassNode classNode) {
        return classNode != null && !JAVA_LANG_OBJECT.equals(classNode.getName());
    }

    private static boolean isWithinRange(
            int targetLine, int targetCol, int line, int col, int lastLine, int lastCol) {
        if (line <= 0 || col <= 0 || lastLine <= 0 || lastCol <= 0) {
            return false;
        }
        if (targetLine < line || targetLine > lastLine) {
            return false;
        }
        if (targetLine == line && targetCol < col) {
            return false;
        }
        return targetLine != lastLine || targetCol <= lastCol;
    }
}