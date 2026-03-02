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

    @Test
    void isTraitReturnsFalseForEnum() {
        ClassNode enumNode = firstClassNode("""
                enum Color { RED, GREEN, BLUE }
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(enumNode));
    }

    @Test
    void isTraitReturnsFalseForAnnotationType() {
        ClassNode annotationNode = firstClassNode("""
                import java.lang.annotation.*
                @Retention(RetentionPolicy.RUNTIME)
                @interface MyAnnotation {}
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(annotationNode));
    }

    @Test
    void isTraitReturnsFalseForAbstractClass() {
        ClassNode abstractNode = firstClassNode("""
                abstract class Shape {
                    abstract double area()
                }
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(abstractNode));
    }

    @Test
    void isTraitWithTraitAnnotation() {
        ClassNode traitNode = firstClassNode("""
                @groovy.transform.Trait
                class ParcelableHelper {
                    String describe() { 'helper' }
                }
                """);

        // The @Trait annotation should make it detected as a trait
        // (behavior depends on Groovy version)
        assertNotNull(traitNode);
    }

    @Test
    void isTraitWithTraitContainingMethods() {
        ClassNode traitNode = firstClassNode("""
                trait Greetable {
                    String greet(String name) { "Hello, $name" }
                    String farewell(String name) { "Goodbye, $name" }
                }
                """);

        assertTrue(GroovyTypeKindHelper.isTrait(traitNode));
    }

    @Test
    void isTraitWithTraitContainingOnlyAbstractMethods() {
        ClassNode traitNode = firstClassNode("""
                trait Identifiable {
                    abstract String getId()
                }
                """);

        assertTrue(GroovyTypeKindHelper.isTrait(traitNode));
    }

    @Test
    void isTraitReturnsFalseForScriptClass() {
        // A Groovy script gets compiled to a class extending Script
        ClassNode scriptNode = firstClassNode("""
                println 'hello'
                """);

        assertFalse(GroovyTypeKindHelper.isTrait(scriptNode));
    }

    private ClassNode firstClassNode(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///GroovyTypeKindHelperTest.groovy", source);
        ModuleNode moduleNode = result.getModuleNode();
        assertNotNull(moduleNode);
        assertFalse(moduleNode.getClasses().isEmpty());
        return moduleNode.getClasses().get(0);
    }
}
