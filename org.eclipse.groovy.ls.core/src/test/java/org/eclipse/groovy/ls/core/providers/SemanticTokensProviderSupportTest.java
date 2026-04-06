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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class SemanticTokensProviderSupportTest {

    @Test
    void semanticTokenEncodingSupportRoundTripsEncodedData() throws Exception {
        Object support = createEncodingSupport();
        List<Integer> encoded = List.of(
                2, 4, 3, SemanticTokensProvider.TYPE_CLASS, SemanticTokensProvider.MOD_DECLARATION,
                0, 5, 4, SemanticTokensProvider.TYPE_METHOD, 0,
                2, 1, 6, SemanticTokensProvider.TYPE_VARIABLE, SemanticTokensProvider.MOD_READONLY);

        List<?> decoded = invokeList(support, "decodeTokenData", encoded);
        List<Integer> reencoded = invokeIntegerList(support, "encodeTokenData", decoded);

        assertEquals(encoded, reencoded);
    }

    @Test
    void semanticTokenEncodingSupportMergesUniqueSpansInSortedOrder() throws Exception {
        Object support = createEncodingSupport();
        List<Integer> primary = List.of(
                0, 0, 4, SemanticTokensProvider.TYPE_CLASS, 0,
                2, 2, 5, SemanticTokensProvider.TYPE_METHOD, 0);
        List<Integer> supplemental = List.of(
                0, 0, 4, SemanticTokensProvider.TYPE_TYPE, SemanticTokensProvider.MOD_READONLY,
                1, 1, 3, SemanticTokensProvider.TYPE_VARIABLE, 0);

        List<Integer> merged = invokeIntegerPairList(support, "mergeTokenData", primary, supplemental);
        List<Token> tokens = decodeAbsoluteTokens(merged);

        assertEquals(3, tokens.size());
        assertEquals(new Token(0, 0, 4, SemanticTokensProvider.TYPE_CLASS, 0), tokens.get(0));
        assertEquals(new Token(1, 1, 3, SemanticTokensProvider.TYPE_VARIABLE, 0), tokens.get(1));
        assertEquals(new Token(2, 2, 5, SemanticTokensProvider.TYPE_METHOD, 0), tokens.get(2));
    }

    @Test
    void semanticTokensIncludeNamespaceAndTypeForStaticStarImport() {
        String uri = "file:///SemanticTokensStaticStarImport.groovy";
        DocumentManager manager = new DocumentManager();
        manager.didOpen(uri, """
                import static java.util.Collections.*
                class Demo {
                    def items = emptyList()
                }
                """);

        SemanticTokensProvider provider = new SemanticTokensProvider(manager);
        SemanticTokensParams params = new SemanticTokensParams(new TextDocumentIdentifier(uri));

        SemanticTokens tokens = provider.getSemanticTokensFullBestEffort(params);
        List<Token> decoded = decodeAbsoluteTokens(tokens.getData());

        assertFalse(decoded.isEmpty());
        assertTrue(decoded.stream().anyMatch(token -> token.line == 0
                && token.tokenType == SemanticTokensProvider.TYPE_NAMESPACE));
        assertTrue(decoded.stream().anyMatch(token -> token.line == 0
                && token.tokenType == SemanticTokensProvider.TYPE_TYPE));

        manager.didClose(uri);
    }

    private Object createEncodingSupport() throws Exception {
        for (Class<?> nested : SemanticTokensProvider.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("SemanticTokenEncodingSupport")) {
                Constructor<?> constructor = nested.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
        throw new IllegalStateException("SemanticTokenEncodingSupport not found");
    }

    @SuppressWarnings("unchecked")
    private List<Integer> invokeIntegerList(Object target, String methodName, Object arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, List.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(target, arg);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> invokeIntegerPairList(Object target, String methodName, Object firstArg, Object secondArg)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, List.class, List.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(target, firstArg, secondArg);
    }

    private List<?> invokeList(Object target, String methodName, Object arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, List.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(target, arg);
    }

    private List<Token> decodeAbsoluteTokens(List<Integer> encoded) {
        List<Token> decoded = new ArrayList<>();
        int previousLine = 0;
        int previousColumn = 0;
        for (int index = 0; index + 4 < encoded.size(); index += 5) {
            int deltaLine = encoded.get(index);
            int deltaColumn = encoded.get(index + 1);
            int line = previousLine + deltaLine;
            int column = deltaLine == 0 ? previousColumn + deltaColumn : deltaColumn;
            decoded.add(new Token(line, column, encoded.get(index + 2), encoded.get(index + 3), encoded.get(index + 4)));
            previousLine = line;
            previousColumn = column;
        }
        return decoded;
    }

    private static final class Token {
        private final int line;
        private final int column;
        private final int length;
        private final int tokenType;
        private final int modifiers;

        private Token(int line, int column, int length, int tokenType, int modifiers) {
            this.line = line;
            this.column = column;
            this.length = length;
            this.tokenType = tokenType;
            this.modifiers = modifiers;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Token token)) {
                return false;
            }
            return line == token.line
                    && column == token.column
                    && length == token.length
                    && tokenType == token.tokenType
                    && modifiers == token.modifiers;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(line, column, length, tokenType, modifiers);
        }
    }
}