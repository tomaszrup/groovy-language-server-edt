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
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureInformation;

final class SignatureHelpAstSupport {

    List<SignatureInformation> collectAstSignatures(ModuleNode ast, String methodName) {
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
            signatures.add(astMethodToSignature(constructor));
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
                signatures.add(astMethodToSignature(method));
            }
        }
    }

    private SignatureInformation astMethodToSignature(MethodNode method) {
        StringBuilder label = new StringBuilder();
        label.append(method.getName()).append('(');

        Parameter[] params = method.getParameters();
        List<ParameterInformation> paramInfos = new ArrayList<>();

        for (int index = 0; index < params.length; index++) {
            String typeName = params[index].getType().getNameWithoutPackage();
            String paramName = ParameterNameSupport.displayName(params[index].getName());
            String paramLabel = paramName != null ? typeName + " " + paramName : typeName;

            if (index > 0) {
                label.append(", ");
            }
            label.append(paramLabel);

            ParameterInformation paramInfo = new ParameterInformation();
            paramInfo.setLabel(paramLabel);
            paramInfos.add(paramInfo);
        }

        label.append(')');

        if (!method.isConstructor()) {
            String returnType = method.getReturnType().getNameWithoutPackage();
            label.append(": ").append(returnType);
        }

        SignatureInformation sig = new SignatureInformation();
        sig.setLabel(label.toString());
        sig.setParameters(paramInfos);
        return sig;
    }
}