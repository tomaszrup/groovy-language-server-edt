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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.Test;

/**
 * Tests for helper methods in {@link UnusedDeclarationDetector},
 * focusing on Spock specification support.
 */
class UnusedDeclarationDetectorTest {

    // ----- isSpockSpecification -----

    @Test
    void isSpockSpecificationReturnsTrueForSimpleName() throws Exception {
        IType type = mockType("Specification", null);
        assertTrue(invokeIsSpockSpecification(type));
    }

    @Test
    void isSpockSpecificationReturnsTrueForFqn() throws Exception {
        IType type = mockType("spock.lang.Specification", null);
        assertTrue(invokeIsSpockSpecification(type));
    }

    @Test
    void isSpockSpecificationReturnsFalseForOtherSuperclass() throws Exception {
        IType type = mockType("TestCase", null);
        assertFalse(invokeIsSpockSpecification(type));
    }

    @Test
    void isSpockSpecificationReturnsFalseForNullSuperclass() throws Exception {
        IType type = mockType(null, null);
        assertFalse(invokeIsSpockSpecification(type));
    }

    // ----- isTestMethod with Spock feature methods -----

    @Test
    void isTestMethodReturnsTrueForStringNamedMethodInSpockSpec() throws Exception {
        IType declaringType = mockType("Specification", null);
        IMethod method = mockMethod("value returns epsilon", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForSpockSetupMethod() throws Exception {
        IType declaringType = mockType("spock.lang.Specification", null);
        IMethod method = mockMethod("setup", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForSpockCleanupMethod() throws Exception {
        IType declaringType = mockType("spock.lang.Specification", null);
        IMethod method = mockMethod("cleanup", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForSpockHelperMethod() throws Exception {
        IType declaringType = mockType("Specification", null);
        IMethod method = mockMethod("helperMethod", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsFalseForRegularMethodInNonTestClass() throws Exception {
        IType declaringType = mockType("Object", null);
        // Non-test class, non-test directory
        when(declaringType.getResource()).thenReturn(null);
        IMethod method = mockMethod("someMethod", declaringType, new IAnnotation[0]);
        assertFalse(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForTestPrefixedMethodInTestCase() throws Exception {
        IType declaringType = mockType("TestCase", null);
        IMethod method = mockMethod("testSomething", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    // ----- Reflection helpers -----

    private static boolean invokeIsSpockSpecification(IType type) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isSpockSpecification", IType.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, type);
    }

    private static boolean invokeIsTestMethod(IMethod method) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isTestMethod", IMethod.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, method);
    }

    // ----- Mock factories -----

    private static IType mockType(String superclassName, String path) throws Exception {
        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(superclassName);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        if (path != null) {
            org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
            org.eclipse.core.runtime.IPath iPath = mock(org.eclipse.core.runtime.IPath.class);
            when(iPath.toString()).thenReturn(path);
            when(resource.getFullPath()).thenReturn(iPath);
            when(type.getResource()).thenReturn(resource);
        } else {
            when(type.getResource()).thenReturn(null);
        }
        return type;
    }

    private static IMethod mockMethod(String name, IType declaringType,
                                       IAnnotation[] annotations) throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn(name);
        when(method.getDeclaringType()).thenReturn(declaringType);
        when(method.getAnnotations()).thenReturn(annotations);
        return method;
    }
}
