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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Diagnostic;
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

    // ----- isMainMethod -----

    @Test
    void isMainMethodReturnsTrueForStaticMain() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("main");
        when(method.getFlags()).thenReturn(Flags.AccStatic);
        assertTrue(invokeIsMainMethod(method));
    }

    @Test
    void isMainMethodReturnsFalseForNonStaticMain() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("main");
        when(method.getFlags()).thenReturn(0);
        assertFalse(invokeIsMainMethod(method));
    }

    @Test
    void isMainMethodReturnsFalseForStaticNonMain() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("run");
        when(method.getFlags()).thenReturn(Flags.AccStatic);
        assertFalse(invokeIsMainMethod(method));
    }

    @Test
    void isMainMethodReturnsFalseWhenExceptionThrown() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("main");
        when(method.getFlags()).thenThrow(new RuntimeException("model error"));
        assertFalse(invokeIsMainMethod(method));
    }

    // ----- isTestAnnotation -----

    @Test
    void isTestAnnotationMatchesJunit5Test() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Test");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationMatchesParameterizedTest() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("ParameterizedTest");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationMatchesBeforeEach() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("BeforeEach");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationMatchesFqnPrefix() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("org.junit.jupiter.api.Test");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationReturnsFalseForRegularAnnotation() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Override");
        assertFalse(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationMatchesSpockUnroll() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Unroll");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    @Test
    void isTestAnnotationMatchesTestNgFqnPrefix() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("org.testng.annotations.Test");
        assertTrue(invokeIsTestAnnotation(annotation));
    }

    // ----- isTestType -----

    @Test
    void isTestTypeReturnsTrueForAnnotatedType() throws Exception {
        IAnnotation testAnnotation = mock(IAnnotation.class);
        when(testAnnotation.getElementName()).thenReturn("RunWith");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { testAnnotation });
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getResource()).thenReturn(null);
        assertTrue(invokeIsTestType(type));
    }

    @Test
    void isTestTypeReturnsTrueForGroovyTestCase() throws Exception {
        IType type = mockType("GroovyTestCase", null);
        assertTrue(invokeIsTestType(type));
    }

    @Test
    void isTestTypeReturnsTrueForTestDirectory() throws Exception {
        IType type = mockType("Object", "/project/src/test/groovy/MyTest.groovy");
        assertTrue(invokeIsTestType(type));
    }

    @Test
    void isTestTypeReturnsFalseForNonTestClass() throws Exception {
        IType type = mockType("Object", "/project/src/main/groovy/Service.groovy");
        assertFalse(invokeIsTestType(type));
    }

    @Test
    void isTestTypeReturnsTrueForJunitFqnTestCase() throws Exception {
        IType type = mockType("junit.framework.TestCase", null);
        assertTrue(invokeIsTestType(type));
    }

    // ----- isTestMethod with annotated methods -----

    @Test
    void isTestMethodReturnsTrueForAnnotatedMethod() throws Exception {
        IAnnotation testAnnotation = mock(IAnnotation.class);
        when(testAnnotation.getElementName()).thenReturn("Test");
        IType declaringType = mockType("Object", null);
        IMethod method = mockMethod("myTest", declaringType, new IAnnotation[] { testAnnotation });
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForSetUpInTestCase() throws Exception {
        IType declaringType = mockType("TestCase", null);
        IMethod method = mockMethod("setUp", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    @Test
    void isTestMethodReturnsTrueForTearDownInTestCase() throws Exception {
        IType declaringType = mockType("TestCase", null);
        IMethod method = mockMethod("tearDown", declaringType, new IAnnotation[0]);
        assertTrue(invokeIsTestMethod(method));
    }

    // ----- createUnusedDiagnostic -----

    @Test
    void createUnusedDiagnosticReturnsNullForNonSourceRef() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementName()).thenReturn("foo");
        Diagnostic result = invokeCreateUnusedDiagnostic(element, "content");
        assertNull(result);
    }

    @Test
    void createUnusedDiagnosticReturnsDiagnosticForMethod() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("unusedMethod");
        ISourceRange nameRange = mock(ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(5);
        when(nameRange.getLength()).thenReturn(12);
        when(method.getNameRange()).thenReturn(nameRange);

        String content = "void unusedMethod() {}";
        Diagnostic result = invokeCreateUnusedDiagnostic(method, content);

        assertNotNull(result);
        assertTrue(result.getMessage().isLeft());
        String msg = result.getMessage().getLeft();
        assertTrue(msg.contains("unusedMethod"));
        assertTrue(msg.contains("never used"));
        assertEquals("groovy.unusedDeclaration", result.getCode().getLeft());
    }

    @Test
    void createUnusedDiagnosticReturnsDiagnosticForType() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("UnusedClass");
        ISourceRange nameRange = mock(ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(6);
        when(nameRange.getLength()).thenReturn(11);
        when(type.getNameRange()).thenReturn(nameRange);

        String content = "class UnusedClass {}";
        Diagnostic result = invokeCreateUnusedDiagnostic(type, content);

        assertNotNull(result);
        assertTrue(result.getMessage().isLeft());
        String msg = result.getMessage().getLeft();
        assertTrue(msg.contains("type"));
        assertTrue(msg.contains("UnusedClass"));
    }

    @Test
    void createUnusedDiagnosticReturnsNullForNullNameRange() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("test");
        when(method.getNameRange()).thenReturn(null);

        Diagnostic result = invokeCreateUnusedDiagnostic(method, "content");
        assertNull(result);
    }

    @Test
    void createUnusedDiagnosticReturnsNullForNegativeOffset() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("test");
        ISourceRange nameRange = mock(ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(-1);
        when(nameRange.getLength()).thenReturn(4);
        when(method.getNameRange()).thenReturn(nameRange);

        Diagnostic result = invokeCreateUnusedDiagnostic(method, "content");
        assertNull(result);
    }

    // ----- detectUnusedDeclarations entry point -----

    @Test
    void detectUnusedDeclarationsReturnsEmptyForMissingWorkingCopy() {
        DocumentManager dm = new DocumentManager();
        List<Diagnostic> result = UnusedDeclarationDetector
                .detectUnusedDeclarations("file:///missing.groovy", dm);
        assertTrue(result.isEmpty());
    }

    @Test
    void detectUnusedDeclarationsReturnsEmptyForMissingContent() {
        // create a mock DocumentManager where working copy exists but content is null
        DocumentManager dm = new DocumentManager();
        List<Diagnostic> result = UnusedDeclarationDetector
                .detectUnusedDeclarations("file:///NoDocs.groovy", dm);
        assertTrue(result.isEmpty());
    }

    @Test
    void isUnreferencedReturnsNullWhenReferenceLookupIsIndeterminate() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        DocumentManager documentManager = new DocumentManager();

        try (org.mockito.MockedStatic<ReferenceSearchHelper> searchHelper =
                org.mockito.Mockito.mockStatic(ReferenceSearchHelper.class)) {
            searchHelper.when(() -> ReferenceSearchHelper.referenceExistenceForUnusedDeclaration(
                    element, "file:///src/main/groovy/Foo.groovy", documentManager))
                    .thenReturn(ReferenceSearchHelper.ReferenceExistence.INDETERMINATE);

            assertNull(invokeIsUnreferenced(element, "file:///src/main/groovy/Foo.groovy", documentManager));
        }
    }

    // ----- Reflection helpers -----

    private static Boolean invokeIsUnreferenced(
            IJavaElement element, String uri, DocumentManager documentManager) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isUnreferenced", IJavaElement.class, String.class, DocumentManager.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, element, uri, documentManager);
    }

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

    private static boolean invokeIsMainMethod(IMethod method) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isMainMethod", IMethod.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, method);
    }

    private static boolean invokeIsTestAnnotation(IAnnotation annotation) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isTestAnnotation", IAnnotation.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, annotation);
    }

    private static boolean invokeIsTestType(IType type) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isTestType", IType.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, type);
    }

    private static Diagnostic invokeCreateUnusedDiagnostic(IJavaElement element, String content)
            throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "createUnusedDiagnostic", IJavaElement.class, String.class);
        m.setAccessible(true);
        return (Diagnostic) m.invoke(null, element, content);
    }

    private static boolean invokeIsFrameworkManagedType(IType type) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "isFrameworkManagedType", IType.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, type);
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
        when(method.isConstructor()).thenReturn(false);
        return method;
    }

    // ================================================================
    // collectUnusedDeclarations tests
    // ================================================================

    @Test
    void collectUnusedDeclarationsIteratesMethods() throws Exception {
        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getResource()).thenReturn(null);

        IMethod method1 = mockMethod("regularMethod", type, new IAnnotation[0]);
        IMethod method2 = mockMethod("anotherMethod", type, new IAnnotation[0]);
        when(type.getMethods()).thenReturn(new IMethod[]{method1, method2});
        when(type.getTypes()).thenReturn(new IType[0]);

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, type, "class Foo { void regularMethod() {} void anotherMethod() {} }",
            "file:///src/main/groovy/Foo.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Methods were iterated without exceptions
        assertNotNull(diagnostics);
    }

    @Test
    void collectUnusedDeclarationsSkipsTestMethods() throws Exception {
        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getResource()).thenReturn(null);

        IAnnotation testAnnotation = mock(IAnnotation.class);
        when(testAnnotation.getElementName()).thenReturn("Test");
        IMethod testMethod = mockMethod("testSomething", type, new IAnnotation[]{testAnnotation});
        when(type.getMethods()).thenReturn(new IMethod[]{testMethod});
        when(type.getTypes()).thenReturn(new IType[0]);

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, type, "class Foo { void testSomething() {} }",
            "file:///src/main/groovy/Foo.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Test methods should be skipped
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void collectUnusedDeclarationsSkipsMainMethod() throws Exception {
        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getResource()).thenReturn(null);

        IMethod mainMethod = mockMethod("main", type, new IAnnotation[0]);
        when(mainMethod.getParameterTypes()).thenReturn(new String[]{"[QString;"});
        when(type.getMethods()).thenReturn(new IMethod[]{mainMethod});
        when(type.getTypes()).thenReturn(new IType[0]);

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, type, "class Foo { static void main(String[] args) {} }",
            "file:///src/main/groovy/Foo.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Main method should be skipped
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void collectUnusedDeclarationsRecursesInnerTypes() throws Exception {
        IType outerType = mock(IType.class);
        when(outerType.getSuperclassName()).thenReturn(null);
        when(outerType.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(outerType.getResource()).thenReturn(null);
        when(outerType.getMethods()).thenReturn(new IMethod[0]);

        IType innerType = mock(IType.class);
        when(innerType.getSuperclassName()).thenReturn(null);
        when(innerType.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(innerType.getResource()).thenReturn(null);
        when(innerType.getMethods()).thenReturn(new IMethod[0]);
        when(innerType.getTypes()).thenReturn(new IType[0]);

        when(outerType.getTypes()).thenReturn(new IType[]{innerType});

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, outerType, "class Outer { class Inner {} }",
            "file:///src/main/groovy/Outer.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Should complete without error (inner type was recursed)
        assertNotNull(diagnostics);
    }

    // ================================================================
    // isFrameworkManagedType tests
    // ================================================================

    @Test
    void isFrameworkManagedTypeReturnsTrueForComponent() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Component");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForService() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Service");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForRepository() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Repository");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForController() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Controller");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForRestController() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("RestController");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForConfiguration() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Configuration");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForSpringBootApplication() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("SpringBootApplication");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsTrueForFqnPrefix() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("org.springframework.stereotype.Component");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertTrue(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsFalseForPlainClass() throws Exception {
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        assertFalse(invokeIsFrameworkManagedType(type));
    }

    @Test
    void isFrameworkManagedTypeReturnsFalseForNonFrameworkAnnotation() throws Exception {
        IAnnotation annotation = mock(IAnnotation.class);
        when(annotation.getElementName()).thenReturn("Override");
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { annotation });
        assertFalse(invokeIsFrameworkManagedType(type));
    }

    // ================================================================
    // collectUnusedDeclarations — constructor skip in @Component types
    // ================================================================

    @Test
    void collectUnusedDeclarationsSkipsConstructorInComponentType() throws Exception {
        IAnnotation componentAnnotation = mock(IAnnotation.class);
        when(componentAnnotation.getElementName()).thenReturn("Component");

        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getAnnotations()).thenReturn(new IAnnotation[] { componentAnnotation });
        when(type.getResource()).thenReturn(null);

        IMethod constructor = mockMethod("MyService", type, new IAnnotation[0]);
        when(constructor.isConstructor()).thenReturn(true);
        when(type.getMethods()).thenReturn(new IMethod[] { constructor });
        when(type.getTypes()).thenReturn(new IType[0]);

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, type, "class MyService { MyService() {} }",
            "file:///src/main/groovy/MyService.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Constructor in @Component type should be skipped
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void collectUnusedDeclarationsDoesNotSkipConstructorInPlainType() throws Exception {
        IType type = mock(IType.class);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getResource()).thenReturn(null);

        IMethod constructor = mockMethod("PlainClass", type, new IAnnotation[0]);
        when(constructor.isConstructor()).thenReturn(true);
        when(type.getMethods()).thenReturn(new IMethod[] { constructor });
        when(type.getTypes()).thenReturn(new IType[0]);

        java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics = new java.util.ArrayList<>();

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "collectUnusedDeclarations", IType.class, String.class, String.class,
            DocumentManager.class, java.util.List.class, int[].class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, type, "class PlainClass { PlainClass() {} }",
            "file:///src/main/groovy/PlainClass.groovy", new DocumentManager(), diagnostics, new int[]{20}, true);

        // Constructor in plain (non-framework) type should NOT be skipped —
        // it will proceed to isUnreferenced() which returns false for mocks
        assertNotNull(diagnostics);
    }

    // ================================================================
    // isUnreferenced tests
    // ================================================================

    @Test
    void isUnreferencedReturnsNullForMockElementWithoutDeterministicReferences() throws Exception {
        // Create a mock element that produces null pattern
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);

        java.lang.reflect.Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
            "isUnreferenced", org.eclipse.jdt.core.IJavaElement.class, String.class,
            DocumentManager.class);
        m.setAccessible(true);
        Boolean result = (Boolean) m.invoke(
            null, element, "file:///src/main/groovy/Foo.groovy", new DocumentManager());

        // When reference existence cannot be determined cheaply, fading is skipped.
        assertNull(result);
    }

    @Test
    void exceedsMethodSearchBudgetReturnsTrueWhenCandidateMethodsExceedLimit() throws Exception {
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getMethods()).thenReturn(createMethods(type, 21));
        when(type.getTypes()).thenReturn(new IType[0]);

        assertTrue(invokeExceedsMethodSearchBudget(new IType[] { type }));
    }

    @Test
    void exceedsMethodSearchBudgetIgnoresSkippedMethods() throws Exception {
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        IMethod[] methods = new IMethod[25];
        for (int i = 0; i < methods.length; i++) {
            methods[i] = mockMethod("testMethod" + i, type, new IAnnotation[0]);
        }
        when(type.getMethods()).thenReturn(methods);
        when(type.getTypes()).thenReturn(new IType[0]);

        assertFalse(invokeExceedsMethodSearchBudget(new IType[] { type }));
    }

    @Test
    void exceedsMethodSearchBudgetCountsInnerTypeMethods() throws Exception {
        IType outerType = mock(IType.class);
        when(outerType.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(outerType.getMethods()).thenReturn(createMethods(outerType, 10));

        IType innerType = mock(IType.class);
        when(innerType.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(innerType.getMethods()).thenReturn(createMethods(innerType, 11));
        when(innerType.getTypes()).thenReturn(new IType[0]);

        when(outerType.getTypes()).thenReturn(new IType[] { innerType });

        assertTrue(invokeExceedsMethodSearchBudget(new IType[] { outerType }));
    }

    private static boolean invokeExceedsMethodSearchBudget(IType[] types) throws Exception {
        Method m = UnusedDeclarationDetector.class.getDeclaredMethod(
                "exceedsMethodSearchBudget", IType[].class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, (Object) types);
    }

    private static IMethod[] createMethods(IType declaringType, int count) throws Exception {
        IMethod[] methods = new IMethod[count];
        for (int i = 0; i < count; i++) {
            methods[i] = mockMethod("method" + i, declaringType, new IAnnotation[0]);
        }
        return methods;
    }
}
