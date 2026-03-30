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
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;

/**
 * Provides signature help for method calls in Groovy documents.
 * <p>
 * When the user types {@code (} or {@code ,} inside a method invocation,
 * this provider shows the parameter signatures of the invoked method.
 * <p>
 * Uses JDT's {@link ICompilationUnit#codeSelect(int, int)} to resolve the method
 * being called, then enumerates its overloaded variants.
 */
public class SignatureHelpProvider {

    private final DocumentManager documentManager;

    public SignatureHelpProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute signature help at the cursor position.
     */
    public SignatureHelp getSignatureHelp(SignatureHelpParams params) {
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        SignatureHelp jdtHelp = getSignatureHelpFromJdt(uri, position, workingCopy);
        if (jdtHelp != null) {
            return jdtHelp;
        }

        // Fallback: use Groovy AST to find method signatures
        return getSignatureHelpFromGroovyAST(uri, position);
    }

    private SignatureHelp getSignatureHelpFromJdt(String uri,
            Position position,
            ICompilationUnit workingCopy) {
        if (workingCopy == null) {
            return null;
        }

        try {
            String content = documentManager.getContent(uri);
            PositionUtils.LineIndex lineIndex = content != null
                    ? PositionUtils.buildLineIndex(content)
                    : null;
            SignatureContext context = resolveSignatureContext(content, position, lineIndex);
            if (context == null) {
                return null;
            }

            List<SignatureInformation> signatures = collectJdtSignatures(workingCopy, context.methodNameEnd);
            return createSignatureHelp(signatures, context.activeParameter);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Signature help JDT failed for " + uri + ", falling back to AST", e);
            return null;
        }
    }

    private SignatureContext resolveSignatureContext(String content, Position position) {
        return resolveSignatureContext(
                content,
                position,
                content != null ? PositionUtils.buildLineIndex(content) : null);
    }

    private SignatureContext resolveSignatureContext(String content,
            Position position,
            PositionUtils.LineIndex lineIndex) {
        if (content == null || position == null) {
            return null;
        }

        int offset = lineIndex != null
                ? lineIndex.positionToOffset(position)
                : positionToOffset(content, position);
        int openingParenOffset = findInvocationStart(content, offset);
        if (openingParenOffset < 0) {
            return null;
        }

        int methodNameEnd = findMethodNameEnd(content, offset);
        if (methodNameEnd < 0) {
            return null;
        }

        int activeParameter = countCommas(content, openingParenOffset + 1, offset);
        return new SignatureContext(methodNameEnd, activeParameter);
    }

    private List<SignatureInformation> collectJdtSignatures(ICompilationUnit workingCopy, int methodNameEnd)
            throws JavaModelException {
        List<SignatureInformation> signatures = new ArrayList<>();
        IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, methodNameEnd);
        if (elements == null || elements.length == 0) {
            return signatures;
        }

