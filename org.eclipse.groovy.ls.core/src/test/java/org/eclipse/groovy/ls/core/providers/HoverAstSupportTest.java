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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class HoverAstSupportTest {

    private static final String URI = "file:///HoverAstSupportTest.groovy";

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void findConstructorCallAtOffsetReturnsMatchingConstructorCall() {
        String source = "def helper = new Helper()\n";
        ModuleNode module = parseModule(source);

        ConstructorCallExpression call = HoverAstSupport.findConstructorCallAtOffset(
                module,
                source.indexOf("Helper"),
                "Helper",
                PositionUtils.buildLineIndex(source));

        assertNotNull(call);
        assertEquals("Helper", call.getType().getNameWithoutPackage());
    }

    @Test
    void findMethodCallAtOffsetReturnsMatchingMethodCall() {
        String source = """
                def helper = new Helper()
                helper.run()
                """;
        ModuleNode module = parseModule(source);

        MethodCallExpression call = HoverAstSupport.findMethodCallAtOffset(
                module,
                source.indexOf("run"),
                "run",
                PositionUtils.buildLineIndex(source));

        assertNotNull(call);
        assertEquals("run", call.getMethodAsString());
    }

    @Test
    void findMethodCallAtOffsetReturnsNullWhenCursorIsOutsideMethodNameRange() {
        String source = "helper.run()\n";
        ModuleNode module = parseModule(source);

        MethodCallExpression call = HoverAstSupport.findMethodCallAtOffset(
                module,
                source.indexOf("helper"),
                "run",
                PositionUtils.buildLineIndex(source));

        assertNull(call);
    }

    @Test
    void resolveReceiverTypeFromAstResolvesConstructorAssignedVariable() {
        String source = """
                import com.acme.Helper
                def helper = new Helper()
                helper.run()
                """;
        ModuleNode module = parseModule(source);
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);

        try (MockedStatic<ScopedTypeLookupSupport> scopedLookup = Mockito.mockStatic(ScopedTypeLookupSupport.class)) {
            scopedLookup.when(() -> ScopedTypeLookupSupport.findType(project, "com.acme.Helper", URI))
                    .thenReturn(resolvedType);

            IType receiverType = HoverAstSupport.resolveReceiverTypeFromAst(
                    module,
                    project,
                    source.indexOf("run"),
                    "run",
                    PositionUtils.buildLineIndex(source),
                    URI);

            assertSame(resolvedType, receiverType);
        }
    }

    @Test
        void resolveReceiverTypeFromAstReturnsNullForMethodCallDerivedLocalVariable() {
        String source = """
                import com.acme.Helper
                                class Factory {
                                        Helper makeHelper() { new Helper() }
                                }
                                def helper = new Factory().makeHelper()
                                helper.run()
                """;
        ModuleNode module = parseModule(source);
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);

        try (MockedStatic<ScopedTypeLookupSupport> scopedLookup = Mockito.mockStatic(ScopedTypeLookupSupport.class)) {
            scopedLookup.when(() -> ScopedTypeLookupSupport.findType(project, "com.acme.Helper", URI))
                    .thenReturn(resolvedType);

                        IType receiverType = HoverAstSupport.resolveReceiverTypeFromAst(
                    module,
                    project,
                    source.indexOf("run"),
                    "run",
                    PositionUtils.buildLineIndex(source),
                    URI);

                        assertNull(receiverType);
        }
    }

    @Test
    void resolveClassNodeToITypeChecksStarPackageAndAutoImports() {
        IJavaProject project = mock(IJavaProject.class);
        IType starImportedType = mock(IType.class);
        IType autoImportedType = mock(IType.class);

        ModuleNode starImportedModule = parseModule("import com.acme.*\ndef helper = new Helper()\n");
        ModuleNode defaultModule = parseModule("def amount = 0\n");

        try (MockedStatic<ScopedTypeLookupSupport> scopedLookup = Mockito.mockStatic(ScopedTypeLookupSupport.class)) {
            scopedLookup.when(() -> ScopedTypeLookupSupport.findType(project, "com.acme.Helper", URI))
                    .thenReturn(starImportedType);
            scopedLookup.when(() -> ScopedTypeLookupSupport.findType(project, "java.math.BigDecimal", URI))
                    .thenReturn(autoImportedType);
            IType starResolved = HoverAstSupport.resolveClassNodeToIType(
                    ClassHelper.makeWithoutCaching("Helper"),
                    starImportedModule,
                    project,
                    URI);
            IType autoResolved = HoverAstSupport.resolveClassNodeToIType(
                    ClassHelper.makeWithoutCaching("BigDecimal"),
                    defaultModule,
                    project,
                    URI);

            assertSame(starImportedType, starResolved);
            assertSame(autoImportedType, autoResolved);
        }
    }

    @Test
    void resolveClassNodeToITypeChecksCurrentModulePackage() {
        IJavaProject project = mock(IJavaProject.class);
        IType packageType = mock(IType.class);
        ModuleNode module = parseModule("package demo\ndef helper = new LocalType()\n");
        ClassNode typeNode = ClassHelper.makeWithoutCaching("LocalType");

        try (MockedStatic<ScopedTypeLookupSupport> scopedLookup = Mockito.mockStatic(ScopedTypeLookupSupport.class)) {
            scopedLookup.when(() -> ScopedTypeLookupSupport.findType(project, "demo.LocalType", URI))
                    .thenReturn(packageType);

            IType resolved = HoverAstSupport.resolveClassNodeToIType(typeNode, module, project, URI);

            assertSame(packageType, resolved);
        }
    }

    @Test
    void resolveClassNodeToITypeReturnsNullForObjectAndMissingProject() {
        ModuleNode module = parseModule("def x = 1\n");

        assertNull(HoverAstSupport.resolveClassNodeToIType(ClassHelper.OBJECT_TYPE, module, mock(IJavaProject.class), URI));
        assertNull(HoverAstSupport.resolveClassNodeToIType(ClassHelper.makeWithoutCaching("Helper"), module, null, URI));
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse(URI, source);
        assertTrue(result.hasAST());
        return result.getModuleNode();
    }
}