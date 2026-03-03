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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
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

    private final DocumentManager documentManager;
    private final java.util.concurrent.atomic.AtomicReference<InlayHintSettings> currentSettings =
            new java.util.concurrent.atomic.AtomicReference<>(InlayHintSettings.defaults());

    public InlayHintProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
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

        ModuleNode module = documentManager.getGroovyAST(uri);
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

            if (effectiveSettings.isParameterNamesEnabled()) {
                ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
                List<InlayHint> parameterHints = new ArrayList<>();

                if (workingCopy != null) {
                    ParameterHintCollector jdtCollector =
                            new ParameterHintCollector(content, requestedRange, workingCopy);
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

    private static final class ParameterHintCollector extends ClassCodeVisitorSupport {

        private final String source;
        private final Range requestedRange;
        private final ICompilationUnit workingCopy;
        private final List<InlayHint> hints = new ArrayList<>();
        private final Set<String> emitted = new HashSet<>();
        private SourceUnit sourceUnit;

        ParameterHintCollector(String source, Range requestedRange, ICompilationUnit workingCopy) {
            this.source = source;
            this.requestedRange = requestedRange;
            this.workingCopy = workingCopy;
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
                visitModuleClass(classNode);
            }

            BlockStatement statementBlock = module.getStatementBlock();
            if (statementBlock != null) {
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
            addMethodCallHints(call);
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
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
                if (isHintableArgument(argument) && parameterName != null && !parameterName.isBlank()) {
                    Position position = new Position(
                            Math.max(0, argument.getLineNumber() - 1),
                            Math.max(0, argument.getColumnNumber() - 1));
                    addParameterHint(position, parameterName + ":");
                }
            }
        }

        private boolean isHintableArgument(Expression argument) {
            return argument != null && argument.getLineNumber() >= 1 && !isNamedArgument(argument);
        }

        private IMethod resolveBestMethodTarget(int offset, String methodName, int argumentCount) {
            try {
                IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
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
                IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
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
                    constructors.add(method);
                }
                return;
            }

            if (element instanceof IType type) {
                for (IMethod method : type.getMethods()) {
                    if (method.isConstructor()) {
                        constructors.add(method);
                    }
                }
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
            try {
                String[] names = method.getParameterNames();
                if (names != null && names.length > 0) {
                    return names;
                }
            } catch (Exception ignored) {
                // Parameter names may be absent when debug metadata is unavailable.
            }

            int count = getParameterCount(method);
            if (count < 0 || count > 1000) {
                return new String[0];
            }
            String[] fallback = new String[count];
            for (int i = 0; i < count; i++) {
                fallback[i] = "arg" + i;
            }
            return fallback;
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