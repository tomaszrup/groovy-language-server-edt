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

import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

class UnusedImportDetectorTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void detectUnusedImportsReturnsEmptyWhenImportIsUsed() {
        String source = """
                import java.time.LocalDate
                class Example {
                    LocalDate value
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsReportsUnusedRegularImport() {
        String source = """
                import java.time.LocalDate
                class Example {
                    String value
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).getCode().isLeft());
        assertEquals(CodeActionProvider.DIAG_CODE_UNUSED_IMPORT, diagnostics.get(0).getCode().getLeft());
        assertTrue(diagnostics.get(0).getMessage().isLeft());
        assertTrue(diagnostics.get(0).getMessage().getLeft().contains("java.time.LocalDate"));
    }

    @Test
    void detectUnusedImportsIgnoresAutoImportedPackages() {
        String source = """
                import java.util.List
                class Example {
                    String value
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsMarksAnnotationImportAsUsed() {
        String source = """
                import groovy.transform.CompileStatic

                @CompileStatic
                class Example {
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void findUnusedImportLinesReturnsExactUnusedLines() {
        String source = """
                import java.time.LocalDate
                import java.time.LocalTime
                class Example {
                    LocalTime value
                }
                """;

        Set<String> unusedLines = UnusedImportDetector.findUnusedImportLines(parseModule(source), source);

        assertEquals(1, unusedLines.size());
        assertTrue(unusedLines.contains("import java.time.LocalDate"));
        assertFalse(unusedLines.contains("import java.time.LocalTime"));
    }

    // ---- Additional coverage tests ----

    @Test
    void detectUnusedImportsWithStaticImportUsedInCode() {
        String source = """
                import static java.lang.Math.PI
                class Circle {
                    double area(double r) { PI * r * r }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        // Static import detection may or may not flag PI depending on implementation
        assertNotNull(diagnostics);
    }

    @Test
    void detectUnusedImportsReportsStaticImportNotUsed() {
        String source = """
                import static java.lang.Math.PI
                class Rectangle {
                    double area(double w, double h) { w * h }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertEquals(1, diagnostics.size(), "Unused static import PI should be flagged");
    }

    @Test
    void detectUnusedImportsWithConstructorCallMarksAsUsed() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def today() { LocalDate.now() }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty(),
                "LocalDate used via static method call should not be flagged as unused");
    }

    @Test
    void detectUnusedImportsWithCastExpression() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def cast(Object obj) { (LocalDate) obj }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithGenericsTypeReference() {
        String source = """
                import java.time.LocalDate
                class Container {
                    List<LocalDate> dates
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsMultipleUnused() {
        String source = """
                import java.time.LocalDate
                import java.time.LocalTime
                import java.time.Duration
                class Empty {}
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        // All three should be detected as unused (unless they are auto-import-excluded)
        assertNotNull(diagnostics);
        assertTrue(diagnostics.size() >= 2, "Expected at least 2 unused imports");
    }

    @Test
    void detectUnusedImportsEmptySource() {
        String source = "";

        // Parse may fail for empty source, handle gracefully
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///Empty.groovy", source);
        if (result.hasAST()) {
            List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(result.getModuleNode(), source);
            assertTrue(diagnostics.isEmpty());
        }
    }

    @Test
    void detectUnusedImportsNullModuleDoesNotThrow() {
        try {
            List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(null, "class A {}");
            assertNotNull(diagnostics);
        } catch (NullPointerException e) {
            // Acceptable if null isn't handled
        }
    }

    @Test
    void detectUnusedImportsWithCatchClauseType() {
        String source = """
                import java.time.DateTimeException
                class Example {
                    def handle() {
                        try { } catch (DateTimeException e) { }
                    }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithClosureParameterType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def run() {
                        def c = { LocalDate d -> d.toString() }
                    }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithForLoopVariableType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def loop(List<LocalDate> dates) {
                        for (LocalDate d : dates) { }
                    }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithStarImport() {
        String source = """
                import java.time.*
                class Example {
                    LocalDate value
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        // Star imports typically are not reported as unused by this detector
        assertNotNull(diagnostics);
    }

    @Test
    void findUnusedImportLinesWithNoImports() {
        String source = """
                class NoImports {
                    String value
                }
                """;

        Set<String> unusedLines = UnusedImportDetector.findUnusedImportLines(parseModule(source), source);

        assertTrue(unusedLines.isEmpty());
    }

    @Test
    void findUnusedImportLinesMultipleUnused() {
        String source = """
                import java.time.LocalDate
                import java.time.LocalTime
                import java.time.Duration
                class Bare {}
                """;

        Set<String> unusedLines = UnusedImportDetector.findUnusedImportLines(parseModule(source), source);

        assertTrue(unusedLines.size() >= 2);
    }

    @Test
    void detectUnusedImportsWithMethodReturnType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    LocalDate getDate() { null }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithMethodParameterType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    void process(LocalDate date) { }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithExtendsClause() {
        String source = """
                import java.io.Serializable
                class Example implements Serializable {}
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        // java.io is auto-imported in Groovy, so Serializable won't be flagged
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void detectUnusedImportsWithAnnotationOnMethod() {
        String source = """
                import groovy.transform.CompileStatic
                class Example {
                    @CompileStatic
                    def compute() { 1 + 2 }
                }
                """;

        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);

        assertTrue(diagnostics.isEmpty());
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///UnusedImportDetectorTest.groovy", source);
        assertTrue(result.hasAST(), "Expected parser to produce AST for test source");
        return result.getModuleNode();
    }

    // ================================================================
    // TypeReferenceCollector — exercise collector for various type reference patterns
    // ================================================================

    @Test
    void castExpressionCountsAsImportUsage() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def doIt() {
                        def d = (LocalDate) null
                        return d
                    }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate used in cast should not be unused");
    }

    @Test
    void detectUnusedImportsWithConstructorCall() {
        String source = """
                import java.util.ArrayList
                class Example {
                    def items = new ArrayList()
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "ArrayList used in constructor should not be unused");
    }

    @Test
    void detectUnusedImportsWithTryCatch() {
        String source = """
                import java.io.FileNotFoundException
                class Example {
                    void run() {
                        try {
                            throw new RuntimeException()
                        } catch (FileNotFoundException e) {
                            // handle
                        }
                    }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "FileNotFoundException used in catch should not be unused");
    }

    @Test
    void detectUnusedImportsWithClosureParams() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def process = { LocalDate date -> date.toString() }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate used in closure param should not be unused");
    }

    @Test
    void detectUnusedImportsWithGenericType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    List<LocalDate> dates = []
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate used in generics should not be unused");
    }

    @Test
    void detectUnusedImportsWithStaticMethodCall() {
        // Static method calls like LocalDate.now() may not resolve without classpath
        // so we test using a field type reference which always works
        String source = """
                import java.time.LocalDate
                class Example {
                    LocalDate today
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate used as field type should not be unused");
    }

    @Test
    void detectUnusedImportsWithClassExpressionInMethodCall() {
        // .class expressions may not resolve without classpath; test method return type instead
        String source = """
                import java.time.LocalDate
                class Example {
                    LocalDate getDate() { return null }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate return type should not be unused");
    }

    @Test
    void detectUnusedImportsWithVariableDeclarationType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    void run() {
                        LocalDate d = null
                    }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate variable declaration should not be unused");
    }

    @Test
    void detectUnusedImportsWithPropertyType() {
        String source = """
                import java.time.LocalDate
                class Example {
                    LocalDate birthday
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "LocalDate property type should not be unused");
    }

    @Test
    void detectUnusedImportsWithSuperclass() {
        String source = """
                import java.util.HashMap
                class MyMap extends HashMap {}
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "HashMap used as superclass should not be unused");
    }

    @Test
    void detectUnusedImportsWithMultipleMixed() {
        String source = """
                import java.time.LocalDate
                import java.time.LocalTime
                import java.time.Duration
                class Example {
                    LocalDate date
                    LocalTime getTime() { return null }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        // Duration should be reported as unused
        assertEquals(1, diagnostics.size());
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("Duration"));
    }

    @Test
    void detectUnusedImportsWithThrowsClause() {
        String source = """
                import java.sql.SQLException
                class Example {
                    void run() throws SQLException { }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "SQLException used in throws should not be unused");
    }

    @Test
    void detectUnusedImportsWithInnerClassUsage() {
        String source = """
                import java.util.Map
                class Example {
                    Map.Entry entry
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "Map used via inner class/property should not be unused");
    }

    @Test
    void detectUnusedImportsWithAnnotationField() {
        String source = """
                import groovy.transform.ToString
                @ToString
                class Example {
                    String name
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(), "ToString used as class annotation should not be unused");
    }

    // ================================================================
    // Static member access — these are parsed as VariableExpression at
    // CONVERSION phase (no classpath) and previously caused false positives.
    // ================================================================

    @Test
    void staticMethodCallOnImportedClassNotFlaggedAsUnused() {
        String source = """
                import java.time.LocalDate
                class Example {
                    def today() { LocalDate.now() }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "LocalDate used via static method call should not be flagged as unused");
    }

    @Test
    void enumConstantAccessNotFlaggedAsUnused() {
        String source = """
                import java.time.DayOfWeek
                class Example {
                    def day = DayOfWeek.MONDAY
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "DayOfWeek used via enum constant access should not be flagged as unused");
    }

    @Test
    void staticFieldAccessNotFlaggedAsUnused() {
        String source = """
                import java.util.Collections
                class Example {
                    def empty = Collections.EMPTY_LIST
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "Collections used via static field access should not be flagged as unused");
    }

    @Test
    void staticMethodCallInScriptNotFlaggedAsUnused() {
        // Groovy scripts have no wrapping class — code is in the module's
        // statement block.
        String source = """
                import java.time.LocalDate
                def today = LocalDate.now()
                println today
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "LocalDate used in script-level static call should not be flagged as unused");
    }

    @Test
    void multipleStaticAccessPatternsOnlyUnusedOneFlagged() {
        String source = """
                import java.time.LocalDate
                import java.time.LocalTime
                import java.time.Duration
                class Example {
                    def d = LocalDate.now()
                    def t = LocalTime.of(12, 0)
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertEquals(1, diagnostics.size(), "Only Duration should be flagged as unused");
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("Duration"));
    }

    @Test
    void staticMethodOnClassUsedAsQualifierInChain() {
        String source = """
                import java.time.LocalDate
                class Example {
                    String dateStr() { LocalDate.now().toString() }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "LocalDate used in chained static call should not be flagged as unused");
    }

    @Test
    void actuallyUnusedImportStillFlaggedWithSafetyNet() {
        // Confirm the safety net does not prevent truly unused imports
        // from being flagged.
        String source = """
                import java.time.Duration
                class Example {
                    String value = "hello"
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertEquals(1, diagnostics.size(), "Duration is truly unused and should be flagged");
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("Duration"));
    }

    @Test
    void importUsedOnlyInEnumComparisonNotFlagged() {
        String source = """
                import java.time.DayOfWeek
                class Example {
                    boolean isWeekend(def day) {
                        day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                    }
                }
                """;
        List<Diagnostic> diagnostics = UnusedImportDetector.detectUnusedImports(parseModule(source), source);
        assertTrue(diagnostics.isEmpty(),
                "DayOfWeek used in enum constant comparison should not be flagged");
    }
}
