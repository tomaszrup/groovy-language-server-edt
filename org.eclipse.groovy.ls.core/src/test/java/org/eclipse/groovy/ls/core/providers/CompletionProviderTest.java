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

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.junit.jupiter.api.Test;

class CompletionProviderTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void normalizePackageNameTrimsWhitespaceAndTrailingDots() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        assertEquals("com.example", invokeNormalizePackageName(provider, "  com.example...  "));
        assertEquals("", invokeNormalizePackageName(provider, "..."));
        assertNull(invokeNormalizePackageName(provider, null));
    }

    @Test
    void findTraitFieldHelperNodeFindsHelperInCurrentModule() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ModuleNode module = parseModule("""
                package demo
                trait Values {
                    String text
                }
                """, "file:///CompletionProviderCurrentModuleTest.groovy");

        ClassNode traitNode = findClass(module, "Values");
        module.addClass(new ClassNode(traitNode.getName() + "$Trait$FieldHelper", 0, ClassHelper.OBJECT_TYPE));

        ClassNode helper = invokeFindTraitFieldHelperNode(provider, traitNode, module);

        assertNotNull(helper);
        assertEquals("demo.Values$Trait$FieldHelper", helper.getName());
    }

    @Test
    void findTraitFieldHelperNodeFindsHelperInOtherOpenDocument() throws Exception {
        String uriMain = "file:///CompletionProviderOtherModuleMain.groovy";
        String uriHelper = "file:///CompletionProviderOtherModuleHelper.groovy";

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uriMain, """
                package demo
                trait Values {
                    String text
                }
                """);
        documentManager.didOpen(uriHelper, "package demo\nclass Placeholder {}\n");

        CompletionProvider provider = new CompletionProvider(documentManager);

        ModuleNode mainModule = documentManager.getGroovyAST(uriMain);
        ModuleNode helperModule = documentManager.getGroovyAST(uriHelper);
        ClassNode traitNode = findClass(mainModule, "Values");

        helperModule.addClass(new ClassNode(traitNode.getName() + "$Trait$FieldHelper", 0, ClassHelper.OBJECT_TYPE));

        ClassNode helper = invokeFindTraitFieldHelperNode(provider, traitNode, mainModule);

        assertNotNull(helper);
        assertEquals("demo.Values$Trait$FieldHelper", helper.getName());

        documentManager.didClose(uriMain);
        documentManager.didClose(uriHelper);
    }

    @Test
    void findTraitFieldHelperNodeReturnsNullWhenHelperMissing() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        ModuleNode module = parseModule("""
                package demo
                trait Values {
                    String text
                }
                """, "file:///CompletionProviderNoHelperTest.groovy");

        ClassNode traitNode = findClass(module, "Values");
        ClassNode helper = invokeFindTraitFieldHelperNode(provider, traitNode, module);

        assertNull(helper);
    }

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        if (!result.hasAST()) {
            throw new AssertionError("Expected AST for completion fixture");
        }
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(classNode -> simpleName.equals(classNode.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }

    private String invokeNormalizePackageName(CompletionProvider provider, String value) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod("normalizePackageName", String.class);
        method.setAccessible(true);
        return (String) method.invoke(provider, value);
    }

    private ClassNode invokeFindTraitFieldHelperNode(
            CompletionProvider provider,
            ClassNode traitNode,
            ModuleNode currentModule) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod(
                "findTraitFieldHelperNode",
                ClassNode.class,
                ModuleNode.class);
        method.setAccessible(true);
        return (ClassNode) method.invoke(provider, traitNode, currentModule);
    }
}
