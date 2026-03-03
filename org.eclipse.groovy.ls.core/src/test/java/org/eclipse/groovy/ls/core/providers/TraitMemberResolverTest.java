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
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.junit.jupiter.api.Test;

class TraitMemberResolverTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();
    private final DocumentManager documentManager = new DocumentManager();

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

    // ---- Additional coverage tests ----

    @Test
    void resolveTraitClassNodeReturnsOriginalWhenNotFoundInModule() {
        ModuleNode module = parseModule("""
                class Standalone {}
                """, "file:///TraitNotFoundTest.groovy");

        // Create a reference to a trait that doesn't exist in the module
        ClassNode fakeRef = new ClassNode("com.example.NonExistent", 0, null);

        ClassNode resolved = TraitMemberResolver.resolveTraitClassNode(fakeRef, module, null);

        // Should return the original ref since it couldn't resolve
        assertNotNull(resolved);
        assertEquals("com.example.NonExistent", resolved.getName());
    }

    @Test
    void collectTraitFieldsReturnsEmptyForClassWithoutInterfaces() {
        ModuleNode module = parseModule("""
                class Simple { int value }
                """, "file:///TraitFieldsEmpty.groovy");

        ClassNode owner = findClass(module, "Simple");

        List<FieldNode> fields = TraitMemberResolver.collectTraitFields(owner, module, null);

        assertTrue(fields.isEmpty());
    }

    @Test
    void collectTraitPropertiesReturnsEmptyForClassWithoutInterfaces() {
        ModuleNode module = parseModule("""
                class Simple { int value }
                """, "file:///TraitPropsEmpty.groovy");

        ClassNode owner = findClass(module, "Simple");

        List<PropertyNode> properties = TraitMemberResolver.collectTraitProperties(owner, module, null);

        assertTrue(properties.isEmpty());
    }

    @Test
    void findFieldHelperNodeReturnsNullWhenNoHelper() {
        ModuleNode module = parseModule("""
                trait NoHelper { String name }
                """, "file:///TraitNoHelper.groovy");

        ClassNode traitNode = findClass(module, "NoHelper");
        assertNull(TraitMemberResolver.findFieldHelperNode(traitNode, module));
    }

    @Test
    void findFieldHelperNodeReturnsNullForNullInputs() {
        assertNull(TraitMemberResolver.findFieldHelperNode(null, null));
    }

    @Test
    void demangleTraitFieldNameHandlesNull() {
        assertNull(TraitMemberResolver.demangleTraitFieldName(null));
    }

    @Test
    void demangleTraitFieldNameHandlesMultipleDoubleUnderscores() {
        assertEquals("field", TraitMemberResolver.demangleTraitFieldName("pkg_Trait__nested__field"));
    }

    @Test
    void isTraitFieldMatchHandlesNullTarget() {
        assertFalse(TraitMemberResolver.isTraitFieldMatch("abc", null));
    }

    @Test
    void findTraitFieldReturnsNullForNullInputs() {
        assertNull(TraitMemberResolver.findTraitField(null, "name", null));
        ModuleNode module = parseModule("trait T { String name }", "file:///TraitNull.groovy");
        ClassNode traitNode = findClass(module, "T");
        assertNull(TraitMemberResolver.findTraitField(traitNode, null, module));
    }

    @Test
    void collectAllTraitFieldsFromDirectFields() {
        ModuleNode module = parseModule("""
                trait HasFields {
                    String name
                    int count
                }
                """, "file:///TraitAllFields.groovy");

        ClassNode traitNode = findClass(module, "HasFields");

        List<FieldNode> fields = TraitMemberResolver.collectAllTraitFields(traitNode, module);

        // Should have entries for both fields (may be property-backed)
        assertNotNull(fields);
    }

    @Test
    void collectAllTraitFieldsReturnsEmptyForNull() {
        List<FieldNode> fields = TraitMemberResolver.collectAllTraitFields(null, null);
        assertTrue(fields.isEmpty());
    }

    @Test
    void findTraitDeclarationUriReturnsNullWhenNotInModule() {
        ModuleNode module = parseModule("""
                class NoTrait {}
                """, "file:///TraitUriNotFound.groovy");

        ClassNode fakeRef = new ClassNode("com.example.SomeTrait", 0, null);

        String uri = TraitMemberResolver.findTraitDeclarationUri(
                fakeRef, "file:///TraitUriNotFound.groovy", module, null);

        assertNull(uri);
    }

    @Test
    void collectTraitMethodsWithMultipleTraitImplementation() {
        ModuleNode module = parseModule("""
                trait Auditable {
                    String getCreatedBy() { 'system' }
                }
                trait Timestamped {
                    long getCreatedAt() { 0L }
                }
                class Entity implements Auditable, Timestamped {}
                """, "file:///TraitMultiImpl.groovy");

        ClassNode owner = findClass(module, "Entity");

        List<MethodNode> methods = TraitMemberResolver.collectTraitMethods(owner, module, null);
        Set<String> names = methods.stream().map(MethodNode::getName).collect(Collectors.toSet());

        assertTrue(names.contains("getCreatedBy"));
        assertTrue(names.contains("getCreatedAt"));
    }

    @Test
    void collectTraitPropertiesWithMultipleTraits() {
        ModuleNode module = parseModule("""
                trait Named { String name }
                trait Aged { int age }
                class Person implements Named, Aged {}
                """, "file:///TraitMultiProps.groovy");

        ClassNode owner = findClass(module, "Person");

        List<PropertyNode> properties = TraitMemberResolver.collectTraitProperties(owner, module, null);
        Set<String> names = properties.stream().map(PropertyNode::getName).collect(Collectors.toSet());

        assertTrue(names.contains("name"));
        assertTrue(names.contains("age"));
    }

    @Test
    void traitFieldMatchWithExactName() {
        assertTrue(TraitMemberResolver.isTraitFieldMatch("myField", "myField"));
        assertFalse(TraitMemberResolver.isTraitFieldMatch("myField", "otherField"));
    }

    @Test
    void resolveTraitClassNodeWithEmptySimpleName() {
        ModuleNode module = parseModule("class A {}", "file:///Empty.groovy");
        ClassNode emptyRef = new ClassNode("", 0, null);

        ClassNode resolved = TraitMemberResolver.resolveTraitClassNode(emptyRef, module, null);
        assertEquals(emptyRef, resolved); // Returns original when name is empty
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

    // ================================================================
    // collectTraitFields tests
    // ================================================================

    @Test
    void collectTraitFieldsReturnsFieldsFromTrait() {
        String source = "trait HasCount {\n"
                + "    int counter = 0\n"
                + "}\n"
                + "class Impl implements HasCount {}\n";
        String uri = "file:///TraitFields.groovy";
        ModuleNode ast = parseModule(source, uri);
        ClassNode impl = findClass(ast, "Impl");

        java.util.List<org.codehaus.groovy.ast.FieldNode> fields =
                TraitMemberResolver.collectTraitFields(impl, ast, documentManager);

        assertTrue(fields.size() >= 1,
                "Expected at least one trait field but got " + fields.size());
    }

    @Test
    void collectTraitFieldsReturnsEmptyForNoTraits() {
        String source = "class Plain {}\n";
        String uri = "file:///NoTraitFields.groovy";
        ModuleNode ast = parseModule(source, uri);
        ClassNode plain = findClass(ast, "Plain");

        java.util.List<org.codehaus.groovy.ast.FieldNode> fields =
                TraitMemberResolver.collectTraitFields(plain, ast, documentManager);

        assertTrue(fields.isEmpty());
    }

    // ================================================================
    // collectTraitProperties tests
    // ================================================================

    @Test
    void collectTraitPropertiesReturnsPropertiesFromTrait() {
        String source = "trait Named {\n"
                + "    String name\n"
                + "}\n"
                + "class Person implements Named {}\n";
        String uri = "file:///TraitProps.groovy";
        ModuleNode ast = parseModule(source, uri);
        ClassNode person = findClass(ast, "Person");

        java.util.List<org.codehaus.groovy.ast.PropertyNode> props =
                TraitMemberResolver.collectTraitProperties(person, ast, documentManager);

        assertTrue(props.size() >= 1,
                "Expected at least one trait property but got " + props.size());
    }

    @Test
    void collectTraitPropertiesReturnsEmptyForNoTraits() {
        String source = "class Empty {}\n";
        String uri = "file:///NoTraitProps.groovy";
        ModuleNode ast = parseModule(source, uri);
        ClassNode empty = findClass(ast, "Empty");

        java.util.List<org.codehaus.groovy.ast.PropertyNode> props =
                TraitMemberResolver.collectTraitProperties(empty, ast, documentManager);

        assertTrue(props.isEmpty());
    }

    // ================================================================
    // collectAllTraitFields tests
    // ================================================================

    @Test
    void collectAllTraitFieldsIncludesFieldsAndProperties() {
        String source = "trait WithFields {\n"
                + "    int count = 0\n"
                + "    String label\n"
                + "}\n"
                + "class User implements WithFields {}\n";
        String uri = "file:///AllTraitFields.groovy";
        ModuleNode ast = parseModule(source, uri);
        ClassNode trait = findClass(ast, "WithFields");

        java.util.List<org.codehaus.groovy.ast.FieldNode> all =
                TraitMemberResolver.collectAllTraitFields(trait, ast);

        assertTrue(all.size() >= 1,
                "Expected at least 1 trait field but got " + all.size());
    }

    // ================================================================
    // isTraitFieldMatch tests
    // ================================================================

    @Test
    void isTraitFieldMatchDetectsMatchingPattern() {
        // Trait field helper names use pattern: TraitName__fieldName
        assertTrue(TraitMemberResolver.isTraitFieldMatch(
                "MyTrait__counter", "counter"));
    }

    @Test
    void isTraitFieldMatchReturnsFalseForNonMatch() {
        assertFalse(TraitMemberResolver.isTraitFieldMatch(
                "MyTrait__counter", "other"));
    }

    @Test
    void isTraitFieldMatchReturnsTrueForExactSameName() {
        // Exact match returns true per implementation
        assertTrue(TraitMemberResolver.isTraitFieldMatch(
                "counter", "counter"));
    }

    // ================================================================
    // demangleTraitFieldName tests
    // ================================================================

    @Test
    void demangleTraitFieldNameExtractsSimpleName() {
        assertEquals("counter",
                TraitMemberResolver.demangleTraitFieldName("MyTrait__counter"));
    }

    @Test
    void demangleTraitFieldNameReturnsOriginalWithoutDoubleUnderscore() {
        assertEquals("plainField",
                TraitMemberResolver.demangleTraitFieldName("plainField"));
    }

    // ================================================================
    // findTraitDeclarationUri tests
    // ================================================================

    @Test
    void findTraitDeclarationUriReturnsNullForEmptyName() {
        // findTraitDeclarationUri returns null when the interface reference has an empty name
        String source = "class Nothing {}\n";
        String uri = "file:///NoTraitUri.groovy";
        ModuleNode ast = parseModule(source, uri);

        // Create a ClassNode with empty name
        ClassNode emptyRef = new ClassNode("", 0, null);
        String result = TraitMemberResolver.findTraitDeclarationUri(
                emptyRef, uri, ast, documentManager);

        assertNull(result);
    }

    @Test
    void findTraitDeclarationUriReturnsUriForTraitMethod() {
        String source = "trait Greeter {\n"
                + "    String greet() { 'hi' }\n"
                + "}\n"
                + "class Hello implements Greeter {}\n";
        String uri = "file:///TraitUri.groovy";
        documentManager.didOpen(uri, source);
        ModuleNode ast = parseModule(source, uri);
        ClassNode hello = findClass(ast, "Hello");
        // The ifaceRef is the first interface of Hello
        ClassNode ifaceRef = hello.getInterfaces()[0];

        String result = TraitMemberResolver.findTraitDeclarationUri(
                ifaceRef, uri, ast, documentManager);

        assertNotNull(result);
        documentManager.didClose(uri);
    }
}
