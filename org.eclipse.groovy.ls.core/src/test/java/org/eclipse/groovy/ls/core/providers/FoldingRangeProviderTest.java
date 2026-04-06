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

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FoldingRangeProvider}.
 */
class FoldingRangeProviderTest {

    private FoldingRangeProvider provider;
    private DocumentManager documentManager;

    @BeforeEach
    void setUp() {
        documentManager = new DocumentManager();
        provider = new FoldingRangeProvider(documentManager);
    }

    // ---- getFoldingRanges: missing document ----

    @Test
    void returnsEmptyForMissingDocument() {
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier("file:///Missing.groovy"));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        assertNotNull(ranges);
        assertTrue(ranges.isEmpty());
    }

    // ---- getFoldingRanges: empty document ----

    @Test
    void returnsEmptyForEmptyDocument() {
        String uri = "file:///Empty.groovy";
        documentManager.didOpen(uri, "");
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        assertTrue(ranges.isEmpty());
        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: single-line class (no folding) ----

    @Test
    void noFoldingForSingleLineClass() {
        String uri = "file:///Single.groovy";
        documentManager.didOpen(uri, "class Foo {}");
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        // A single-line class may produce a range, but start == end
        // so it's effectively no folding
        boolean hasMeaningful = ranges.stream()
                .anyMatch(r -> r.getEndLine() > r.getStartLine());
        assertFalse(hasMeaningful);
        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: multi-line class ----

    @Test
    void foldingForMultiLineClass() {
        String uri = "file:///MultiClass.groovy";
        documentManager.didOpen(uri, """
                class Foo {
                    String name
                    int count
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        assertFalse(ranges.isEmpty());
        // Should have at least the class folding range
        assertTrue(ranges.stream().anyMatch(r -> r.getEndLine() > r.getStartLine()));
        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: class with methods ----

    @Test
    void foldingForClassWithMethods() {
        String uri = "file:///ClassMethods.groovy";
        documentManager.didOpen(uri, """
                class Calculator {
                    int add(int a, int b) {
                        return a + b
                    }
                    int subtract(int a, int b) {
                        return a - b
                    }
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        // Expect class fold + 2 method folds
        long multiLineRanges = ranges.stream()
                .filter(r -> r.getEndLine() > r.getStartLine())
                .count();
        assertTrue(multiLineRanges >= 3, "Expected at least 3 folding ranges (class + 2 methods), got " + multiLineRanges);
        documentManager.didClose(uri);
    }

    // ---- addImportFoldingFromText ----

    @Test
    void importFoldingForMultipleImports() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addImportFoldingFromText("""
                import java.util.List
                import java.util.Map
                import java.util.Set
                
                class Foo {}
                """, ranges);
        assertEquals(1, ranges.size());
        assertEquals(FoldingRangeKind.Imports, ranges.get(0).getKind());
        assertEquals(0, ranges.get(0).getStartLine());
        assertEquals(2, ranges.get(0).getEndLine());
    }

    @Test
    void noImportFoldingForSingleImport() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addImportFoldingFromText("import java.util.List\nclass Foo {}", ranges);
        assertTrue(ranges.isEmpty());
    }

    @Test
    void noImportFoldingWhenNoImports() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addImportFoldingFromText("class Foo {}", ranges);
        assertTrue(ranges.isEmpty());
    }

    // ---- addCommentFolding ----

    @Test
    void commentFoldingForBlockComment() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addCommentFolding("""
                /*
                 * This is a block comment
                 * spanning multiple lines
                 */
                class Foo {}
                """, ranges);
        assertEquals(1, ranges.size());
        assertEquals(FoldingRangeKind.Comment, ranges.get(0).getKind());
        assertEquals(0, ranges.get(0).getStartLine());
        assertEquals(3, ranges.get(0).getEndLine());
    }

    @Test
    void commentFoldingForJavadocComment() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addCommentFolding("""
                /**
                 * Javadoc comment.
                 * @param foo bar
                 */
                class Foo {}
                """, ranges);
        assertEquals(1, ranges.size());
        assertEquals(FoldingRangeKind.Comment, ranges.get(0).getKind());
    }

    @Test
    void noCommentFoldingForSingleLineComment() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addCommentFolding("// single line\nclass Foo {}", ranges);
        assertTrue(ranges.isEmpty());
    }

    @Test
    void multipleBlockComments() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addCommentFolding("""
                /*
                 * First comment
                 */
                class Foo {}
                /*
                 * Second comment
                 */
                """, ranges);
        assertEquals(2, ranges.size());
    }

    @Test
    void noCommentFoldingForSingleLineBlock() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addCommentFolding("/* short */\nclass Foo {}", ranges);
        assertTrue(ranges.isEmpty());
    }

    // ---- addMultiLineStringFolding ----

    @Test
    void multiLineStringFoldingTripleDoubleQuote() {
        List<FoldingRange> ranges = new ArrayList<>();
        String tripleQuote = "\"\"\"";
        String content = "def s = " + tripleQuote + "\nline 1\nline 2\n" + tripleQuote;
        provider.addMultiLineStringFolding(content, ranges);
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0).getStartLine());
        assertEquals(3, ranges.get(0).getEndLine());
    }

    @Test
    void multiLineStringFoldingTripleSingleQuote() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addMultiLineStringFolding("def s = '''\nline 1\nline 2\n'''", ranges);
        assertEquals(1, ranges.size());
    }

    @Test
    void noFoldingForSingleLineString() {
        List<FoldingRange> ranges = new ArrayList<>();
        provider.addMultiLineStringFolding("def s = 'hello'", ranges);
        assertTrue(ranges.isEmpty());
    }

    // ---- getFoldingRanges: complete document with imports, comments, class, methods ----

    @Test
    void fullDocumentFolding() {
        String uri = "file:///FullDocument.groovy";
        documentManager.didOpen(uri, """
                import java.util.List
                import java.util.Map
                
                /*
                 * File-level comment
                 */
                class MyService {
                    void doWork() {
                        println 'working'
                    }
                    
                    void cleanup() {
                        println 'cleaning'
                    }
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);

        // Expect: imports, comment, class, 2 methods = 5 ranges minimum
        assertTrue(ranges.size() >= 4,
                "Expected at least 4 folding ranges, got " + ranges.size());

        // Check imports range exists
        assertTrue(ranges.stream().anyMatch(r ->
                FoldingRangeKind.Imports.equals(r.getKind())));

        // Check comment range exists
        assertTrue(ranges.stream().anyMatch(r ->
                FoldingRangeKind.Comment.equals(r.getKind())));

        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: multiple classes ----

    @Test
    void foldingForMultipleClasses() {
        String uri = "file:///MultipleClasses.groovy";
        documentManager.didOpen(uri, """
                class First {
                    void alpha() {
                        println 'a'
                    }
                }
                class Second {
                    void beta() {
                        println 'b'
                    }
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        // 2 classes + 2 methods = at least 4
        long count = ranges.stream()
                .filter(r -> r.getEndLine() > r.getStartLine())
                .count();
        assertTrue(count >= 4, "Expected at least 4 folding ranges, got " + count);
        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: enum ----

    @Test
    void foldingForEnum() {
        String uri = "file:///EnumFold.groovy";
        documentManager.didOpen(uri, """
                enum Color {
                    RED,
                    GREEN,
                    BLUE
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        assertTrue(ranges.stream().anyMatch(r -> r.getEndLine() > r.getStartLine()));
        documentManager.didClose(uri);
    }

    // ---- getFoldingRanges: interface ----

    @Test
    void foldingForInterface() {
        String uri = "file:///InterfaceFold.groovy";
        documentManager.didOpen(uri, """
                interface Printable {
                    void print()
                    void format()
                }
                """);
        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));
        List<FoldingRange> ranges = provider.getFoldingRanges(params);
        assertTrue(ranges.stream().anyMatch(r -> r.getEndLine() > r.getStartLine()));
        documentManager.didClose(uri);
    }
}
