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

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result = compilerService.parse("file:///UnusedImportDetectorTest.groovy", source);
        assertTrue(result.hasAST(), "Expected parser to produce AST for test source");
        return result.getModuleNode();
    }
}
