/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;

/**
 * Shared test utilities for the Groovy Language Server test suite.
 */
public final class TestHelper {

    private static final GroovyCompilerService COMPILER = new GroovyCompilerService();

    private TestHelper() {
    }

    /**
     * Create a fresh {@link DocumentManager} instance.
     */
    public static DocumentManager createDocumentManager() {
        return new DocumentManager();
    }

    /**
     * Open a virtual Groovy document in the given manager.
     */
    public static void openDocument(DocumentManager manager, String uri, String source) {
        manager.didOpen(uri, source);
    }

    /**
     * Parse Groovy source into a {@link ModuleNode}, asserting an AST is produced.
     */
    public static ModuleNode parseGroovy(String source) {
        return parseGroovy("file:///Test.groovy", source);
    }

    /**
     * Parse Groovy source with a specific URI.
     */
    public static ModuleNode parseGroovy(String uri, String source) {
        GroovyCompilerService.ParseResult result = COMPILER.parse(uri, source);
        assertTrue(result.hasAST(), "Expected AST for source: " + source);
        return result.getModuleNode();
    }

    /**
     * Find a class by simple name in a parsed {@link ModuleNode}.
     */
    public static ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(c -> simpleName.equals(c.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }

    /**
     * Invoke a private/package-private method via reflection.
     */
    public static Object reflectionInvoke(Object target, String methodName,
                                           Class<?>[] argTypes, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, argTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
