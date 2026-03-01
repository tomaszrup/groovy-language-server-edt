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
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);

                    // Walk backwards to find the method name before the '('
                    int methodNameEnd = findMethodNameEnd(content, offset);
                    if (methodNameEnd >= 0) {
                        // Count commas before cursor to determine active parameter
                        int activeParameter = countCommas(content, methodNameEnd + 1, offset);

                        // Try to resolve the method element at the call site
                        IJavaElement[] elements = workingCopy.codeSelect(methodNameEnd, 0);
                        if (elements != null && elements.length > 0) {
                            List<SignatureInformation> signatures = new ArrayList<>();

                            for (IJavaElement element : elements) {
                                if (element instanceof IMethod) {
                                    SignatureInformation sig = toSignatureInformation((IMethod) element);
                                    if (sig != null) {
                                        signatures.add(sig);
                                    }
                                } else if (element instanceof IType) {
                                    // Constructor call — enumerate constructors
                                    IType type = (IType) element;
                                    for (IMethod constructor : type.getMethods()) {
                                        if (constructor.isConstructor()) {
                                            SignatureInformation sig = toSignatureInformation(constructor);
                                            if (sig != null) {
                                                signatures.add(sig);
                                            }
                                        }
                                    }
                                }
                            }

                            if (!signatures.isEmpty()) {
                                SignatureHelp help = new SignatureHelp();
                                help.setSignatures(signatures);
                                help.setActiveSignature(0);
                                help.setActiveParameter(activeParameter);
                                return help;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                GroovyLanguageServerPlugin.logError("Signature help JDT failed for " + uri + ", falling back to AST", t);
            }
        }

        // Fallback: use Groovy AST to find method signatures
        return getSignatureHelpFromGroovyAST(uri, position);
    }

    /**
     * Convert a JDT {@link IMethod} to an LSP {@link SignatureInformation}.
     */
    private SignatureInformation toSignatureInformation(IMethod method) {
        try {
            StringBuilder label = new StringBuilder();
            label.append(method.getElementName()).append('(');

            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();
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
        if (content == null) {
            return null;
        }

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            return null;
        }

        int offset = positionToOffset(content, position);

        // Walk backwards to find the method name before the '('
        int methodNameEnd = findMethodNameEnd(content, offset);
        if (methodNameEnd < 0) {
            return null;
        }

        // Count commas to determine active parameter
        int activeParameter = countCommas(content, methodNameEnd + 1, offset);

        // Extract the method name
        String methodName = extractWordAt(content, methodNameEnd);
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }

        // Search the AST for methods with this name
        List<SignatureInformation> signatures = new ArrayList<>();
        for (ClassNode classNode : ast.getClasses()) {
            // Check if it's a constructor call (class name matches)
            if (classNode.getNameWithoutPackage().equals(methodName)) {
                // Find declared constructors
                for (MethodNode method : classNode.getDeclaredConstructors()) {
                    SignatureInformation sig = astMethodToSignature(method);
                    if (sig != null) {
                        signatures.add(sig);
                    }
                }
                // If no explicit constructors, add default
                if (signatures.isEmpty()) {
                    SignatureInformation sig = new SignatureInformation();
                    sig.setLabel(classNode.getNameWithoutPackage() + "()");
                    sig.setParameters(new ArrayList<>());
                    signatures.add(sig);
                }
            }

            // Check methods
            for (MethodNode method : classNode.getMethods()) {
                if (method.getName().equals(methodName)) {
                    SignatureInformation sig = astMethodToSignature(method);
                    if (sig != null) {
                        signatures.add(sig);
                    }
                }
            }
        }

        if (signatures.isEmpty()) {
            return null;
        }

        SignatureHelp help = new SignatureHelp();
        help.setSignatures(signatures);
        help.setActiveSignature(0);
        help.setActiveParameter(activeParameter);
        return help;
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
            String paramName = params[i].getName();
            String paramLabel = typeName + " " + paramName;

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
}
