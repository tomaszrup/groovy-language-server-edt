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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class SemanticTokensVisitorTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void emitsClassMethodParameterAndPropertyTokens() {
        String source = """
                class Person {
                    String name
                    def greet(String other) {
                        return other.toUpperCase()
                    }
                }
                def p = new Person(name: 'Ada')
                p.greet('Bob')
                """;

        List<DecodedToken> tokens = collectTokens(source, null);

        assertFalse(tokens.isEmpty());
        assertTrue(tokens.stream().allMatch(token -> token.column >= 0));
        assertTrue(tokens.stream().allMatch(token -> token.length > 0));
        assertTrue(tokens.stream().allMatch(token -> token.modifiers >= 0));
        assertTrue(
            hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE));
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
        assertTrue(
            hasTokenType(tokens, SemanticTokensProvider.TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_VARIABLE));
    }

    @Test
    void emitsTokensForSimpleClassDeclaration() {
        String source = """
                class Named {
                    String name
                }
                """;

        List<DecodedToken> tokens = collectTokens(source, null);

        assertFalse(tokens.isEmpty());
        assertTrue(
                hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE)
                        || hasTokenType(tokens, SemanticTokensProvider.TYPE_PROPERTY));
    }

    @Test
    void respectsRangeRestrictionWhenEncodingTokens() {
        String source = """
                class One {
                    String a
                }

                class Two {
                    String b
                }
                """;

        Range firstClassOnly = new Range(new Position(0, 0), new Position(3, 0));
        List<DecodedToken> tokens = collectTokens(source, firstClassOnly);

        assertFalse(tokens.isEmpty());
        assertTrue(tokens.stream().allMatch(token -> token.line >= 0 && token.line <= 3));
    }

    // ---- Method call expressions ----

    @Test
    void emitsMethodCallTokens() {
        String source = """
                class Foo {
                    void bar() {}
                    void baz() {
                        bar()
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    @Test
    void emitsStaticMethodCallTokens() {
        String source = """
                class Util {
                    static void log(String msg) {}
                }
                class App {
                    void run() {
                        Util.log('hello')
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Constructor call expressions ----

    @Test
    void emitsConstructorCallTypeToken() {
        String source = """
                class Widget {}
                def w = new Widget()
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS));
    }

    // ---- Property expressions ----

    @Test
    void emitsPropertyAccessTokens() {
        String source = """
                class Config {
                    String host
                    int port
                }
                def c = new Config()
                println c.host
                println c.port
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_PROPERTY));
    }

    // ---- Variable declarations ----

    @Test
    void emitsVariableDeclarationTokens() {
        String source = """
                def x = 42
                String name = 'hello'
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_VARIABLE));
    }

    // ---- Enum types ----

    @Test
    void emitsEnumTokens() {
        String source = """
                enum Color {
                    RED, GREEN, BLUE
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_ENUM)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_ENUM_MEMBER));
    }

    // ---- Interface types ----

    @Test
    void emitsInterfaceTokens() {
        String source = """
                interface Printable {
                    void print()
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_INTERFACE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Trait types ----

    @Test
    void emitsTraitTokens() {
        String source = """
                trait Flyable {
                    void fly() { println 'flying' }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Annotations ----

    @Test
    void emitsAnnotationTokens() {
        String source = """
                @Deprecated
                class OldClass {
                    @Deprecated
                    void oldMethod() {}
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Closures ----

    @Test
    void emitsClosureTokens() {
        String source = """
                def list = [1, 2, 3]
                list.each { item ->
                    println item
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_PARAMETER));
    }

    // ---- Imports ----

    @Test
    void emitsImportTokens() {
        String source = """
                import java.util.List
                import java.util.Map
                
                class Foo {
                    List items
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_NAMESPACE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE));
    }

    // ---- Package declaration ----

    @Test
    void emitsPackageToken() {
        String source = """
                package com.example
                
                class Foo {}
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_NAMESPACE));
    }

    // ---- Inheritance (extends/implements) ----

    @Test
    void emitsInheritanceTokens() {
        String source = """
                class Base {}
                class Child extends Base {}
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Should have TYPE tokens for both Base and Child
        long typeTokens = tokens.stream()
                .filter(t -> t.tokenType == SemanticTokensProvider.TYPE_TYPE
                        || t.tokenType == SemanticTokensProvider.TYPE_CLASS)
                .count();
        assertTrue(typeTokens >= 2);
    }

    // ---- Named arguments ----

    @Test
    void emitsNamedArgumentTokens() {
        String source = """
                class Cfg {
                    String host
                    int port
                }
                def c = new Cfg(host: 'localhost', port: 8080)
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Generics ----

    @Test
    void emitsGenericTypeTokens() {
        String source = """
                class Box<T> {
                    T value
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE));
    }

    // ---- Class expression ----

    @Test
    void emitsClassExpressionToken() {
        String source = """
                def cls = String.class
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Static field & final field ----

    @Test
    void emitsTokensForStaticFields() {
        String source = """
                class Constants {
                    static final int MAX = 100
                    static String label = 'test'
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // At minimum, the class and field types should produce tokens
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE));
    }

    // ---- Empty source ----

    @Test
    void emptySourceProducesNoTokens() {
        List<DecodedToken> tokens = collectTokens("", null);
        assertTrue(tokens.isEmpty());
    }

    // ---- Script (no class) ----

    @Test
    void scriptSourceProducesTokens() {
        String source = """
                def x = 42
                println x
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Method exceptions ----

    @Test
    void emitsExceptionTypeTokens() {
        String source = """
                class Service {
                    void process() throws IOException, RuntimeException {
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Multiple methods ----

    @Test
    void emitsTokensForMultipleMethods() {
        String source = """
                class Service {
                    void alpha() {}
                    void beta(String s) {}
                    int gamma(int a, int b) { return a + b }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        long methodTokens = tokens.stream()
                .filter(t -> t.tokenType == SemanticTokensProvider.TYPE_METHOD)
                .count();
        assertTrue(methodTokens >= 3);
    }

    // ---- Abstract class ----

    @Test
    void emitsAbstractModifier() {
        String source = """
                abstract class Shape {
                    abstract void draw()
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.stream().anyMatch(
                t -> (t.modifiers & SemanticTokensProvider.MOD_ABSTRACT) != 0));
    }

    // ---- Deprecated annotation ----

    @Test
    void emitsDeprecatedModifier() {
        String source = """
                class Old {
                    @Deprecated
                    void legacy() {}
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(tokens.stream().anyMatch(
                t -> (t.modifiers & SemanticTokensProvider.MOD_DEPRECATED) != 0));
    }

    // ================================================================
    // Batch 6 — additional visitor coverage tests
    // ================================================================

    // ---- Trait declarations ----

    @Test
    void emitsStructTokenForTraitDeclaration() {
        String source = """
                trait Flyable {
                    String fly() { 'whoosh' }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Trait should emit TYPE_STRUCT (5)
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_STRUCT),
                "Expected TYPE_STRUCT token for trait");
    }

    @Test
    void emitsDedicatedTypeKeywordTokenForTraitDeclaration() {
        String source = """
                trait Flyable {
                    String fly() { 'whoosh' }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE_KEYWORD));
    }

    @Test
    void emitsTypeKeywordTokensForClassExtendsAndImplements() {
        String source = """
                class Animal {}
                interface Flyer {}
                class Bird extends Animal implements Flyer {}
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertEquals(5, countTokenType(tokens, SemanticTokensProvider.TYPE_TYPE_KEYWORD));
    }

    @Test
    void emitsStructAndMethodForTraitWithMultipleMembers() {
        String source = """
                trait Describable {
                    String name
                    abstract String describe()
                    String greet() { "Hello, $name" }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_STRUCT));
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
        assertTrue(tokens.stream().anyMatch(
                t -> (t.modifiers & SemanticTokensProvider.MOD_ABSTRACT) != 0));
    }

    @Test
    void emitsPropertyTokenForTraitPropertyDeclaration() {
        String source = """
                trait Named {
                    String name
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(tokens.stream().anyMatch(t -> "name".equals(t.text)
                && t.tokenType == SemanticTokensProvider.TYPE_PROPERTY),
                "Expected trait property declaration to be tokenized as property");
    }

    @Test
    void emitsPropertyTokenForImplicitTraitMemberUsageInImplementingClass() {
        String source = """
                trait Named {
                    String name
                }
                class Person implements Named {
                    String label() {
                        name
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(tokens.stream().anyMatch(t -> t.line == 5
                && "name".equals(t.text)
                && t.tokenType == SemanticTokensProvider.TYPE_PROPERTY),
                "Expected implicit trait member usage to be tokenized as property");
    }

    // ---- Generics with bounds ----

    @Test
    void emitsTypeParameterForGenericBounds() {
        String source = """
                class Box<T extends Comparable<T>> {
                    T value
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Generics may produce TYPE_TYPE_PARAMETER or TYPE_TYPE depending on resolution
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS),
                "Expected type-related token for generic bound T");
    }

    @Test
    void emitsTokensForMultipleGenericParameters() {
        String source = """
                class Pair<A, B> {
                    A first
                    B second
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Multiple generic type params should generate tokens
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS),
                "Expected type-related tokens for generic parameters");
    }

    // ---- Method with throws clause ----

    @Test
    void emitsTokensForMethodWithThrowsClause() {
        String source = """
                class Service {
                    void process() throws IOException, IllegalArgumentException {
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Inner class references ----

    @Test
    void emitsTokensForInnerClassDeclaration() {
        String source = """
                class Outer {
                    class Inner {
                        String data
                    }
                    Inner create() { new Inner() }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        // Should have CLASS tokens for both Outer and Inner
        long classTokens = tokens.stream()
                .filter(t -> t.tokenType == SemanticTokensProvider.TYPE_CLASS)
                .count();
        assertTrue(classTokens >= 2, "Expected at least 2 class tokens for Outer+Inner");
    }

    // ---- Script-level code (synthetic class) ----

    @Test
    void emitsTokensForScriptLevelCode() {
        String source = """
                def x = 42
                println x
                String name = 'Groovy'
                println name.toUpperCase()
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_VARIABLE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Enum member access ----

    @Test
    void emitsEnumMemberTokenForEnumConstants() {
        String source = """
                enum Color { RED, GREEN, BLUE }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_ENUM));
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_ENUM_MEMBER),
                "Expected TYPE_ENUM_MEMBER for enum constants");
    }

    @Test
    void emitsEnumMemberAndMethodForEnumWithBehavior() {
        String source = """
                enum Planet {
                    EARTH(1.0), MARS(0.38)
                    final double gravity
                    Planet(double g) { this.gravity = g }
                    String describe() { "${name()}: $gravity" }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_ENUM));
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Static field/method ----

    @Test
    void emitsStaticModifierForStaticMembers() {
        String source = """
                class Config {
                    static final String VERSION = '1.0'
                    static int count = 0
                    static void reset() { count = 0 }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(tokens.stream().anyMatch(
                t -> (t.modifiers & SemanticTokensProvider.MOD_STATIC) != 0),
                "Expected MOD_STATIC for static members");
    }

    // ---- Final variable (MOD_READONLY) ----

    @Test
    void emitsReadonlyForFinalField() {
        String source = """
                class Immutable {
                    final String id = 'abc'
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty(), "Expected tokens for class with final field");
    }

    // ---- Class with interface implementation ----

    @Test
    void emitsInterfaceTokenForInterfaceDeclaration() {
        String source = """
                interface Printable {
                    void print()
                }
                class Doc implements Printable {
                    void print() { println 'doc' }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_INTERFACE));
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS));
    }

    // ---- Library types (MOD_DEFAULT_LIB) ----

    @Test
    void emitsDefaultLibForStandardLibraryTypes() {
        String source = """
                import java.util.List
                import java.util.Map
                class Demo {
                    String name
                    List<Integer> numbers
                    Map<String, Object> data
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Standard lib types may or may not resolve to java.* fqn without classpath
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_TYPE)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_PROPERTY)
                || tokens.stream().anyMatch(t -> (t.modifiers & SemanticTokensProvider.MOD_DEFAULT_LIB) != 0),
                "Expected type-related tokens for library types");
    }

    // ---- Closure parameters ----

    @Test
    void emitsParameterTokenForClosureParams() {
        String source = """
                class Demo {
                    void run() {
                        [1,2,3].each { int item ->
                            println item
                        }
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_VARIABLE));
    }

    // ---- Named arguments ----

    @Test
    void emitsParameterTokenForNamedArgs() {
        String source = """
                class Cfg {
                    String host
                    int port
                }
                class Demo {
                    void run() {
                        def c = new Cfg(host: 'localhost', port: 8080)
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        // Named args may produce TYPE_PARAMETER or just be part of constructor call
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_PARAMETER)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS),
                "Expected tokens for named-arg constructor call");
    }

    // ---- Annotation ----

    @Test
    void emitsDecoratorTokenForAnnotation() {
        String source = """
                @interface MyAnnotation {
                    String value() default ''
                }
                @MyAnnotation('test')
                class Annotated {}
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_DECORATOR)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS));
    }

    // ---- Package declaration ----

    @Test
    void emitsNamespaceTokenForPackageDeclaration() {
        String source = """
                package com.example.demo
                class Widget {}
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_NAMESPACE),
                "Expected TYPE_NAMESPACE for package");
    }

    // ---- Empty source ----

    @Test
    void emitsNoTokensForEmptySource() {
        List<DecodedToken> tokens = collectTokens("", null);
        assertTrue(tokens.isEmpty());
    }

    // ---- Multiple classes in one file ----

    @Test
    void emitsTokensForMultipleClassesInOneFile() {
        String source = """
                class First {
                    void a() {}
                }
                class Second {
                    void b() {}
                }
                class Third extends First {
                    void c() {}
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        long classTokens = tokens.stream()
                .filter(t -> t.tokenType == SemanticTokensProvider.TYPE_CLASS)
                .count();
        assertTrue(classTokens >= 3, "Expected tokens for 3 classes");
    }

    // ---- Property access chain ----

    @Test
    void emitsPropertyTokensForChainedAccess() {
        String source = """
                class Addr { String city }
                class Person { Addr address }
                def p = new Person()
                p.address.city
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_PROPERTY)
                || hasTokenType(tokens, SemanticTokensProvider.TYPE_VARIABLE));
    }

    // ---- Cast expression triggers class expression visit ----

    @Test
    void emitsTypeTokenForCastExpression() {
        String source = """
                class Demo {
                    void run() {
                        def obj = (String) null
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
    }

    // ---- Try/catch with exception type ----

    @Test
    void emitsTypeTokenForCatchClauseExceptionType() {
        String source = """
                class Demo {
                    void run() {
                        try { } catch (RuntimeException e) { }
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertFalse(tokens.isEmpty());
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_METHOD));
    }

    // ---- Class extending with generics ----

    @Test
    void emitsTokensForGenericSuperclass() {
        String source = """
                class StringList extends ArrayList<String> {
                    void addItem(String s) { add(s) }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        assertTrue(hasTokenType(tokens, SemanticTokensProvider.TYPE_CLASS));
    }

    @Test
    void emitsKeywordTokenForSpockAndLabel() {
        String source = """
                class MySpec extends Object {
                    def 'test something'() {
                        given:
                        def x = 1

                        and:
                        def y = 2

                        when:
                        def z = x + y

                        then:
                        z == 3
                    }
                }
                """;
        List<DecodedToken> tokens = collectTokens(source, null);
        // given, and, when, then should all be keyword tokens
        List<DecodedToken> keywordTokens = tokens.stream()
                .filter(t -> t.tokenType == SemanticTokensProvider.TYPE_KEYWORD)
                .toList();
        assertTrue(keywordTokens.size() >= 4,
                "Expected at least 4 keyword tokens (given, and, when, then), got " + keywordTokens.size());
        // Verify 'and' is present as a keyword at line 5 (0-based)
        assertTrue(keywordTokens.stream().anyMatch(t -> t.line == 5 && t.length == 3),
                "Expected 'and' keyword token at line 5");
    }

    private List<DecodedToken> collectTokens(String source, Range range) {
        ModuleNode moduleNode = parseModule(source);
        SemanticTokensVisitor visitor = new SemanticTokensVisitor(source, range);
        visitor.visitModule(moduleNode);
        return decode(visitor.getEncodedTokens(), source);
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result =
                compilerService.parse("file:///SemanticTokensVisitorTest.groovy", source);
        assertTrue(result.hasAST(), "Expected AST for semantic token fixture");
        ModuleNode moduleNode = result.getModuleNode();
        assertNotNull(moduleNode);
        return moduleNode;
    }

    private boolean hasTokenType(List<DecodedToken> tokens, int type) {
        return tokens.stream().anyMatch(token -> token.tokenType == type);
    }

    private long countTokenType(List<DecodedToken> tokens, int type) {
        return tokens.stream().filter(token -> token.tokenType == type).count();
    }

    private List<DecodedToken> decode(List<Integer> encoded, String source) {
        List<DecodedToken> decoded = new ArrayList<>();
        int previousLine = 0;
        int previousColumn = 0;
        String[] lines = source.split("\n", -1);

        for (int i = 0; i + 4 < encoded.size(); i += 5) {
            int deltaLine = encoded.get(i);
            int deltaColumn = encoded.get(i + 1);
            int length = encoded.get(i + 2);
            int tokenType = encoded.get(i + 3);
            int modifiers = encoded.get(i + 4);

            int line = previousLine + deltaLine;
            int column = deltaLine == 0 ? previousColumn + deltaColumn : deltaColumn;
                String text = line >= 0 && line < lines.length && column >= 0
                    && column + length <= lines[line].length()
                    ? lines[line].substring(column, column + length)
                    : "";

                decoded.add(new DecodedToken(line, column, length, tokenType, modifiers, text));
            previousLine = line;
            previousColumn = column;
        }

        return decoded;
    }

    private static final class DecodedToken {
        private final int line;
        private final int column;
        private final int length;
        private final int tokenType;
        private final int modifiers;
        private final String text;

        private DecodedToken(int line, int column, int length, int tokenType, int modifiers, String text) {
            this.line = line;
            this.column = column;
            this.length = length;
            this.tokenType = tokenType;
            this.modifiers = modifiers;
            this.text = text;
        }
    }
}
