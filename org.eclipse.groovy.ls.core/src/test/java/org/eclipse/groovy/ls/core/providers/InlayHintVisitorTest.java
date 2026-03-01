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

import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class InlayHintVisitorTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void emitsTypeAndParameterHintsForDefAndMethodCalls() {
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        InlayHintVisitor visitor = new InlayHintVisitor(source, null, InlayHintSettings.defaults());
        visitor.visitModule(parseModule(source));
        List<InlayHint> hints = visitor.getHints();

        assertFalse(hints.isEmpty());
        assertTrue(containsTypeHint(hints, ": String"));
        assertTrue(containsParameterHint(hints, "person:"));
    }

    @Test
    void respectsRangeFilterForHints() {
        String source = """
                def first = 'one'
                def second = 'two'
                """;

        Range firstLineOnly = new Range(new Position(0, 0), new Position(0, 30));
        InlayHintVisitor visitor = new InlayHintVisitor(source, firstLineOnly, InlayHintSettings.defaults());
        visitor.visitModule(parseModule(source));
        List<InlayHint> hints = visitor.getHints();

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().allMatch(hint -> hint.getPosition().getLine() == 0));
    }

    @Test
    void disabledSettingsProduceNoHints() {
        String source = """
                def first = 'one'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        InlayHintSettings disabled = new InlayHintSettings(false, false, false, false);
        InlayHintVisitor visitor = new InlayHintVisitor(source, null, disabled);
        visitor.visitModule(parseModule(source));

        assertTrue(visitor.getHints().isEmpty());
    }

    private ModuleNode parseModule(String source) {
        GroovyCompilerService.ParseResult result =
                compilerService.parse("file:///InlayHintVisitorTest.groovy", source);
        assertTrue(result.hasAST(), "Expected AST for inlay hint fixture");
        ModuleNode moduleNode = result.getModuleNode();
        assertNotNull(moduleNode);
        return moduleNode;
    }

    private boolean containsTypeHint(List<InlayHint> hints, String label) {
        return hints.stream().anyMatch(hint ->
                hint.getKind() == InlayHintKind.Type
                        && hint.getLabel().isLeft()
                        && label.equals(hint.getLabel().getLeft()));
    }

    private boolean containsParameterHint(List<InlayHint> hints, String label) {
        return hints.stream().anyMatch(hint ->
                hint.getKind() == InlayHintKind.Parameter
                        && hint.getLabel().isLeft()
                        && label.equals(hint.getLabel().getLeft()));
    }
}
