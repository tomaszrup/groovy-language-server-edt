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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.junit.jupiter.api.Test;

class GeneratedAccessorResolverTest {

    @Test
    void findMethodUsesBinaryMemberMetadataForGeneratedGetter() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType sourceType = mock(IType.class);
        IType binaryType = mock(IType.class);
        ICompilationUnit compilationUnit = mock(ICompilationUnit.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        IPackageFragment fragment = mock(IPackageFragment.class);
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod getter = mock(IMethod.class);

        when(sourceType.getCompilationUnit()).thenReturn(compilationUnit);
        when(sourceType.getJavaProject()).thenReturn(project);
        when(sourceType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(sourceType.getMethods()).thenReturn(new IMethod[0]);

        when(project.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[] {root});
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_BINARY);
        when(root.getPackageFragment("com.example")).thenReturn(fragment);
        when(fragment.getOrdinaryClassFile("Helper.class")).thenReturn(classFile);
        when(classFile.exists()).thenReturn(true);
        when(classFile.getType()).thenReturn(binaryType);
        when(binaryType.exists()).thenReturn(true);
        when(binaryType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(binaryType.getMethods()).thenReturn(new IMethod[] {getter});
        when(binaryType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(binaryType)).thenReturn(new IType[0]);

        when(getter.getElementName()).thenReturn("getSomeList");

        assertSame(getter, GeneratedAccessorResolver.findMethod(sourceType, "getSomeList"));
    }

    @Test
    void findRecordComponentFallsBackToSourceRecordHeader() throws JavaModelException {
        IType recordType = mock(IType.class);
        when(recordType.getElementName()).thenReturn("Recc");
        when(recordType.getSource()).thenReturn("public record Recc(java.lang.String something) {}\n");

        JavaRecordSourceSupport.RecordComponentInfo component =
                GeneratedAccessorResolver.findRecordComponent(recordType, "something");

        assertNotNull(component);
        assertEquals("something", component.name());
        assertEquals("java.lang.String", component.type());
    }
}