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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
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
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * AST visitor that collects Groovy inlay hints.
 */
public class InlayHintVisitor extends ClassCodeVisitorSupport {

    private static final Pattern DEF_METHOD_PATTERN_TEMPLATE =
            Pattern.compile("\\bdef\\s+%s\\s*\\(");
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private final String source;
    private final Range requestedRange;
    private final InlayHintSettings settings;
    private final List<InlayHint> hints = new ArrayList<>();
    private final Set<String> emittedHints = new HashSet<>();
    private final Map<String, List<MethodNode>> methodsByName = new HashMap<>();

    private SourceUnit sourceUnit;

    public InlayHintVisitor(String source, Range requestedRange, InlayHintSettings settings) {
        this.source = source;
        this.requestedRange = requestedRange;
        this.settings = settings != null ? settings : InlayHintSettings.defaults();
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    public List<InlayHint> getHints() {
        hints.sort(Comparator.comparing((InlayHint hint) -> hint.getPosition().getLine())
                .thenComparing(hint -> hint.getPosition().getCharacter()));
        return hints;
    }

    public void visitModule(ModuleNode module) {
        this.sourceUnit = module.getContext();
        indexDeclaredMethods(module);

        for (ClassNode classNode : module.getClasses()) {
            if (isTopLevelScriptClass(classNode)) {
                visitTopLevelScriptClass(classNode);
            } else {
                visitClass(classNode);
            }
        }

        BlockStatement statementBlock = module.getStatementBlock();
        if (statementBlock != null) {
            statementBlock.visit(this);
        }
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expr) {
        if (settings.isVariableTypesEnabled()) {
            addVariableTypeHint(expr);
        }
        super.visitDeclarationExpression(expr);
    }

    @Override
    public void visitMethod(MethodNode node) {
        if (settings.isMethodReturnTypesEnabled()) {
            addMethodReturnTypeHint(node);
        }
        super.visitMethod(node);
    }

    @Override
    public void visitClosureExpression(ClosureExpression expr) {
        if (settings.isClosureParameterTypesEnabled()) {
            addClosureParameterHints(expr);
        }
        super.visitClosureExpression(expr);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        if (settings.isParameterNamesEnabled()) {
            addMethodCallParameterHints(call);
        }
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        if (settings.isParameterNamesEnabled()) {
            addConstructorParameterHints(call);
        }
        super.visitConstructorCallExpression(call);
    }

    private void addVariableTypeHint(DeclarationExpression expr) {
        if (expr == null || expr.getLineNumber() < 1) {
            return;
        }
        Expression left = expr.getLeftExpression();
        if (!(left instanceof VariableExpression)) {
            return;
        }
        VariableExpression variable = (VariableExpression) left;
        String variableName = variable.getName();
        if (variableName == null || variableName.isEmpty()) {
            return;
        }

        if (!isDefStyleVariable(variable)) {
            return;
        }

        ClassNode inferredType = null;
        Expression rightExpr = expr.getRightExpression();
        if (rightExpr != null) {
            inferredType = rightExpr.getType();
        }

        // When the inferred type is unhelpful (Object) and the right expression
        // is a method call chain (e.g., new Foo().bar()), try to resolve the
        // receiver type from the chain — even though we can't determine the exact
        // return type without JDT, knowing the receiver type is still useful.
        if (isUnhelpfulType(inferredType) && rightExpr instanceof MethodCallExpression methodCall) {
            ClassNode chainReceiverType = resolveMethodCallReceiverType(methodCall);
            if (chainReceiverType != null && !isUnhelpfulType(chainReceiverType)) {
                // We know the receiver but not the method return type at AST level.
                // Skip the hint rather than showing the wrong type.
                // The JDT-aware InlayHintProvider will handle this separately.
                return;
            }
        }

        if (isUnhelpfulType(inferredType)) {
            return;
        }

        if (isRedundantConstructorType(rightExpr, inferredType)) {
            return;
        }

        Position hintPosition = new Position(
                Math.max(0, variable.getLineNumber() - 1),
                Math.max(0, variable.getColumnNumber() - 1) + variableName.length());

        addTypeHint(hintPosition, ": " + formatType(inferredType));
    }

    /**
     * Walk a MethodCallExpression chain to find the outermost receiver type.
     * For {@code new Foo().bar().baz()}, returns the ClassNode for {@code Foo}.
     */
    private ClassNode resolveMethodCallReceiverType(MethodCallExpression methodCall) {
        Expression objectExpr = methodCall.getObjectExpression();
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReceiverType(nestedCall);
        }
        return null;
    }

