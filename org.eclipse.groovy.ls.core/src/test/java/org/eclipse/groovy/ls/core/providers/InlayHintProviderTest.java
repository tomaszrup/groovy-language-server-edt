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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

class InlayHintProviderTest {

    @Test
    void getInlayHintsReturnsEmptyWhenDocumentIsMissing() {
        InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> hints = provider.getInlayHints(paramsFor("file:///MissingInlayHintDoc.groovy"), InlayHintSettings.defaults());

        assertTrue(hints.isEmpty());
    }

    @Test
    void getInlayHintsProducesTypeAndParameterHints() {
        String uri = "file:///InlayHintProviderIntegrationTest.groovy";
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), InlayHintSettings.defaults());

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(hint -> ": String".equals(labelText(hint))));
        assertTrue(hints.stream().anyMatch(hint -> "person:".equals(labelText(hint))));

        documentManager.didClose(uri);
    }

    @Test
    void getInlayHintsRespectsDisabledParameterNameSetting() {
        String uri = "file:///InlayHintProviderSettingsTest.groovy";
        String source = """
                def name = 'Ada'
                def greet(String person) { "Hi ${person}" }
                greet('Bob')
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, source);

        InlayHintProvider provider = new InlayHintProvider(documentManager);
        InlayHintSettings noParameterNames = new InlayHintSettings(true, false, true, true);
        List<InlayHint> hints = provider.getInlayHints(paramsFor(uri), noParameterNames);

        assertTrue(hints.stream().noneMatch(hint -> hint.getKind() == InlayHintKind.Parameter));
        assertTrue(hints.stream().anyMatch(hint -> hint.getKind() == InlayHintKind.Type));

        documentManager.didClose(uri);
    }

    @Test
    void dedupeAndSortRemovesDuplicatesAndSortsByPosition() throws Exception {
        InlayHintProvider provider = new InlayHintProvider(new DocumentManager());

        List<InlayHint> input = new ArrayList<>();
        input.add(hint(2, 5, "b:", InlayHintKind.Parameter));
        input.add(hint(1, 3, ": String", InlayHintKind.Type));
        input.add(hint(1, 3, ": String", InlayHintKind.Parameter));
        input.add(hint(0, 10, "a:", InlayHintKind.Parameter));

        List<InlayHint> output = invokeDedupeAndSort(provider, input);

        assertEquals(3, output.size());
        assertEquals(0, output.get(0).getPosition().getLine());
        assertEquals(10, output.get(0).getPosition().getCharacter());
        assertEquals(1, output.get(1).getPosition().getLine());
        assertEquals(3, output.get(1).getPosition().getCharacter());
        assertEquals(2, output.get(2).getPosition().getLine());
        assertEquals(5, output.get(2).getPosition().getCharacter());
    }

    private InlayHintParams paramsFor(String uri) {
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setRange(new Range(new Position(0, 0), new Position(200, 0)));
        return params;
    }

    private InlayHint hint(int line, int character, String label, InlayHintKind kind) {
        InlayHint hint = new InlayHint();
        hint.setPosition(new Position(line, character));
        hint.setKind(kind);
        hint.setLabel(label);
        return hint;
    }

    @SuppressWarnings("unchecked")
    private List<InlayHint> invokeDedupeAndSort(InlayHintProvider provider, List<InlayHint> hints)
            throws Exception {
        Method method = InlayHintProvider.class.getDeclaredMethod("dedupeAndSort", List.class);
        method.setAccessible(true);
        return (List<InlayHint>) method.invoke(provider, hints);
    }

    private String labelText(InlayHint hint) {
        if (hint.getLabel().isLeft()) {
            return hint.getLabel().getLeft();
        }
        StringBuilder builder = new StringBuilder();
        for (InlayHintLabelPart part : hint.getLabel().getRight()) {
            if (part != null && part.getValue() != null) {
                builder.append(part.getValue());
            }
        }
        return builder.toString();
    }
}
