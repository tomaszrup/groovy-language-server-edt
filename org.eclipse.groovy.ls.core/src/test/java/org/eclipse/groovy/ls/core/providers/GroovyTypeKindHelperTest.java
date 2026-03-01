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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.junit.jupiter.api.Test;

class GroovyTypeKindHelperTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void isTraitReturnsFalseForNull() {
        assertFalse(GroovyTypeKindHelper.isTrait(null));
    }

    @Test
    void isTraitReturnsTrueForTraitDeclaration() {
        ClassNode traitNode = firstClassNode("""
                trait Named {
                    String name
                }
                """);

        assertTrue(GroovyTypeKindHelper.isTrait(traitNode));
    }

    @Test
    void isTraitReturnsFalseForRegularClass() {
        ClassNode classNode = firstClassNode("""
                class Person {
                    String name
                }
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(classNode));
    }

    @Test
    void isTraitReturnsFalseForInterface() {
        ClassNode interfaceNode = firstClassNode("""
                interface Worker {
                    void work()
                }
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(interfaceNode));
    }

    private ClassNode firstClassNode(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///GroovyTypeKindHelperTest.groovy", source);
        ModuleNode moduleNode = result.getModuleNode();
        assertNotNull(moduleNode);
        assertFalse(moduleNode.getClasses().isEmpty());
        return moduleNode.getClasses().get(0);
    }
}
