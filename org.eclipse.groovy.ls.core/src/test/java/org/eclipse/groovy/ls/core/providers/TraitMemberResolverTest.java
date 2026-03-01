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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.junit.jupiter.api.Test;

class TraitMemberResolverTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void resolveTraitClassNodeFindsDeclarationInCurrentModule() {
        ModuleNode module = parseModule("""
                trait Timestamped {
                    long getEpoch() { 1L }
                }
                class User implements Timestamped {
                }
                """, "file:///TraitResolveTest.groovy");

        ClassNode owner = findClass(module, "User");
        ClassNode ifaceRef = owner.getInterfaces()[0];

        ClassNode resolved = TraitMemberResolver.resolveTraitClassNode(ifaceRef, module, null);

        assertNotNull(resolved);
        assertEquals("Timestamped", resolved.getNameWithoutPackage());
        assertTrue(resolved.getMethods().stream().anyMatch(method -> "getEpoch".equals(method.getName())));
    }

    @Test
    void collectTraitMethodsIncludesDirectTraitMembers() {
        ModuleNode module = parseModule("""
                trait Timestamped {
                    long getEpoch() { 1L }
                }
                class User implements Timestamped {
                }
                """, "file:///TraitMethodsTest.groovy");

        ClassNode owner = findClass(module, "User");

        List<MethodNode> methods = TraitMemberResolver.collectTraitMethods(owner, module, null);
        Set<String> names = methods.stream().map(MethodNode::getName).collect(Collectors.toSet());

        assertTrue(names.contains("getEpoch"));
    }

    @Test
    void collectTraitPropertiesAndFieldsIncludesDirectTraitMembers() {
        ModuleNode module = parseModule("""
                trait Values {
                    String text
                }
                class Impl implements Values {
                }
                """, "file:///TraitFieldsTest.groovy");

        ClassNode owner = findClass(module, "Impl");

        List<PropertyNode> properties = TraitMemberResolver.collectTraitProperties(owner, module, null);
        Set<String> propertyNames = properties.stream().map(PropertyNode::getName).collect(Collectors.toSet());

        List<FieldNode> fields = TraitMemberResolver.collectTraitFields(owner, module, null);
        Set<String> demangledFieldNames = fields.stream()
                .map(FieldNode::getName)
                .map(TraitMemberResolver::demangleTraitFieldName)
                .collect(Collectors.toSet());

        assertTrue(propertyNames.contains("text"));
        assertTrue(demangledFieldNames.contains("text"));
    }

    @Test
    void findTraitDeclarationUriReturnsCurrentUriWhenTraitIsInSameModule() {
        ModuleNode module = parseModule("""
                trait FeatureToggle {
                }
                class Service implements FeatureToggle {
                }
                """, "file:///TraitUriTest.groovy");

        ClassNode owner = findClass(module, "Service");
        ClassNode ifaceRef = owner.getInterfaces()[0];

        String uri = TraitMemberResolver.findTraitDeclarationUri(
                ifaceRef,
                "file:///TraitUriTest.groovy",
                module,
                null);

        assertEquals("file:///TraitUriTest.groovy", uri);
    }

    @Test
    void traitFieldNameHelpersHandleMangledAndPlainNames() {
        assertEquals("count", TraitMemberResolver.demangleTraitFieldName("demo_Trait__count"));
        assertEquals("count", TraitMemberResolver.demangleTraitFieldName("count"));
        assertTrue(TraitMemberResolver.isTraitFieldMatch("demo_Trait__count", "count"));
        assertTrue(TraitMemberResolver.isTraitFieldMatch("count", "count"));
        assertFalse(TraitMemberResolver.isTraitFieldMatch("demo_Trait__count", "other"));
        assertFalse(TraitMemberResolver.isTraitFieldMatch(null, "count"));
    }

    @Test
    void findTraitFieldResolvesPropertyBackedField() {
        ModuleNode module = parseModule("""
                trait Values {
                    String text
                }
                """, "file:///TraitFindFieldTest.groovy");

        ClassNode traitNode = findClass(module, "Values");

        FieldNode found = TraitMemberResolver.findTraitField(traitNode, "text", module);
        FieldNode notFound = TraitMemberResolver.findTraitField(traitNode, "missing", module);

        assertNotNull(found);
        assertEquals("text", TraitMemberResolver.demangleTraitFieldName(found.getName()));
        assertNull(notFound);
    }

    @Test
    void collectTraitMethodsReturnsEmptyForClassWithoutInterfaces() {
        ModuleNode module = parseModule("""
                class Plain {
                    String name
                }
                """, "file:///TraitNoInterfacesTest.groovy");

        ClassNode owner = findClass(module, "Plain");

        List<MethodNode> methods = TraitMemberResolver.collectTraitMethods(owner, module, null);

        assertTrue(methods.isEmpty());
    }

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        assertTrue(result.hasAST(), "Expected parser to produce AST for trait fixture");
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(classNode -> classNode.getLineNumber() >= 0)
                .filter(classNode -> simpleName.equals(classNode.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }
}
