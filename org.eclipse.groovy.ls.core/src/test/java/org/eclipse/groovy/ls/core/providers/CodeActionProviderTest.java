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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

class CodeActionProviderTest {

        @Test
        void getCodeActionsOffersRemoveAllUnusedImportsQuickFix() {
                String uri = "file:///CodeActionProviderRemoveUnusedQuickFixTest.groovy";
                String content = """
                                import java.time.LocalDate
                                import java.time.LocalTime
                                class Example {
                                        LocalTime value
                                }
                                """;

                DocumentManager documentManager = new DocumentManager();
                documentManager.didOpen(uri, content);

                DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
                CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

                Diagnostic trigger = new Diagnostic();
                trigger.setRange(new Range(new Position(0, 0), new Position(0, 1)));
                trigger.setSeverity(DiagnosticSeverity.Warning);
                trigger.setMessage("unused import");

                List<CodeAction> actions = actions(provider, uri, content, List.of(CodeActionKind.QuickFix), List.of(trigger));

                CodeAction removeAll = actions.stream()
                                .filter(action -> "Remove all unused imports".equals(action.getTitle()))
                                .filter(action -> CodeActionKind.QuickFix.equals(action.getKind()))
                                .findFirst()
                                .orElse(null);

                assertNotNull(removeAll);
                assertNotNull(removeAll.getEdit());
                assertNotNull(removeAll.getEdit().getChanges());
                List<TextEdit> edits = removeAll.getEdit().getChanges().get(uri);
                assertNotNull(edits);
                assertEquals(1, edits.size());
                assertEquals(0, edits.get(0).getRange().getStart().getLine());
                assertEquals(1, edits.get(0).getRange().getEnd().getLine());

                documentManager.didClose(uri);
        }

        @Test
        void getCodeActionsOffersSourceRemoveAllUnusedImportsForSpecificKind() {
                String uri = "file:///CodeActionProviderRemoveUnusedSourceTest.groovy";
                String content = """
                                import java.time.LocalDate
                                import java.time.LocalTime
                                class Example {
                                        LocalTime value
                                }
                                """;

                DocumentManager documentManager = new DocumentManager();
                documentManager.didOpen(uri, content);

                DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
                CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

                List<CodeAction> actions = actions(
                                provider,
                                uri,
                                content,
                                List.of(CodeActionProvider.SOURCE_KIND_REMOVE_UNUSED_IMPORTS),
                                List.of());

                CodeAction removeAll = actions.stream()
                                .filter(action -> "Remove all unused imports".equals(action.getTitle()))
                                .filter(action -> CodeActionProvider.SOURCE_KIND_REMOVE_UNUSED_IMPORTS.equals(action.getKind()))
                                .findFirst()
                                .orElse(null);

                assertNotNull(removeAll);
                assertTrue(actions.stream().noneMatch(action -> "Organize imports".equals(action.getTitle())));

                documentManager.didClose(uri);
        }

        @Test
        void getCodeActionsSkipsRemoveAllUnusedImportsWhenNothingToRemove() {
                String uri = "file:///CodeActionProviderNoUnusedImportsTest.groovy";
                String content = """
                                import java.time.LocalTime
                                class Example {
                                        LocalTime value
                                }
                                """;

                DocumentManager documentManager = new DocumentManager();
                documentManager.didOpen(uri, content);

                DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
                CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

                List<CodeAction> actions = actions(
                                provider,
                                uri,
                                content,
                                List.of(CodeActionKind.QuickFix, CodeActionProvider.SOURCE_KIND_REMOVE_UNUSED_IMPORTS),
                                List.of());

                assertTrue(actions.stream().noneMatch(action -> "Remove all unused imports".equals(action.getTitle())));

                documentManager.didClose(uri);
        }

