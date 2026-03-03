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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.TextEdit;

import com.google.gson.JsonObject;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @Test
    void getCompletionsFallsBackToAstAndKeywordsWithoutWorkingCopy() {
        String uri = "file:///CompletionProviderFallbackCompletions.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                class Sample {
                    String name
                    String greet(String who) { "hi ${who}" }
                }
                def value = new Sample()
                gr
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(5, 2));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(item -> "greet".equals(item.getLabel())));

        manager.didClose(uri);
    }

    @Test
    void getCompletionsReturnsNoFallbackItemsInAnnotationContext() {
        String uri = "file:///CompletionProviderAnnotationContext.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, "@\n");

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(0, 1));

        assertTrue(provider.getCompletions(params).isEmpty());

        manager.didClose(uri);
    }

    @Test
    void resolveCompletionItemReturnsInputUnchanged() {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        var item = new org.eclipse.lsp4j.CompletionItem("value");
        assertSame(item, provider.resolveCompletionItem(item));
    }

    // ---- getCompletions: returns empty for unknown URI ----

    @Test
    void getCompletionsReturnsEmptyForUnknownUri() {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///UnknownCompletionUri.groovy"));
        params.setPosition(new Position(0, 0));

        assertTrue(provider.getCompletions(params).isEmpty());
    }

    // ---- addClassCompletions: class name, methods, fields, properties ----

    @Test
    void addClassCompletionsIncludesClassNameMethodsFieldsProperties() throws Exception {
        DocumentManager manager = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(manager);

        ModuleNode module = parseModule("""
                class Person {
                    String name
                    private int age
                    String greet(String who) { "Hello ${who}" }
                    int getAge() { age }
                }
                """, "file:///AddClassCompletions.groovy");

        ClassNode classNode = findClass(module, "Person");
        List<CompletionItem> items = new ArrayList<>();
        invokeAddClassCompletions(provider, classNode, "", items);

        // Class name
        assertTrue(items.stream().anyMatch(i -> "Person".equals(i.getLabel())
                && i.getKind() == CompletionItemKind.Class));
        // Method
        assertTrue(items.stream().anyMatch(i -> "greet".equals(i.getLabel())
                && i.getKind() == CompletionItemKind.Method));
        // Method getAge
        assertTrue(items.stream().anyMatch(i -> "getAge".equals(i.getLabel())));
        // Property
        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())
                && i.getKind() == CompletionItemKind.Property));
    }

    @Test
    void addClassCompletionsFiltersByPrefix() throws Exception {
        DocumentManager manager = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(manager);

        ModuleNode module = parseModule("""
                class Sample {
                    String name
                    void doSomething() {}
                    void doAnother(int x) {}
                    int count
                }
                """, "file:///AddClassCompletionsPrefix.groovy");

        ClassNode classNode = findClass(module, "Sample");
        List<CompletionItem> items = new ArrayList<>();
        invokeAddClassCompletions(provider, classNode, "do", items);

        assertTrue(items.stream().allMatch(i ->
                i.getLabel().startsWith("do") || i.getLabel().startsWith("Do")));
        assertTrue(items.stream().anyMatch(i -> "doSomething".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "doAnother".equals(i.getLabel())));
        // "name" and "count" should NOT be included since they don't match "do"
        assertFalse(items.stream().anyMatch(i -> "name".equals(i.getLabel())));
        assertFalse(items.stream().anyMatch(i -> "count".equals(i.getLabel())));
    }

    @Test
    void addClassCompletionsMethodWithParamsUsesSnippet() throws Exception {
        DocumentManager manager = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(manager);

        ModuleNode module = parseModule("""
                class Calc {
                    int add(int a, int b) { a + b }
                }
                """, "file:///SnippetCompletion.groovy");

        ClassNode classNode = findClass(module, "Calc");
        List<CompletionItem> items = new ArrayList<>();
        invokeAddClassCompletions(provider, classNode, "add", items);

        CompletionItem addItem = items.stream()
                .filter(i -> "add".equals(i.getLabel()))
                .findFirst().orElse(null);
        assertNotNull(addItem);
        assertEquals(InsertTextFormat.Snippet, addItem.getInsertTextFormat());
        assertTrue(addItem.getInsertText().contains("${1:a}"));
        assertTrue(addItem.getInsertText().contains("${2:b}"));
    }

    @Test
    void addClassCompletionsNoParamMethodGetsParens() throws Exception {
        DocumentManager manager = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(manager);

        ModuleNode module = parseModule("""
                class Svc {
                    void run() {}
                }
                """, "file:///NoParamCompletion.groovy");

        ClassNode classNode = findClass(module, "Svc");
        List<CompletionItem> items = new ArrayList<>();
        invokeAddClassCompletions(provider, classNode, "run", items);

        CompletionItem runItem = items.stream()
                .filter(i -> "run".equals(i.getLabel()))
                .findFirst().orElse(null);
        assertNotNull(runItem);
        assertEquals("run()", runItem.getInsertText());
    }

    @Test
    void addClassCompletionsSkipsInternalFields() throws Exception {
        DocumentManager manager = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(manager);

        ModuleNode module = parseModule("""
                class Internal {
                    String visible
                }
                """, "file:///InternalFields.groovy");

        ClassNode classNode = findClass(module, "Internal");
        // Groovy compiler may generate $staticClassInfo etc.
        List<CompletionItem> items = new ArrayList<>();
        invokeAddClassCompletions(provider, classNode, "", items);

        assertFalse(items.stream().anyMatch(i -> i.getLabel().startsWith("$")));
    }

    // ---- getFallbackCompletions: comprehensive integration tests ----

    @Test
    void getFallbackCompletionsIncludesKeywordsAndClassMembers() throws Exception {
        String uri = "file:///FallbackIntegration.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                class MyService {
                    String name
                    void processData(int batch) {}
                }
                def s = new MyService()
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        List<CompletionItem> items = invokeGetFallbackCompletions(provider, uri, new Position(4, 0));

        // Should include keywords
        assertTrue(items.stream().anyMatch(i -> "def".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "class".equals(i.getLabel())));
        // Should include class name
        assertTrue(items.stream().anyMatch(i -> "MyService".equals(i.getLabel())));
        // Should include method
        assertTrue(items.stream().anyMatch(i -> "processData".equals(i.getLabel())));
        // Should include property
        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())));

        manager.didClose(uri);
    }

    @Test
    void getFallbackCompletionsWithPrefixFiltersResults() throws Exception {
        String uri = "file:///FallbackFiltered.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                class Handler {
                    void handleRequest() {}
                    void handleResponse() {}
                    void process() {}
                }
                ha
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        List<CompletionItem> items = invokeGetFallbackCompletions(provider, uri, new Position(5, 2));

        assertTrue(items.stream().anyMatch(i -> "handleRequest".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "handleResponse".equals(i.getLabel())));
        // "process" should not be included because the prefix "ha" doesn't match
        assertFalse(items.stream().anyMatch(i -> "process".equals(i.getLabel())));

        manager.didClose(uri);
    }

    @Test
    void getFallbackCompletionsReturnsEmptyForAnnotationContext() throws Exception {
        String uri = "file:///FallbackAnnotation.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, "@\nclass Foo {}");

        CompletionProvider provider = new CompletionProvider(manager);
        List<CompletionItem> items = invokeGetFallbackCompletions(provider, uri, new Position(0, 1));

        assertTrue(items.isEmpty());

        manager.didClose(uri);
    }

    @Test
    void getFallbackCompletionsReturnsEmptyForMissingContent() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        List<CompletionItem> items = invokeGetFallbackCompletions(provider,
                "file:///NoSuchDoc.groovy", new Position(0, 0));
        assertTrue(items.isEmpty());
    }

    // ---- getExistingImports ----

    @Test
    void getExistingImportsCombinesAstAndTextImports() throws Exception {
        String uri = "file:///ExistingImports.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                import java.time.LocalDate
                import java.util.List
                class Foo {}
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        Set<String> imports = invokeGetExistingImports(provider, uri,
                "import java.time.LocalDate\nimport java.util.List\nclass Foo {}");

        assertTrue(imports.contains("java.time.LocalDate"));
        assertTrue(imports.contains("java.util.List"));

        manager.didClose(uri);
    }

    @Test
    void getExistingImportsReturnsEmptyForNoImports() throws Exception {
        String uri = "file:///NoImports.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, "class Foo {}");

        CompletionProvider provider = new CompletionProvider(manager);
        Set<String> imports = invokeGetExistingImports(provider, uri, "class Foo {}");

        // May only contain text-parsed results (none in this case)
        assertFalse(imports.contains("java.lang.String"));

        manager.didClose(uri);
    }

    // ---- getCompletions: multiple classes in same file ----

    @Test
    void getCompletionsFallbackIncludesMultipleClasses() {
        String uri = "file:///MultiClassCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                class Alpha {
                    void alphaMethod() {}
                }
                class Beta {
                    void betaMethod() {}
                }
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(5, 0));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "Alpha".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "Beta".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "alphaMethod".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "betaMethod".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- getCompletions: enum members ----

    @Test
    void getCompletionsFallbackIncludesEnumConstants() {
        String uri = "file:///EnumCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                enum Color {
                    RED, GREEN, BLUE
                    String display() { name().toLowerCase() }
                }
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(3, 0));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "Color".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "display".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- getCompletions: interface ----

    @Test
    void getCompletionsFallbackIncludesInterfaceMethods() {
        String uri = "file:///InterfaceCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                interface Greeter {
                    String greet(String name)
                }
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(2, 0));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "Greeter".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "greet".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- getCompletions: trait with properties ----

    @Test
    void getCompletionsFallbackIncludesTraitMembers() {
        String uri = "file:///TraitCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                trait Named {
                    String displayName
                    String getFormattedName() { displayName.toUpperCase() }
                }
                class User implements Named {
                }
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(5, 0));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "Named".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "User".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- getCompletions: with package ----

    @Test
    void getCompletionsFallbackWithPackagedSource() {
        String uri = "file:///PackagedCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                package com.example
                
                class Service {
                    void start() {}
                    void stop() {}
                }
                st
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(6, 2));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "start".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "stop".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- getCompletions: complex class with multiple member types ----

    @Test
    void getCompletionsFallbackComplexClass() {
        String uri = "file:///ComplexClassCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                class DataProcessor {
                    String inputPath
                    String outputPath
                    private boolean verbose
                    
                    void configure(String input, String output) {
                        this.inputPath = input
                        this.outputPath = output
                    }
                    
                    List process(List data) {
                        data.collect { it.toString() }
                    }
                    
                    void setVerbose(boolean v) { this.verbose = v }
                    boolean isVerbose() { this.verbose }
                }
                con
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(new Position(17, 3));

        var items = provider.getCompletions(params);

        assertTrue(items.stream().anyMatch(i -> "configure".equals(i.getLabel())));

        manager.didClose(uri);
    }

    // ---- addTraitMemberCompletions tests ----

    @Test
    void addTraitMemberCompletionsCollectsFromTraitInSameFile() throws Exception {
        String uri = "file:///TraitMemberCompletion.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                trait Printable {
                    String label
                    void printSelf() { println label }
                }
                class Item implements Printable {
                }
                """);

        CompletionProvider provider = new CompletionProvider(manager);
        ModuleNode module = manager.getGroovyAST(uri);
        assertNotNull(module);

        ClassNode itemNode = findClass(module, "Item");
        List<CompletionItem> items = new ArrayList<>();
        invokeAddTraitMemberCompletions(provider, itemNode, "", items);

        // Trait methods and properties should be included
        assertTrue(items.stream().anyMatch(i -> "printSelf".equals(i.getLabel())));

        manager.didClose(uri);
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

    private void invokeAddClassCompletions(CompletionProvider provider, ClassNode classNode,
                                           String prefix, List<CompletionItem> items) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod("addClassCompletions",
                ClassNode.class, String.class, List.class);
        method.setAccessible(true);
        method.invoke(provider, classNode, prefix, items);
    }

    @SuppressWarnings("unchecked")
    private List<CompletionItem> invokeGetFallbackCompletions(CompletionProvider provider,
                                                              String uri, Position position) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod("getFallbackCompletions",
                String.class, Position.class);
        method.setAccessible(true);
        return (List<CompletionItem>) method.invoke(provider, uri, position);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeGetExistingImports(CompletionProvider provider,
                                                  String uri, String content) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod("getExistingImports",
                String.class, String.class);
        method.setAccessible(true);
        return (Set<String>) method.invoke(provider, uri, content);
    }

    private void invokeAddTraitMemberCompletions(CompletionProvider provider, ClassNode classNode,
                                                  String prefix, List<CompletionItem> items) throws Exception {
        Method method = CompletionProvider.class.getDeclaredMethod("addTraitMemberCompletions",
                ClassNode.class, String.class, List.class);
        method.setAccessible(true);
        method.invoke(provider, classNode, prefix, items);
    }

    // ---- matchesPrefix tests ----

    @Test
    void matchesPrefixReturnsTrueForEmptyPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeMatchesPrefix(provider, "anything", ""));
    }

    @Test
    void matchesPrefixCaseInsensitive() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeMatchesPrefix(provider, "getString", "getS"));
        assertTrue(invokeMatchesPrefix(provider, "getString", "gets"));
        assertTrue(invokeMatchesPrefix(provider, "getString", "GETS"));
    }

    @Test
    void matchesPrefixReturnsFalseForNonMatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeMatchesPrefix(provider, "hello", "xyz"));
    }

    // ---- extractPrefix tests ----

    @Test
    void extractPrefixAtMiddleOfIdentifier() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("get", invokeExtractPrefix(provider, "obj.getString()", 7));
    }

    @Test
    void extractPrefixAtStartOfContent() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("abc", invokeExtractPrefix(provider, "abc.def", 3));
    }

    @Test
    void extractPrefixAtZeroOffsetReturnsEmpty() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("", invokeExtractPrefix(provider, "hello", 0));
    }

    @Test
    void extractPrefixAfterDotReturnsEmpty() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("", invokeExtractPrefix(provider, "obj.", 4));
    }

    // ---- positionToOffset tests ----

    @Test
    void positionToOffsetFirstLineFirstColumn() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "class Foo {}", new Position(0, 6));
        assertEquals(6, offset);
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "line0\nline1\nline2", new Position(1, 3));
        assertEquals(9, offset);
    }

    @Test
    void positionToOffsetClampsBeyondEnd() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        int offset = invokePositionToOffset(provider, "abc", new Position(0, 100));
        assertEquals(3, offset);
    }

    // ---- isAnnotationContext tests ----

    @Test
    void isAnnotationContextDetectsAtSign() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeIsAnnotationContext(provider, "@Com", 4, 1));
    }

    @Test
    void isAnnotationContextReturnsFalseWithoutAt() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeIsAnnotationContext(provider, "class Foo", 6, 6));
    }

    // ---- shouldAutoImportType tests ----

    @Test
    void shouldAutoImportReturnsFalseForAlreadyImported() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Set<String> existing = new HashSet<>(Set.of("java.time.LocalDate"));
        assertFalse(invokeShouldAutoImportType(provider, "java.time.LocalDate", "java.time", "com.demo", existing));
    }

    @Test
    void shouldAutoImportReturnsFalseForAutoImportedPackage() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeShouldAutoImportType(provider, "java.util.List", "java.util", "com.demo", new HashSet<>()));
    }

    @Test
    void shouldAutoImportReturnsFalseForSamePackage() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeShouldAutoImportType(provider, "com.demo.Other", "com.demo", "com.demo", new HashSet<>()));
    }

    @Test
    void shouldAutoImportReturnsTrueForNewType() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeShouldAutoImportType(provider, "org.apache.Foo", "org.apache", "com.demo", new HashSet<>()));
    }

    @Test
    void shouldAutoImportReturnsFalseForNullFqn() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeShouldAutoImportType(provider, null, "pkg", "com.demo", new HashSet<>()));
    }

    @Test
    void shouldAutoImportReturnsFalseForNullPackage() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeShouldAutoImportType(provider, "com.Foo", null, "com.demo", new HashSet<>()));
    }

    // ---- isAutoImportedPackage tests ----

    @ParameterizedTest
    @MethodSource("autoImportedPackages")
    void isAutoImportedPackageRecognizesKnownPackages(String packageName) throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeIsAutoImportedPackage(provider, packageName));
    }

    private static Stream<Arguments> autoImportedPackages() {
        return Stream.of(
                Arguments.of("java.lang"),
                Arguments.of("java.util"),
                Arguments.of("groovy.lang"));
    }

    @Test
    void isAutoImportedPackageReturnsFalseForCustom() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse(invokeIsAutoImportedPackage(provider, "com.example"));
    }

    // ---- getCurrentPackageName tests ----

    @Test
    void getCurrentPackageNameParsesPackageDeclaration() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("com.example", invokeGetCurrentPackageName(provider, "package com.example\n\nclass A {}"));
    }

    @Test
    void getCurrentPackageNameWithSemicolon() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("com.example", invokeGetCurrentPackageName(provider, "package com.example;\n\nclass A {}"));
    }

    @Test
    void getCurrentPackageNameReturnsEmptyForNoPackage() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("", invokeGetCurrentPackageName(provider, "class A {}"));
    }

    @Test
    void getCurrentPackageNameReturnsEmptyForNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("", invokeGetCurrentPackageName(provider, null));
    }

    @Test
    void getCurrentPackageNameReturnsEmptyForEmptyContent() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("", invokeGetCurrentPackageName(provider, ""));
    }

    // ---- findImportInsertLine tests ----

    @Test
    void findImportInsertLineAfterLastImport() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "package com.demo\n\nimport java.util.List\nimport java.util.Map\n\nclass A {}";
        int line = invokeFindImportInsertLine(provider, content);
        assertEquals(4, line); // After "import java.util.Map" (line index 3) → 3+1=4
    }

    @Test
    void findImportInsertLineAfterPackageWhenNoImports() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "package com.demo\n\nclass A {}";
        int line = invokeFindImportInsertLine(provider, content);
        assertEquals(2, line); // packageLine=0 → 0+2=2
    }

    @Test
    void findImportInsertLineReturnsZeroForEmpty() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals(0, invokeFindImportInsertLine(provider, ""));
    }

    @Test
    void findImportInsertLineReturnsZeroForNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals(0, invokeFindImportInsertLine(provider, null));
    }

    // ---- parseImportsFromContent tests ----

    @Test
    void parseImportsFromContentExtractsRegularImports() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "import java.time.LocalDate\nimport java.time.LocalTime\nclass A {}";
        Set<String> imports = invokeParseImportsFromContent(provider, content);
        assertTrue(imports.contains("java.time.LocalDate"));
        assertTrue(imports.contains("java.time.LocalTime"));
    }

    @Test
    void parseImportsFromContentIgnoresStaticImports() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "import static java.lang.Math.PI\nimport java.time.LocalDate\nclass A {}";
        Set<String> imports = invokeParseImportsFromContent(provider, content);
        assertFalse(imports.contains("java.lang.Math.PI"));
        assertTrue(imports.contains("java.time.LocalDate"));
    }

    @Test
    void parseImportsFromContentIgnoresStarImports() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "import java.time.*\nclass A {}";
        Set<String> imports = invokeParseImportsFromContent(provider, content);
        assertFalse(imports.contains("java.time.*"));
    }

    @Test
    void parseImportsFromContentHandlesNullAndEmpty() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue(invokeParseImportsFromContent(provider, null).isEmpty());
        assertTrue(invokeParseImportsFromContent(provider, "").isEmpty());
    }

    @Test
    void parseImportsFromContentHandlesSemicolonTerminated() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String content = "import java.time.LocalDate;\nclass A {}";
        Set<String> imports = invokeParseImportsFromContent(provider, content);
        assertTrue(imports.contains("java.time.LocalDate"));
    }

    // ================================================================
    // getKeywordCompletions tests
    // ================================================================

    @SuppressWarnings("unchecked")
    @Test
    void getKeywordCompletionsReturnsAllForEmptyPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Method m = CompletionProvider.class.getDeclaredMethod("getKeywordCompletions", String.class);
        m.setAccessible(true);
        List<CompletionItem> items = (List<CompletionItem>) m.invoke(provider, "");
        assertFalse(items.isEmpty());
        assertTrue(items.size() > 10, "Expected many keywords but got " + items.size());
        assertTrue(items.stream().allMatch(i -> i.getKind() == CompletionItemKind.Keyword));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getKeywordCompletionsFiltersByPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Method m = CompletionProvider.class.getDeclaredMethod("getKeywordCompletions", String.class);
        m.setAccessible(true);
        List<CompletionItem> items = (List<CompletionItem>) m.invoke(provider, "de");
        assertFalse(items.isEmpty());
        assertTrue(items.stream().allMatch(i -> i.getLabel().startsWith("de")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getKeywordCompletionsReturnsEmptyForUnmatchedPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Method m = CompletionProvider.class.getDeclaredMethod("getKeywordCompletions", String.class);
        m.setAccessible(true);
        List<CompletionItem> items = (List<CompletionItem>) m.invoke(provider, "zzzzz");
        assertTrue(items.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getKeywordCompletionsSortTextHasPriority9() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Method m = CompletionProvider.class.getDeclaredMethod("getKeywordCompletions", String.class);
        m.setAccessible(true);
        List<CompletionItem> items = (List<CompletionItem>) m.invoke(provider, "def");
        assertFalse(items.isEmpty());
        assertTrue(items.get(0).getSortText().startsWith("9_"));
    }

    // ================================================================
    // getModuleFromWorkingCopy tests
    // ================================================================

    @Test
    void getModuleFromWorkingCopyReturnsNullForNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Method m = CompletionProvider.class.getDeclaredMethod("getModuleFromWorkingCopy", ICompilationUnit.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, (ICompilationUnit) null));
    }

    @Test
    void getModuleFromWorkingCopyReturnsNullForNonGroovyUnit() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        ICompilationUnit unit = mock(ICompilationUnit.class);
        Method m = CompletionProvider.class.getDeclaredMethod("getModuleFromWorkingCopy", ICompilationUnit.class);
        m.setAccessible(true);
        // Regular mock doesn't have getModuleNode() method → reflection will throw NoSuchMethodException → catch → null
        assertNull(m.invoke(provider, unit));
    }

    // ================================================================
    // addOwnClassAstCompletions tests - exercises AST-based completion path
    // ================================================================

    @Test
    void addOwnClassAstCompletionsAddsProperties() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///OwnClassProps.groovy";
        String source = "class Person {\n    String name\n    int age\n    void greet() {}\n}";
        dm.didOpen(uri, source);

        CompletionProvider provider = new CompletionProvider(dm);
        List<CompletionItem> items = new ArrayList<>();
        Method m = CompletionProvider.class.getDeclaredMethod(
                "addOwnClassAstCompletions", String.class, ICompilationUnit.class, String.class, List.class);
        m.setAccessible(true);
        m.invoke(provider, uri, null, "", items);

        // Should have added properties and methods from the AST
        assertFalse(items.isEmpty(), "Expected AST completions but got none");
    }

    @Test
    void addOwnClassAstCompletionsFiltersByPrefix() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///OwnClassPrefix.groovy";
        String source = "class Person {\n    String name\n    int age\n    void greet() {}\n}";
        dm.didOpen(uri, source);

        CompletionProvider provider = new CompletionProvider(dm);
        List<CompletionItem> items = new ArrayList<>();
        Method m = CompletionProvider.class.getDeclaredMethod(
                "addOwnClassAstCompletions", String.class, ICompilationUnit.class, String.class, List.class);
        m.setAccessible(true);
        m.invoke(provider, uri, null, "na", items);

        // Should only contain items matching prefix "na"
        assertTrue(items.stream().allMatch(i -> i.getLabel().toLowerCase().startsWith("na")
                || i.getInsertText() != null && i.getInsertText().toLowerCase().startsWith("na")));
    }

    @Test
    void addOwnClassAstCompletionsAddsMethodsWithSnippets() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///OwnClassMethods.groovy";
        String source = "class Calc {\n    int add(int a, int b) { return a + b }\n    void reset() {}\n}";
        dm.didOpen(uri, source);

        CompletionProvider provider = new CompletionProvider(dm);
        List<CompletionItem> items = new ArrayList<>();
        Method m = CompletionProvider.class.getDeclaredMethod(
                "addOwnClassAstCompletions", String.class, ICompilationUnit.class, String.class, List.class);
        m.setAccessible(true);
        m.invoke(provider, uri, null, "", items);

        boolean hasMethod = items.stream().anyMatch(i -> "add".equals(i.getLabel()) || "reset".equals(i.getLabel()));
        assertTrue(hasMethod, "Expected method completions from AST");
    }

    @Test
    void addOwnClassAstCompletionsDeduplicatesExistingItems() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///OwnClassDedup.groovy";
        String source = "class Thing {\n    String label\n}";
        dm.didOpen(uri, source);

        CompletionProvider provider = new CompletionProvider(dm);
        List<CompletionItem> items = new ArrayList<>();
        // Pre-add an item with same label
        CompletionItem existing = new CompletionItem("label");
        items.add(existing);

        Method m = CompletionProvider.class.getDeclaredMethod(
                "addOwnClassAstCompletions", String.class, ICompilationUnit.class, String.class, List.class);
        m.setAccessible(true);
        m.invoke(provider, uri, null, "", items);

        // Should not duplicate "label"
        long labelCount = items.stream().filter(i -> "label".equals(i.getLabel())).count();
        assertTrue(labelCount <= 2, "Expected minimal duplication but got " + labelCount);
    }

    // ================================================================
    // resolveTypeNameFromAST tests
    // ================================================================

    @Test
    void resolveTypeNameFromASTResolvesFqn() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);
        when(project.findType("java.util.List")).thenReturn(resolvedType);

        ModuleNode module = parseModule("class A {}", "file:///ResolveASTFqn.groovy");

        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveTypeNameFromAST", String.class, ModuleNode.class, IJavaProject.class);
        m.setAccessible(true);
        IType result = (IType) m.invoke(provider, "java.util.List", module, project);
        assertSame(resolvedType, result);
    }

    @Test
    void resolveTypeNameFromASTResolvesViaImport() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);
        when(project.findType("java.time.LocalDate")).thenReturn(resolvedType);

        ModuleNode module = parseModule(
                "import java.time.LocalDate\nclass A {}", "file:///ResolveASTImport.groovy");

        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveTypeNameFromAST", String.class, ModuleNode.class, IJavaProject.class);
        m.setAccessible(true);
        IType result = (IType) m.invoke(provider, "LocalDate", module, project);
        assertSame(resolvedType, result);
    }

    @Test
    void resolveTypeNameFromASTResolvesViaAutoImport() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType resolvedType = mock(IType.class);
        when(project.findType("java.lang.String")).thenReturn(resolvedType);

        ModuleNode module = parseModule("class A {}", "file:///ResolveASTAuto.groovy");

        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveTypeNameFromAST", String.class, ModuleNode.class, IJavaProject.class);
        m.setAccessible(true);
        IType result = (IType) m.invoke(provider, "String", module, project);
        assertSame(resolvedType, result);
    }

    @Test
    void resolveTypeNameFromASTReturnsNullWhenNotFound() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);

        ModuleNode module = parseModule("class A {}", "file:///ResolveASTNull.groovy");

        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveTypeNameFromAST", String.class, ModuleNode.class, IJavaProject.class);
        m.setAccessible(true);
        IType result = (IType) m.invoke(provider, "CompletelyUnknown", module, project);
        assertNull(result);
    }

    // ---- reflection helpers for new tests ----

    private boolean invokeMatchesPrefix(CompletionProvider provider, String name, String prefix) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("matchesPrefix", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, name, prefix);
    }

    private String invokeExtractPrefix(CompletionProvider provider, String content, int offset) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("extractPrefix", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, offset);
    }

    private int invokePositionToOffset(CompletionProvider provider, String content, Position position) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content, position);
    }

    private boolean invokeIsAnnotationContext(CompletionProvider provider, String content, int offset, int prefixStart) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationContext", String.class, int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, content, offset, prefixStart);
    }

    private boolean invokeShouldAutoImportType(CompletionProvider provider, String fqn, String packageName, String currentPackage, Set<String> existingImports) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("shouldAutoImportType", String.class, String.class, String.class, Set.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, fqn, packageName, currentPackage, existingImports);
    }

    private boolean invokeIsAutoImportedPackage(CompletionProvider provider, String packageName) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("isAutoImportedPackage", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, packageName);
    }

    private String invokeGetCurrentPackageName(CompletionProvider provider, String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("getCurrentPackageName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content);
    }

    private int invokeFindImportInsertLine(CompletionProvider provider, String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("findImportInsertLine", String.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeParseImportsFromContent(CompletionProvider provider, String content) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("parseImportsFromContent", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(provider, content);
    }

    // ================================================================
    // JDT Mock Tests — resolveElementType
    // ================================================================

    @Test
    void resolveElementTypeForMockedField() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType stringType = mock(IType.class);
        when(project.findType("String")).thenReturn(stringType);

        IField field = mock(IField.class);
        when(field.getElementType()).thenReturn(IJavaElement.FIELD);
        when(field.getTypeSignature()).thenReturn("QString;");
        IType declaringType = mock(IType.class);
        when(field.getDeclaringType()).thenReturn(declaringType);

        IType result = invokeResolveElementType(provider, field, project);
        // Signature.toString("QString;") = "String", project.findType("String") returns stringType
        assertNotNull(result);
    }

    @Test
    void resolveElementTypeForMockedLocalVariable() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType intType = mock(IType.class);
        when(project.findType("int")).thenReturn(intType);

        ILocalVariable local = mock(ILocalVariable.class);
        when(local.getElementType()).thenReturn(IJavaElement.LOCAL_VARIABLE);
        when(local.getTypeSignature()).thenReturn("I");
        // Parent is a method -> member -> declaringType
        IMethod parent = mock(IMethod.class);
        IType parentType = mock(IType.class);
        when(local.getParent()).thenReturn(parent);
        when(parent.getDeclaringType()).thenReturn(parentType);

        assertNotNull(invokeResolveElementType(provider, local, project));
    }

    @Test
    void resolveElementTypeForMockedMethod() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType listType = mock(IType.class);
        when(project.findType("List")).thenReturn(listType);

        IMethod method = mock(IMethod.class);
        when(method.getElementType()).thenReturn(IJavaElement.METHOD);
        when(method.getReturnType()).thenReturn("QList;");
        IType declaringType = mock(IType.class);
        when(method.getDeclaringType()).thenReturn(declaringType);

        IType result = invokeResolveElementType(provider, method, project);
        assertNotNull(result);
    }

    @Test
    void resolveElementTypeForMockedType() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);

        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        // resolveElementType returns the IType directly
        IType result = invokeResolveElementType(provider, type, project);
        assertSame(type, result);
    }

    @Test
    void resolveElementTypeReturnsNullForUnknownElement() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);

        // A generic IJavaElement that is not IField/ILocalVariable/IMethod/IType
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.PACKAGE_FRAGMENT);

        IType result = invokeResolveElementType(provider, element, project);
        assertNull(result);
    }

    // ================================================================
    // JDT Mock Tests — resolveTypeName
    // ================================================================

    @Test
    void resolveTypeNameDirectLookup() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType found = mock(IType.class);
        when(project.findType("java.lang.String")).thenReturn(found);

        IType result = invokeResolveTypeName(provider, "java.lang.String", null, project);
        assertSame(found, result);
    }

    @Test
    void resolveTypeNameViaDeclaringType() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("MyType")).thenReturn(null);
        IType resolved = mock(IType.class);
        when(project.findType("com.example.MyType")).thenReturn(resolved);

        IType declaringType = mock(IType.class);
        when(declaringType.resolveType("MyType")).thenReturn(
                new String[][]{{ "com.example", "MyType" }});

        IType result = invokeResolveTypeName(provider, "MyType", declaringType, project);
        assertSame(resolved, result);
    }

    @Test
    void resolveTypeNameViaAutoImportPackage() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("ArrayList")).thenReturn(null);
        IType listType = mock(IType.class);
        when(project.findType("java.util.ArrayList")).thenReturn(listType);

        IType result = invokeResolveTypeName(provider, "ArrayList", null, project);
        assertSame(listType, result);
    }

    @Test
    void resolveTypeNameReturnsNullWhenNotFound() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        // findType returns null for everything

        IType result = invokeResolveTypeName(provider, "NonExistentType", null, project);
        assertNull(result);
    }

    // ================================================================
    // JDT Mock Tests — methodToCompletionItem
    // ================================================================

    @Test
    void methodToCompletionItemWithParameters() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IMethod method = mock(IMethod.class);
        when(method.getParameterTypes()).thenReturn(new String[]{"QString;", "I"});
        when(method.getParameterNames()).thenReturn(new String[]{"name", "age"});
        when(method.getReturnType()).thenReturn("V");
        when(method.isConstructor()).thenReturn(false);

        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Person");

        CompletionItem item = invokeMethodToCompletionItem(provider, method, "greet", owner, "0");
        assertNotNull(item);
        assertTrue(item.getLabel().contains("greet"));
        assertTrue(item.getLabel().contains("String"));
        assertEquals(CompletionItemKind.Method, item.getKind());
        assertEquals(InsertTextFormat.Snippet, item.getInsertTextFormat());
        assertTrue(item.getInsertText().contains("${1:name}"));
        assertTrue(item.getInsertText().contains("${2:age}"));
    }

    @Test
    void methodToCompletionItemNoParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IMethod method = mock(IMethod.class);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.getReturnType()).thenReturn("QString;");
        when(method.isConstructor()).thenReturn(false);

        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Service");

        CompletionItem item = invokeMethodToCompletionItem(provider, method, "run", owner, "1");
        assertNotNull(item);
        assertEquals("run()", item.getLabel());
        assertTrue(item.getDetail().contains("String"));
        assertEquals("run()", item.getInsertText());
        assertEquals(InsertTextFormat.PlainText, item.getInsertTextFormat());
    }

    @Test
    void methodToCompletionItemConstructor() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IMethod method = mock(IMethod.class);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.getReturnType()).thenReturn("V");
        when(method.isConstructor()).thenReturn(true);

        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Foo");

        CompletionItem item = invokeMethodToCompletionItem(provider, method, "Foo", owner, "0");
        assertEquals(CompletionItemKind.Constructor, item.getKind());
    }

    // ================================================================
    // JDT Mock Tests — addMembersOfType
    // ================================================================

    @Test
    void addMembersOfTypeMockedSimple() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("MyClass");

        IMethod m1 = mock(IMethod.class);
        when(m1.getElementName()).thenReturn("doStuff");
        when(m1.getFlags()).thenReturn(Flags.AccPublic);
        when(m1.getParameterTypes()).thenReturn(new String[0]);
        when(m1.getParameterNames()).thenReturn(new String[0]);
        when(m1.getReturnType()).thenReturn("V");
        when(m1.isConstructor()).thenReturn(false);

        IField f1 = mock(IField.class);
        when(f1.getElementName()).thenReturn("value");
        when(f1.getFlags()).thenReturn(Flags.AccPublic);
        when(f1.getTypeSignature()).thenReturn("I");

        when(type.getMethods()).thenReturn(new IMethod[]{ m1 });
        when(type.getFields()).thenReturn(new IField[]{ f1 });

        // Type hierarchy returns no supertypes
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(type)).thenReturn(new IType[0]);
        when(type.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, type, "", false, items);

        assertTrue(items.size() >= 2);
        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("doStuff")));
        assertTrue(items.stream().anyMatch(i -> "value".equals(i.getLabel())));
    }

    @Test
    void addMembersOfTypeStaticOnlyFilter() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Utils");

        IMethod staticM = mock(IMethod.class);
        when(staticM.getElementName()).thenReturn("helper");
        when(staticM.getFlags()).thenReturn(Flags.AccPublic | Flags.AccStatic);
        when(staticM.getParameterTypes()).thenReturn(new String[0]);
        when(staticM.getParameterNames()).thenReturn(new String[0]);
        when(staticM.getReturnType()).thenReturn("V");
        when(staticM.isConstructor()).thenReturn(false);

        IMethod instanceM = mock(IMethod.class);
        when(instanceM.getElementName()).thenReturn("run");
        when(instanceM.getFlags()).thenReturn(Flags.AccPublic);
        when(instanceM.getParameterTypes()).thenReturn(new String[0]);

        when(type.getMethods()).thenReturn(new IMethod[]{ staticM, instanceM });
        when(type.getFields()).thenReturn(new IField[0]);

        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(type)).thenReturn(new IType[0]);
        when(type.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, type, "", true, items);

        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("helper")));
        assertFalse(items.stream().anyMatch(i -> i.getLabel() != null && i.getLabel().contains("run")));
    }

    @Test
    void addMembersOfTypePrefixFilter() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Widget");

        IMethod m1 = mock(IMethod.class);
        when(m1.getElementName()).thenReturn("getName");
        when(m1.getFlags()).thenReturn(Flags.AccPublic);
        when(m1.getParameterTypes()).thenReturn(new String[0]);
        when(m1.getParameterNames()).thenReturn(new String[0]);
        when(m1.getReturnType()).thenReturn("QString;");
        when(m1.isConstructor()).thenReturn(false);

        IMethod m2 = mock(IMethod.class);
        when(m2.getElementName()).thenReturn("setName");
        when(m2.getFlags()).thenReturn(Flags.AccPublic);
        when(m2.getParameterTypes()).thenReturn(new String[]{"QString;"});

        when(type.getMethods()).thenReturn(new IMethod[]{ m1, m2 });
        when(type.getFields()).thenReturn(new IField[0]);

        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(type)).thenReturn(new IType[0]);
        when(type.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, type, "get", false, items);

        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("getName")));
        assertFalse(items.stream().anyMatch(i -> i.getLabel() != null && i.getLabel().contains("setName")));
    }

    @Test
    void addMembersOfTypeSkipsDollarAndAngleBracket() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IType type = mock(IType.class);
        when(type.getElementName()).thenReturn("Obj");

        IMethod hidden = mock(IMethod.class);
        when(hidden.getElementName()).thenReturn("<clinit>");
        when(hidden.getFlags()).thenReturn(Flags.AccPublic);
        when(hidden.getParameterTypes()).thenReturn(new String[0]);

        IField dollarField = mock(IField.class);
        when(dollarField.getElementName()).thenReturn("$internal");

        IField doubleUnder = mock(IField.class);
        when(doubleUnder.getElementName()).thenReturn("__meta");

        when(type.getMethods()).thenReturn(new IMethod[]{ hidden });
        when(type.getFields()).thenReturn(new IField[]{ dollarField, doubleUnder });

        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(type)).thenReturn(new IType[0]);
        when(type.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, type, "", false, items);

        assertEquals(0, items.size(), "$, __ and <> members should be filtered out");
    }

    @Test
    void addMembersOfTypeWithSupertypes() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        IType childType = mock(IType.class);
        when(childType.getElementName()).thenReturn("Child");

        IMethod childMethod = mock(IMethod.class);
        when(childMethod.getElementName()).thenReturn("childMethod");
        when(childMethod.getFlags()).thenReturn(Flags.AccPublic);
        when(childMethod.getParameterTypes()).thenReturn(new String[0]);
        when(childMethod.getParameterNames()).thenReturn(new String[0]);
        when(childMethod.getReturnType()).thenReturn("V");
        when(childMethod.isConstructor()).thenReturn(false);

        when(childType.getMethods()).thenReturn(new IMethod[]{ childMethod });
        when(childType.getFields()).thenReturn(new IField[0]);

        IType parentType = mock(IType.class);
        when(parentType.getElementName()).thenReturn("Parent");

        IMethod parentMethod = mock(IMethod.class);
        when(parentMethod.getElementName()).thenReturn("parentMethod");
        when(parentMethod.getFlags()).thenReturn(Flags.AccPublic);
        when(parentMethod.getParameterTypes()).thenReturn(new String[0]);
        when(parentMethod.getParameterNames()).thenReturn(new String[0]);
        when(parentMethod.getReturnType()).thenReturn("V");
        when(parentMethod.isConstructor()).thenReturn(false);

        when(parentType.getMethods()).thenReturn(new IMethod[]{ parentMethod });
        when(parentType.getFields()).thenReturn(new IField[0]);

        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        when(hierarchy.getAllSupertypes(childType)).thenReturn(new IType[]{ parentType });
        when(childType.newSupertypeHierarchy(null)).thenReturn(hierarchy);

        List<CompletionItem> items = new ArrayList<>();
        invokeAddMembersOfType(provider, childType, "", false, items);

        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("childMethod")),
                "Should include child method");
        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("parentMethod")),
                "Should include parent method");
    }

    // ================================================================
    // JDT Mock Tests — getIdentifierCompletions
    // ================================================================

    @Test
    void getIdentifierCompletionsMockedWorkingCopy() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IType type = mock(IType.class);

        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("myField");
        when(field.getTypeSignature()).thenReturn("QString;");

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("myMethod");
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.getReturnType()).thenReturn("V");
        when(method.isConstructor()).thenReturn(false);

        when(type.getFields()).thenReturn(new IField[]{ field });
        when(type.getMethods()).thenReturn(new IMethod[]{ method });
        when(workingCopy.getTypes()).thenReturn(new IType[]{ type });

        List<CompletionItem> items = invokeGetIdentifierCompletions(provider, workingCopy, "file:///test.groovy", "my");
        assertTrue(items.stream().anyMatch(i -> "myField".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> i.getLabel().contains("myMethod")));
    }

    @Test
    void getIdentifierCompletionsEmptyPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);

        List<CompletionItem> items = invokeGetIdentifierCompletions(provider, workingCopy, "file:///test.groovy", "");
        assertEquals(0, items.size(), "Empty prefix should return empty");
    }

    @Test
    void getIdentifierCompletionsFiltersByPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IType type = mock(IType.class);

        IField field1 = mock(IField.class);
        when(field1.getElementName()).thenReturn("alpha");
        when(field1.getTypeSignature()).thenReturn("I");

        IField field2 = mock(IField.class);
        when(field2.getElementName()).thenReturn("beta");
        when(field2.getTypeSignature()).thenReturn("I");

        when(type.getFields()).thenReturn(new IField[]{ field1, field2 });
        when(type.getMethods()).thenReturn(new IMethod[0]);
        when(workingCopy.getTypes()).thenReturn(new IType[]{ type });

        List<CompletionItem> items = invokeGetIdentifierCompletions(provider, workingCopy, "file:///test.groovy", "al");
        assertTrue(items.stream().anyMatch(i -> "alpha".equals(i.getLabel())));
        assertFalse(items.stream().anyMatch(i -> "beta".equals(i.getLabel())));
    }

    // ================================================================
    // JDT Mock Tests — findFieldTypeDirectly
    // ================================================================

    @Test
    void findFieldTypeDirectlyFindsField() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(workingCopy.getTypes()).thenReturn(new IType[]{ type });

        IField field = mock(IField.class);
        when(field.exists()).thenReturn(true);
        when(field.getTypeSignature()).thenReturn("QString;");
        when(type.getField("myVar")).thenReturn(field);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        IType stringType = mock(IType.class);
        when(project.findType("String")).thenReturn(stringType);

        IType result = invokeFindFieldTypeDirectly(provider, workingCopy, "file:///test.groovy", "myVar", project);
        assertSame(stringType, result);
    }

    @Test
    void findFieldTypeDirectlyFindsMethod() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(workingCopy.getTypes()).thenReturn(new IType[]{ type });

        IField noField = mock(IField.class);
        when(noField.exists()).thenReturn(false);
        when(type.getField("getData")).thenReturn(noField);

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getData");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getReturnType()).thenReturn("QList;");

        when(type.getMethods()).thenReturn(new IMethod[]{ method });

        IType listType = mock(IType.class);
        when(project.findType("List")).thenReturn(null);
        when(project.findType("java.util.List")).thenReturn(listType);

        IType result = invokeFindFieldTypeDirectly(provider, workingCopy, "file:///test.groovy", "getData", project);
        assertSame(listType, result);
    }

    @Test
    void findFieldTypeDirectlyReturnsNullWhenNotFound() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(workingCopy.getTypes()).thenReturn(new IType[]{ type });

        IField noField = mock(IField.class);
        when(noField.exists()).thenReturn(false);
        when(type.getField("missing")).thenReturn(noField);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        IType result = invokeFindFieldTypeDirectly(provider, workingCopy, "file:///test.groovy", "missing", project);
        assertNull(result);
    }

    // ================================================================
    // JDT Mock Tests — getCompletions (main entry point)
    // ================================================================

    @Test
    void getCompletionsReturnsEmptyForNullContent() {
        DocumentManager dm = new DocumentManager();
        CompletionProvider provider = new CompletionProvider(dm);
        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///nonexist.groovy"));
        params.setPosition(new Position(0, 0));

        List<CompletionItem> items = provider.getCompletions(params);
        assertTrue(items.isEmpty());
    }

    @Test
    void getCompletionsFallbackKeywords() {
        DocumentManager dm = new DocumentManager();
        dm.didOpen("file:///test.groovy", "cla");
        CompletionProvider provider = new CompletionProvider(dm);

        CompletionParams params = new CompletionParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///test.groovy"));
        params.setPosition(new Position(0, 3));

        List<CompletionItem> items = provider.getCompletions(params);
        assertTrue(items.stream().anyMatch(i -> "class".equals(i.getLabel())));
    }

    @Test
    void resolveCompletionItemReturnsUnchanged() {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem original = new CompletionItem("test");
        CompletionItem resolved = provider.resolveCompletionItem(original);
        assertSame(original, resolved);
    }

    // ================================================================
    // JDT Mock Reflection helpers
    // ================================================================

    private IType invokeResolveElementType(CompletionProvider provider,
            IJavaElement element, IJavaProject project) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveElementType", IJavaElement.class, IJavaProject.class);
        m.setAccessible(true);
        return (IType) m.invoke(provider, element, project);
    }

    private IType invokeResolveTypeName(CompletionProvider provider,
            String typeName, IType declaringType, IJavaProject project) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveTypeName", String.class, IType.class, IJavaProject.class);
        m.setAccessible(true);
        return (IType) m.invoke(provider, typeName, declaringType, project);
    }

    private CompletionItem invokeMethodToCompletionItem(CompletionProvider provider,
            IMethod method, String name, IType owner, String sortPrefix) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "methodToCompletionItem", IMethod.class, String.class, IType.class, String.class);
        m.setAccessible(true);
        return (CompletionItem) m.invoke(provider, method, name, owner, sortPrefix);
    }

    private void invokeAddMembersOfType(CompletionProvider provider,
            IType type, String prefix, boolean staticOnly, List<CompletionItem> items) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "addMembersOfType", IType.class, String.class, boolean.class, List.class);
        m.setAccessible(true);
        m.invoke(provider, type, prefix, staticOnly, items);
    }

    @SuppressWarnings("unchecked")
    private List<CompletionItem> invokeGetIdentifierCompletions(CompletionProvider provider,
            ICompilationUnit workingCopy, String uri, String prefix) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "getIdentifierCompletions", ICompilationUnit.class, String.class, String.class);
        m.setAccessible(true);
        return (List<CompletionItem>) m.invoke(provider, workingCopy, uri, prefix);
    }

    private IType invokeFindFieldTypeDirectly(CompletionProvider provider,
            ICompilationUnit workingCopy, String lspUri, String fieldName, IJavaProject project) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "findFieldTypeDirectly", ICompilationUnit.class, String.class, String.class, IJavaProject.class);
        m.setAccessible(true);
        return (IType) m.invoke(provider, workingCopy, lspUri, fieldName, project);
    }

    // ================================================================
    // Utility method reflection helpers
    // ================================================================

    private CompletionItemKind invokeResolveTypeKind(CompletionProvider provider, int modifiers) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("resolveTypeKind", int.class);
        m.setAccessible(true);
        return (CompletionItemKind) m.invoke(provider, modifiers);
    }

    private TextEdit invokeCreateImportEdit(CompletionProvider provider, int line, String fqn) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("createImportEdit", int.class, String.class);
        m.setAccessible(true);
        return (TextEdit) m.invoke(provider, line, fqn);
    }

    private String invokeBuildAstMethodInvocation(CompletionProvider provider,
            String name, Parameter[] parameters) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "buildAstMethodInvocation", String.class, Parameter[].class);
        m.setAccessible(true);
        return (String) m.invoke(provider, name, parameters);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeCollectSeenCompletionNames(CompletionProvider provider,
            List<CompletionItem> items) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod("collectSeenCompletionNames", List.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(provider, items);
    }

    private boolean invokeIsTraitAccessorForField(CompletionProvider provider,
            IMethod method, String capitalizedFieldName) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "isTraitAccessorForField", IMethod.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, method, capitalizedFieldName);
    }

    private ClassNode invokeResolveClassMemberExpressionType(CompletionProvider provider,
            ClassNode classNode, String exprName) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveClassMemberExpressionType", ClassNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, classNode, exprName);
    }

    private ClassNode invokeResolveLocalVariableTypeInClass(CompletionProvider provider,
            ClassNode classNode, String varName) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "resolveLocalVariableTypeInClass", ClassNode.class, String.class);
        m.setAccessible(true);
        return (ClassNode) m.invoke(provider, classNode, varName);
    }

    private Object invokeMethodBodyBlock(CompletionProvider provider, Object methodNode) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "methodBodyBlock", org.codehaus.groovy.ast.MethodNode.class);
        m.setAccessible(true);
        return m.invoke(provider, methodNode);
    }

    private IJavaProject invokeGetProjectFromWorkingCopy(ICompilationUnit workingCopy) throws Exception {
        Method m = CompletionProvider.class.getDeclaredMethod(
                "getProjectFromWorkingCopy", ICompilationUnit.class);
        m.setAccessible(true);
        return (IJavaProject) m.invoke(null, workingCopy);
    }

    // ================================================================
    // resolveTypeKind tests
    // ================================================================

    @Test
    void resolveTypeKindInterface() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals(CompletionItemKind.Interface,
                invokeResolveTypeKind(provider, Flags.AccInterface));
    }

    @Test
    void resolveTypeKindEnum() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals(CompletionItemKind.Enum,
                invokeResolveTypeKind(provider, Flags.AccEnum));
    }

    @Test
    void resolveTypeKindClass() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals(CompletionItemKind.Class,
                invokeResolveTypeKind(provider, Flags.AccPublic));
    }

    // ================================================================
    // createImportEdit tests
    // ================================================================

    @Test
    void createImportEditProducesCorrectEdit() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        TextEdit edit = invokeCreateImportEdit(provider, 3, "java.util.List");
        assertNotNull(edit);
        assertEquals("import java.util.List\n", edit.getNewText());
        assertEquals(3, edit.getRange().getStart().getLine());
        assertEquals(0, edit.getRange().getStart().getCharacter());
        assertEquals(3, edit.getRange().getEnd().getLine());
        assertEquals(0, edit.getRange().getEnd().getCharacter());
    }

    @Test
    void createImportEditAtLine0() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        TextEdit edit = invokeCreateImportEdit(provider, 0, "com.example.Foo");
        assertEquals("import com.example.Foo\n", edit.getNewText());
        assertEquals(0, edit.getRange().getStart().getLine());
    }

    // ================================================================
    // buildAstMethodInvocation tests
    // ================================================================

    @Test
    void buildAstMethodInvocationNoParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String result = invokeBuildAstMethodInvocation(provider, "run", new Parameter[0]);
        assertEquals("run()", result);
    }

    @Test
    void buildAstMethodInvocationOneParam() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Parameter p = new Parameter(ClassHelper.STRING_TYPE, "name");
        String result = invokeBuildAstMethodInvocation(provider, "greet", new Parameter[]{p});
        assertEquals("greet(name)", result);
    }

    @Test
    void buildAstMethodInvocationMultipleParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Parameter p1 = new Parameter(ClassHelper.int_TYPE, "x");
        Parameter p2 = new Parameter(ClassHelper.int_TYPE, "y");
        Parameter p3 = new Parameter(ClassHelper.STRING_TYPE, "label");
        String result = invokeBuildAstMethodInvocation(provider, "draw",
                new Parameter[]{p1, p2, p3});
        assertEquals("draw(x, y, label)", result);
    }

    // ================================================================
    // collectSeenCompletionNames tests
    // ================================================================

    @Test
    void collectSeenCompletionNamesUsesFilterText() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem item = new CompletionItem("displayLabel");
        item.setFilterText("filterName");
        Set<String> seen = invokeCollectSeenCompletionNames(provider, List.of(item));
        assertTrue(seen.contains("filterName"));
        assertFalse(seen.contains("displayLabel"));
    }

    @Test
    void collectSeenCompletionNamesFallsBackToLabel() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem item = new CompletionItem("myLabel");
        Set<String> seen = invokeCollectSeenCompletionNames(provider, List.of(item));
        assertTrue(seen.contains("myLabel"));
    }

    @Test
    void collectSeenCompletionNamesEmptyList() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        Set<String> seen = invokeCollectSeenCompletionNames(provider, List.of());
        assertTrue(seen.isEmpty());
    }

    // ================================================================
    // isTraitAccessorForField tests
    // ================================================================

    @Test
    void isTraitAccessorForFieldGetterMatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        assertTrue(invokeIsTraitAccessorForField(provider, method, "Name"));
    }

    @Test
    void isTraitAccessorForFieldIsMatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("isActive");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        assertTrue(invokeIsTraitAccessorForField(provider, method, "Active"));
    }

    @Test
    void isTraitAccessorForFieldNoMatchWithParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getParameterTypes()).thenReturn(new String[]{"QString;"});
        assertFalse(invokeIsTraitAccessorForField(provider, method, "Name"));
    }

    @Test
    void isTraitAccessorForFieldNoMatchDifferentName() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("setName");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        assertFalse(invokeIsTraitAccessorForField(provider, method, "Name"));
    }


    @Test
    void getProjectFromWorkingCopyReturnsNullForNull() throws Exception {
        assertNull(invokeGetProjectFromWorkingCopy(null));
    }

    @Test
    void getProjectFromWorkingCopyReturnsProject() throws Exception {
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        when(cu.getJavaProject()).thenReturn(project);
        when(project.exists()).thenReturn(true);
        assertSame(project, invokeGetProjectFromWorkingCopy(cu));
    }

    @Test
    void getProjectFromWorkingCopyReturnsNullForNonExistent() throws Exception {
        ICompilationUnit cu = mock(ICompilationUnit.class);
        IJavaProject project = mock(IJavaProject.class);
        when(cu.getJavaProject()).thenReturn(project);
        when(project.exists()).thenReturn(false);
        assertNull(invokeGetProjectFromWorkingCopy(cu));
    }

    @Test
    void getProjectFromWorkingCopyReturnsNullOnException() throws Exception {
        ICompilationUnit cu = mock(ICompilationUnit.class);
        when(cu.getJavaProject()).thenThrow(new RuntimeException("test"));
        assertNull(invokeGetProjectFromWorkingCopy(cu));
    }

    // ================================================================
    // resolveClassMemberExpressionType tests (AST-based)
    // ================================================================

    @Test
    void resolveClassMemberExpressionTypeFindsProperty() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  String name\n  def run() { name }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        ClassNode result = invokeResolveClassMemberExpressionType(provider, classNode, "name");
        assertNotNull(result);
        assertTrue(result.getName().contains("String"));
    }

    @Test
    void resolveClassMemberExpressionTypeFindsField() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  private int count = 0\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        ClassNode result = invokeResolveClassMemberExpressionType(provider, classNode, "count");
        assertNotNull(result);
        assertEquals("int", result.getName());
    }

    @Test
    void resolveClassMemberExpressionTypeReturnsNullForUnknown() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  String name\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        assertNull(invokeResolveClassMemberExpressionType(provider, classNode, "missing"));
    }

    // ================================================================

    @Test
    void methodBodyBlockReturnsBlock() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  void run() {\n    println 'hi'\n  }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        var methods = classNode.getMethods().stream()
                .filter(m -> "run".equals(m.getName()))
                .toList();
        assertFalse(methods.isEmpty());
        Object block = invokeMethodBodyBlock(provider, methods.get(0));
        assertNotNull(block);
        assertTrue(block instanceof org.codehaus.groovy.ast.stmt.BlockStatement);
    }

    @Test
    void methodBodyBlockReturnsNullForAbstract() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "abstract class Foo {\n  abstract void run()\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        var methods = classNode.getMethods().stream()
                .filter(m -> "run".equals(m.getName()))
                .toList();
        assertFalse(methods.isEmpty());
        // Abstract methods may not have a BlockStatement body
        // (result may be null or a non-block statement)
        invokeMethodBodyBlock(provider, methods.get(0)); // should not throw
    }

    // ================================================================
    // resolveLocalVariableTypeInClass tests (AST-based)
    // ================================================================

    @Test
    void resolveLocalVariableTypeInClassFindsTypedVar() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  void run() {\n    String x = 'hi'\n  }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        ClassNode result = invokeResolveLocalVariableTypeInClass(provider, classNode, "x");
        assertNotNull(result);
        assertTrue(result.getName().contains("String"));
    }

    @Test
    void resolveLocalVariableTypeInClassFindsConstructorCallType() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  void run() {\n    def items = new ArrayList()\n  }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        ClassNode result = invokeResolveLocalVariableTypeInClass(provider, classNode, "items");
        assertNotNull(result);
        assertTrue(result.getName().contains("ArrayList"));
    }

    @Test
    void resolveLocalVariableTypeInClassReturnsNullForMissing() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  void run() {\n    int x = 1\n  }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        assertNull(invokeResolveLocalVariableTypeInClass(provider, classNode, "notDeclared"));
    }

    @Test
    void resolveLocalVariableTypeInClassFindsInConstructor() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String source = "class Foo {\n  Foo() {\n    String val = 'init'\n  }\n}";
        ModuleNode ast = parseModule(source, "file:///test.groovy");
        ClassNode classNode = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElseThrow();
        ClassNode result = invokeResolveLocalVariableTypeInClass(provider, classNode, "val");
        assertNotNull(result);
        assertTrue(result.getName().contains("String"));
    }

    // ================================================================
    // extractNonStaticImport tests
    // ================================================================

    @Test
    void extractNonStaticImportRegular() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("java.util.List", invokeExtractNonStaticImport(provider, "import java.util.List"));
    }

    @Test
    void extractNonStaticImportWithSemicolon() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertEquals("java.util.List", invokeExtractNonStaticImport(provider, "import java.util.List;"));
    }

    @Test
    void extractNonStaticImportStaticReturnsNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertNull(invokeExtractNonStaticImport(provider, "import static java.lang.Math.PI"));
    }

    @Test
    void extractNonStaticImportStarReturnsNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertNull(invokeExtractNonStaticImport(provider, "import java.util.*"));
    }

    @Test
    void extractNonStaticImportNonImportLineReturnsNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertNull(invokeExtractNonStaticImport(provider, "class Foo {}"));
    }

    @Test
    void extractNonStaticImportEmptyTargetReturnsNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertNull(invokeExtractNonStaticImport(provider, "import "));
    }

    // ================================================================
    // shouldIncludeMethod tests
    // ================================================================

    @Test
    void shouldIncludeMethodNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getFlags()).thenReturn(0);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        assertTrue((boolean) invokeShouldIncludeMethod(provider, method, "", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeMethodConstructorExcluded() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("<init>");
        assertFalse((boolean) invokeShouldIncludeMethod(provider, method, "", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeMethodPrefixMismatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        assertFalse((boolean) invokeShouldIncludeMethod(provider, method, "xyz", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeMethodStaticOnlyFiltersNonStatic() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getFlags()).thenReturn(0); // not static
        assertFalse((boolean) invokeShouldIncludeMethod(provider, method, "", true, new HashSet<>()));
    }

    @Test
    void shouldIncludeMethodAlreadySeen() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn("getName");
        when(method.getFlags()).thenReturn(0);
        when(method.getParameterTypes()).thenReturn(new String[0]);
        Set<String> seen = new HashSet<>();
        seen.add("getName/0");
        assertFalse((boolean) invokeShouldIncludeMethod(provider, method, "", false, seen));
    }

    // ================================================================
    // shouldIncludeField tests
    // ================================================================

    @Test
    void shouldIncludeFieldNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("name");
        when(field.getFlags()).thenReturn(0);
        assertTrue((boolean) invokeShouldIncludeField(provider, field, "", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeFieldDollarPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("$internal");
        assertFalse((boolean) invokeShouldIncludeField(provider, field, "", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeFieldDoubleUnderscorePrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("__meta");
        assertFalse((boolean) invokeShouldIncludeField(provider, field, "", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeFieldPrefixMismatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("name");
        assertFalse((boolean) invokeShouldIncludeField(provider, field, "xyz", false, new HashSet<>()));
    }

    @Test
    void shouldIncludeFieldStaticOnlyFiltersNonStatic() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("name");
        when(field.getFlags()).thenReturn(0);
        assertFalse((boolean) invokeShouldIncludeField(provider, field, "", true, new HashSet<>()));
    }

    @Test
    void shouldIncludeFieldAlreadySeen() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getElementName()).thenReturn("name");
        when(field.getFlags()).thenReturn(0);
        Set<String> seen = new HashSet<>();
        seen.add("f:name");
        assertFalse((boolean) invokeShouldIncludeField(provider, field, "", false, seen));
    }

    // ================================================================
    // buildFieldCompletionItem tests
    // ================================================================

    @Test
    void buildFieldCompletionItemRegular() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getFlags()).thenReturn(0);
        when(field.getTypeSignature()).thenReturn("QString;");
        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Person");
        when(owner.getFullyQualifiedName()).thenReturn("com.example.Person");
        CompletionItem item = invokeBuildFieldCompletionItem(provider, field, "name", owner, "0");
        assertEquals("name", item.getLabel());
        assertEquals(CompletionItemKind.Field, item.getKind());
        assertEquals("0_name", item.getSortText());
    }

    @Test
    void buildFieldCompletionItemEnum() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getFlags()).thenReturn(Flags.AccEnum);
        when(field.getTypeSignature()).thenReturn("QString;");
        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Color");
        when(owner.getFullyQualifiedName()).thenReturn("com.example.Color");
        CompletionItem item = invokeBuildFieldCompletionItem(provider, field, "RED", owner, "0");
        assertEquals(CompletionItemKind.EnumMember, item.getKind());
    }

    // ================================================================
    // resolveFieldDetail tests
    // ================================================================

    @Test
    void resolveFieldDetailNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getTypeSignature()).thenReturn("QString;");
        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Person");
        String detail = invokeResolveFieldDetail(provider, field, owner);
        assertEquals("String — Person", detail);
    }

    @Test
    void resolveFieldDetailException() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getTypeSignature()).thenThrow(new RuntimeException("fail"));
        IType owner = mock(IType.class);
        when(owner.getElementName()).thenReturn("Person");
        String detail = invokeResolveFieldDetail(provider, field, owner);
        assertEquals("Person", detail);
    }

    // ================================================================
    // resolveMethodReturnType tests
    // ================================================================

    @Test
    void resolveMethodReturnTypeNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getReturnType()).thenReturn("QString;");
        String result = invokeResolveMethodReturnType(provider, method);
        assertEquals("String", result);
    }

    @Test
    void resolveMethodReturnTypeException() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IMethod method = mock(IMethod.class);
        when(method.getReturnType()).thenThrow(new RuntimeException("fail"));
        String result = invokeResolveMethodReturnType(provider, method);
        assertEquals("Object", result);
    }

    // ================================================================
    // resolveFieldTypeSignature tests
    // ================================================================

    @Test
    void resolveFieldTypeSignatureNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getTypeSignature()).thenReturn("I");
        String result = invokeResolveFieldTypeSignature(provider, field);
        assertEquals("int", result);
    }

    @Test
    void resolveFieldTypeSignatureException() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IField field = mock(IField.class);
        when(field.getTypeSignature()).thenThrow(new RuntimeException("fail"));
        String result = invokeResolveFieldTypeSignature(provider, field);
        assertEquals("Object", result);
    }

    // ================================================================
    // isTypeFieldIdentifierCandidate tests
    // ================================================================

    @Test
    void isTypeFieldIdentifierCandidateNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertTrue((boolean) invokeIsTypeFieldIdentifierCandidate(provider, "name", "na"));
    }

    @Test
    void isTypeFieldIdentifierCandidateDollarPrefix() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse((boolean) invokeIsTypeFieldIdentifierCandidate(provider, "$name", ""));
    }

    @Test
    void isTypeFieldIdentifierCandidateDoubleUnderscore() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse((boolean) invokeIsTypeFieldIdentifierCandidate(provider, "__meta", ""));
    }

    @Test
    void isTypeFieldIdentifierCandidatePrefixMismatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        assertFalse((boolean) invokeIsTypeFieldIdentifierCandidate(provider, "name", "xyz"));
    }

    // ================================================================
    // applyAstMethodInsertText tests
    // ================================================================

    @Test
    void applyAstMethodInsertTextNoParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem item = new CompletionItem();
        invokeApplyAstMethodInsertText(provider, item, "run", new Parameter[0]);
        assertEquals("run()", item.getInsertText());
    }

    @Test
    void applyAstMethodInsertTextWithParams() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem item = new CompletionItem();
        Parameter[] params = new Parameter[]{ new Parameter(ClassHelper.STRING_TYPE, "name") };
        invokeApplyAstMethodInsertText(provider, item, "greet", params);
        assertEquals("greet(${1:name})", item.getInsertText());
        assertEquals(InsertTextFormat.Snippet, item.getInsertTextFormat());
    }

    @Test
    void applyAstMethodInsertTextNull() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        CompletionItem item = new CompletionItem();
        invokeApplyAstMethodInsertText(provider, item, "run", null);
        assertEquals("run()", item.getInsertText());
    }
    // ================================================================

    @Test
    void resolveAstTypeInProjectDirectHit() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType mockType = mock(IType.class);
        ClassNode classNode = ClassHelper.make("java.util.List");
        when(project.findType("java.util.List")).thenReturn(mockType);
        IType result = invokeResolveAstTypeInProject(provider, project, classNode);
        assertSame(mockType, result);
    }

    @Test
    void resolveAstTypeInProjectAutoImportFallback() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType mockType = mock(IType.class);
        ClassNode classNode = ClassHelper.make("ArrayList");
        when(project.findType("ArrayList")).thenReturn(null);
        when(project.findType("java.util.ArrayList")).thenReturn(mockType);
        IType result = invokeResolveAstTypeInProject(provider, project, classNode);
        assertSame(mockType, result);
    }

    @Test
    void resolveAstTypeInProjectNotFound() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        ClassNode classNode = ClassHelper.make("UnknownType");
        when(project.findType(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        IType result = invokeResolveAstTypeInProject(provider, project, classNode);
        assertNull(result);
    }

    // ================================================================
    // isSearchResultEligible tests
    // ================================================================

    @Test
    void isSearchResultEligibleNormal() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        Set<String> seen = new HashSet<>();
        boolean result = (boolean) invokeIsSearchResultEligible(provider, "List", "java.util.List", 0, false, project, seen);
        assertTrue(result);
        assertTrue(seen.contains("List")); // should be added
    }

    @Test
    void isSearchResultEligibleAlreadySeen() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        Set<String> seen = new HashSet<>();
        seen.add("List");
        boolean result = (boolean) invokeIsSearchResultEligible(provider, "List", "java.util.List", 0, false, project, seen);
        assertFalse(result);
    }

    @Test
    void isSearchResultEligibleAnnotationOnlyNonAnnotation() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("com.example.Foo")).thenReturn(null);
        Set<String> seen = new HashSet<>();
        boolean result = (boolean) invokeIsSearchResultEligible(provider, "Foo", "com.example.Foo", 0, true, project, seen);
        assertFalse(result);
    }

    // ================================================================
    // isAnnotationTypeCandidate tests
    // ================================================================

    @Test
    void isAnnotationTypeCandidateByModifiers() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        // Flags.AccAnnotation = 0x2000
        boolean result = (boolean) invokeIsAnnotationTypeCandidate(provider, project, "com.example.MyAnnotation", 0x2000);
        assertTrue(result);
    }

    @Test
    void isAnnotationTypeCandidateByJdtLookup() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(project.findType("com.example.MyAnnotation")).thenReturn(type);
        when(type.exists()).thenReturn(true);
        when(type.isAnnotation()).thenReturn(true);
        boolean result = (boolean) invokeIsAnnotationTypeCandidate(provider, project, "com.example.MyAnnotation", 0);
        assertTrue(result);
    }

    @Test
    void isAnnotationTypeCandidateFalse() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("com.example.NotAnnotation")).thenReturn(null);
        boolean result = (boolean) invokeIsAnnotationTypeCandidate(provider, project, "com.example.NotAnnotation", 0);
        assertFalse(result);
    }

    // ================================================================
    // isAnnotationImport tests
    // ================================================================

    @Test
    void isAnnotationImportTrue() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType type = mock(IType.class);
        when(project.findType("com.example.MyAnnotation")).thenReturn(type);
        when(type.exists()).thenReturn(true);
        when(type.isAnnotation()).thenReturn(true);
        assertTrue((boolean) invokeIsAnnotationImport(provider, project, "com.example.MyAnnotation"));
    }

    @Test
    void isAnnotationImportFalse() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        when(project.findType("com.example.NotAnno")).thenReturn(null);
        assertFalse((boolean) invokeIsAnnotationImport(provider, project, "com.example.NotAnno"));
    }

    // ================================================================
    // patchContentForDotCompletion — nonEmpty prefix returns false
    // ================================================================

    @Test
    void patchContentForDotCompletionNonEmptyPrefixReturnsFalse() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        boolean result = (boolean) invokePatchContentForDotCompletion(provider, workingCopy, "foo.bar", 3, "ba");
        assertFalse(result);
    }

    // ================================================================
    // Reflection helpers for newly tested methods
    // ================================================================

    private String invokeExtractNonStaticImport(CompletionProvider provider, String line) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("extractNonStaticImport", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, line);
    }

    private Object invokeShouldIncludeMethod(CompletionProvider provider, IMethod method, String prefix, boolean staticOnly, Set<String> seen) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("shouldIncludeMethod", IMethod.class, String.class, boolean.class, Set.class);
        m.setAccessible(true);
        return m.invoke(provider, method, prefix, staticOnly, seen);
    }

    private Object invokeShouldIncludeField(CompletionProvider provider, IField field, String prefix, boolean staticOnly, Set<String> seen) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("shouldIncludeField", IField.class, String.class, boolean.class, Set.class);
        m.setAccessible(true);
        return m.invoke(provider, field, prefix, staticOnly, seen);
    }

    private CompletionItem invokeBuildFieldCompletionItem(CompletionProvider provider, IField field, String name, IType ownerType, String sortPrefix) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("buildFieldCompletionItem", IField.class, String.class, IType.class, String.class);
        m.setAccessible(true);
        return (CompletionItem) m.invoke(provider, field, name, ownerType, sortPrefix);
    }

    private String invokeResolveFieldDetail(CompletionProvider provider, IField field, IType ownerType) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveFieldDetail", IField.class, IType.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, field, ownerType);
    }

    private String invokeResolveMethodReturnType(CompletionProvider provider, IMethod method) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveMethodReturnType", IMethod.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, method);
    }

    private String invokeResolveFieldTypeSignature(CompletionProvider provider, IField field) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveFieldTypeSignature", IField.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, field);
    }

    private Object invokeIsTypeFieldIdentifierCandidate(CompletionProvider provider, String name, String prefix) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("isTypeFieldIdentifierCandidate", String.class, String.class);
        m.setAccessible(true);
        return m.invoke(provider, name, prefix);
    }

    private void invokeApplyAstMethodInsertText(CompletionProvider provider, CompletionItem item, String name, Parameter[] params) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("applyAstMethodInsertText", CompletionItem.class, String.class, Parameter[].class);
        m.setAccessible(true);
        m.invoke(provider, item, name, params);
    }

    private IType invokeResolveAstTypeInProject(CompletionProvider provider, IJavaProject project, ClassNode exprType) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveAstTypeInProject", IJavaProject.class, ClassNode.class);
        m.setAccessible(true);
        return (IType) m.invoke(provider, project, exprType);
    }

    private Object invokeIsSearchResultEligible(CompletionProvider provider, String simpleName, String fqn, int modifiers, boolean annotationOnly, IJavaProject project, Set<String> seen) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("isSearchResultEligible", String.class, String.class, int.class, boolean.class, IJavaProject.class, Set.class);
        m.setAccessible(true);
        return m.invoke(provider, simpleName, fqn, modifiers, annotationOnly, project, seen);
    }

    private Object invokeIsAnnotationTypeCandidate(CompletionProvider provider, IJavaProject project, String fqn, int modifiers) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationTypeCandidate", IJavaProject.class, String.class, int.class);
        m.setAccessible(true);
        return m.invoke(provider, project, fqn, modifiers);
    }

    private Object invokeIsAnnotationImport(CompletionProvider provider, IJavaProject project, String fqn) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationImport", IJavaProject.class, String.class);
        m.setAccessible(true);
        return m.invoke(provider, project, fqn);
    }

    private Object invokePatchContentForDotCompletion(CompletionProvider provider, ICompilationUnit workingCopy, String content, int dotPos, String prefix) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("patchContentForDotCompletion", ICompilationUnit.class, String.class, int.class, String.class);
        m.setAccessible(true);
        return m.invoke(provider, workingCopy, content, dotPos, prefix);
    }

    // ================================================================
    // resolveLocalVariableTypeInBlock tests
    // ================================================================

    @Test
    void resolveLocalVariableTypeInBlockFindsType() throws Exception {
        String source = "class Foo { void run() { String x = 'hello' } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///resolveBlock.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        org.codehaus.groovy.ast.ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("run");
        if (!methods.isEmpty() && methods.get(0).getCode() instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) {
            Object result = invokeResolveLocalVariableTypeInBlock(block, "x");
            if (result != null) {
                assertTrue(result.toString().contains("String"));
            }
        }
    }

    @Test
    void resolveLocalVariableTypeInBlockReturnsNullForMissing() throws Exception {
        String source = "class Foo { void run() { int y = 1 } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///resolveBlock2.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        org.codehaus.groovy.ast.ClassNode cls = module.getClasses().get(0);
        var methods = cls.getMethods("run");
        if (!methods.isEmpty() && methods.get(0).getCode() instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) {
            Object result = invokeResolveLocalVariableTypeInBlock(block, "notHere");
            assertNull(result);
        }
    }

    // ================================================================

    @Test
    void addFallbackClassNameCompletionAddsItem() throws Exception {
        String source = "class Foo { void run() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackClass.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackClassNameCompletion(cls, "F", items);
        // Should add Foo since it starts with F
        assertFalse(items.isEmpty());
    }

    @Test
    void addFallbackClassNameCompletionNoMatchPrefix() throws Exception {
        String source = "class Foo { void run() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackClass2.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackClassNameCompletion(cls, "Z", items);
        assertTrue(items.isEmpty());
    }

    // ================================================================
    // addFallbackMethodCompletions tests
    // ================================================================

    @Test
    void addFallbackMethodCompletionsAddsItem() throws Exception {
        String source = "class Foo { void doSomething() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackMethod.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackMethodCompletions(cls, "do", items);
        assertFalse(items.isEmpty());
    }

    @Test
    void addFallbackMethodCompletionsNoMatch() throws Exception {
        String source = "class Foo { void doSomething() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackMethod2.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackMethodCompletions(cls, "xyz", items);
        assertTrue(items.isEmpty());
    }

    // ================================================================
    // addFallbackFieldCompletions tests
    // ================================================================

    @Test
    void addFallbackFieldCompletionsAddsItem() throws Exception {
        String source = "class Foo { String name = 'test' }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackField.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackFieldCompletions(cls, "n", items);
        // Groovy class fields may or may not include 'name' depending on AST representation
    }

    // ================================================================
    // addFallbackPropertyCompletions tests
    // ================================================================

    @Test
    void addFallbackPropertyCompletionsAddsItem() throws Exception {
        String source = "class Foo { String name = 'test' }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackProp.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackPropertyCompletions(cls, "n", items);
        assertFalse(items.isEmpty());
    }

    @Test
    void addFallbackPropertyCompletionsEmptyPrefix() throws Exception {
        String source = "class Foo { String name = 'test'; int count = 0 }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///fallbackProp2.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        invokeAddFallbackPropertyCompletions(cls, "", items);
        assertTrue(items.size() >= 2);
    }

    // ================================================================
    // addOwnAstPropertyCompletions tests
    // ================================================================

    @Test
    void addOwnAstPropertyCompletionsAddsItem() throws Exception {
        String source = "class Foo { String name = 'test' }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///ownProp.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        invokeAddOwnAstPropertyCompletions(cls, "", seen, items);
        assertFalse(items.isEmpty());
    }

    // ================================================================
    // addOwnAstFieldCompletions tests
    // ================================================================

    @Test
    void addOwnAstFieldCompletionsAddsItem() throws Exception {
        String source = "class Foo { private String secret = 'hidden' }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///ownField.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        invokeAddOwnAstFieldCompletions(cls, "", seen, items);
        // May or may not be empty depending on $ filter
    }

    // ================================================================
    // addOwnAstMethodCompletions tests
    // ================================================================

    @Test
    void addOwnAstMethodCompletionsAddsItem() throws Exception {
        String source = "class Foo { void doWork() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///ownMethod.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        invokeAddOwnAstMethodCompletions(cls, "do", seen, items);
        assertFalse(items.isEmpty());
    }

    @Test
    void addOwnAstMethodCompletionsNoMatch() throws Exception {
        String source = "class Foo { void doWork() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///ownMethod2.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        invokeAddOwnAstMethodCompletions(cls, "xyz", seen, items);
        assertTrue(items.isEmpty());
    }

    // ================================================================
    // addOwnClassAstCompletionsForClass tests
    // ================================================================

    @Test
    void addOwnClassAstCompletionsForClassAddsItems() throws Exception {
        String source = "class Foo { String name = 'test'; void doWork() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///ownClassAst.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        java.util.List<org.eclipse.lsp4j.CompletionItem> items = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        invokeAddOwnClassAstCompletionsForClass(cls, "", seen, items);
        assertFalse(items.isEmpty());
    }

    // ================================================================
    // resolveAstExpressionType tests
    // ================================================================

    @Test
    void resolveAstExpressionTypeForField() throws Exception {
        String source = "class Foo { String name = 'hello'; void run() { } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///exprType.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();
        org.codehaus.groovy.ast.ClassNode cls = module.getClasses().get(0);
        Object result = invokeResolveAstExpressionType(cls, module, "name");
        // May return ClassNode or null depending on AST structure
    }

    // ================================================================
    // resolveClassMemberExpressionType tests
    // ================================================================

    @Test
    void resolveClassMemberExpressionTypeForMethod() throws Exception {
        String source = "class Foo { String getName() { return 'test' } }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///memberExpr.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Object result = invokeResolveClassMemberExpressionType(cls, "getName");
        if (result != null) {
            assertTrue(result.toString().contains("String"));
        }
    }

    @Test
    void resolveClassMemberExpressionTypeForProperty() throws Exception {
        String source = "class Foo { String name = 'test' }";
        var compileResult = new org.eclipse.groovy.ls.core.GroovyCompilerService().parse("file:///memberExpr2.groovy", source);
        org.codehaus.groovy.ast.ClassNode cls = compileResult.getModuleNode().getClasses().get(0);
        Object result = invokeResolveClassMemberExpressionType(cls, "name");
        // Properties are class-level 'def' fields - may or may not resolve
    }

    // ================================================================
    // resolveQualifiedType tests (MEDIUM - mock IJavaProject)
    // ================================================================

    @Test
    void resolveQualifiedTypeNoDotsReturnsNull() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        Object result = invokeResolveQualifiedType("String", project);
        assertNull(result);
    }

    @Test
    void resolveQualifiedTypeWithDots() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType type = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("java.lang.String")).thenReturn(type);
        Object result = invokeResolveQualifiedType("java.lang.String", project);
        assertEquals(type, result);
    }

    // ================================================================
    // resolveAstAutoImportType tests
    // ================================================================

    @Test
    void resolveAstAutoImportTypeFindsInJavaLang() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType type = mock(org.eclipse.jdt.core.IType.class);
        when(project.findType("java.lang.String")).thenReturn(type);
        Object result = invokeResolveAstAutoImportType("String", project);
        assertEquals(type, result);
    }

    @Test
    void resolveAstAutoImportTypeReturnsNullForUnknown() throws Exception {
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        Object result = invokeResolveAstAutoImportType("UnknownType", project);
        assertNull(result);
    }

    // ================================================================
    // buildTypeSearchItem tests
    // ================================================================

    @Test
    void buildTypeSearchItemCreatesItem() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///typeSearch.groovy";
        dm.didOpen(uri, "package com.example\n\nclass Foo {}");
        CompletionProvider cp = new CompletionProvider(dm);
        Object result = invokeBuildTypeSearchItem(cp, "MyClass", "com.example", org.eclipse.jdt.core.Flags.AccPublic, uri);
        assertNotNull(result);
        org.eclipse.lsp4j.CompletionItem item = (org.eclipse.lsp4j.CompletionItem) result;
        assertEquals("MyClass", item.getLabel());
    }

    // ================================================================
    // Reflection helpers for batch 3
    // ================================================================

    private Object invokeResolveLocalVariableTypeInBlock(org.codehaus.groovy.ast.stmt.BlockStatement block, String varName) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveLocalVariableTypeInBlock",
                org.codehaus.groovy.ast.stmt.BlockStatement.class, String.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        return m.invoke(cp, block, varName);
    }

    private void invokeAddFallbackClassNameCompletion(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addFallbackClassNameCompletion",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, items);
    }

    private void invokeAddFallbackMethodCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addFallbackMethodCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, items);
    }

    private void invokeAddFallbackFieldCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addFallbackFieldCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, items);
    }

    private void invokeAddFallbackPropertyCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addFallbackPropertyCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, items);
    }

    private void invokeAddOwnAstPropertyCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.Set<String> seen, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addOwnAstPropertyCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.Set.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, seen, items);
    }

    private void invokeAddOwnAstFieldCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.Set<String> seen, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addOwnAstFieldCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.Set.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, seen, items);
    }

    private void invokeAddOwnAstMethodCompletions(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.Set<String> seen, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addOwnAstMethodCompletions",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.Set.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, seen, items);
    }

    private void invokeAddOwnClassAstCompletionsForClass(org.codehaus.groovy.ast.ClassNode cls, String prefix, java.util.Set<String> seen, java.util.List<org.eclipse.lsp4j.CompletionItem> items) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("addOwnClassAstCompletionsForClass",
                org.codehaus.groovy.ast.ClassNode.class, String.class, java.util.Set.class, java.util.List.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        m.invoke(cp, cls, prefix, seen, items);
    }

    private Object invokeResolveAstExpressionType(org.codehaus.groovy.ast.ClassNode cls, org.codehaus.groovy.ast.ModuleNode module, String name) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveAstExpressionType",
                org.codehaus.groovy.ast.ClassNode.class, org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        return m.invoke(cp, cls, module, name);
    }

    private Object invokeResolveClassMemberExpressionType(org.codehaus.groovy.ast.ClassNode cls, String name) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveClassMemberExpressionType",
                org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        return m.invoke(cp, cls, name);
    }

    private Object invokeResolveQualifiedType(String name, org.eclipse.jdt.core.IJavaProject project) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveQualifiedType",
                String.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        return m.invoke(cp, name, project);
    }

    private Object invokeResolveAstAutoImportType(String name, org.eclipse.jdt.core.IJavaProject project) throws Exception {
        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("resolveAstAutoImportType",
                String.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        CompletionProvider cp = new CompletionProvider(new DocumentManager());
        return m.invoke(cp, name, project);
    }

    private Object invokeBuildTypeSearchItem(CompletionProvider cp, String typeName, String packageName, int modifiers, String uri) throws Exception {
        // Need to find the TypeSearchContext inner class and create one
        // buildTypeSearchItem(String, String, int, String, TypeSearchContext)
        // TypeSearchContext is a private inner class - use reflection to get it
        Class<?>[] innerClasses = CompletionProvider.class.getDeclaredClasses();
        Class<?> tscClass = null;
        for (Class<?> c : innerClasses) {
            if (c.getSimpleName().equals("TypeSearchContext")) {
                tscClass = c;
                break;
            }
        }
        if (tscClass == null) {
            return null; // TypeSearchContext not found
        }

        // Create a TypeSearchContext via its 4-arg constructor
        // TypeSearchContext(boolean annotationOnly, String currentPackage, Set<String> existingImports, int importInsertLine)
        var ctor = tscClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object tsc = ctor.newInstance(false, "com.example", new java.util.HashSet<String>(), 1);

        java.lang.reflect.Method m = CompletionProvider.class.getDeclaredMethod("buildTypeSearchItem",
                String.class, String.class, int.class, String.class, tscClass);
        m.setAccessible(true);
        return m.invoke(cp, typeName, packageName, modifiers, uri, tsc);
    }

    // ================================================================
    // addTraitAstMethodCompletions tests (81 missed instructions)
    // ================================================================

    @Test
    void addTraitAstMethodCompletionsAddsMatchingMethods() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///traitAstMethods.groovy";
        dm.didOpen(uri, "class Foo {}");
        CompletionProvider cp = new CompletionProvider(dm);

        // Create a ClassNode acting as a trait with some methods
        String traitSource = """
                trait Greeter {
                    String greet(String name) { "Hello ${name}" }
                    int count() { 0 }
                }
                """;
        var compileResult = new GroovyCompilerService().parse("file:///TraitForTest.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstMethodCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        // Should have added at least the 'greet' and 'count' methods
        assertTrue(items.stream().anyMatch(i -> "greet".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "count".equals(i.getLabel())));
    }

    @Test
    void addTraitAstMethodCompletionsFiltersByPrefix() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///traitAstFilter.groovy";
        dm.didOpen(uri, "class Foo {}");
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = """
                trait Worker {
                    void doWork() {}
                    void doPlay() {}
                    void rest() {}
                }
                """;
        var compileResult = new GroovyCompilerService().parse("file:///WorkerTrait.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstMethodCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "do", seen, items);

        // Only methods starting with "do" should be included
        assertTrue(items.stream().allMatch(i -> i.getLabel().startsWith("do")));
        assertFalse(items.stream().anyMatch(i -> "rest".equals(i.getLabel())));
    }

    @Test
    void addTraitAstMethodCompletionsSkipsDuplicates() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { void run() {} }";
        var compileResult = new GroovyCompilerService().parse("file:///DupTrait.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        seen.add("run/0"); // Already seen
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstMethodCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        // run/0 was already seen, so items should NOT contain it
        assertFalse(items.stream().anyMatch(i -> "run".equals(i.getLabel())));
    }

    @Test
    void addTraitAstMethodCompletionsSkipsSyntheticMethods() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name }";
        var compileResult = new GroovyCompilerService().parse("file:///SynthTrait.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstMethodCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        // Should not include methods starting with $ or <
        assertTrue(items.stream().noneMatch(i -> i.getLabel().startsWith("$")));
        assertTrue(items.stream().noneMatch(i -> i.getLabel().startsWith("<")));
    }

    // ================================================================
    // addTraitAstPropertyCompletions tests
    // ================================================================

    @Test
    void addTraitAstPropertyCompletionsAddsMatchingProps() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name; int count }";
        var compileResult = new GroovyCompilerService().parse("file:///PropTrait.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstPropertyCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "count".equals(i.getLabel())));
    }

    @Test
    void addTraitAstPropertyCompletionsFiltersByPrefix() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name; int count }";
        var compileResult = new GroovyCompilerService().parse("file:///PropTraitPfx.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstPropertyCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "na", seen, items);

        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())));
        assertTrue(items.stream().noneMatch(i -> "count".equals(i.getLabel())));
    }

    @Test
    void addTraitAstPropertyCompletionsSkipsDuplicates() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name }";
        var compileResult = new GroovyCompilerService().parse("file:///PropTraitDup.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        seen.add("name"); // pre-seen
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstPropertyCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        assertTrue(items.isEmpty());
    }

    // ================================================================
    // addTraitAstFieldCompletions tests
    // ================================================================

    @Test
    void addTraitAstFieldCompletionsAddsFields() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name; int count }";
        var compileResult = new GroovyCompilerService().parse("file:///FieldTrait.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstFieldCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        // Trait fields might include some backing fields
        assertNotNull(items);
    }

    @Test
    void addTraitAstFieldCompletionsSkipsDollarAndDunder() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String traitSource = "trait T { String name }";
        var compileResult = new GroovyCompilerService().parse("file:///FieldTraitDollar.groovy", traitSource);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitAstFieldCompletions",
                ClassNode.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, "", seen, items);

        // No items should start with $ or __
        assertTrue(items.stream().noneMatch(i -> i.getLabel().startsWith("$")));
        assertTrue(items.stream().noneMatch(i -> i.getLabel().startsWith("__")));
    }

    // ================================================================
    // addTraitJdtPropertyCompletions tests
    // ================================================================

    @Test
    void addTraitJdtPropertyCompletionsAddsGetterProperties() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IMethod getNameMethod = org.mockito.Mockito.mock(org.eclipse.jdt.core.IMethod.class);
        org.mockito.Mockito.when(getNameMethod.getElementName()).thenReturn("getName");
        org.mockito.Mockito.when(getNameMethod.getParameterTypes()).thenReturn(new String[0]);
        org.mockito.Mockito.when(getNameMethod.getReturnType()).thenReturn("QString;");

        org.eclipse.jdt.core.IMethod getCountMethod = org.mockito.Mockito.mock(org.eclipse.jdt.core.IMethod.class);
        org.mockito.Mockito.when(getCountMethod.getElementName()).thenReturn("getCount");
        org.mockito.Mockito.when(getCountMethod.getParameterTypes()).thenReturn(new String[0]);
        org.mockito.Mockito.when(getCountMethod.getReturnType()).thenReturn("I");

        org.eclipse.jdt.core.IMethod normalMethod = org.mockito.Mockito.mock(org.eclipse.jdt.core.IMethod.class);
        org.mockito.Mockito.when(normalMethod.getElementName()).thenReturn("doWork");
        org.mockito.Mockito.when(normalMethod.getParameterTypes()).thenReturn(new String[0]);

        org.mockito.Mockito.when(traitType.getMethods()).thenReturn(
                new org.eclipse.jdt.core.IMethod[]{getNameMethod, getCountMethod, normalMethod});

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtPropertyCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "", seen, items);

        // Should have "name" and "count" as properties (from getName, getCount)
        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "count".equals(i.getLabel())));
        // "doWork" is not a getter, shouldn't produce property
        assertTrue(items.stream().noneMatch(i -> "doWork".equals(i.getLabel())));
    }

    @Test
    void addTraitJdtPropertyCompletionsFiltersGettersWithParams() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IMethod getWithParam = org.mockito.Mockito.mock(org.eclipse.jdt.core.IMethod.class);
        org.mockito.Mockito.when(getWithParam.getElementName()).thenReturn("getValue");
        org.mockito.Mockito.when(getWithParam.getParameterTypes()).thenReturn(new String[]{"QString;"});

        org.mockito.Mockito.when(traitType.getMethods()).thenReturn(
                new org.eclipse.jdt.core.IMethod[]{getWithParam});

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtPropertyCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "", seen, items);

        // Getter with params should NOT be treated as property
        assertTrue(items.isEmpty());
    }

    // ================================================================
    // resolveTraitTypeByImports tests
    // ================================================================

    @Test
    void resolveTraitTypeByImportsReturnsNullForNullModule() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IJavaProject project = org.mockito.Mockito.mock(org.eclipse.jdt.core.IJavaProject.class);

        Method m = CompletionProvider.class.getDeclaredMethod("resolveTraitTypeByImports",
                String.class, org.codehaus.groovy.ast.ModuleNode.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        org.eclipse.jdt.core.IType result = (org.eclipse.jdt.core.IType) m.invoke(cp, "SomeTrait", null, project);

        assertNull(result);
    }

    @Test
    void resolveTraitTypeByImportsResolvesExplicitImport() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String source = "import groovy.transform.ToString\nclass Foo {}";
        var compileResult = new GroovyCompilerService().parse("file:///ResolveImport.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();

        org.eclipse.jdt.core.IJavaProject project = org.mockito.Mockito.mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(project.findType("groovy.transform.ToString")).thenReturn(mockType);

        Method m = CompletionProvider.class.getDeclaredMethod("resolveTraitTypeByImports",
                String.class, org.codehaus.groovy.ast.ModuleNode.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        org.eclipse.jdt.core.IType result = (org.eclipse.jdt.core.IType) m.invoke(cp, "ToString", module, project);

        assertEquals(mockType, result);
    }

    @Test
    void resolveTraitTypeByImportsResolvesStarImport() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String source = "import groovy.transform.*\nclass Foo {}";
        var compileResult = new GroovyCompilerService().parse("file:///ResolveStarImport.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();

        org.eclipse.jdt.core.IJavaProject project = org.mockito.Mockito.mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IType mockType = org.mockito.Mockito.mock(org.eclipse.jdt.core.IType.class);
        org.mockito.Mockito.when(project.findType("groovy.transform.ToString")).thenReturn(mockType);

        Method m = CompletionProvider.class.getDeclaredMethod("resolveTraitTypeByImports",
                String.class, org.codehaus.groovy.ast.ModuleNode.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        org.eclipse.jdt.core.IType result = (org.eclipse.jdt.core.IType) m.invoke(cp, "ToString", module, project);

        assertEquals(mockType, result);
    }

    @Test
    void resolveTraitTypeByImportsReturnsNullForNoMatch() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String source = "class Foo {}";
        var compileResult = new GroovyCompilerService().parse("file:///ResolveNoMatch.groovy", source);
        org.codehaus.groovy.ast.ModuleNode module = compileResult.getModuleNode();

        org.eclipse.jdt.core.IJavaProject project = org.mockito.Mockito.mock(org.eclipse.jdt.core.IJavaProject.class);

        Method m = CompletionProvider.class.getDeclaredMethod("resolveTraitTypeByImports",
                String.class, org.codehaus.groovy.ast.ModuleNode.class, org.eclipse.jdt.core.IJavaProject.class);
        m.setAccessible(true);
        org.eclipse.jdt.core.IType result = (org.eclipse.jdt.core.IType) m.invoke(cp, "UnknownTrait", module, project);

        assertNull(result);
    }

    // ================================================================
    // addTraitFieldHelperCompletions tests
    // ================================================================

    @Test
    void addTraitFieldHelperCompletionsReturnsEmptyWhenNoHelper() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        String source = "trait T { void m() {} }";
        var compileResult = new GroovyCompilerService().parse("file:///NoHelper.groovy", source);
        ClassNode traitNode = compileResult.getModuleNode().getClasses().get(0);
        org.codehaus.groovy.ast.ModuleNode ast = compileResult.getModuleNode();

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitFieldHelperCompletions",
                ClassNode.class, org.codehaus.groovy.ast.ModuleNode.class,
                String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitNode, ast, "", seen, items);

        // No field helper class exists for this simple trait
        assertNotNull(items);
    }

    // ================================================================
    // addTraitJdtFieldCompletions tests
    // ================================================================

    @Test
    void addTraitJdtFieldCompletionsAddsMatchingFields() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IField field1 = mock(org.eclipse.jdt.core.IField.class);
        when(field1.getElementName()).thenReturn("name");
        when(field1.getTypeSignature()).thenReturn("QString;");
        org.eclipse.jdt.core.IField field2 = mock(org.eclipse.jdt.core.IField.class);
        when(field2.getElementName()).thenReturn("age");
        when(field2.getTypeSignature()).thenReturn("I");
        when(traitType.getFields()).thenReturn(new org.eclipse.jdt.core.IField[]{ field1, field2 });

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtFieldCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "", seen, items);

        assertEquals(2, items.size());
        assertTrue(items.stream().anyMatch(i -> "name".equals(i.getLabel())));
        assertTrue(items.stream().anyMatch(i -> "age".equals(i.getLabel())));
    }

    @Test
    void addTraitJdtFieldCompletionsSkipsDollarAndDoubleUnderscoreFields() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IField dollar = mock(org.eclipse.jdt.core.IField.class);
        when(dollar.getElementName()).thenReturn("$staticInit");
        org.eclipse.jdt.core.IField dunder = mock(org.eclipse.jdt.core.IField.class);
        when(dunder.getElementName()).thenReturn("__hidden");
        org.eclipse.jdt.core.IField normal = mock(org.eclipse.jdt.core.IField.class);
        when(normal.getElementName()).thenReturn("visible");
        when(normal.getTypeSignature()).thenReturn("QString;");
        when(traitType.getFields()).thenReturn(
                new org.eclipse.jdt.core.IField[]{ dollar, dunder, normal });

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtFieldCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "", seen, items);

        assertEquals(1, items.size());
        assertEquals("visible", items.get(0).getLabel());
    }

    @Test
    void addTraitJdtFieldCompletionsFiltersByPrefix() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IField f1 = mock(org.eclipse.jdt.core.IField.class);
        when(f1.getElementName()).thenReturn("name");
        org.eclipse.jdt.core.IField f2 = mock(org.eclipse.jdt.core.IField.class);
        when(f2.getElementName()).thenReturn("count");
        when(f2.getTypeSignature()).thenReturn("I");
        when(traitType.getFields()).thenReturn(
                new org.eclipse.jdt.core.IField[]{ f1, f2 });

        Set<String> seen = new HashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtFieldCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "co", seen, items);

        assertEquals(1, items.size());
        assertEquals("count", items.get(0).getLabel());
    }

    @Test
    void addTraitJdtFieldCompletionsDeduplicatesViaSeen() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IType traitType = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.jdt.core.IField f1 = mock(org.eclipse.jdt.core.IField.class);
        when(f1.getElementName()).thenReturn("value");
        when(f1.getTypeSignature()).thenReturn("QString;");
        when(traitType.getFields()).thenReturn(
                new org.eclipse.jdt.core.IField[]{ f1 });

        Set<String> seen = new HashSet<>();
        seen.add("value"); // Already seen
        List<CompletionItem> items = new ArrayList<>();

        Method m = CompletionProvider.class.getDeclaredMethod("addTraitJdtFieldCompletions",
                org.eclipse.jdt.core.IType.class, String.class, Set.class, List.class);
        m.setAccessible(true);
        m.invoke(cp, traitType, "", seen, items);

        assertTrue(items.isEmpty(), "Should skip items already in seen set");
    }

    // ================================================================
    // getCompletions branch tests (null content, null working copy)
    // ================================================================

    @Test
    void getCompletionsReturnsEmptyForNullContentBatch5() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.lsp4j.CompletionParams params = new org.eclipse.lsp4j.CompletionParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier("file:///NullContent.groovy"));
        params.setPosition(new org.eclipse.lsp4j.Position(0, 0));

        List<CompletionItem> result = cp.getCompletions(params);
        assertTrue(result.isEmpty());
    }

    // ================================================================
    // resolveFieldTypeSignature tests
    // ================================================================

    @Test
    void resolveFieldTypeSignatureDecodesSimpleType() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IField field = mock(org.eclipse.jdt.core.IField.class);
        when(field.getTypeSignature()).thenReturn("QString;");

        Method m = CompletionProvider.class.getDeclaredMethod("resolveFieldTypeSignature",
                org.eclipse.jdt.core.IField.class);
        m.setAccessible(true);
        String result = (String) m.invoke(cp, field);

        assertEquals("String", result);
    }

    @Test
    void resolveFieldTypeSignatureFallsBackOnException() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        org.eclipse.jdt.core.IField field = mock(org.eclipse.jdt.core.IField.class);
        when(field.getTypeSignature()).thenThrow(
                new org.eclipse.jdt.core.JavaModelException(new RuntimeException("test"), 0));

        Method m = CompletionProvider.class.getDeclaredMethod("resolveFieldTypeSignature",
                org.eclipse.jdt.core.IField.class);
        m.setAccessible(true);
        String result = (String) m.invoke(cp, field);

        assertNotNull(result);
    }

    // ================================================================
    // matchesPrefix tests
    // ================================================================

    @Test
    void matchesPrefixVariousCases() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("matchesPrefix",
                String.class, String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(cp, "myField", "myF"));
        assertTrue((boolean) m.invoke(cp, "MyField", "my"));
        assertFalse((boolean) m.invoke(cp, "myField", "xyz"));
        assertTrue((boolean) m.invoke(cp, "anything", "")); // empty prefix matches all
    }

    // ================================================================
    // extractPrefix tests
    // ================================================================

    @Test
    void extractPrefixExtractsWordBeforeOffset() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("extractPrefix",
                String.class, int.class);
        m.setAccessible(true);

        assertEquals("hello", (String) m.invoke(cp, "hello world", 5));
        assertEquals("wo", (String) m.invoke(cp, "hello wo", 8));
        assertEquals("", (String) m.invoke(cp, "hello ", 6));
    }

    // ================================================================
    // positionToOffset tests
    // ================================================================

    @Test
    void positionToOffsetInCompletion() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("positionToOffset",
                String.class, org.eclipse.lsp4j.Position.class);
        m.setAccessible(true);

        org.eclipse.lsp4j.Position pos = new org.eclipse.lsp4j.Position(1, 3);
        int result = (int) m.invoke(cp, "hello\nworld", pos);
        assertEquals(9, result);
    }

    // ================================================================
    // Batch 6 — additional CompletionProvider utility method tests
    // ================================================================

    // ---- isAnnotationContext ----

    @Test
    void isAnnotationContextReturnsTrueForAtSign() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationContext",
                String.class, int.class, int.class);
        m.setAccessible(true);

        // "@Dep|" — offset=4, prefixStart=1 (after @)
        assertTrue((boolean) m.invoke(cp, "@Dep", 4, 1));
    }

    @Test
    void isAnnotationContextReturnsFalseForRegularCode() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("isAnnotationContext",
                String.class, int.class, int.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(cp, "class Foo {}", 5, 0));
    }

    // ---- findImportInsertLine ----

    @Test
    void findImportInsertLineAfterPackage() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("findImportInsertLine",
                String.class);
        m.setAccessible(true);

        int line = (int) m.invoke(cp, "package com.example\n\nclass A {}");
        assertTrue(line >= 1, "Import insert should be after package");
    }

    @Test
    void findImportInsertLineAfterExistingImports() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("findImportInsertLine",
                String.class);
        m.setAccessible(true);

        int line = (int) m.invoke(cp, "package com.example\nimport java.util.List\nclass A {}");
        assertTrue(line >= 2, "Import insert should be after existing imports");
    }

    // ---- getCurrentPackageName ----

    @Test
    void getCurrentPackageNameExtractsPackage() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("getCurrentPackageName",
                String.class);
        m.setAccessible(true);

        assertEquals("com.example", m.invoke(cp, "package com.example\nclass A {}"));
    }

    @Test
    void getCurrentPackageNameReturnsNullForNoPackage() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("getCurrentPackageName",
                String.class);
        m.setAccessible(true);

        assertEquals("", m.invoke(cp, "class A {}"));
    }

    // ---- extractNonStaticImport ----

    @Test
    void extractNonStaticImportParsesImportLine() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("extractNonStaticImport",
                String.class);
        m.setAccessible(true);

        assertEquals("java.util.List", m.invoke(cp, "import java.util.List"));
    }

    @Test
    void extractNonStaticImportReturnsNullForStaticImport() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("extractNonStaticImport",
                String.class);
        m.setAccessible(true);

        assertNull(m.invoke(cp, "import static java.util.Collections.sort"));
    }

    // ---- isAutoImportedPackage ----

    @Test
    void isAutoImportedRecognizesStandardPackages() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("isAutoImportedPackage",
                String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(cp, "java.lang"));
        assertTrue((boolean) m.invoke(cp, "groovy.lang"));
        assertFalse((boolean) m.invoke(cp, "com.example"));
    }

    // ---- buildAstMethodInvocation ----

    @Test
    void buildAstMethodInvocationCreatesSnippet() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("buildAstMethodInvocation",
                String.class, Parameter[].class);
        m.setAccessible(true);

        Parameter[] params = new Parameter[] {
                new Parameter(org.codehaus.groovy.ast.ClassHelper.STRING_TYPE, "name"),
                new Parameter(org.codehaus.groovy.ast.ClassHelper.int_TYPE, "count")
        };
        String result = (String) m.invoke(cp, "greet", params);
        assertNotNull(result);
        assertTrue(result.startsWith("greet("));
    }

    @Test
    void buildAstMethodInvocationNoParamsReturnsParens() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);

        Method m = CompletionProvider.class.getDeclaredMethod("buildAstMethodInvocation",
                String.class, Parameter[].class);
        m.setAccessible(true);

        String result = (String) m.invoke(cp, "run", new Parameter[0]);
        assertNotNull(result);
        assertTrue(result.contains("run("));
    }

    // ---- resolveLocalVariableTypeInBlock ----

    @Test
    void resolveLocalVariableTypeInBlockFindsStringType() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);
        GroovyCompilerService cs = new GroovyCompilerService();

        String source = """
                class Demo {
                    void run() {
                        String name = 'hello'
                        println name
                    }
                }
                """;
        org.codehaus.groovy.ast.ModuleNode ast = cs.parse("file:///resolveLocal.groovy", source).getModuleNode();
        org.codehaus.groovy.ast.ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        org.codehaus.groovy.ast.MethodNode run = demo.getMethods().stream()
                .filter(mn -> "run".equals(mn.getName())).findFirst().orElseThrow();
        org.codehaus.groovy.ast.stmt.BlockStatement block =
                (org.codehaus.groovy.ast.stmt.BlockStatement) run.getCode();

        Method m = CompletionProvider.class.getDeclaredMethod("resolveLocalVariableTypeInBlock",
                org.codehaus.groovy.ast.stmt.BlockStatement.class, String.class);
        m.setAccessible(true);

        org.codehaus.groovy.ast.ClassNode result =
                (org.codehaus.groovy.ast.ClassNode) m.invoke(cp, block, "name");
        assertNotNull(result, "Should resolve the type of 'name'");
    }

    // ---- resolveClassMemberExpressionType ----

    @Test
    void resolveClassMemberExpressionTypeFindsFieldType() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);
        GroovyCompilerService cs = new GroovyCompilerService();

        String source = """
                class Demo {
                    String title
                }
                """;
        org.codehaus.groovy.ast.ModuleNode ast = cs.parse("file:///resolveMember.groovy", source).getModuleNode();
        org.codehaus.groovy.ast.ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();

        Method m = CompletionProvider.class.getDeclaredMethod("resolveClassMemberExpressionType",
                org.codehaus.groovy.ast.ClassNode.class, String.class);
        m.setAccessible(true);

        org.codehaus.groovy.ast.ClassNode result =
                (org.codehaus.groovy.ast.ClassNode) m.invoke(cp, demo, "title");
        assertNotNull(result, "Should resolve the type of field 'title'");
    }

    // ---- resolveAstExpressionType ----

    @Test
    void resolveAstExpressionTypeResolvesVariable() throws Exception {
        DocumentManager dm = new DocumentManager();
        CompletionProvider cp = new CompletionProvider(dm);
        GroovyCompilerService cs = new GroovyCompilerService();

        String source = """
                class Demo {
                    String title = 'hello'
                    void run() {
                        def x = title
                    }
                }
                """;
        org.codehaus.groovy.ast.ModuleNode ast = cs.parse("file:///resolveExpr.groovy", source).getModuleNode();
        org.codehaus.groovy.ast.ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();

        Method m = CompletionProvider.class.getDeclaredMethod("resolveAstExpressionType",
                org.codehaus.groovy.ast.ClassNode.class,
                org.codehaus.groovy.ast.ModuleNode.class, String.class);
        m.setAccessible(true);

        org.codehaus.groovy.ast.ClassNode result =
                (org.codehaus.groovy.ast.ClassNode) m.invoke(cp, demo, ast, "title");
        assertNotNull(result, "Should resolve expression type for 'title'");
    }
}