    private void addMethodReturnTypeHint(MethodNode node) {
        if (node == null || node.getLineNumber() < 1 || node.isSynthetic()) {
            return;
        }
        if ("<init>".equals(node.getName()) || "<clinit>".equals(node.getName())) {
            return;
        }

        ClassNode returnType = node.getReturnType();
        if (isUnhelpfulType(returnType)) {
            return;
        }
        if (!isDefStyleMethod(node)) {
            return;
        }

        String methodName = node.getName();
        if (methodName == null || methodName.isEmpty()) {
            return;
        }

        int line = Math.max(0, node.getLineNumber() - 1);
        int methodNameCol = findNameInLine(line, Math.max(0, node.getColumnNumber() - 1), methodName);
        if (methodNameCol < 0) {
            methodNameCol = Math.max(0, node.getColumnNumber() - 1);
        }

        Position hintPosition = new Position(line, methodNameCol + methodName.length());
        addTypeHint(hintPosition, ": " + formatType(returnType));
    }

    private void addClosureParameterHints(ClosureExpression expr) {
        if (expr == null || expr.getLineNumber() < 1) {
            return;
        }
        Parameter[] parameters = expr.getParameters();
        if (parameters == null || parameters.length == 0) {
            return;
        }

        for (Parameter parameter : parameters) {
            if (isHintableClosureParameter(parameter)) {
                String name = parameter.getName();
                Position position = new Position(
                        Math.max(0, parameter.getLineNumber() - 1),
                        Math.max(0, parameter.getColumnNumber() - 1) + name.length());
                addTypeHint(position, ": " + formatType(parameter.getType()));
            }
        }
    }

    private void addMethodCallParameterHints(MethodCallExpression call) {
        if (call == null || call.getLineNumber() < 1) {
            return;
        }

        String methodName = call.getMethodAsString();
        if (methodName == null || methodName.isEmpty()) {
            return;
        }

        List<Expression> arguments = toArgumentExpressions(call.getArguments());
        if (arguments.isEmpty()) {
            return;
        }

        MethodNode targetMethod = findMatchingMethod(methodName, arguments.size());
        if (targetMethod == null) {
            return;
        }

        Parameter[] params = targetMethod.getParameters();
        int max = Math.min(arguments.size(), params.length);
        for (int index = 0; index < max; index++) {
            Expression arg = arguments.get(index);
            String parameterName = params[index].getName();
            if (isHintableParameterArgument(arg, parameterName)) {
                Position position = new Position(
                        Math.max(0, arg.getLineNumber() - 1),
                        Math.max(0, arg.getColumnNumber() - 1));
                addParameterHint(position, parameterName + ":");
            }
        }
    }

    private void addConstructorParameterHints(ConstructorCallExpression call) {
        if (call == null || call.getLineNumber() < 1) {
            return;
        }

        List<Expression> arguments = toArgumentExpressions(call.getArguments());
        if (arguments.isEmpty()) {
            return;
        }

        ClassNode type = call.getType();
        if (type == null) {
            return;
        }

        MethodNode constructor = findMatchingConstructor(type, arguments.size());
        if (constructor == null) {
            return;
        }

        Parameter[] params = constructor.getParameters();
        int max = Math.min(arguments.size(), params.length);
        for (int index = 0; index < max; index++) {
            Expression arg = arguments.get(index);
            String parameterName = params[index].getName();
            if (isHintableParameterArgument(arg, parameterName)) {
                Position position = new Position(
                        Math.max(0, arg.getLineNumber() - 1),
                        Math.max(0, arg.getColumnNumber() - 1));
                addParameterHint(position, parameterName + ":");
            }
        }
    }

    private void addTypeHint(Position position, String label) {
        if (!isPositionInRange(position) || label == null || label.isBlank()) {
            return;
        }
        String key = position.getLine() + ":" + position.getCharacter() + ":" + label;
        if (!emittedHints.add(key)) {
            return;
        }

        InlayHint hint = new InlayHint();
        hint.setPosition(position);
        hint.setKind(InlayHintKind.Type);
        hint.setLabel(Either.forLeft(label));
        hint.setPaddingLeft(true);
        hints.add(hint);
    }

