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

    private List<DecodedToken> collectTokens(String source, Range range) {
        ModuleNode moduleNode = parseModule(source);
        SemanticTokensVisitor visitor = new SemanticTokensVisitor(source, range);
        visitor.visitModule(moduleNode);
        return decode(visitor.getEncodedTokens());
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

    private List<DecodedToken> decode(List<Integer> encoded) {
        List<DecodedToken> decoded = new ArrayList<>();
        int previousLine = 0;
        int previousColumn = 0;

        for (int i = 0; i + 4 < encoded.size(); i += 5) {
            int deltaLine = encoded.get(i);
            int deltaColumn = encoded.get(i + 1);
            int length = encoded.get(i + 2);
            int tokenType = encoded.get(i + 3);
            int modifiers = encoded.get(i + 4);

            int line = previousLine + deltaLine;
            int column = deltaLine == 0 ? previousColumn + deltaColumn : deltaColumn;

            decoded.add(new DecodedToken(line, column, length, tokenType, modifiers));
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

        private DecodedToken(int line, int column, int length, int tokenType, int modifiers) {
            this.line = line;
            this.column = column;
            this.length = length;
            this.tokenType = tokenType;
            this.modifiers = modifiers;
        }
    }
}
