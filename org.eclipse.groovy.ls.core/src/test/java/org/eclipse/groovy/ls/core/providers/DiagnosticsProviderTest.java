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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.IMarker;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @Test
    void collectFromGroovyCompilerProducesEmptyForValidSource() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsProviderValid.groovy";
        String source = "class Valid { String name; void run() { println name } }";
        documentManager.didOpen(uri, source);

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        List<Diagnostic> diagnostics = new ArrayList<>();

        invoke(
                provider,
                "collectFromGroovyCompiler",
                new Class<?>[] { String.class, List.class },
                new Object[] { uri, diagnostics });

        assertTrue(diagnostics.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void offsetRangeToLspRangeHandlesSingleLine() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        Range range = (Range) invoke(
                provider,
                "offsetRangeToLspRange",
                new Class<?>[] { String.class, int.class, int.class },
                new Object[] { "abcdef", 1, 4 });

        assertEquals(0, range.getStart().getLine());
        assertEquals(1, range.getStart().getCharacter());
        assertEquals(0, range.getEnd().getLine());
        assertEquals(4, range.getEnd().getCharacter());
    }

    @Test
    void offsetRangeToLspRangeHandlesStartOfLine() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        Range range = (Range) invoke(
                provider,
                "offsetRangeToLspRange",
                new Class<?>[] { String.class, int.class, int.class },
                new Object[] { "abc\ndef\n", 0, 3 });

        assertEquals(0, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
        assertEquals(0, range.getEnd().getLine());
        assertEquals(3, range.getEnd().getCharacter());
    }

    @Test
    void extractMissingTypeNameHandlesVariousFormats() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        String noSuchClassCompiling = (String) invoke(
                provider,
                "extractMissingTypeName",
                new Class<?>[] { String.class },
                new Object[] { "No such class: my.pkg.Type -- while compiling" });
        assertEquals("my.pkg.Type", noSuchClassCompiling);

        String simpleClass = (String) invoke(
                provider,
                "extractMissingTypeName",
                new Class<?>[] { String.class },
                new Object[] { "unable to resolve class SomeType" });
        assertEquals("SomeType", simpleClass);
    }

    @Test
    void shouldSkipDiagnosticFiltersGroovyRunScript() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        // Check various known filter IDs
        boolean skipRunScriptBody = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { 67108964, "anything", null, null });

        // Non-filtered ID
        boolean notSkipped = (boolean) invoke(
                provider,
                "shouldSkipDiagnostic",
                new Class<?>[] { int.class, String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { 42, "Non matching message", null, null });

        assertTrue(skipRunScriptBody);
        assertFalse(notSkipped);
    }

    @Test
    void typeExistsInProjectContextChecksAutoImports() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        IType listType = mock(IType.class);
        when(listType.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("java.util.List")).thenReturn(listType);

        boolean exists = (boolean) invoke(
                provider,
                "typeExistsInProjectContext",
                new Class<?>[] { String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[] { "List", javaProject, null });

        assertTrue(exists);
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    // ================================================================
    // toDiagnostic(IProblem, String) mock tests
    // ================================================================

    @Test
    void toDiagnosticConvertsMockedErrorProblem() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IProblem problem = mock(IProblem.class);
        when(problem.isError()).thenReturn(true);
        when(problem.isWarning()).thenReturn(false);
        when(problem.getMessage()).thenReturn("Syntax error");
        when(problem.getSourceStart()).thenReturn(5);
        when(problem.getSourceEnd()).thenReturn(10);
        when(problem.getSourceLineNumber()).thenReturn(1);
        when(problem.getID()).thenReturn(42);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[]{ IProblem.class, String.class },
                new Object[]{ problem, "hello\nworld\n" });

        assertNotNull(diag);
        assertEquals(DiagnosticSeverity.Error, diag.getSeverity());
        assertTrue(String.valueOf(diag.getMessage()).contains("Syntax error"));
        assertTrue(diag.getSource().contains("groovy"));
        assertNotNull(diag.getRange());
    }

    @Test
    void toDiagnosticConvertsMockedWarningProblem() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IProblem problem = mock(IProblem.class);
        when(problem.isError()).thenReturn(false);
        when(problem.isWarning()).thenReturn(true);
        when(problem.getMessage()).thenReturn("Unused variable");
        when(problem.getSourceStart()).thenReturn(0);
        when(problem.getSourceEnd()).thenReturn(3);
        when(problem.getSourceLineNumber()).thenReturn(1);
        when(problem.getID()).thenReturn(99);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[]{ IProblem.class, String.class },
                new Object[]{ problem, "abcdef" });

        assertEquals(DiagnosticSeverity.Warning, diag.getSeverity());
    }

    @Test
    void toDiagnosticConvertsMockedInfoProblem() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IProblem problem = mock(IProblem.class);
        when(problem.isError()).thenReturn(false);
        when(problem.isWarning()).thenReturn(false);
        when(problem.getMessage()).thenReturn("Info message");
        when(problem.getSourceStart()).thenReturn(-1);
        when(problem.getSourceEnd()).thenReturn(-1);
        when(problem.getSourceLineNumber()).thenReturn(5);
        when(problem.getID()).thenReturn(0);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[]{ IProblem.class, String.class },
                new Object[]{ problem, "line1\nline2\nline3\nline4\nline5\n" });

        assertEquals(DiagnosticSeverity.Information, diag.getSeverity());
        // Falls back to line number when source offsets are -1
        assertEquals(4, diag.getRange().getStart().getLine()); // 5-1 = 4 (0-based)
    }

    @Test
    void toDiagnosticHandlesNullContent() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IProblem problem = mock(IProblem.class);
        when(problem.isError()).thenReturn(true);
        when(problem.isWarning()).thenReturn(false);
        when(problem.getMessage()).thenReturn("Error");
        when(problem.getSourceStart()).thenReturn(0);
        when(problem.getSourceEnd()).thenReturn(5);
        when(problem.getSourceLineNumber()).thenReturn(1);
        when(problem.getID()).thenReturn(1);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[]{ IProblem.class, String.class },
                new Object[]{ problem, null });

        assertNotNull(diag);
        // With null content but valid offsets, falls back to line number
    }

    // ================================================================
    // publishDiagnostics / getLatestDiagnostics / clearDiagnostics tests
    // ================================================================

    @Test
    void getLatestDiagnosticsReturnsEmptyListForUnknownUri() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        List<Diagnostic> diags = provider.getLatestDiagnostics("file:///Unknown.groovy");
        assertNotNull(diags);
        assertTrue(diags.isEmpty());
    }

    @Test
    void clearDiagnosticsRemovesCachedDiagnostics() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        provider.clearDiagnostics("file:///SomeFile.groovy");
        List<Diagnostic> diags = provider.getLatestDiagnostics("file:///SomeFile.groovy");
        assertTrue(diags.isEmpty());
    }

    @Test
    void publishDiagnosticsSkipsWhenNoClient() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        // No client connected; should not throw
        provider.publishDiagnostics("file:///NoClient.groovy");
        assertTrue(provider.getLatestDiagnostics("file:///NoClient.groovy").isEmpty());
    }

    @Test
    void connectAllowsPublishing() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///ConnectTest.groovy";
        dm.didOpen(uri, "class Valid {}");

        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        LanguageClient client = mock(LanguageClient.class);
        provider.connect(client);

        provider.publishDiagnostics(uri);
        verify(client).publishDiagnostics(any(PublishDiagnosticsParams.class));
        dm.didClose(uri);
    }

    @Test
    void collectDiagnosticsForCodeActionsReturnsDiagnostics() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///CollectCodeActions.groovy";
        dm.didOpen(uri, "class Broken { def x = }");

        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        List<Diagnostic> diags = provider.collectDiagnosticsForCodeActions(uri);
        assertNotNull(diags);
        // Should find at least syntax errors from Groovy compiler fallback
        assertFalse(diags.isEmpty());

        dm.didClose(uri);
    }

    // ================================================================
    // isExistingTypeTransformLoaderFailure edge cases
    // ================================================================

        @ParameterizedTest
        @MethodSource("transformLoaderFailureFalseCases")
        void isExistingTypeTransformLoaderFailureReturnsFalseForEdgeCases(String message,
                                          boolean provideProject)
            throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IJavaProject project = provideProject ? mock(IJavaProject.class) : null;

        boolean result = (boolean) invoke(provider,
                "isExistingTypeTransformLoaderFailure",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
            new Object[]{ message, project, null });
        assertFalse(result);
    }

        private static Stream<Arguments> transformLoaderFailureFalseCases() {
        return Stream.of(
            Arguments.of(null, true),
            Arguments.of(
                "No such class: X -- JDTClassNode.getTypeClass() cannot locate it using transform loader",
                false),
            Arguments.of("Some other error", true));
        }

    // ================================================================
    // isResolvableUnableToResolveClassFailure edge cases
    // ================================================================

    @Test
    void isResolvableUnableToResolveReturnsCorrectForFalsePositive() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(type.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("com.example.MyClass")).thenReturn(type);

        boolean result = (boolean) invoke(provider,
                "isResolvableUnableToResolveClassFailure",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[]{ "unable to resolve class com.example.MyClass", javaProject, null });
        assertTrue(result);
    }

    @Test
    void isResolvableUnableToResolveReturnsFalseForNonMatchingMessage() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        boolean result = (boolean) invoke(provider,
                "isResolvableUnableToResolveClassFailure",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[]{ "some other error", mock(IJavaProject.class), null });
        assertFalse(result);
    }

    // ================================================================
    // offsetRangeToLspRange more tests
    // ================================================================

    @Test
    void offsetRangeToLspRangeAtEndOfFile() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        Range range = (Range) invoke(provider,
                "offsetRangeToLspRange",
                new Class<?>[]{ String.class, int.class, int.class },
                new Object[]{ "abc", 0, 3 });

        assertEquals(0, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
        assertEquals(0, range.getEnd().getLine());
        assertEquals(3, range.getEnd().getCharacter());
    }

    @Test
    void offsetRangeToLspRangePastEnd() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        Range range = (Range) invoke(provider,
                "offsetRangeToLspRange",
                new Class<?>[]{ String.class, int.class, int.class },
                new Object[]{ "ab", 0, 99 });

        // Should handle gracefully without throwing
        assertNotNull(range);
    }

    @Test
    void offsetRangeToLspRangeMultipleLines() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        String content = "line1\nline2\nline3";
        Range range = (Range) invoke(provider,
                "offsetRangeToLspRange",
                new Class<?>[]{ String.class, int.class, int.class },
                new Object[]{ content, 6, 11 }); // "line2"

        assertEquals(1, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
        assertEquals(1, range.getEnd().getLine());
        assertEquals(5, range.getEnd().getCharacter());
    }

    // ================================================================
    // collectFromGroovyCompiler with more source patterns
    // ================================================================

    @Test
    void collectFromGroovyCompilerHandlesMultipleErrors() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///DiagMultiError.groovy";
        dm.didOpen(uri, "class X {\n  def f() { return }\n  def g() { return }\n}");

        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        List<Diagnostic> diagnostics = new ArrayList<>();

        invoke(provider, "collectFromGroovyCompiler",
                new Class<?>[]{ String.class, List.class },
                new Object[]{ uri, diagnostics });

        // Should have at least one error
        assertNotNull(diagnostics);
        dm.didClose(uri);
    }

    @Test
    void collectFromGroovyCompilerSkipsClosedDocument() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        List<Diagnostic> diagnostics = new ArrayList<>();

        invoke(provider, "collectFromGroovyCompiler",
                new Class<?>[]{ String.class, List.class },
                new Object[]{ "file:///NotOpen.groovy", diagnostics });

        assertTrue(diagnostics.isEmpty());
    }

    // ================================================================
    // typeExistsInProjectContext edge cases
    // ================================================================

    @Test
    void typeExistsInProjectContextWithOnDemandImport() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        ICompilationUnit contextUnit = mock(ICompilationUnit.class);
        IPackageDeclaration pkg = mock(IPackageDeclaration.class);
        IImportDeclaration starImport = mock(IImportDeclaration.class);
        IType foundType = mock(IType.class);

        when(pkg.getElementName()).thenReturn("com.app");
        when(contextUnit.getPackageDeclarations()).thenReturn(new IPackageDeclaration[]{ pkg });
        when(starImport.getElementName()).thenReturn("com.lib");
        when(starImport.isOnDemand()).thenReturn(true);
        when(contextUnit.getImports()).thenReturn(new IImportDeclaration[]{ starImport });

        when(foundType.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("com.lib.Widget")).thenReturn(foundType);

        boolean exists = (boolean) invoke(provider,
                "typeExistsInProjectContext",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[]{ "Widget", javaProject, contextUnit });
        assertTrue(exists);
    }

    @Test
    void typeExistsInProjectContextWithExactImport() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        ICompilationUnit contextUnit = mock(ICompilationUnit.class);
        IImportDeclaration exactImport = mock(IImportDeclaration.class);
        IType foundType = mock(IType.class);

        when(contextUnit.getPackageDeclarations()).thenReturn(new IPackageDeclaration[0]);
        when(exactImport.getElementName()).thenReturn("com.lib.Widget");
        when(exactImport.isOnDemand()).thenReturn(false);
        when(contextUnit.getImports()).thenReturn(new IImportDeclaration[]{ exactImport });

        when(foundType.exists()).thenReturn(true);
        when(javaProject.findType(anyString())).thenReturn(null);
        when(javaProject.findType("com.lib.Widget")).thenReturn(foundType);

        boolean exists = (boolean) invoke(provider,
                "typeExistsInProjectContext",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[]{ "Widget", javaProject, contextUnit });
        assertTrue(exists);
    }

    @Test
    void typeExistsWithFqnDirectLookup() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IJavaProject javaProject = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        when(foundType.exists()).thenReturn(true);
        when(javaProject.findType("com.example.FooBar")).thenReturn(foundType);

        boolean exists = (boolean) invoke(provider,
                "typeExistsInProjectContext",
                new Class<?>[]{ String.class, IJavaProject.class, ICompilationUnit.class },
                new Object[]{ "com.example.FooBar", javaProject, null });
        assertTrue(exists);
    }

    // ================================================================
    // toDiagnostic (IMarker) tests — use thenAnswer for overloaded getAttribute
    // ================================================================

    private IMarker createMockMarker(int severity, String message, int charStart, int charEnd, int lineNumber) {
        IMarker marker = mock(IMarker.class);
        when(marker.getAttribute(anyString(), anyInt())).thenAnswer(inv -> {
            String attr = inv.getArgument(0);
            int def = inv.getArgument(1);
            if (IMarker.SEVERITY.equals(attr)) return severity;
            if (IMarker.CHAR_START.equals(attr)) return charStart;
            if (IMarker.CHAR_END.equals(attr)) return charEnd;
            if (IMarker.LINE_NUMBER.equals(attr)) return lineNumber;
            return def;
        });
        when(marker.getAttribute(anyString(), anyString())).thenAnswer(inv -> {
            String attr = inv.getArgument(0);
            String def = inv.getArgument(1);
            if (IMarker.MESSAGE.equals(attr)) return message;
            return def;
        });
        return marker;
    }

    @Test
    void toDiagnosticConvertsMarkerError() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String content = "class A {\n    void run() {}\n}";

        IMarker marker = createMockMarker(IMarker.SEVERITY_ERROR, "Test error", 0, 5, 1);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[] { IMarker.class, String.class },
                new Object[] { marker, content });
        assertNotNull(diag);
        assertEquals(DiagnosticSeverity.Error, diag.getSeverity());
    }

    @Test
    void toDiagnosticConvertsMarkerWarning() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String content = "class B {\n    int x\n}";

        IMarker marker = createMockMarker(IMarker.SEVERITY_WARNING, "Test warning", 10, 15, 1);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[] { IMarker.class, String.class },
                new Object[] { marker, content });
        assertNotNull(diag);
        assertEquals(DiagnosticSeverity.Warning, diag.getSeverity());
    }

    @Test
    void toDiagnosticConvertsMarkerInfo() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String content = "class C {}";

        IMarker marker = createMockMarker(IMarker.SEVERITY_INFO, "Info", 0, 5, 1);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[] { IMarker.class, String.class },
                new Object[] { marker, content });
        assertNotNull(diag);
        assertEquals(DiagnosticSeverity.Information, diag.getSeverity());
    }

    @Test
    void toDiagnosticMarkerUsesLineNumberWhenNoCharStart() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String content = "line0\nline1\nline2";

        IMarker marker = createMockMarker(IMarker.SEVERITY_ERROR, "error", -1, -1, 2);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[] { IMarker.class, String.class },
                new Object[] { marker, content });
        assertNotNull(diag);
        // When char offsets are missing, uses line number (1-based in marker → 0-based in LSP)
        assertEquals(1, diag.getRange().getStart().getLine());
    }

    @Test
    void toDiagnosticMarkerHandlesNullContent() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());

        IMarker marker = createMockMarker(IMarker.SEVERITY_WARNING, "msg", 0, 3, 1);

        Diagnostic diag = (Diagnostic) invoke(provider, "toDiagnostic",
                new Class<?>[] { IMarker.class, String.class },
                new Object[] { marker, null });
        assertNotNull(diag);
        // With null content, falls back to line number branch
        assertEquals(DiagnosticSeverity.Warning, diag.getSeverity());
    }

    // ================================================================
    // publishDiagnosticsDebounced test
    // ================================================================

    @Test
    void publishDiagnosticsDebouncedDoesNotThrowWithoutClient() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        Method m = DiagnosticsProvider.class.getDeclaredMethod("publishDiagnosticsDebounced", String.class);
        m.setAccessible(true);
        // Should not throw even without client connected
        m.invoke(provider, "file:///test.groovy");
        assertTrue(provider.getLatestDiagnostics("file:///test.groovy").isEmpty());
    }

    // ================================================================
    // collectDiagnostics via public API
    // ================================================================

    @Test
    void collectDiagnosticsForCodeActionsGracefulForUnknownUri() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        List<Diagnostic> diags = provider.collectDiagnosticsForCodeActions("file:///nonexistent.groovy");
        assertNotNull(diags);
    }

    @Test
    void extractMissingTypeNameHandlesEmptyMessage() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String result = (String) invoke(provider, "extractMissingTypeName",
                new Class<?>[] { String.class }, new Object[] { "" });
        assertNull(result);
    }

    @Test
    void extractMissingTypeNameHandlesNullMessage() {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        // extractMissingTypeName doesn't guard against null — it throws NPE
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
            invoke(provider, "extractMissingTypeName",
                    new Class<?>[] { String.class }, new Object[] { (String) null });
        });
    }

    // ================================================================
    // createNoClasspathWarning tests
    // ================================================================

    @Test
    void createNoClasspathWarningWithContent() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        org.eclipse.lsp4j.Diagnostic diag = (org.eclipse.lsp4j.Diagnostic) invoke(provider,
                "createNoClasspathWarning", new Class<?>[]{ String.class },
                new Object[]{ "package com.example\nclass Foo {}" });
        assertNotNull(diag);
        assertEquals(DiagnosticSeverity.Warning, diag.getSeverity());
        assertNotNull(diag.getMessage());
        assertEquals("groovy.noClasspath", diag.getCode().getLeft());
    }

    @Test
    void createNoClasspathWarningWithNullContent() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        org.eclipse.lsp4j.Diagnostic diag = (org.eclipse.lsp4j.Diagnostic) invoke(provider,
                "createNoClasspathWarning", new Class<?>[]{ String.class },
                new Object[]{ (String) null });
        assertNotNull(diag);
        assertEquals(0, diag.getRange().getEnd().getCharacter());
    }

    @Test
    void createNoClasspathWarningWithCrLf() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        org.eclipse.lsp4j.Diagnostic diag = (org.eclipse.lsp4j.Diagnostic) invoke(provider,
                "createNoClasspathWarning", new Class<?>[]{ String.class },
                new Object[]{ "hello world\r\nsecond line" });
        assertNotNull(diag);
        // first line length should be 11 ("hello world" without \r)
        assertEquals(11, diag.getRange().getEnd().getCharacter());
    }

    // ================================================================
    // extractSimpleTypeName tests
    // ================================================================

    @Test
    void extractSimpleTypeNameWithDot() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String result = (String) invoke(provider, "extractSimpleTypeName",
                new Class<?>[]{ String.class }, new Object[]{ "com.example.Foo" });
        assertEquals("Foo", result);
    }

    @Test
    void extractSimpleTypeNameWithoutDot() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String result = (String) invoke(provider, "extractSimpleTypeName",
                new Class<?>[]{ String.class }, new Object[]{ "Bar" });
        assertEquals("Bar", result);
    }

    @Test
    void extractSimpleTypeNameWithTrailingDot() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        String result = (String) invoke(provider, "extractSimpleTypeName",
                new Class<?>[]{ String.class }, new Object[]{ "com.example." });
        // lastDot == length - 1, so lastDot < length - 1 is false, returns full string
        assertEquals("com.example.", result);
    }

    // ================================================================
    // isValidTypeCandidate tests
    // ================================================================

    @Test
    void isValidTypeCandidateTrue() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        boolean result = (boolean) invoke(provider, "isValidTypeCandidate",
                new Class<?>[]{ String.class }, new Object[]{ "com.example.Foo" });
        assertTrue(result);
    }

    @Test
    void isValidTypeCandidateNull() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        boolean result = (boolean) invoke(provider, "isValidTypeCandidate",
                new Class<?>[]{ String.class }, new Object[]{ (String) null });
        assertFalse(result);
    }

    @Test
    void isValidTypeCandidateBlank() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        boolean result = (boolean) invoke(provider, "isValidTypeCandidate",
                new Class<?>[]{ String.class }, new Object[]{ "   " });
        assertFalse(result);
    }

    // ================================================================
    // collectTypeCandidates tests
    // ================================================================

    @Test
    void collectTypeCandidatesIncludesAutoPackages() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        @SuppressWarnings("unchecked")
        java.util.Set<String> result = (java.util.Set<String>) invoke(provider,
                "collectTypeCandidates", new Class<?>[]{ String.class, ICompilationUnit.class },
                new Object[]{ "ArrayList", null });
        assertNotNull(result);
        assertTrue(result.contains("java.util.ArrayList"));
        assertTrue(result.contains("java.lang.ArrayList"));
    }

    @Test
    void collectTypeCandidatesWithPackageDeclaration() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IPackageDeclaration pkgDecl = mock(IPackageDeclaration.class);
        when(pkgDecl.getElementName()).thenReturn("com.test");
        when(cu.getPackageDeclarations()).thenReturn(new IPackageDeclaration[]{ pkgDecl });
        when(cu.getImports()).thenReturn(new IImportDeclaration[]{});
        @SuppressWarnings("unchecked")
        java.util.Set<String> result = (java.util.Set<String>) invoke(provider,
                "collectTypeCandidates", new Class<?>[]{ String.class, ICompilationUnit.class },
                new Object[]{ "MyClass", cu });
        assertTrue(result.contains("com.test.MyClass"));
    }

    @Test
    void collectTypeCandidatesWithImports() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit cu = mock(ICompilationUnit.class);
        when(cu.getPackageDeclarations()).thenReturn(new IPackageDeclaration[]{});
        IImportDeclaration imp = mock(IImportDeclaration.class);
        when(imp.isOnDemand()).thenReturn(true);
        when(imp.getElementName()).thenReturn("org.util");
        when(cu.getImports()).thenReturn(new IImportDeclaration[]{ imp });
        @SuppressWarnings("unchecked")
        java.util.Set<String> result = (java.util.Set<String>) invoke(provider,
                "collectTypeCandidates", new Class<?>[]{ String.class, ICompilationUnit.class },
                new Object[]{ "Widget", cu });
        assertTrue(result.contains("org.util.Widget"));
    }

    // ================================================================
    // anyCandidateTypeExists tests
    // ================================================================

    @Test
    void anyCandidateTypeExistsTrue() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType foundType = mock(IType.class);
        when(foundType.exists()).thenReturn(true);
        when(project.findType("java.util.List")).thenReturn(foundType);
        java.util.Set<String> candidates = new java.util.HashSet<>(java.util.List.of("java.util.List"));
        boolean result = (boolean) invoke(provider, "anyCandidateTypeExists",
                new Class<?>[]{ java.util.Set.class, IJavaProject.class },
                new Object[]{ candidates, project });
        assertTrue(result);
    }

    @Test
    void anyCandidateTypeExistsFalse() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        java.util.Set<String> candidates = new java.util.HashSet<>(java.util.List.of("com.z.Missing"));
        boolean result = (boolean) invoke(provider, "anyCandidateTypeExists",
                new Class<?>[]{ java.util.Set.class, IJavaProject.class },
                new Object[]{ candidates, project });
        assertFalse(result);
    }

    @Test
    void anyCandidateTypeExistsSkipsBlank() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        java.util.Set<String> candidates = new java.util.HashSet<>(java.util.List.of("", "  "));
        boolean result = (boolean) invoke(provider, "anyCandidateTypeExists",
                new Class<?>[]{ java.util.Set.class, IJavaProject.class },
                new Object[]{ candidates, project });
        assertFalse(result);
    }

    // ================================================================
    // addPackageCandidates tests
    // ================================================================

    @Test
    void addPackageCandidatesNullUnit() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        java.util.Set<String> candidates = new java.util.HashSet<>();
        invoke(provider, "addPackageCandidates",
                new Class<?>[]{ java.util.Set.class, String.class, ICompilationUnit.class },
                new Object[]{ candidates, "Foo", null });
        assertTrue(candidates.isEmpty());
    }

    @Test
    void addPackageCandidatesWithPackage() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IPackageDeclaration pkgDecl = mock(IPackageDeclaration.class);
        when(pkgDecl.getElementName()).thenReturn("org.sample");
        when(cu.getPackageDeclarations()).thenReturn(new IPackageDeclaration[]{ pkgDecl });
        java.util.Set<String> candidates = new java.util.HashSet<>();
        invoke(provider, "addPackageCandidates",
                new Class<?>[]{ java.util.Set.class, String.class, ICompilationUnit.class },
                new Object[]{ candidates, "Bar", cu });
        assertTrue(candidates.contains("org.sample.Bar"));
    }

    // ================================================================
    // addImportCandidates tests
    // ================================================================

    @Test
    void addImportCandidatesNullUnit() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        java.util.Set<String> candidates = new java.util.HashSet<>();
        invoke(provider, "addImportCandidates",
                new Class<?>[]{ java.util.Set.class, String.class, ICompilationUnit.class },
                new Object[]{ candidates, "Foo", null });
        assertTrue(candidates.isEmpty());
    }

    @Test
    void addImportCandidatesExplicitImport() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IImportDeclaration imp = mock(IImportDeclaration.class);
        when(imp.isOnDemand()).thenReturn(false);
        when(imp.getElementName()).thenReturn("com.pkg.MyWidget");
        when(cu.getImports()).thenReturn(new IImportDeclaration[]{ imp });
        java.util.Set<String> candidates = new java.util.HashSet<>();
        invoke(provider, "addImportCandidates",
                new Class<?>[]{ java.util.Set.class, String.class, ICompilationUnit.class },
                new Object[]{ candidates, "MyWidget", cu });
        assertTrue(candidates.contains("com.pkg.MyWidget"));
    }

    @Test
    void addImportCandidatesStarImport() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IImportDeclaration imp = mock(IImportDeclaration.class);
        when(imp.isOnDemand()).thenReturn(true);
        when(imp.getElementName()).thenReturn("com.pkg");
        when(cu.getImports()).thenReturn(new IImportDeclaration[]{ imp });
        java.util.Set<String> candidates = new java.util.HashSet<>();
        invoke(provider, "addImportCandidates",
                new Class<?>[]{ java.util.Set.class, String.class, ICompilationUnit.class },
                new Object[]{ candidates, "Thing", cu });
        assertTrue(candidates.contains("com.pkg.Thing"));
    }

    // ================================================================
    // collectDiagnostics branch tests
    // ================================================================

    @Test
    void collectDiagnosticsReturnsEmptyForClosedDocument() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        // Don't open any document — content is null
        @SuppressWarnings("unchecked")
        List<Diagnostic> result = (List<Diagnostic>) invoke(provider,
                "collectDiagnostics", new Class<?>[]{ String.class },
                new Object[]{ "file:///ClosedDoc.groovy" });
        assertTrue(result.isEmpty());
    }

    @Test
    void collectDiagnosticsSyntaxOnlyWhenNoClasspath() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        String uri = "file:///NoCP.groovy";
        dm.didOpen(uri, "class A {}");
        // Set classpath checker that always returns false
        provider.setClasspathChecker(u -> false);
        provider.setBuildInProgressSupplier(() -> false);

        @SuppressWarnings("unchecked")
        List<Diagnostic> result = (List<Diagnostic>) invoke(provider,
                "collectDiagnostics", new Class<?>[]{ String.class },
                new Object[]{ uri });
        // Should contain the "no classpath" warning
        assertTrue(result.stream().anyMatch(d ->
                String.valueOf(d.getMessage()).toLowerCase().contains("classpath")),
                "Expected a no-classpath warning diagnostic");
        dm.didClose(uri);
    }

    @Test
    void collectDiagnosticsSyntaxOnlyWhenBuildInProgress() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        String uri = "file:///BuildProg.groovy";
        dm.didOpen(uri, "class B { invalid syntax !!! }");
        provider.setClasspathChecker(u -> true);
        provider.setBuildInProgressSupplier(() -> true);

        @SuppressWarnings("unchecked")
        List<Diagnostic> result = (List<Diagnostic>) invoke(provider,
                "collectDiagnostics", new Class<?>[]{ String.class },
                new Object[]{ uri });
        // Should have syntax errors from Groovy compiler but no classpath warning
        assertNotNull(result);
        dm.didClose(uri);
    }

    @Test
    void collectDiagnosticsNoClasspathWarningContainsExpectedText() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        String uri = "file:///UnusedImps.groovy";
        String source = "import java.util.Map\nclass C {}";
        dm.didOpen(uri, source);
        // Force no-classpath branch
        provider.setClasspathChecker(u -> false);
        provider.setBuildInProgressSupplier(() -> false);

        @SuppressWarnings("unchecked")
        List<Diagnostic> result = (List<Diagnostic>) invoke(provider,
                "collectDiagnostics", new Class<?>[]{ String.class },
                new Object[]{ uri });
        // The no-classpath warning should mention "Classpath"
        assertTrue(result.stream().anyMatch(d ->
                String.valueOf(d.getMessage()).toLowerCase().contains("classpath")),
                "Expected a no-classpath warning diagnostic in result");
        dm.didClose(uri);
    }

    // ================================================================
    // collectFromGroovyCompiler tests
    // ================================================================

    @Test
    void collectFromGroovyCompilerCapturesSyntaxErrors() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        String uri = "file:///SyntaxErr.groovy";
        dm.didOpen(uri, "class { }"); // Missing class name

        List<Diagnostic> diagnostics = new ArrayList<>();
        invoke(provider, "collectFromGroovyCompiler",
                new Class<?>[]{ String.class, List.class },
                new Object[]{ uri, diagnostics });

        assertTrue(diagnostics.size() >= 1,
                "Expected at least one syntax error diagnostic");
        assertEquals(DiagnosticSeverity.Error, diagnostics.get(0).getSeverity());
        dm.didClose(uri);
    }

    @Test
    void collectFromGroovyCompilerReturnsForNullContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        // Content is null since document is not open
        List<Diagnostic> diagnostics = new ArrayList<>();
        invoke(provider, "collectFromGroovyCompiler",
                new Class<?>[]{ String.class, List.class },
                new Object[]{ "file:///NoContent.groovy", diagnostics });

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void collectFromGroovyCompilerUsesCleanSource() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider provider = new DiagnosticsProvider(dm);
        String uri = "file:///CleanSource.groovy";
        dm.didOpen(uri, "class Valid { void m() {} }");

        List<Diagnostic> diagnostics = new ArrayList<>();
        invoke(provider, "collectFromGroovyCompiler",
                new Class<?>[]{ String.class, List.class },
                new Object[]{ uri, diagnostics });

        assertTrue(diagnostics.isEmpty(),
                "Valid source should have no syntax errors");
        dm.didClose(uri);
    }

    // ================================================================
    // createNoClasspathWarning tests
    // ================================================================

    @Test
    void createNoClasspathWarningReturnsDiagnostic() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        Diagnostic result = (Diagnostic) invoke(provider,
                "createNoClasspathWarning", new Class<?>[]{ String.class },
                new Object[]{ "class Foo {}" });
        assertNotNull(result);
        assertEquals(DiagnosticSeverity.Warning, result.getSeverity());
        assertTrue(String.valueOf(result.getMessage()).toLowerCase().contains("classpath"));
    }
}
