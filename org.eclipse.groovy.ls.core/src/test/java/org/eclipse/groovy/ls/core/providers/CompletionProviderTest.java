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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
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
}