    private void addParameterHint(Position position, String label) {
        if (!isPositionInRange(position) || label == null || label.isBlank()) {
            return;
        }
        String key = position.getLine() + ":" + position.getCharacter() + ":" + label;
        if (!emittedHints.add(key)) {
            return;
        }

        InlayHint hint = new InlayHint();
        hint.setPosition(position);
        hint.setKind(InlayHintKind.Parameter);
        hint.setLabel(Either.forLeft(label));
        hint.setPaddingRight(true);
        hints.add(hint);
    }

    private List<Expression> toArgumentExpressions(Expression argumentsExpression) {
        if (argumentsExpression instanceof ArgumentListExpression argumentListExpression) {
            return argumentListExpression.getExpressions();
        }
        if (argumentsExpression instanceof TupleExpression tupleExpression) {
            return tupleExpression.getExpressions();
        }
        return Collections.emptyList();
    }

    private boolean isHintableClosureParameter(Parameter parameter) {
        if (parameter == null || parameter.getLineNumber() < 1) {
            return false;
        }
        if (!isDefStyleParameter(parameter)) {
            return false;
        }
        ClassNode inferredType = parameter.getType();
        if (isUnhelpfulType(inferredType)) {
            return false;
        }
        String name = parameter.getName();
        return name != null && !name.isEmpty();
    }

    private boolean isHintableParameterArgument(Expression arg, String parameterName) {
        return arg != null
                && arg.getLineNumber() >= 1
                && !isNamedArgumentExpression(arg)
                && parameterName != null
                && !parameterName.isEmpty()
                && !isArgumentNameMatchingParameter(arg, parameterName);
    }

