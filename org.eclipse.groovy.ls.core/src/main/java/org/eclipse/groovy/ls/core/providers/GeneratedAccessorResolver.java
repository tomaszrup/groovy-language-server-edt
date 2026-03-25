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

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;

final class GeneratedAccessorResolver {

    private GeneratedAccessorResolver() {
    }

    static IMethod findMethod(IType receiverType, String methodName) throws JavaModelException {
        if (receiverType == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        IType memberSource = JavaBinaryMemberResolver.resolveMemberSource(receiverType);
        if (memberSource == null) {
            return null;
        }

        IMethod method = findMethod(memberSource.getMethods(), methodName);
        if (method != null) {
            return method;
        }

        ITypeHierarchy hierarchy = memberSource.newSupertypeHierarchy(null);
        if (hierarchy == null) {
            return null;
        }

        for (IType superType : hierarchy.getAllSupertypes(memberSource)) {
            method = findMethod(superType.getMethods(), methodName);
            if (method != null) {
                return method;
            }
        }

        return null;
    }

    static JavaRecordSourceSupport.RecordComponentInfo findRecordComponent(IType receiverType, String methodName) {
        if (receiverType == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        for (JavaRecordSourceSupport.RecordComponentInfo component
                : JavaRecordSourceSupport.getRecordComponents(receiverType)) {
            if (methodName.equals(component.name())) {
                return component;
            }
        }

        return null;
    }

    private static IMethod findMethod(IMethod[] methods, String methodName) {
        if (methods == null) {
            return null;
        }

        for (IMethod method : methods) {
            if (methodName.equals(method.getElementName())) {
                return method;
            }
        }

        return null;
    }
}