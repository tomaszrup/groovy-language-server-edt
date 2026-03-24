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

import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

final class JavaBinaryMemberResolver {

    private JavaBinaryMemberResolver() {
    }

    static IType resolveMemberSource(IType type) throws JavaModelException {
        if (type == null) {
            return null;
        }

        if (type.getClassFile() != null) {
            return type;
        }

        ICompilationUnit compilationUnit = type.getCompilationUnit();
        if (compilationUnit == null) {
            return type;
        }

        IJavaProject javaProject = type.getJavaProject();
        if (javaProject == null) {
            return type;
        }

        String fullyQualifiedName = type.getFullyQualifiedName();
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return type;
        }

        IType binaryType = findBinaryType(javaProject, fullyQualifiedName);
        if (binaryType != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[jdt] Using binary member metadata for " + fullyQualifiedName);
            return binaryType;
        }

        return type;
    }

    static IType findBinaryType(IJavaProject javaProject, String fullyQualifiedName)
            throws JavaModelException {
        if (javaProject == null || fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return null;
        }

        int packageSeparator = fullyQualifiedName.lastIndexOf('.');
        String packageName = packageSeparator >= 0
                ? fullyQualifiedName.substring(0, packageSeparator) : "";
        String simpleName = packageSeparator >= 0
                ? fullyQualifiedName.substring(packageSeparator + 1) : fullyQualifiedName;
        String classFileName = simpleName + ".class";

        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_BINARY) {
                continue;
            }

            IPackageFragment fragment = root.getPackageFragment(packageName);
            if (fragment == null) {
                continue;
            }

            IOrdinaryClassFile classFile = fragment.getOrdinaryClassFile(classFileName);
            if (classFile == null || !classFile.exists()) {
                continue;
            }

            IType binaryType = classFile.getType();
            if (binaryType != null && binaryType.exists()) {
                return binaryType;
            }
        }

        return null;
    }
}