    private MethodNode findMatchingMethod(String methodName, int argumentCount) {
        List<MethodNode> candidates = methodsByName.get(methodName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        for (MethodNode method : candidates) {
            if (method.getParameters().length == argumentCount) {
                return method;
            }
        }
        for (MethodNode method : candidates) {
            if (method.getParameters().length >= argumentCount) {
                return method;
            }
        }
        return candidates.get(0);
    }

    private MethodNode findMatchingConstructor(ClassNode type, int argumentCount) {
        List<ConstructorNode> constructors = type.getDeclaredConstructors();
        if (constructors == null || constructors.isEmpty()) {
            return null;
        }

        for (ConstructorNode constructor : constructors) {
            if (constructor.getParameters().length == argumentCount) {
                return constructor;
            }
        }
        for (ConstructorNode constructor : constructors) {
            if (constructor.getParameters().length >= argumentCount) {
                return constructor;
            }
        }
        return constructors.get(0);
    }

    private void indexDeclaredMethods(ModuleNode module) {
        methodsByName.clear();
        for (ClassNode classNode : module.getClasses()) {
            for (MethodNode method : classNode.getMethods()) {
                methodsByName.computeIfAbsent(method.getName(), key -> new ArrayList<>()).add(method);
            }
        }
    }

    private boolean isTopLevelScriptClass(ClassNode classNode) {
        return classNode.isScript() && classNode.getLineNumber() < 1;
    }

    private void visitTopLevelScriptClass(ClassNode classNode) {
        MethodNode runMethod = classNode.getMethod("run", Parameter.EMPTY_ARRAY);
        if (runMethod != null && runMethod.getCode() != null) {
            runMethod.getCode().visit(this);
        }
        for (MethodNode method : classNode.getMethods()) {
            if (!"run".equals(method.getName()) && !"main".equals(method.getName())
                    && method.getLineNumber() > 0) {
                visitMethod(method);
            }
        }
    }

    private boolean isDefStyleVariable(VariableExpression variable) {
        if (variable == null || variable.getLineNumber() < 1) {
            return false;
        }
        boolean dynamicTyped = false;
        try {
            dynamicTyped = variable.isDynamicTyped();
        } catch (Exception e) {
            dynamicTyped = false;
        }
        if (dynamicTyped) {
            return true;
        }

        ClassNode originType = variable.getOriginType();
        if (originType != null && JAVA_LANG_OBJECT.equals(originType.getName())) {
            return true;
        }

        int line = Math.max(0, variable.getLineNumber() - 1);
        String lineText = getLineText(line);
        if (lineText == null) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\bdef\\s+" + Pattern.quote(variable.getName()) + "\\b");
        return pattern.matcher(lineText).find();
    }

    private boolean isDefStyleParameter(Parameter parameter) {
        if (parameter == null) {
            return false;
        }
        boolean dynamicTyped = false;
        try {
            dynamicTyped = parameter.isDynamicTyped();
        } catch (Exception e) {
            dynamicTyped = false;
        }
        if (dynamicTyped) {
            return true;
        }

        ClassNode type = parameter.getType();
        return type != null && JAVA_LANG_OBJECT.equals(type.getName());
    }

    private boolean isDefStyleMethod(MethodNode method) {
        if (method == null || method.getLineNumber() < 1) {
            return false;
        }
        int line = Math.max(0, method.getLineNumber() - 1);
        String lineText = getLineText(line);
        if (lineText == null) {
            return false;
        }

        Pattern pattern = Pattern.compile(
                DEF_METHOD_PATTERN_TEMPLATE.pattern().formatted(Pattern.quote(method.getName())));
        Matcher matcher = pattern.matcher(lineText);
        if (matcher.find()) {
            return true;
        }

        ClassNode declaredType = method.getReturnType();
        return declaredType != null && JAVA_LANG_OBJECT.equals(declaredType.getName());
    }

    private boolean isNamedArgumentExpression(Expression expr) {
        return expr instanceof NamedArgumentListExpression || expr instanceof MapExpression;
    }

    private boolean isRedundantConstructorType(Expression rightExpression, ClassNode inferredType) {
        if (!(rightExpression instanceof ConstructorCallExpression)) {
            return false;
        }
        return formatType(inferredType).equals(formatType(rightExpression.getType()));
    }

    private boolean isArgumentNameMatchingParameter(Expression arg, String parameterName) {
        if (arg instanceof VariableExpression variable) {
            String argName = variable.getName();
            return argName != null && argName.equals(parameterName);
        }
        return false;
    }

    private boolean isUnhelpfulType(ClassNode type) {
        if (type == null) {
            return true;
        }
        String name = type.getName();
        return name == null
                || name.isBlank()
            || JAVA_LANG_OBJECT.equals(name)
                || "groovy.lang.GroovyObject".equals(name)
                || "void".equals(name)
                || "java.lang.Void".equals(name);
    }

    private String formatType(ClassNode type) {
        if (type == null) {
            return "Object";
        }
        String name = type.getNameWithoutPackage();
        if (name == null || name.isBlank()) {
            name = type.getName();
        }
        int innerClassSep = name.indexOf('$');
        if (innerClassSep >= 0 && innerClassSep < name.length() - 1) {
            name = name.substring(innerClassSep + 1);
        }
        return name;
    }

    private boolean isPositionInRange(Position position) {
        if (requestedRange == null || position == null) {
            return true;
        }
        return compare(position, requestedRange.getStart()) >= 0
                && compare(position, requestedRange.getEnd()) <= 0;
    }

    private int compare(Position left, Position right) {
        if (left.getLine() != right.getLine()) {
            return Integer.compare(left.getLine(), right.getLine());
        }
        return Integer.compare(left.getCharacter(), right.getCharacter());
    }

    private int findNameInLine(int line, int fromColumn, String name) {
        String lineText = getLineText(line);
        if (lineText == null || name == null || name.isEmpty()) {
            return -1;
        }
        int safeFrom = Math.max(0, Math.min(fromColumn, lineText.length()));
        int idx = lineText.indexOf(name, safeFrom);
        if (idx >= 0 && isNameBoundary(lineText, idx, idx + name.length())) {
            return idx;
        }

        idx = lineText.indexOf(name);
        if (idx >= 0 && isNameBoundary(lineText, idx, idx + name.length())) {
            return idx;
        }
        return -1;
    }

    private boolean isNameBoundary(String lineText, int start, int end) {
        boolean leftBoundary = start <= 0 || !Character.isJavaIdentifierPart(lineText.charAt(start - 1));
        boolean rightBoundary = end >= lineText.length() || !Character.isJavaIdentifierPart(lineText.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private String getLineText(int targetLine) {
        if (targetLine < 0 || source == null || source.isEmpty()) {
            return null;
        }

        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < source.length(); index++) {
            if (line == targetLine) {
                break;
            }
            if (source.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        if (line != targetLine) {
            return null;
        }

        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (lineStart > lineEnd || lineStart >= source.length()) {
            return "";
        }
        return source.substring(lineStart, lineEnd);
    }
}