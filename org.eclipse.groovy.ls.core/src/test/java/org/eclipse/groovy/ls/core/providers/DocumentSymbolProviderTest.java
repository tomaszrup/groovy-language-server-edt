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

import java.lang.reflect.Method;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Groovy AST fallback path and helpers in {@link DocumentSymbolProvider}.
 */
class DocumentSymbolProviderTest {

    private DocumentSymbolProvider provider;
    private DocumentManager documentManager;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new DocumentSymbolProvider(documentManager);
    }

    // ---- offsetToPosition ----

    @Test
    void offsetToPositionFirstLine() throws Exception {
        Position pos = invokeOffsetToPosition("hello\nworld", 3);
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        Position pos = invokeOffsetToPosition("hello\nworld", 8);
        assertEquals(1, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    @Test
    void offsetToPositionClamped() throws Exception {
        Position pos = invokeOffsetToPosition("hi", 99);
        assertEquals(0, pos.getLine());
        assertEquals(2, pos.getCharacter());
    }

    // ---- toDocumentSymbolFromAST ----

    @Test
    void toDocumentSymbolFromASTClassWithMembers() throws Exception {
        ModuleNode module = parseModule("""
                class Foo {
                    String name
                    int count
                    void greet(String msg) {}
                }
                """, "file:///DocSymClass.groovy");

        ClassNode cls = findClass(module, "Foo");
        String content = "class Foo {\n    String name\n    int count\n    void greet(String msg) {}\n}\n";
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, content);

        assertNotNull(symbol);
        assertEquals("Foo", symbol.getName());
        assertEquals(SymbolKind.Class, symbol.getKind());

        List<DocumentSymbol> children = symbol.getChildren();
        assertNotNull(children);
        // At least: name (property), count (property), greet (method)
        assertTrue(children.size() >= 3, "Expected at least 3 children, got " + children.size());
        assertTrue(children.stream().anyMatch(c -> "name".equals(c.getName())));
        assertTrue(children.stream().anyMatch(c -> "count".equals(c.getName())));
        assertTrue(children.stream().anyMatch(c -> "greet".equals(c.getName())));
    }

    @Test
    void toDocumentSymbolFromASTInterface() throws Exception {
        ModuleNode module = parseModule("interface Greeter { void greet() }",
                "file:///DocSymInterface.groovy");
        ClassNode cls = findClass(module, "Greeter");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, "interface Greeter { void greet() }");

        assertEquals("Greeter", symbol.getName());
        assertEquals(SymbolKind.Interface, symbol.getKind());
    }

    @Test
    void toDocumentSymbolFromASTEnum() throws Exception {
        ModuleNode module = parseModule("enum Color { RED, GREEN, BLUE }",
                "file:///DocSymEnum.groovy");
        ClassNode cls = findClass(module, "Color");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(cls, "enum Color { RED, GREEN, BLUE }");

        assertEquals("Color", symbol.getName());
        assertEquals(SymbolKind.Enum, symbol.getKind());
        // Enum constants should appear as children
        assertTrue(symbol.getChildren().stream().anyMatch(c -> "RED".equals(c.getName())));
    }

    @Test
    void toDocumentSymbolFromASTWithSuper() throws Exception {
        ModuleNode module = parseModule("""
                class Base {}
                class Child extends Base {}
                """, "file:///DocSymExtends.groovy");
        ClassNode child = findClass(module, "Child");
        DocumentSymbol symbol = invokeToDocumentSymbolFromAST(child,
                "class Base {}\nclass Child extends Base {}\n");

        assertNotNull(symbol.getDetail());
        assertTrue(symbol.getDetail().contains("extends"));
        assertTrue(symbol.getDetail().contains("Base"));
    }

    // ---- setRangesFromAST ----

    @Test
    void setRangesFromASTSetsValidRanges() throws Exception {
        ModuleNode module = parseModule("class Foo { void bar() {} }",
                "file:///SetRanges.groovy");
        ClassNode cls = findClass(module, "Foo");

        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("Foo");
        invokeSetRangesFromAST(symbol, cls, "class Foo { void bar() {} }");

        assertNotNull(symbol.getRange());
        assertNotNull(symbol.getSelectionRange());
        assertTrue(symbol.getRange().getStart().getLine() >= 0);
    }

    @Test
    void setRangesFromASTHandlesNullNode() throws Exception {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("Test");
        invokeSetRangesFromAST(symbol, null, "class Test {}");

        assertEquals(0, symbol.getRange().getStart().getLine());
        assertEquals(0, symbol.getRange().getStart().getCharacter());
    }

    // ---- getDocumentSymbolsFromGroovyAST (via DocumentManager) ----

    @Test
    void getDocumentSymbolsFromGroovyASTReturnsClassesAndMembers() throws Exception {
        String uri = "file:///DocSymAST.groovy";
        String content = """
                class Foo {
                    String name
                    void doStuff() {}
                }
                """;

        documentManager.didOpen(uri, content);
        List<Either<SymbolInformation, DocumentSymbol>> symbols = invokeGetDocumentSymbolsFromGroovyAST(uri, content);

        assertNotNull(symbols);
        assertFalse(symbols.isEmpty());
        DocumentSymbol fooSymbol = symbols.stream()
                .filter(Either::isRight)
                .map(Either::getRight)
                .filter(s -> "Foo".equals(s.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(fooSymbol, "Expected 'Foo' symbol");
        assertTrue(fooSymbol.getChildren().stream().anyMatch(c -> "name".equals(c.getName())));
        assertTrue(fooSymbol.getChildren().stream().anyMatch(c -> "doStuff".equals(c.getName())));

        documentManager.didClose(uri);
    }

    @Test
    void getDocumentSymbolsFromGroovyASTReturnsEmptyForNoAST() throws Exception {
        String uri = "file:///DocSymNoAST.groovy";
        // Don't open the document, so there's no AST
        List<Either<SymbolInformation, DocumentSymbol>> symbols =
                invokeGetDocumentSymbolsFromGroovyAST(uri, "class Foo {}");
        assertTrue(symbols.isEmpty());
    }

    // ---- Full getDocumentSymbols (public API, fallback path) ----

    @Test
    void getDocumentSymbolsFallsBackToAST() {
        String uri = "file:///DocSymPublic.groovy";
        String content = """
                class Alpha {
                    int value
                    void compute() {}
                }
                """;

        documentManager.didOpen(uri, content);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
        List<Either<SymbolInformation, DocumentSymbol>> result = provider.getDocumentSymbols(params);

        // Since there's no JDT working copy, the fallback AST path should kick in
        assertNotNull(result);
        assertFalse(result.isEmpty());

        documentManager.didClose(uri);
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private Position invokeOffsetToPosition(String content, int offset) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("offsetToPosition", String.class, int.class);
        m.setAccessible(true);
        return (Position) m.invoke(provider, content, offset);
    }

    private DocumentSymbol invokeToDocumentSymbolFromAST(ClassNode classNode, String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("toDocumentSymbolFromAST",
                ClassNode.class, String.class);
        m.setAccessible(true);
        return (DocumentSymbol) m.invoke(provider, classNode, content);
    }

    private void invokeSetRangesFromAST(DocumentSymbol symbol,
                                         org.codehaus.groovy.ast.ASTNode node,
                                         String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("setRangesFromAST",
                DocumentSymbol.class, org.codehaus.groovy.ast.ASTNode.class, String.class);
        m.setAccessible(true);
        m.invoke(provider, symbol, node, content);
    }

    @SuppressWarnings("unchecked")
    private List<Either<SymbolInformation, DocumentSymbol>> invokeGetDocumentSymbolsFromGroovyAST(
            String uri, String content) throws Exception {
        Method m = DocumentSymbolProvider.class.getDeclaredMethod("getDocumentSymbolsFromGroovyAST",
                String.class, String.class);
        m.setAccessible(true);
        return (List<Either<SymbolInformation, DocumentSymbol>>) m.invoke(provider, uri, content);
    }

    // ---- AST helpers ----

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        if (!result.hasAST()) {
            throw new AssertionError("Expected AST for fixture: " + uri);
        }
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(c -> simpleName.equals(c.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }
}
