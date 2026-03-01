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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class DiagnosticsProviderTest {

    @Test
    void shouldSkipDiagnosticFiltersTypeCollisionByIdOrMessage() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        boolean skipById = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, org.eclipse.jdt.core.IJavaProject.class, org.eclipse.jdt.core.ICompilationUnit.class },
                new Object[] { 16777539, "anything", null, null });

        boolean skipByMessage = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, org.eclipse.jdt.core.IJavaProject.class, org.eclipse.jdt.core.ICompilationUnit.class },
                new Object[] { -1, "The type User is already defined", null, null });

        boolean notSkipped = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, org.eclipse.jdt.core.IJavaProject.class, org.eclipse.jdt.core.ICompilationUnit.class },
                new Object[] { -1, "Some other problem", null, null });

        assertTrue(skipById);
        assertTrue(skipByMessage);
        assertFalse(notSkipped);
    }

    @Test
    void extractMissingTypeNameParsesNoSuchClassMessage() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        String extracted = (String) invoke(
                provider,
                "extractMissingTypeName",
                new Class<?>[] { String.class },
                new Object[] { "No such class: com.example.Missing -- while compiling" });

        String absent = (String) invoke(
                provider,
                "extractMissingTypeName",
                new Class<?>[] { String.class },
                new Object[] { "Unexpected message" });

        assertEquals("com.example.Missing", extracted);
        assertNull(absent);
    }

    @Test
    void shouldSkipDiagnosticFiltersTransformLoaderFailureWhenTypeExists() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);
        when(resolvedType.exists()).thenReturn(true);
        when(javaProject.findType("com.example.Available")).thenReturn(resolvedType);

        String message = "No such class: com.example.Available -- while resolving "
                + "JDTClassNode.getTypeClass() cannot locate it using transform loader";

        boolean skipped = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { -1, message, javaProject, null });

        assertTrue(skipped);
    }

    @Test
    void shouldSkipDiagnosticFiltersUnresolvedClassWhenResolvableFromContext() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        ICompilationUnit contextUnit = mock(ICompilationUnit.class);
        IPackageDeclaration pkg = mock(IPackageDeclaration.class);
        IImportDeclaration onDemandImport = mock(IImportDeclaration.class);
        IType resolvedType = mock(IType.class);

        when(pkg.getElementName()).thenReturn("com.example");
        when(contextUnit.getPackageDeclarations()).thenReturn(new IPackageDeclaration[] { pkg });

        when(onDemandImport.getElementName()).thenReturn("com.other");
        when(onDemandImport.isOnDemand()).thenReturn(true);
        when(contextUnit.getImports()).thenReturn(new IImportDeclaration[] { onDemandImport });

        when(resolvedType.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("com.example.Missing")).thenReturn(resolvedType);

        boolean skipped = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { -1, "unable to resolve class Missing", javaProject, contextUnit });

        assertTrue(skipped);
    }

    @Test
    void shouldSkipDiagnosticDoesNotFilterUnresolvedClassWhenTypeIsMissing() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        when(javaProject.findType(anyString())).thenReturn(null);

        boolean skipped = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { -1, "unable to resolve class Missing", javaProject, null });

        assertFalse(skipped);
    }

    @Test
    void typeExistsInProjectContextUsesJavaLangFallbackForSimpleName() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        IType javaLangString = mock(IType.class);

        when(javaLangString.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("java.lang.String")).thenReturn(javaLangString);

        boolean exists = (boolean) invoke(
                provider,
                "typeExistsInProjectContext",
                new Class<?>[] { String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { "String", javaProject, null });

        assertTrue(exists);
    }

    @Test
    void offsetRangeToLspRangeConvertsOffsetsAcrossLines() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        Range range = (Range) invoke(
                provider,
                "offsetRangeToLspRange",
                new Class<?>[] { String.class, int.class, int.class },
                new Object[] { "ab\ncde\n", 1, 4 });

        assertEquals(0, range.getStart().getLine());
        assertEquals(1, range.getStart().getCharacter());
        assertEquals(1, range.getEnd().getLine());
        assertEquals(1, range.getEnd().getCharacter());
    }

    @Test
    void collectFromGroovyCompilerAddsSyntaxErrorDiagnostics() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsProviderFallbackTest.groovy";
        String source = "class Broken { def x = }";
        documentManager.didOpen(uri, source);

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        List<Diagnostic> diagnostics = new ArrayList<>();

        invoke(
                provider,
                "collectFromGroovyCompiler",
                new Class<?>[] { String.class, List.class },
                new Object[] { uri, diagnostics });

        assertFalse(diagnostics.isEmpty());
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error));
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.getMessage() != null));
        assertNotNull(diagnostics.get(0).getRange());

        documentManager.didClose(uri);
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