    @Test
    void getCodeActionsOffersImplementMissingInterfaceMemberQuickFix() {
        String uri = "file:///CodeActionProviderTest.groovy";
        String content = """
                interface Greeter {
                    String greet(String name)
                }

                class Impl implements Greeter {
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = missingInterfaceDiagnostic(4, "Impl");
        List<CodeAction> actions = quickFixActions(provider, uri, content, List.of(diagnostic));

        CodeAction implementAction = actions.stream()
                .filter(action -> "Implement missing interface member".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(implementAction);
        assertEquals(CodeActionKind.QuickFix, implementAction.getKind());
        assertTrue(Boolean.TRUE.equals(implementAction.getIsPreferred()));
        assertNotNull(implementAction.getEdit());
        assertNotNull(implementAction.getEdit().getChanges());
        assertTrue(implementAction.getEdit().getChanges().containsKey(uri));

        List<TextEdit> edits = implementAction.getEdit().getChanges().get(uri);
        assertNotNull(edits);
        assertFalse(edits.isEmpty());

        String inserted = edits.get(0).getNewText();
        assertNotNull(inserted);
        assertTrue(inserted.contains("@Override"));
        assertTrue(inserted.contains("String greet(String name)"));
        assertTrue(inserted.contains("UnsupportedOperationException"));

        documentManager.didClose(uri);
    }

    @Test
    void getCodeActionsSkipsImplementActionWhenMethodAlreadyImplemented() {
        String uri = "file:///CodeActionProviderAlreadyImplementedTest.groovy";
        String content = """
                interface Greeter {
                    String greet(String name)
                }

                class Impl implements Greeter {
                    @Override
                    String greet(String name) {
                        name
                    }
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = missingInterfaceDiagnostic(4, "Impl");
        List<CodeAction> actions = quickFixActions(provider, uri, content, List.of(diagnostic));

        List<CodeAction> implementActions = actions.stream()
                .filter(action -> action.getTitle() != null
                        && action.getTitle().startsWith("Implement missing interface member"))
                .collect(Collectors.toList());

        assertTrue(implementActions.isEmpty());

        documentManager.didClose(uri);
    }

    @Test
    void helperMethodsParseDiagnosticAndClassNamePatterns() throws Exception {
        CodeActionProvider provider = new CodeActionProvider(new DocumentManager(), new DiagnosticsProvider(new DocumentManager()));

        Diagnostic diag = missingInterfaceDiagnostic(0, "com.example.Impl");
        boolean isMissing = (boolean) invoke(
                provider,
                "isMissingInterfaceMemberDiagnostic",
                new Class<?>[] { Diagnostic.class },
                new Object[] { diag });

        String quoted = (String) invoke(
                provider,
                "extractClassSimpleNameFromMessage",
                new Class<?>[] { String.class },
                new Object[] { "The class 'com.example.Impl' must be declared abstract or the method 'x' must be implemented" });

        String unquoted = (String) invoke(
                provider,
                "extractClassSimpleNameFromMessage",
                new Class<?>[] { String.class },
                new Object[] { "The type com.example.Other must implement the inherited abstract method" });

        assertTrue(isMissing);
        assertEquals("Impl", quoted);
        assertEquals("Other", unquoted);
        assertNull((String) invoke(
                provider,
                "extractClassSimpleNameFromMessage",
                new Class<?>[] { String.class },
                new Object[] { "Random diagnostic message" }));
    }

    private List<CodeAction> quickFixActions(
            CodeActionProvider provider,
            String uri,
            String content,
            List<Diagnostic> diagnostics) {
                return actions(provider, uri, content, List.of(CodeActionKind.QuickFix), diagnostics);
        }

        private List<CodeAction> actions(
                        CodeActionProvider provider,
                        String uri,
                        String content,
                        List<String> onlyKinds,
                        List<Diagnostic> diagnostics) {
        CodeActionContext context = new CodeActionContext();
        context.setDiagnostics(diagnostics);
                context.setOnly(onlyKinds);

        CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier(uri),
                new Range(new Position(0, 0), new Position(content.split("\\n", -1).length + 1, 0)),
                context);

        return provider.getCodeActions(params).stream()
                .filter(Either::isRight)
                .map(Either::getRight)
                .collect(Collectors.toList());
    }

    private Diagnostic missingInterfaceDiagnostic(int line, String className) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        diagnostic.setRange(new Range(new Position(line, 0), new Position(line, 1)));
        diagnostic.setMessage("The class '" + className
                + "' must be declared abstract or the method 'java.lang.String greet(java.lang.String)' must be implemented");
        return diagnostic;
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