        for (IJavaElement element : elements) {
            IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
            addJdtSignaturesForElement(signatures, remappedElement != null ? remappedElement : element);
        }
        return signatures;
    }

    private void addJdtSignaturesForElement(List<SignatureInformation> signatures, IJavaElement element)
            throws JavaModelException {
        if (element instanceof IMethod methodElement) {
            addJdtSignature(signatures, methodElement);
            return;
        }

        if (element instanceof IType typeElement) {
            IType memberSource = JavaBinaryMemberResolver.resolveMemberSource(typeElement);
            boolean foundConstructor = false;
            for (IMethod constructor : memberSource.getMethods()) {
                if (constructor.isConstructor()) {
                    foundConstructor = true;
                    addJdtSignature(signatures, constructor);
                }
            }

            if (!foundConstructor) {
                addRecordConstructorSignature(signatures, typeElement);
            }
        }
    }

    private void addRecordConstructorSignature(List<SignatureInformation> signatures, IType typeElement) {
        List<JavaRecordSourceSupport.RecordComponentInfo> components = JavaRecordSourceSupport.getRecordComponents(typeElement);
        if (components.isEmpty()) {
            return;
        }

        SignatureInformation signature = new SignatureInformation();
        List<ParameterInformation> parameters = new ArrayList<>();
        StringBuilder label = new StringBuilder(typeElement.getElementName()).append('(');

        for (int index = 0; index < components.size(); index++) {
            JavaRecordSourceSupport.RecordComponentInfo component = components.get(index);
            String parameterLabel = component.type() + " " + component.name();
            if (index > 0) {
                label.append(", ");
            }
            label.append(parameterLabel);

            ParameterInformation parameter = new ParameterInformation();
            parameter.setLabel(parameterLabel);
            parameters.add(parameter);
        }

        label.append(')');
        signature.setLabel(label.toString());
        signature.setParameters(parameters);
        signatures.add(signature);
    }

    private void addJdtSignature(List<SignatureInformation> signatures, IMethod method) {
        SignatureInformation signature = toSignatureInformation(method);
        if (signature != null) {
            signatures.add(signature);
        }
    }

    /**
     * Convert a JDT {@link IMethod} to an LSP {@link SignatureInformation}.
     */
    private SignatureInformation toSignatureInformation(IMethod method) {
        try {
            StringBuilder label = new StringBuilder();
            label.append(method.getElementName()).append('(');

            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = JdtParameterNameResolver.resolve(method);
            List<ParameterInformation> parameters = new ArrayList<>();

            for (int i = 0; i < paramTypes.length; i++) {
                String typeName = Signature.toString(paramTypes[i]);
                String paramName = (paramNames != null && i < paramNames.length)
                        ? paramNames[i] : "arg" + i;

                String paramLabel = typeName + " " + paramName;
                if (i > 0) label.append(", ");
                label.append(paramLabel);

                ParameterInformation paramInfo = new ParameterInformation();
                paramInfo.setLabel(paramLabel);
                parameters.add(paramInfo);
            }

            label.append(')');

            // Add return type
            if (!method.isConstructor()) {
                String returnType = Signature.toString(method.getReturnType());
                label.append(": ").append(returnType);
            }

            SignatureInformation sig = new SignatureInformation();
            sig.setLabel(label.toString());
            sig.setParameters(parameters);

            return sig;

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to build signature info", e);
            return null;
        }
    }

    /**
     * Find the offset of the method name just before the opening '('.
     * Walks backwards from the cursor, skipping nested parens/brackets.
     */
    private int findMethodNameEnd(String content, int offset) {
        int depth = 0;
        for (int i = offset - 1; i >= 0; i--) {
            char c = content.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth > 0) {
                    depth--;
                } else {
                    // Found the matching '(' — the method name is just before it
                    // Walk back over whitespace
                    int nameEnd = i - 1;
                    while (nameEnd >= 0 && Character.isWhitespace(content.charAt(nameEnd))) {
                        nameEnd--;
                    }
                    return nameEnd;
                }
            }
        }
        return -1;
    }

    private int findInvocationStart(String content, int offset) {
        int depth = 0;
        for (int i = offset - 1; i >= 0; i--) {
            char c = content.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth > 0) {
                    depth--;
                } else if (c == '(') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Count the number of commas between two offsets (for determining
     * the active parameter index). Skips commas nested in parens/brackets/braces.
     */
    private int countCommas(String content, int start, int end) {
        int commas = 0;
        int depth = 0;
        int safeEnd = Math.min(end, content.length());

        for (int i = start; i < safeEnd; i++) {
            char c = content.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }

        return commas;
    }

    // ---- Groovy AST fallback signature help ----

    /**
     * Provide signature help from the Groovy AST when JDT is unavailable.
     * Resolves the method name at the call site and finds matching declarations.
     */
    private SignatureHelp getSignatureHelpFromGroovyAST(String uri, Position position) {
        String content = documentManager.getContent(uri);
        ModuleNode ast = documentManager.getGroovyAST(uri);
        PositionUtils.LineIndex lineIndex = content != null
                ? PositionUtils.buildLineIndex(content)
                : null;
        SignatureContext context = resolveSignatureContext(content, position, lineIndex);
        if (ast == null || context == null) {
            return null;
        }

        String methodName = extractWordAt(content, context.methodNameEnd);
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }

        List<SignatureInformation> signatures = collectAstSignatures(ast, methodName);
        return createSignatureHelp(signatures, context.activeParameter);
    }

    private List<SignatureInformation> collectAstSignatures(ModuleNode ast, String methodName) {
        List<SignatureInformation> signatures = new ArrayList<>();
        for (ClassNode classNode : ast.getClasses()) {
            addAstConstructorSignatures(signatures, classNode, methodName);
            addAstMethodSignatures(signatures, classNode, methodName);
        }
        return signatures;
    }

    private void addAstConstructorSignatures(List<SignatureInformation> signatures,
            ClassNode classNode,
            String methodName) {
        if (!classNode.getNameWithoutPackage().equals(methodName)) {
            return;
        }

        int beforeCount = signatures.size();
        for (MethodNode constructor : classNode.getDeclaredConstructors()) {
            SignatureInformation signature = astMethodToSignature(constructor);
            signatures.add(signature);
        }

        if (signatures.size() == beforeCount) {
            SignatureInformation defaultConstructor = new SignatureInformation();
            defaultConstructor.setLabel(classNode.getNameWithoutPackage() + "()");
            defaultConstructor.setParameters(new ArrayList<>());
            signatures.add(defaultConstructor);
        }
    }

    private void addAstMethodSignatures(List<SignatureInformation> signatures,
            ClassNode classNode,
            String methodName) {
        for (MethodNode method : classNode.getMethods()) {
            if (methodName.equals(method.getName())) {
                SignatureInformation signature = astMethodToSignature(method);
                signatures.add(signature);
            }
        }
    }

    private SignatureHelp createSignatureHelp(List<SignatureInformation> signatures, int activeParameter) {
        if (signatures == null || signatures.isEmpty()) {
            return null;
        }

        SignatureHelp help = new SignatureHelp();
        help.setSignatures(signatures);
        help.setActiveSignature(0);
        help.setActiveParameter(activeParameter);
        return help;
    }

    private static final class SignatureContext {
        private final int methodNameEnd;
        private final int activeParameter;

        private SignatureContext(int methodNameEnd, int activeParameter) {
            this.methodNameEnd = methodNameEnd;
            this.activeParameter = activeParameter;
        }
    }

    /**
     * Convert a Groovy AST MethodNode to an LSP SignatureInformation.
     */
    private SignatureInformation astMethodToSignature(MethodNode method) {
        StringBuilder label = new StringBuilder();
        label.append(method.getName()).append('(');

        Parameter[] params = method.getParameters();
        List<ParameterInformation> paramInfos = new ArrayList<>();

        for (int i = 0; i < params.length; i++) {
            String typeName = params[i].getType().getNameWithoutPackage();
            String paramName = ParameterNameSupport.displayName(params[i].getName());
            String paramLabel = paramName != null ? typeName + " " + paramName : typeName;

            if (i > 0) label.append(", ");
            label.append(paramLabel);

            ParameterInformation paramInfo = new ParameterInformation();
            paramInfo.setLabel(paramLabel);
            paramInfos.add(paramInfo);
        }

        label.append(')');

        // Add return type
        if (!method.isStaticConstructor() && method.getReturnType() != null) {
            label.append(": ").append(method.getReturnType().getNameWithoutPackage());
        }

        SignatureInformation sig = new SignatureInformation();
        sig.setLabel(label.toString());
        sig.setParameters(paramInfos);
        return sig;
    }

    /**
     * Extract the identifier word at the given offset.
     */
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

    private int positionToOffset(String content, Position position) {
        return PositionUtils.positionToOffset(content, position);
    }
}
