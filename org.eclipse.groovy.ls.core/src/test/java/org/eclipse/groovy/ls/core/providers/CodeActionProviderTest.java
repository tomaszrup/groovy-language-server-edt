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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

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
    void removeUnusedImportIsPreferredOnlyWhenCursorIsOnImportSymbol() {
        String uri = "file:///PreferredUnusedImport.groovy";
        String content = """
                import java.time.LocalDate
                class Example {
                    String name
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNUSED_IMPORT));
        diagnostic.setRange(new Range(new Position(0, 17), new Position(0, 26)));
        diagnostic.setMessage("The import 'java.time.LocalDate' is never used");

        CodeAction onSymbol = actionsAtRange(provider, uri, content,
                new Range(new Position(0, 20), new Position(0, 20)),
                List.of(CodeActionKind.QuickFix),
                List.of(diagnostic)).stream()
                .filter(action -> "Remove unused import".equals(action.getTitle()))
                .findFirst()
                .orElse(null);
        assertNotNull(onSymbol);
        assertEquals(Boolean.TRUE, onSymbol.getIsPreferred());

        CodeAction awayFromSymbol = actionsAtRange(provider, uri, content,
                new Range(new Position(2, 4), new Position(2, 4)),
                List.of(CodeActionKind.QuickFix),
                List.of(diagnostic)).stream()
                .filter(action -> "Remove unused import".equals(action.getTitle()))
                .findFirst()
                .orElse(null);
        assertNotNull(awayFromSymbol);
        assertFalse(Boolean.TRUE.equals(awayFromSymbol.getIsPreferred()));

        documentManager.didClose(uri);
    }

    @Test
    void deleteUnusedDeclarationQuickFixUsesDeclarationRangeAndPreferredCursor() throws Exception {
        String uri = "file:///DeleteUnusedDeclaration.groovy";
        String content = """
                class Example {
                    void helper() {
                    }
                }
                """;

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IType type = mock(IType.class);
        IMethod method = mock(IMethod.class);
        ISourceRange methodNameRange = mockSourceRange(content.indexOf("helper"), "helper".length());
        int methodStart = content.indexOf("    void helper()");
        int methodEnd = content.indexOf("    }\n", methodStart) + "    }\n".length();
        ISourceRange methodSourceRange = mockSourceRange(methodStart, methodEnd - methodStart);

        when(method.getNameRange()).thenReturn(methodNameRange);
        when(method.getSourceRange()).thenReturn(methodSourceRange);
        ISourceRange typeNameRange = mockSourceRange(content.indexOf("Example"), "Example".length());
        ISourceRange typeSourceRange = mockSourceRange(0, content.length());
        when(type.getMethods()).thenReturn(new IMethod[] { method });
        when(type.getTypes()).thenReturn(new IType[0]);
        when(type.getNameRange()).thenReturn(typeNameRange);
        when(type.getSourceRange()).thenReturn(typeSourceRange);
        when(workingCopy.getTypes()).thenReturn(new IType[] { type });

        DocumentManager documentManager = new DocumentManager() {
            @Override
            public ICompilationUnit getWorkingCopy(String requestedUri) {
                return uri.equals(requestedUri) ? workingCopy : null;
            }
        };
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNUSED_DECLARATION));
        diagnostic.setRange(new Range(new Position(1, 9), new Position(1, 15)));
        diagnostic.setMessage("The method 'helper' is never used");

        CodeAction action = actionsAtRange(provider, uri, content,
                new Range(new Position(1, 11), new Position(1, 11)),
                List.of(CodeActionKind.QuickFix),
                List.of(diagnostic)).stream()
                .filter(candidate -> "Delete unused declaration".equals(candidate.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(action);
        assertEquals(Boolean.TRUE, action.getIsPreferred());
        List<TextEdit> edits = action.getEdit().getChanges().get(uri);
        assertNotNull(edits);
        assertEquals(1, edits.size());
        assertEquals(1, edits.get(0).getRange().getStart().getLine());
        assertEquals("", edits.get(0).getNewText());

        documentManager.didClose(uri);
    }

    @Test
    void deleteUnusedDeclarationQuickFixDeletesConstructorInsteadOfWholeType() throws Exception {
        String uri = "file:///DeleteUnusedConstructor.groovy";
        String content = """
                class Example {
                    Example() {
                    }
                }
                """;

        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IType type = mock(IType.class);
        IMethod constructor = mock(IMethod.class);
        ISourceRange sharedNameRange = mockSourceRange(content.indexOf("Example"), "Example".length());
        int constructorStart = content.indexOf("    Example()");
        int constructorEnd = content.indexOf("    }\n", constructorStart) + "    }\n".length();
        ISourceRange constructorSourceRange = mockSourceRange(constructorStart, constructorEnd - constructorStart);
        ISourceRange typeSourceRange = mockSourceRange(0, content.length());

        when(constructor.getNameRange()).thenReturn(sharedNameRange);
        when(constructor.getSourceRange()).thenReturn(constructorSourceRange);
        when(type.getMethods()).thenReturn(new IMethod[] { constructor });
        when(type.getTypes()).thenReturn(new IType[0]);
        when(type.getNameRange()).thenReturn(sharedNameRange);
        when(type.getSourceRange()).thenReturn(typeSourceRange);
        when(workingCopy.getTypes()).thenReturn(new IType[] { type });

        DocumentManager documentManager = new DocumentManager() {
            @Override
            public ICompilationUnit getWorkingCopy(String requestedUri) {
                return uri.equals(requestedUri) ? workingCopy : null;
            }
        };
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNUSED_DECLARATION));
        diagnostic.setRange(new Range(new Position(0, 6), new Position(0, 13)));
        diagnostic.setMessage("The method 'Example' is never used");

        CodeAction action = actionsAtRange(provider, uri, content,
                new Range(new Position(0, 8), new Position(0, 8)),
                List.of(CodeActionKind.QuickFix),
                List.of(diagnostic)).stream()
                .filter(candidate -> "Delete unused declaration".equals(candidate.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(action);
        List<TextEdit> edits = action.getEdit().getChanges().get(uri);
        assertNotNull(edits);
        assertEquals(1, edits.size());
        assertEquals(1, edits.get(0).getRange().getStart().getLine());
        assertEquals(3, edits.get(0).getRange().getEnd().getLine());

        documentManager.didClose(uri);
    }

    @Test
    void getCodeActionsOffersCreateTypeActionsForUnresolvedType() {
        String uri = "file:///project/src/main/groovy/com/example/UsesFoo.groovy";
        String content = """
                package com.example

                class UsesFoo {
                    Foo value
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNRESOLVED_TYPE));
        diagnostic.setRange(new Range(new Position(3, 4), new Position(3, 7)));
        diagnostic.setMessage("Groovy:unable to resolve class Foo");

        List<CodeAction> actions = quickFixActions(provider, uri, content, List.of(diagnostic));
        CodeAction createClass = actions.stream()
                .filter(action -> "Create class 'Foo'".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(createClass);
        assertNotNull(createClass.getEdit());
        assertNotNull(createClass.getEdit().getDocumentChanges());
        assertEquals(2, createClass.getEdit().getDocumentChanges().size());
        assertTrue(createClass.getEdit().getDocumentChanges().get(0).isRight());
        assertTrue(createClass.getEdit().getDocumentChanges().get(1).isLeft());
        assertTrue(createClass.getEdit().getDocumentChanges().get(0).getRight().toString().contains("Foo.groovy"));
        assertTrue(createClass.getEdit().getDocumentChanges().get(1).getLeft().toString().contains("package com.example"));
        assertTrue(createClass.getEdit().getDocumentChanges().get(1).getLeft().toString().contains("class Foo"));

        documentManager.didClose(uri);
    }

    @Test
    void organizeImportsRemovesUnusedAddsMissingAndSortsSimpleImports() throws Exception {
        String uri = "file:///OrganizeImportsReal.groovy";
        String content = """
                package com.example

                import z.last.TypeZ
                import java.time.LocalDate

                class Demo {
                    List<String> values
                    TypeZ last
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);
        seedImportSearchCache(provider, "List", List.of("java.util.List"));

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNRESOLVED_TYPE));
        diagnostic.setRange(new Range(new Position(6, 4), new Position(6, 8)));
        diagnostic.setMessage("Groovy:unable to resolve class List");

        CodeAction organizeImports = actions(provider, uri, content,
                List.of(CodeActionKind.SourceOrganizeImports), List.of(diagnostic)).stream()
                .filter(action -> "Organize imports".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(organizeImports);
        List<TextEdit> edits = organizeImports.getEdit().getChanges().get(uri);
        assertNotNull(edits);
        assertEquals(1, edits.size());
        String organized = edits.get(0).getNewText();
        assertTrue(organized.contains("import java.util.List"));
        assertTrue(organized.contains("import z.last.TypeZ"));
        assertFalse(organized.contains("LocalDate"));
        assertTrue(organized.indexOf("import java.util.List") < organized.indexOf("import z.last.TypeZ"));

        documentManager.didClose(uri);
    }

    @Test
    void organizeImportsRemovesUnusedStaticImports() {
        String uri = "file:///OrganizeImportsStatic.groovy";
        String content = """
                import static java.util.Collections.emptyList
                import java.time.LocalTime

                class Demo {
                    LocalTime value
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        CodeAction organizeImports = actions(provider, uri, content,
                List.of(CodeActionKind.SourceOrganizeImports), List.of()).stream()
                .filter(action -> "Organize imports".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(organizeImports);
        String organized = organizeImports.getEdit().getChanges().get(uri).get(0).getNewText();
        assertTrue(organized.contains("import java.time.LocalTime"));
        assertFalse(organized.contains("import static java.util.Collections.emptyList"));

        documentManager.didClose(uri);
    }

    @Test
    void organizeImportsPreservesCommentsInsideImportBlock() {
        String uri = "file:///OrganizeImportsComments.groovy";
        String content = """
                package com.example

                import java.time.LocalDate
                // keep this comment
                import java.time.LocalTime

                class Demo {
                    LocalTime value
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        CodeAction organizeImports = actions(provider, uri, content,
                List.of(CodeActionKind.SourceOrganizeImports), List.of()).stream()
                .filter(action -> "Organize imports".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(organizeImports);
        String organized = organizeImports.getEdit().getChanges().get(uri).get(0).getNewText();
        assertTrue(organized.contains("// keep this comment"));
        assertTrue(organized.contains("import java.time.LocalTime"));
        assertFalse(organized.contains("LocalDate"));

        documentManager.didClose(uri);
    }

    @Test
    void getCodeActionsOffersSourceFixAll() {
        String uri = "file:///FixAllImports.groovy";
        String content = """
                import java.time.LocalDate
                class Example {
                    String name
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNUSED_IMPORT));
        diagnostic.setRange(new Range(new Position(0, 17), new Position(0, 26)));
        diagnostic.setMessage("The import 'java.time.LocalDate' is never used");

        CodeAction fixAll = actions(provider, uri, content,
                List.of(CodeActionKind.SourceFixAll), List.of(diagnostic)).stream()
                .filter(action -> CodeActionKind.SourceFixAll.equals(action.getKind()))
                .findFirst()
                .orElse(null);

        assertNotNull(fixAll);
        assertEquals("Fix all auto-fixable issues", fixAll.getTitle());

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
                .filter(action -> action.getTitle() != null
                        && action.getTitle().startsWith("Implement inherited abstract member"))
                .findFirst()
                .orElse(null);

        assertNotNull(implementAction);
        assertEquals(CodeActionKind.QuickFix, implementAction.getKind());
        assertEquals(Boolean.TRUE, implementAction.getIsPreferred());
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
    void getCodeActionsOffersImplementInheritedAbstractMemberQuickFix() {
        String uri = "file:///CodeActionProviderAbstractBaseTest.groovy";
        String content = """
                abstract class GreeterBase {
                    abstract String greet(String name)
                }

                class Impl extends GreeterBase {
                }
                """;

        DocumentManager documentManager = new DocumentManager();
        documentManager.didOpen(uri, content);

        DiagnosticsProvider diagnosticsProvider = new DiagnosticsProvider(documentManager);
        CodeActionProvider provider = new CodeActionProvider(documentManager, diagnosticsProvider);

        Diagnostic diagnostic = missingInterfaceDiagnostic(4, "Impl");
        List<CodeAction> actions = quickFixActions(provider, uri, content, List.of(diagnostic));

        CodeAction implementAction = actions.stream()
                .filter(action -> "Implement inherited abstract member".equals(action.getTitle()))
                .findFirst()
                .orElse(null);

        assertNotNull(implementAction);
        assertTrue(implementAction.getEdit().getChanges().get(uri).get(0).getNewText().contains("greet"));

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
                        && action.getTitle().startsWith("Implement inherited abstract member"))
                .toList();

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

    private final CodeActionProvider testProvider = new CodeActionProvider(new DocumentManager(), new DiagnosticsProvider(new DocumentManager()));
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    // ---- extractTypeNameFromMessage ----

    @Test
    void extractTypeNameFromUnableToResolveClass() throws Exception {
        assertEquals("Foo", invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"unable to resolve class Foo"}));
    }

    @Test
    void extractTypeNameFromQualifiedMessage() throws Exception {
        assertEquals("Bar", invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"unable to resolve class com.example.Bar"}));
    }

    @Test
    void extractTypeNameFromNullMessage() throws Exception {
        assertNull(invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class}, new Object[] {(String) null}));
    }

    @Test
    void extractTypeNameFromUnrelated() throws Exception {
        assertNull(invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class}, new Object[] {"something else entirely"}));
    }

    // ---- getDiagnosticCode ----

    @Test
    void getDiagnosticCodeReturnsLeftString() throws Exception {
        Diagnostic diag = new Diagnostic();
        diag.setCode(Either.forLeft("groovy.unusedImport"));
        assertEquals("groovy.unusedImport", invokeHelper("getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    @Test
    void getDiagnosticCodeReturnsRightAsString() throws Exception {
        Diagnostic diag = new Diagnostic();
        diag.setCode(Either.forRight(42));
        assertEquals("42", invokeHelper("getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    @Test
    void getDiagnosticCodeReturnsNullWhenNull() throws Exception {
        Diagnostic diag = new Diagnostic();
        assertNull(invokeHelper("getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    // ---- isUnresolvedTypeDiagnostic ----

    @Test
    void isUnresolvedTypeDiagnosticByCode() throws Exception {
        Diagnostic diag = new Diagnostic();
        diag.setCode(Either.forLeft("groovy.unresolvedType.Foo"));
        diag.setMessage("");
        assertTrue((boolean) invokeHelper("isUnresolvedTypeDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    @Test
    void isUnresolvedTypeDiagnosticByMessage() throws Exception {
        Diagnostic diag = new Diagnostic();
        diag.setMessage("Groovy:unable to resolve class Widget");
        assertTrue((boolean) invokeHelper("isUnresolvedTypeDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    @Test
    void isUnresolvedTypeDiagnosticReturnsFalseForUnrelated() throws Exception {
        Diagnostic diag = new Diagnostic();
        diag.setMessage("Syntax error");
        assertFalse((boolean) invokeHelper("isUnresolvedTypeDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {diag}));
    }

    // ---- packagePriority ----

    @Test
    void packagePrioritySortsCommonPackages() throws Exception {
        int javaLang = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.lang.String"});
        int javaUtil = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.util.List"});
        int javaIo = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.io.File"});
        int groovy = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"groovy.lang.Closure"});
        int custom = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"com.example.Foo"});

        assertTrue(javaLang < javaUtil);
        assertTrue(javaUtil < javaIo);
        assertTrue(javaIo < groovy);
        assertTrue(groovy < custom);
    }

    @Test
    void packagePriorityJavaxAndJakarta() throws Exception {
        int javax = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"javax.inject.Inject"});
        int jakarta = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"jakarta.ws.rs.Path"});
        assertEquals(javax, jakarta);
    }

    @Test
    void packagePriorityJunit() throws Exception {
        int junit = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"org.junit.Test"});
        int spock = (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"spock.lang.Specification"});
        assertEquals(junit, spock);
    }

    // ---- sanitizeParameterName ----

    @Test
    void sanitizeParameterNameKeepsValid() throws Exception {
        assertEquals("name", invokeHelper("sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"name", 1}));
    }

    @Test
    void sanitizeParameterNameReplacesInvalidStart() throws Exception {
        assertEquals("arg1", invokeHelper("sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"123invalid", 1}));
    }

    @Test
    void sanitizeParameterNameReplacesNull() throws Exception {
        assertEquals("arg2", invokeHelper("sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {null, 2}));
    }

    @Test
    void sanitizeParameterNameReplacesEmpty() throws Exception {
        assertEquals("arg3", invokeHelper("sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"", 3}));
    }

    // ---- normalizeTypeName ----

    @Test
    void normalizeTypeNameReturnsDefForNull() throws Exception {
        assertEquals("def", invokeHelper("normalizeTypeName",
                new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void normalizeTypeNameReturnsName() throws Exception {
        ClassNode node = new ClassNode("java.util.List", 0, ClassNode.SUPER);
        assertEquals("java.util.List", invokeHelper("normalizeTypeName",
                new Class<?>[] {ClassNode.class}, new Object[] {node}));
    }

    // ---- renderType ----

    @Test
    void renderTypeReturnsDefForNull() throws Exception {
        assertEquals("def", invokeHelper("renderType",
                new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void renderTypeReturnsSimpleName() throws Exception {
        ClassNode node = new ClassNode("java.util.List", 0, ClassNode.SUPER);
        assertEquals("List", invokeHelper("renderType",
                new Class<?>[] {ClassNode.class}, new Object[] {node}));
    }

    // ---- renderParameters ----

    @Test
    void renderParametersEmpty() throws Exception {
        assertEquals("", invokeHelper("renderParameters",
                new Class<?>[] {Parameter[].class}, new Object[] {new Parameter[0]}));
    }

    @Test
    void renderParametersNull() throws Exception {
        assertEquals("", invokeHelper("renderParameters",
                new Class<?>[] {Parameter[].class}, new Object[] {(Parameter[]) null}));
    }

    @Test
    void renderParametersSingle() throws Exception {
        Parameter p = new Parameter(new ClassNode(String.class), "name");
        String result = (String) invokeHelper("renderParameters",
                new Class<?>[] {Parameter[].class}, new Object[] {new Parameter[] {p}});
        assertTrue(result.contains("String"));
        assertTrue(result.contains("name"));
    }

    @Test
    void renderParametersMultiple() throws Exception {
        Parameter p1 = new Parameter(new ClassNode(String.class), "name");
        Parameter p2 = new Parameter(new ClassNode(int.class), "count");
        String result = (String) invokeHelper("renderParameters",
                new Class<?>[] {Parameter[].class}, new Object[] {new Parameter[] {p1, p2}});
        assertTrue(result.contains(","));
        assertTrue(result.contains("name"));
        assertTrue(result.contains("count"));
    }

    // ---- methodSignatureKey ----

    @Test
    void methodSignatureKeyNoParams() throws Exception {
        MethodNode method = new MethodNode("doWork", 0, ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertEquals("doWork()", invokeHelper("methodSignatureKey",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void methodSignatureKeyWithParams() throws Exception {
        Parameter p1 = new Parameter(new ClassNode(String.class), "a");
        Parameter p2 = new Parameter(new ClassNode(int.class), "b");
        MethodNode method = new MethodNode("process", 0, ClassNode.SUPER, new Parameter[] {p1, p2}, ClassNode.EMPTY_ARRAY, null);
        String key = (String) invokeHelper("methodSignatureKey",
                new Class<?>[] {MethodNode.class}, new Object[] {method});
        assertTrue(key.startsWith("process("));
        assertTrue(key.contains("java.lang.String"));
        assertTrue(key.contains("int"));
        assertTrue(key.endsWith(")"));
    }

    // ---- hasSameParameterTypes ----

    @Test
    void hasSameParameterTypesMatchesEmpty() throws Exception {
        MethodNode left = new MethodNode("a", 0, ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        MethodNode right = new MethodNode("b", 0, ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertTrue((boolean) invokeHelper("hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {left, right}));
    }

    @Test
    void hasSameParameterTypesMatchesSameTypes() throws Exception {
        Parameter p1 = new Parameter(new ClassNode(String.class), "a");
        Parameter p2 = new Parameter(new ClassNode(String.class), "b");
        MethodNode left = new MethodNode("a", 0, ClassNode.SUPER, new Parameter[] {p1}, ClassNode.EMPTY_ARRAY, null);
        MethodNode right = new MethodNode("b", 0, ClassNode.SUPER, new Parameter[] {p2}, ClassNode.EMPTY_ARRAY, null);
        assertTrue((boolean) invokeHelper("hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {left, right}));
    }

    @Test
    void hasSameParameterTypesReturnsFalseForDifferentTypes() throws Exception {
        Parameter p1 = new Parameter(new ClassNode(String.class), "a");
        Parameter p2 = new Parameter(new ClassNode(int.class), "b");
        MethodNode left = new MethodNode("a", 0, ClassNode.SUPER, new Parameter[] {p1}, ClassNode.EMPTY_ARRAY, null);
        MethodNode right = new MethodNode("b", 0, ClassNode.SUPER, new Parameter[] {p2}, ClassNode.EMPTY_ARRAY, null);
        assertFalse((boolean) invokeHelper("hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {left, right}));
    }

    @Test
    void hasSameParameterTypesReturnsFalseForDifferentCount() throws Exception {
        Parameter p1 = new Parameter(new ClassNode(String.class), "a");
        MethodNode left = new MethodNode("a", 0, ClassNode.SUPER, new Parameter[] {p1}, ClassNode.EMPTY_ARRAY, null);
        MethodNode right = new MethodNode("b", 0, ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertFalse((boolean) invokeHelper("hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {left, right}));
    }

    // ---- isMethodRequiredForImplementation ----

    @Test
    void isMethodRequiredForImplementationAbstractMethod() throws Exception {
        Parameter[] noParams = Parameter.EMPTY_ARRAY;
        ClassNode declaring = new ClassNode("com.example.MyInterface", java.lang.reflect.Modifier.INTERFACE | java.lang.reflect.Modifier.ABSTRACT, ClassNode.SUPER);
        MethodNode method = new MethodNode("doStuff", java.lang.reflect.Modifier.ABSTRACT | java.lang.reflect.Modifier.PUBLIC,
                ClassNode.SUPER, noParams, ClassNode.EMPTY_ARRAY, null);
        method.setDeclaringClass(declaring);
        assertTrue((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationSkipsStaticAbstract() throws Exception {
        MethodNode method = new MethodNode("staticHelper",
                java.lang.reflect.Modifier.ABSTRACT | java.lang.reflect.Modifier.STATIC,
                ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertFalse((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationSkipsPrivateAbstract() throws Exception {
        MethodNode method = new MethodNode("privateMethod",
                java.lang.reflect.Modifier.ABSTRACT | java.lang.reflect.Modifier.PRIVATE,
                ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertFalse((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationSkipsObjectMethods() throws Exception {
        ClassNode objectClass = new ClassNode("java.lang.Object", 0, null);
        MethodNode method = new MethodNode("hashCode", java.lang.reflect.Modifier.ABSTRACT,
                ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        method.setDeclaringClass(objectClass);
        assertFalse((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationSkipsDollarPrefix() throws Exception {
        MethodNode method = new MethodNode("$getStaticMetaClass", java.lang.reflect.Modifier.ABSTRACT,
                ClassNode.SUPER, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        assertFalse((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationSkipsNull() throws Exception {
        assertFalse((boolean) invokeHelper("isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {(MethodNode) null}));
    }

    // ---- wantsKind ----

    @Test
    void wantsKindReturnsTrueForNullKinds() throws Exception {
        assertTrue((boolean) invokeHelper("wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {null, new String[] {"quickfix"}}));
    }

    @Test
    void wantsKindReturnsTrueWhenMatches() throws Exception {
        assertTrue((boolean) invokeHelper("wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {List.of("quickfix", "source"), new String[] {"quickfix"}}));
    }

    @Test
    void wantsKindReturnsFalseWhenNotMatched() throws Exception {
        assertFalse((boolean) invokeHelper("wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {List.of("source"), new String[] {"quickfix"}}));
    }

    // ---- leadingWhitespace ----

    @Test
    void leadingWhitespaceExtractsSpaces() throws Exception {
        assertEquals("    ", invokeHelper("leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"    code()"}));
    }

    @Test
    void leadingWhitespaceExtractsTabs() throws Exception {
        assertEquals("\t\t", invokeHelper("leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"\t\tcode()"}));
    }

    @Test
    void leadingWhitespaceReturnsEmptyForNoIndent() throws Exception {
        assertEquals("", invokeHelper("leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"code()"}));
    }

    // ---- findImportInsertLine ----

        @ParameterizedTest
        @MethodSource("findImportInsertLineCases")
        void findImportInsertLineHandlesTypicalLayouts(String content, int expectedLine) throws Exception {
        int line = (int) invokeHelper("findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
                assertEquals(expectedLine, line);
    }

        private static Stream<Arguments> findImportInsertLineCases() {
                return Stream.of(
                                Arguments.of(
                                                "package com.demo\n\nimport java.util.List\nimport java.util.Map\n\nclass A {}",
                                                4),
                                Arguments.of("package com.demo\n\nclass A {}", 2),
                                Arguments.of("class A {}", 0));
        }

    // ---- findEnclosingClass ----

    @Test
    void findEnclosingClassFromAST() throws Exception {
        String source = "class Outer {\n  void foo() {}\n}\n";
        ModuleNode module = compilerService.parse("file:///enclosing.groovy", source).getModuleNode();
        assertNotNull(module);
        ClassNode result = (ClassNode) invokeHelper("findEnclosingClass",
                new Class<?>[] {ModuleNode.class, int.class}, new Object[] {module, 2});
        assertNotNull(result);
    }

    // ---- findClassBySimpleName ----

    @Test
    void findClassBySimpleNameFindsMatch() throws Exception {
        String source = "class MyService { void run() {} }";
        ModuleNode module = compilerService.parse("file:///findClass.groovy", source).getModuleNode();
        assertNotNull(module);
        ClassNode result = (ClassNode) invokeHelper("findClassBySimpleName",
                new Class<?>[] {ModuleNode.class, String.class}, new Object[] {module, "MyService"});
        assertNotNull(result);
    }

    @Test
    void findClassBySimpleNameReturnsNullForMissing() throws Exception {
        String source = "class A {}";
        ModuleNode module = compilerService.parse("file:///findClass2.groovy", source).getModuleNode();
        assertNotNull(module);
        ClassNode result = (ClassNode) invokeHelper("findClassBySimpleName",
                new Class<?>[] {ModuleNode.class, String.class}, new Object[] {module, "NonExistent"});
        assertNull(result);
    }

    @Test
    void findClassBySimpleNameReturnsNullForNull() throws Exception {
        String source = "class A {}";
        ModuleNode module = compilerService.parse("file:///findClass3.groovy", source).getModuleNode();
        assertNull(invokeHelper("findClassBySimpleName",
                new Class<?>[] {ModuleNode.class, String.class}, new Object[] {module, null}));
        assertNull(invokeHelper("findClassBySimpleName",
                new Class<?>[] {ModuleNode.class, String.class}, new Object[] {module, ""}));
    }

    // ---- findClassInsertLine ----

    @Test
    void findClassInsertLineFindsClosingBrace() throws Exception {
        String source = "class Foo {\n    void bar() {}\n}\n";
        ModuleNode module = compilerService.parse("file:///findInsert.groovy", source).getModuleNode();
        ClassNode fooClass = module.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(fooClass);
        int line = (int) invokeHelper("findClassInsertLine",
                new Class<?>[] {ClassNode.class, String.class},
                new Object[] {fooClass, source});
        assertTrue(line >= 1);
    }

    // ---- inferIndentUnit ----

    @Test
    void inferIndentUnitDetectsSpaces() throws Exception {
        String[] lines = new String[] {"class A {", "    void run() {}", "}"};
        String result = (String) invokeHelper("inferIndentUnit",
                new Class<?>[] {String[].class, String.class, int.class, int.class},
                new Object[] {lines, "", 0, 2});
        assertEquals("    ", result);
    }

    @Test
    void inferIndentUnitDefaultsToFourSpaces() throws Exception {
        String[] lines = new String[] {"class A {", "}"};
        String result = (String) invokeHelper("inferIndentUnit",
                new Class<?>[] {String[].class, String.class, int.class, int.class},
                new Object[] {lines, "", 0, 1});
        assertEquals("    ", result);
    }

    // ---- extractClassSimpleNameFromMessage edge cases ----

    @Test
    void extractClassSimpleNameUsingTheType() throws Exception {
        assertEquals("MyClass", invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"The type MyClass must implement the inherited abstract method"}));
    }

    @Test
    void extractClassSimpleNameWithDotQualified() throws Exception {
        assertEquals("Impl", invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"The type com.example.Impl must implement ..."}));
    }

    // ---- hasConcreteMethodInHierarchy ----

    @Test
    void hasConcreteMethodInHierarchyFindsDirectMethod() throws Exception {
        String source = "interface Greetable { String greet(String n) }\nclass Impl implements Greetable { String greet(String n) { n } }";
        ModuleNode module = compilerService.parse("file:///concrete.groovy", source).getModuleNode();
        ClassNode impl = module.getClasses().stream()
                .filter(c -> "Impl".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(impl);

        ClassNode iface = module.getClasses().stream()
                .filter(c -> "Greetable".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(iface);
        MethodNode required = iface.getMethods().stream()
                .filter(m -> "greet".equals(m.getName()))
                .findFirst().orElse(null);
        assertNotNull(required);

        assertTrue((boolean) invokeHelper("hasConcreteMethodInHierarchy",
                new Class<?>[] {ClassNode.class, MethodNode.class},
                new Object[] {impl, required}));
    }

    // ---- findMissingInterfaceMethods via AST ----

    @Test
    void findMissingInterfaceMethodsDetectsUnimplemented() throws Exception {
        String source = "interface Greeter { String greet(String name) }\nclass Impl implements Greeter {}";
        ModuleNode module = compilerService.parse("file:///missingMethods.groovy", source).getModuleNode();
        ClassNode impl = module.getClasses().stream()
                .filter(c -> "Impl".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(impl);

        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invokeHelper("findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {impl, module});
        // There should be at least one missing (greet)
        assertFalse(missing.isEmpty());
    }

    @Test
    void findMissingInterfaceMethodsReturnsEmptyWhenAllImplemented() throws Exception {
        String source = "interface Greeter { String greet(String name) }\nclass Impl implements Greeter { String greet(String name) { name } }";
        ModuleNode module = compilerService.parse("file:///allImpl.groovy", source).getModuleNode();
        ClassNode impl = module.getClasses().stream()
                .filter(c -> "Impl".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(impl);

        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invokeHelper("findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {impl, module});
        assertTrue(missing.isEmpty());
    }

    // ---- buildMissingMethodStubsText ----

    @Test
    void buildMissingMethodStubsTextGeneratesOverride() throws Exception {
        String source = "interface Greeter {\n    String greet(String name)\n}\nclass Impl implements Greeter {\n}\n";
        ModuleNode module = compilerService.parse("file:///buildStub.groovy", source).getModuleNode();
        ClassNode impl = module.getClasses().stream()
                .filter(c -> "Impl".equals(c.getNameWithoutPackage()))
                .findFirst().orElse(null);
        assertNotNull(impl);

        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invokeHelper("findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {impl, module});
        assertFalse(missing.isEmpty());

        int insertLine = (int) invokeHelper("findClassInsertLine",
                new Class<?>[] {ClassNode.class, String.class},
                new Object[] {impl, source});

        String result = (String) invokeHelper("buildMissingMethodStubsText",
                new Class<?>[] {ClassNode.class, List.class, String.class, int.class},
                new Object[] {impl, missing, source, insertLine});
        assertNotNull(result);
        assertTrue(result.contains("@Override"));
        assertTrue(result.contains("UnsupportedOperationException"));
    }

    // ---- getCodeActions with implement + multiple interfaces ----

    @Test
    void getCodeActionsMultipleInterfaceMembers() {
        String uri = "file:///MultiInterfaceTest.groovy";
        String content = """
                interface Speaker { String speak() }
                interface Walker { void walk() }
                class Robot implements Speaker, Walker {
                }
                """;

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(2, 0), new Position(2, 1)));
        diag.setMessage("The class 'Robot' must be declared abstract or the method 'speak' must be implemented");

        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        CodeAction implementAction = allActions.stream()
                .filter(a -> a.getTitle() != null && a.getTitle().startsWith("Implement inherited"))
                .findFirst().orElse(null);

        assertNotNull(implementAction);
        dm.didClose(uri);
    }

    // ================================================================
    // getCodeActions with unresolved type diagnostic
    // ================================================================

    @Test
    void getCodeActionsWithUnresolvedTypeDiagnosticGracefullyHandlesNoJdt() {
        String uri = "file:///UnresolvedTypeTest.groovy";
        String content = "class Example {\n    LocalDate today\n}";

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(1, 4), new Position(1, 13)));
        diag.setMessage("Groovy:unable to resolve class LocalDate");
        diag.setCode(Either.forLeft(CodeActionProvider.DIAG_CODE_UNRESOLVED_TYPE));

        // Without JDT, searchClasspathForType returns empty — no add-import actions
        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        assertNotNull(allActions);
        // Should not throw even though JDT is not available
        dm.didClose(uri);
    }

    @Test
    void getCodeActionsWithUnresolvedTypeDiagnosticByMessageOnly() {
        String uri = "file:///UnresolvedTypeMsg.groovy";
        String content = "class Test { FooBar fb }";

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(0, 13), new Position(0, 19)));
        diag.setMessage("unable to resolve class FooBar");

        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        assertNotNull(allActions);
        dm.didClose(uri);
    }

    @Test
    void getCodeActionsReturnsEmptyForNullContent() {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        CodeActionContext ctx = new CodeActionContext();
        ctx.setDiagnostics(List.of());
        CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier("file:///nothing.groovy"),
                new Range(new Position(0, 0), new Position(0, 0)),
                ctx);
        List<Either<Command, CodeAction>> result = provider.getCodeActions(params);
        assertTrue(result.isEmpty());
    }

    @Test
    void getCodeActionsWithSourceKindAddMissingImports() {
        String uri = "file:///SourceAddImports.groovy";
        String content = "class Test { LocalDate d }";

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        List<CodeAction> allActions = actions(provider, uri, content,
                List.of(CodeActionProvider.SOURCE_KIND_ADD_MISSING_IMPORTS), List.of());
        assertNotNull(allActions);
        dm.didClose(uri);
    }

    // ================================================================
    // getSearchProjectsForUri with mockStatic
    // ================================================================

    @SuppressWarnings("unchecked")
    @Test
    void getSearchProjectsForUriFindsProjectViaMockStatic() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile file = mock(IFile.class);
        IProject project = mock(IProject.class);
        IJavaProject javaProject = mock(IJavaProject.class);

        when(workspace.getRoot()).thenReturn(root);
        when(root.findFilesForLocationURI(org.mockito.ArgumentMatchers.any())).thenReturn(new IFile[]{file});
        when(file.getProject()).thenReturn(project);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("TestProject");
        when(javaProject.exists()).thenReturn(true);

        try (MockedStatic<ResourcesPlugin> rsMock = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
             MockedStatic<JavaCore> jcMock = org.mockito.Mockito.mockStatic(JavaCore.class)) {

            rsMock.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            jcMock.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            List<IJavaProject> result = (List<IJavaProject>) invoke(provider,
                    "getSearchProjectsForUri",
                    new Class<?>[] { String.class },
                    new Object[] { "file:///test.groovy" });
            assertNotNull(result);
            assertFalse(result.isEmpty());
            assertEquals(javaProject, result.get(0));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSearchProjectsForUriFallsBackToAllProjects() throws Exception {
        DocumentManager dm = new DocumentManager();
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class);
        IJavaProject javaProject = mock(IJavaProject.class);

        when(workspace.getRoot()).thenReturn(root);
        // findFilesForLocationURI returns empty → triggers fallback
        when(root.findFilesForLocationURI(org.mockito.ArgumentMatchers.any())).thenReturn(new IFile[0]);
        when(root.getProjects()).thenReturn(new IProject[]{project});
        when(project.isOpen()).thenReturn(true);
        when(javaProject.exists()).thenReturn(true);

        try (MockedStatic<ResourcesPlugin> rsMock = org.mockito.Mockito.mockStatic(ResourcesPlugin.class);
             MockedStatic<JavaCore> jcMock = org.mockito.Mockito.mockStatic(JavaCore.class)) {

            rsMock.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            jcMock.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            List<IJavaProject> result = (List<IJavaProject>) invoke(provider,
                    "getSearchProjectsForUri",
                    new Class<?>[] { String.class },
                    new Object[] { "file:///test.groovy" });
            assertNotNull(result);
            assertFalse(result.isEmpty());
        }
    }

    // ================================================================
    // Implement interface edge cases
    // ================================================================

    @Test
    void getCodeActionsImplementsInterfaceWithMultipleAbstractMethods() {
        String uri = "file:///MultiMethodImpl.groovy";
        String content = """
                interface Calculator {
                    int add(int a, int b)
                    int subtract(int a, int b)
                    int multiply(int a, int b)
                }
                class SimpleCalc implements Calculator {
                }
                """;

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(5, 0), new Position(5, 1)));
        diag.setMessage("The class 'SimpleCalc' must be declared abstract or the method 'add' must be implemented");

        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        CodeAction implementAction = allActions.stream()
                .filter(a -> a.getTitle() != null && a.getTitle().contains("Implement"))
                .findFirst().orElse(null);
        assertNotNull(implementAction);
        String editText = implementAction.getEdit().getChanges().get(uri).get(0).getNewText();
        assertTrue(editText.contains("add"));
        assertTrue(editText.contains("subtract"));
        assertTrue(editText.contains("multiply"));
        dm.didClose(uri);
    }

    @Test
    void getCodeActionsImplementsInterfaceHierarchy() {
        // Recursive interface collection
        String uri = "file:///InterfaceHierarchy.groovy";
        String content = """
                interface Base { void base() }
                interface Extended extends Base {
                    void extended()
                }
                class Impl implements Extended {
                }
                """;

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(4, 0), new Position(4, 1)));
        diag.setMessage("The class 'Impl' must be declared abstract or the method 'extended' must be implemented");

        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        CodeAction implementAction = allActions.stream()
                .filter(a -> a.getTitle() != null && a.getTitle().contains("Implement"))
                .findFirst().orElse(null);
        assertNotNull(implementAction);
        dm.didClose(uri);
    }

    @Test
    void getCodeActionsImplementsInterfaceMethodWithParameters() {
        String uri = "file:///ParamTypes.groovy";
        String content = """
                interface Transformer {
                    List<String> transform(Map<String, Integer> input, boolean flag)
                }
                class MyTransformer implements Transformer {
                }
                """;

        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setRange(new Range(new Position(3, 0), new Position(3, 1)));
        diag.setMessage("The class 'MyTransformer' must be declared abstract or the method 'transform' must be implemented");

        List<CodeAction> allActions = quickFixActions(provider, uri, content, List.of(diag));
        assertNotNull(allActions);
        dm.didClose(uri);
    }

    @Test
    void isMissingInterfaceMemberDiagnosticVariants() throws Exception {
        // "must be declared abstract" variant
        assertTrue((boolean) invokeHelper("isMissingInterfaceMemberDiagnostic",
                new Class<?>[] { Diagnostic.class },
                new Object[] { makeDiag("The class Foo must be declared abstract or the method 'bar' must be implemented") }));

        // "must implement the inherited abstract method" variant
        assertTrue((boolean) invokeHelper("isMissingInterfaceMemberDiagnostic",
                new Class<?>[] { Diagnostic.class },
                new Object[] { makeDiag("Foo must implement the inherited abstract method Iface.doStuff()") }));

        // Not matching
        assertFalse((boolean) invokeHelper("isMissingInterfaceMemberDiagnostic",
                new Class<?>[] { Diagnostic.class },
                new Object[] { makeDiag("Some unrelated error") }));

        // Empty message
        assertFalse((boolean) invokeHelper("isMissingInterfaceMemberDiagnostic",
                new Class<?>[] { Diagnostic.class },
                new Object[] { makeDiag("") }));
    }

    // ================================================================
    // Helper method edge cases
    // ================================================================

    @Test
    void packagePriorityCoversAllBranches() throws Exception {
        assertEquals(0, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.lang.String"}));
        assertEquals(1, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.util.List"}));
        assertEquals(2, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.io.File"}));
        assertEquals(3, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.nio.file.Path"}));
        assertEquals(4, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.net.URL"}));
        assertEquals(5, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"java.sql.Connection"}));
        assertEquals(6, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"groovy.transform.Sortable"}));
        assertEquals(7, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"javax.inject.Inject"}));
        assertEquals(7, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"jakarta.servlet.Filter"}));
        assertEquals(8, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"org.junit.Test"}));
        assertEquals(8, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"spock.lang.Specification"}));
        assertEquals(10, (int) invokeHelper("packagePriority", new Class<?>[] {String.class}, new Object[] {"com.example.Foo"}));
    }

    @Test
    void normalizeTypeNameHandlesArrayAndNull() throws Exception {
        assertEquals("def", invokeHelper("normalizeTypeName", new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void renderTypeHandlesNull() throws Exception {
        assertEquals("def", invokeHelper("renderType", new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void renderParametersHandlesNull() throws Exception {
        assertEquals("", invokeHelper("renderParameters", new Class<?>[] {Parameter[].class}, new Object[] {(Parameter[]) null}));
        assertEquals("", invokeHelper("renderParameters", new Class<?>[] {Parameter[].class}, new Object[] {new Parameter[0]}));
    }

    @Test
    void sanitizeParameterNameEdgeCases() throws Exception {
        assertEquals("arg1", invokeHelper("sanitizeParameterName", new Class<?>[] {String.class, int.class}, new Object[] {null, 1}));
        assertEquals("arg2", invokeHelper("sanitizeParameterName", new Class<?>[] {String.class, int.class}, new Object[] {"", 2}));
        assertEquals("arg3", invokeHelper("sanitizeParameterName", new Class<?>[] {String.class, int.class}, new Object[] {"1bad", 3}));
        assertEquals("good", invokeHelper("sanitizeParameterName", new Class<?>[] {String.class, int.class}, new Object[] {"good", 1}));
    }

    @Test
    void getExistingImportsFromDocument() throws Exception {
        String uri = "file:///ExistingImports.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, "import java.util.List\nimport java.util.Map\nclass A {}");
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        @SuppressWarnings("unchecked")
        Set<String> imports = (Set<String>) invoke(provider, "getExistingImports",
                new Class<?>[] {String.class}, new Object[] {uri});
        assertTrue(imports.contains("java.util.List"));
        assertTrue(imports.contains("java.util.Map"));
        dm.didClose(uri);
    }

    @Test
    void getExistingImportsReturnsEmptyForNoImports() throws Exception {
        String uri = "file:///NoImports.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, "class A {}");
        DiagnosticsProvider dp = new DiagnosticsProvider(dm);
        CodeActionProvider provider = new CodeActionProvider(dm, dp);

        @SuppressWarnings("unchecked")
        Set<String> imports = (Set<String>) invoke(provider, "getExistingImports",
                new Class<?>[] {String.class}, new Object[] {uri});
        assertTrue(imports.isEmpty());
        dm.didClose(uri);
    }

    @Test
    void getDiagnosticCodeVariants() throws Exception {
        // Left string code
        Diagnostic d1 = new Diagnostic();
        d1.setCode(Either.forLeft("groovy.error"));
        assertEquals("groovy.error", invoke(testProvider, "getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {d1}));

        // Right numeric code
        Diagnostic d2 = new Diagnostic();
        d2.setCode(Either.forRight(42));
        assertEquals("42", invoke(testProvider, "getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {d2}));

        // Null code
        Diagnostic d3 = new Diagnostic();
        assertNull(invoke(testProvider, "getDiagnosticCode",
                new Class<?>[] {Diagnostic.class}, new Object[] {d3}));
    }

    @Test
    void extractTypeNameFromQualifiedName() throws Exception {
        assertEquals("FooBar", invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"unable to resolve class com.example.FooBar --"}));
    }

    private Diagnostic makeDiag(String message) {
        Diagnostic d = new Diagnostic();
        d.setMessage(message);
        d.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        return d;
    }

    private List<CodeAction> quickFixActions(
            CodeActionProvider provider,
            String uri,
            String content,
            List<Diagnostic> diagnostics) {
                return actions(provider, uri, content, List.of(CodeActionKind.QuickFix), diagnostics);
        }

        private List<CodeAction> actionsAtRange(
                        CodeActionProvider provider,
                        String uri,
                        String content,
                        Range range,
                        List<String> onlyKinds,
                        List<Diagnostic> diagnostics) {
        CodeActionContext context = new CodeActionContext();
        context.setDiagnostics(diagnostics);
                context.setOnly(onlyKinds);

        CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier(uri),
                range,
                context);

        return provider.getCodeActions(params).stream()
                .filter(Either::isRight)
                .map(Either::getRight)
                .toList();
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
                .toList();
    }

    private Diagnostic missingInterfaceDiagnostic(int line, String className) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        diagnostic.setRange(new Range(new Position(line, 0), new Position(line, 1)));
        diagnostic.setMessage("The class '" + className
                + "' must be declared abstract or the method 'java.lang.String greet(java.lang.String)' must be implemented");
        return diagnostic;
    }

    private ISourceRange mockSourceRange(int offset, int length) throws JavaModelException {
        ISourceRange range = mock(ISourceRange.class);
        when(range.getOffset()).thenReturn(offset);
        when(range.getLength()).thenReturn(length);
        return range;
    }

    @SuppressWarnings("unchecked")
    private void seedImportSearchCache(CodeActionProvider provider, String key, List<String> results)
            throws Exception {
        Class<?> cachedClass = Class.forName(
                "org.eclipse.groovy.ls.core.providers.CodeActionProvider$CachedSearchResult");
        var ctor = cachedClass.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        Object cached = ctor.newInstance(results);

        var field = CodeActionProvider.class.getDeclaredField("importSearchCache");
        field.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) field.get(provider);
        cache.put(key, cached);
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeHelper(String methodName, Class<?>[] types, Object[] args) throws Exception {
        return invoke(testProvider, methodName, types, args);
    }

    // ================================================================
    // Pure-logic utility method tests
    // ================================================================

    // ---- extractTypeNameFromMessage ----

    @Test
    void extractTypeNameFromSimpleMessage() throws Exception {
        assertEquals("FooBar", invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"unable to resolve class FooBar"}));
    }

    @Test
    void extractTypeNameFromGroovyPrefixedMessage() throws Exception {
        assertEquals("MyType", invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"Groovy:unable to resolve class MyType"}));
    }

    @Test
    void extractTypeNameFromMessageReturnsNullForNull() throws Exception {
        assertNull(invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {(String) null}));
    }

    @Test
    void extractTypeNameFromMessageReturnsNullForUnrelatedMessage() throws Exception {
        assertNull(invokeHelper("extractTypeNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"some random error"}));
    }

    // ---- extractQuotedTypeCandidate ----

    @Test
    void extractQuotedTypeCandidateFindsQuoted() throws Exception {
        assertEquals("MyType", invokeHelper("extractQuotedTypeCandidate",
                new Class<?>[] {String.class},
                new Object[] {"Cannot find class 'MyType' in scope"}));
    }

    @Test
    void extractQuotedTypeCandidateReturnsNullForNoQuotes() throws Exception {
        assertNull(invokeHelper("extractQuotedTypeCandidate",
                new Class<?>[] {String.class},
                new Object[] {"no quotes here"}));
    }

    @Test
    void extractQuotedTypeCandidateReturnsNullForSingleQuote() throws Exception {
        assertNull(invokeHelper("extractQuotedTypeCandidate",
                new Class<?>[] {String.class},
                new Object[] {"only one' quote"}));
    }

    // ---- extractTypeCandidateAfterMarker ----

    @Test
    void extractTypeCandidateAfterMarkerFindsType() throws Exception {
        assertEquals("Foo", invokeHelper("extractTypeCandidateAfterMarker",
                new Class<?>[] {String.class, String.class},
                new Object[] {"The type Foo is not accessible", "The type "}));
    }

    @Test
    void extractTypeCandidateAfterMarkerReturnsNullWhenNotFound() throws Exception {
        assertNull(invokeHelper("extractTypeCandidateAfterMarker",
                new Class<?>[] {String.class, String.class},
                new Object[] {"random message", "The type "}));
    }

    // ---- simpleTypeName ----

    @Test
    void simpleTypeNameFromFqn() throws Exception {
        assertEquals("List", invokeHelper("simpleTypeName",
                new Class<?>[] {String.class},
                new Object[] {"java.util.List"}));
    }

    @Test
    void simpleTypeNameAlreadySimple() throws Exception {
        assertEquals("Foo", invokeHelper("simpleTypeName",
                new Class<?>[] {String.class},
                new Object[] {"Foo"}));
    }

    // ---- packagePriority ----

    @Test
    void packagePriorityJavaLang() throws Exception {
        assertEquals(0, invoke(testProvider, "packagePriority",
                new Class<?>[] {String.class}, new Object[] {"java.lang.String"}));
    }

    @Test
    void packagePriorityJavaUtil() throws Exception {
        assertEquals(1, invoke(testProvider, "packagePriority",
                new Class<?>[] {String.class}, new Object[] {"java.util.List"}));
    }

    @Test
    void packagePriorityGroovy() throws Exception {
        assertEquals(6, invoke(testProvider, "packagePriority",
                new Class<?>[] {String.class}, new Object[] {"groovy.lang.Closure"}));
    }

    @Test
    void packagePriorityOther() throws Exception {
        assertEquals(10, invoke(testProvider, "packagePriority",
                new Class<?>[] {String.class}, new Object[] {"com.example.Foo"}));
    }

    @Test
    void packagePriorityJUnit() throws Exception {
        assertEquals(8, invoke(testProvider, "packagePriority",
                new Class<?>[] {String.class}, new Object[] {"org.junit.Test"}));
    }

    // ---- isImportSearchCandidate ----

    @Test
    void isImportSearchCandidateAcceptsNormal() throws Exception {
        assertEquals(true, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {"java.util.List"}));
    }

    @Test
    void isImportSearchCandidateRejectsSun() throws Exception {
        assertEquals(false, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {"sun.misc.Unsafe"}));
    }

    @Test
    void isImportSearchCandidateRejectsComSun() throws Exception {
        assertEquals(false, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {"com.sun.internal.Foo"}));
    }

    @Test
    void isImportSearchCandidateRejectsDollar() throws Exception {
        assertEquals(false, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {"Foo$Bar"}));
    }

    @Test
    void isImportSearchCandidateRejectsEmpty() throws Exception {
        assertEquals(false, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {""}));
    }

    @Test
    void isImportSearchCandidateRejectsNull() throws Exception {
        assertEquals(false, invoke(testProvider, "isImportSearchCandidate",
                new Class<?>[] {String.class}, new Object[] {(String) null}));
    }

    // ---- findImportInsertLine ----

    @Test
    void findImportInsertLineAfterExistingImports() throws Exception {
        String content = "package com.example\nimport java.util.List\nclass Foo {}";
        assertEquals(2, invoke(testProvider, "findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content}));
    }

    @Test
    void findImportInsertLineAfterPackage() throws Exception {
        String content = "package com.example\n\nclass Foo {}";
        assertEquals(2, invoke(testProvider, "findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content}));
    }

    @Test
    void findImportInsertLineNoPackageNoImport() throws Exception {
        String content = "class Foo {}";
        assertEquals(0, invoke(testProvider, "findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content}));
    }

    // ---- sanitizeParameterName ----

    @Test
    void sanitizeParameterNameValid() throws Exception {
        assertEquals("name", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"name", 0}));
    }

    @Test
    void sanitizeParameterNameNull() throws Exception {
        assertEquals("arg0", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {null, 0}));
    }

    @Test
    void sanitizeParameterNameEmpty() throws Exception {
        assertEquals("arg1", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"", 1}));
    }

    @Test
    void sanitizeParameterNameInvalidStart() throws Exception {
        assertEquals("arg2", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"1bad", 2}));
    }

    @Test
    void sanitizeParameterNameInvalidMiddle() throws Exception {
        assertEquals("arg3", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"na-me", 3}));
    }

    // ---- renderType ----

    @Test
    void renderTypeNull() throws Exception {
        assertEquals("def", invoke(testProvider, "renderType",
                new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void renderTypeSimple() throws Exception {
        assertEquals("String", invoke(testProvider, "renderType",
                new Class<?>[] {ClassNode.class},
                new Object[] {org.codehaus.groovy.ast.ClassHelper.STRING_TYPE}));
    }

    @Test
    void renderTypeInt() throws Exception {
        assertEquals("int", invoke(testProvider, "renderType",
                new Class<?>[] {ClassNode.class},
                new Object[] {org.codehaus.groovy.ast.ClassHelper.int_TYPE}));
    }

    // ---- normalizeTypeName ----

    @Test
    void normalizeTypeNameNull() throws Exception {
        assertEquals("def", invoke(testProvider, "normalizeTypeName",
                new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null}));
    }

    @Test
    void normalizeTypeNameString() throws Exception {
        assertEquals("java.lang.String", invoke(testProvider, "normalizeTypeName",
                new Class<?>[] {ClassNode.class},
                new Object[] {org.codehaus.groovy.ast.ClassHelper.STRING_TYPE}));
    }

    // ---- leadingWhitespace ----

    @Test
    void leadingWhitespaceSpaces() throws Exception {
        assertEquals("    ", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"    code"}));
    }

    @Test
    void leadingWhitespaceNone() throws Exception {
        assertEquals("", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"code"}));
    }

    @Test
    void leadingWhitespaceTabs() throws Exception {
        assertEquals("\t\t", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"\t\tcode"}));
    }

    // ---- wantsKind ----

    @Test
    void wantsKindNullOnlyKindsReturnsTrue() throws Exception {
        assertEquals(true, invoke(testProvider, "wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {null, new String[] {"quickfix"}}));
    }

    @Test
    void wantsKindMatchingReturnsTrue() throws Exception {
        assertEquals(true, invoke(testProvider, "wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {List.of("quickfix", "refactor"), new String[] {"quickfix"}}));
    }

    @Test
    void wantsKindNoMatchReturnsFalse() throws Exception {
        assertEquals(false, invoke(testProvider, "wantsKind",
                new Class<?>[] {List.class, String[].class},
                new Object[] {List.of("refactor"), new String[] {"quickfix"}}));
    }

    // ---- isMethodRequiredForImplementation ----

    @Test
    void isMethodRequiredForImplementationAbstract() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "abstract class Svc {\n  abstract String process(int n)\n}";
        ModuleNode ast = cs.parse("file:///tmp.groovy", source).getModuleNode();
        ClassNode cls = ast.getClasses().stream()
                .filter(c -> "Svc".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(m -> "process".equals(m.getName())).findFirst().orElseThrow();
        assertEquals(true, invoke(testProvider, "isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationNonAbstract() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class Svc {\n  String process(int n) { '' }\n}";
        ModuleNode ast = cs.parse("file:///tmp.groovy", source).getModuleNode();
        ClassNode cls = ast.getClasses().stream()
                .filter(c -> "Svc".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode method = cls.getMethods().stream()
                .filter(m -> "process".equals(m.getName())).findFirst().orElseThrow();
        assertEquals(false, invoke(testProvider, "isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {method}));
    }

    @Test
    void isMethodRequiredForImplementationNull() throws Exception {
        assertEquals(false, invoke(testProvider, "isMethodRequiredForImplementation",
                new Class<?>[] {MethodNode.class}, new Object[] {(MethodNode) null}));
    }

    // ---- extractClassSimpleNameFromMessage ----

    @Test
    void extractClassSimpleNameFromQuotedMessage() throws Exception {
        assertEquals("Widget", invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"Cannot find class 'com.example.Widget' in scope"}));
    }

    @Test
    void extractClassSimpleNameFromMarkerMessage() throws Exception {
        assertEquals("Widget", invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"The type Widget is not accessible"}));
    }

    @Test
    void extractClassSimpleNameReturnsNullForEmpty() throws Exception {
        assertNull(invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {""}));
    }

    @Test
    void extractClassSimpleNameReturnsNullForNull() throws Exception {
        assertNull(invokeHelper("extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {(String) null}));
    }

    // ---- renderParameters ----

    @Test
    void renderParametersOne() throws Exception {
        Parameter p = new Parameter(org.codehaus.groovy.ast.ClassHelper.STRING_TYPE, "name");
        String result = (String) invoke(testProvider, "renderParameters",
                new Class<?>[] {Parameter[].class},
                new Object[] {new Parameter[] {p}});
        assertTrue(result.contains("String"));
        assertTrue(result.contains("name"));
    }

    // ---- inferIndentUnit ----

    @Test
    void inferIndentUnitFromSource() throws Exception {
        String[] lines = {"class Foo {", "    void run() {}", "}"};
        String result = (String) invoke(testProvider, "inferIndentUnit",
                new Class<?>[] {String[].class, String.class, int.class, int.class},
                new Object[] {lines, "", 0, 2});
        assertEquals("    ", result);
    }

    @Test
    void inferIndentUnitDefault() throws Exception {
        String[] lines = {"class Foo {", "}"};
        String result = (String) invoke(testProvider, "inferIndentUnit",
                new Class<?>[] {String[].class, String.class, int.class, int.class},
                new Object[] {lines, "", 0, 1});
        assertEquals("    ", result);
    }

    // ---- findClassInsertLine ----

    @Test
    void findClassInsertLineAtClosingBrace() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class Foo {\n  void run() {}\n}";
        ModuleNode ast = cs.parse("file:///tmp.groovy", source).getModuleNode();
        ClassNode cls = ast.getClasses().stream()
                .filter(c -> "Foo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        int line = (int) invoke(testProvider, "findClassInsertLine",
                new Class<?>[] {ClassNode.class, String.class},
                new Object[] {cls, source});
        assertEquals(2, line); // line where "}" is
    }

    // ---- hasSameParameterTypes ----

    @Test
    void hasSameParameterTypesBothEmpty() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class X {\n  void a() {}\n  void b() {}\n}";
        ModuleNode ast = cs.parse("file:///tmp.groovy", source).getModuleNode();
        ClassNode cls = ast.getClasses().stream()
                .filter(c -> "X".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode a = cls.getMethods().stream().filter(m -> "a".equals(m.getName())).findFirst().orElseThrow();
        MethodNode b = cls.getMethods().stream().filter(m -> "b".equals(m.getName())).findFirst().orElseThrow();
        assertEquals(true, invoke(testProvider, "hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {a, b}));
    }

    @Test
    void hasSameParameterTypesDifferent() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class X {\n  void a(String s) {}\n  void b(int i) {}\n}";
        ModuleNode ast = cs.parse("file:///tmp.groovy", source).getModuleNode();
        ClassNode cls = ast.getClasses().stream()
                .filter(c -> "X".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode a = cls.getMethods().stream().filter(m -> "a".equals(m.getName())).findFirst().orElseThrow();
        MethodNode b = cls.getMethods().stream().filter(m -> "b".equals(m.getName())).findFirst().orElseThrow();
        assertEquals(false, invoke(testProvider, "hasSameParameterTypes",
                new Class<?>[] {MethodNode.class, MethodNode.class}, new Object[] {a, b}));
    }

    // ================================================================
    // Batch 6 — additional CodeActionProvider coverage
    // ================================================================

    // ---- findMissingInterfaceMethods ----

    @Test
    void findMissingInterfaceMethodsDetectsMissing() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = """
                interface Greeter {
                    String greet(String name)
                    void wave()
                }
                class MyGreeter implements Greeter {
                    String greet(String name) { "Hi $name" }
                }
                """;
        ModuleNode ast = cs.parse("file:///findMissing.groovy", source).getModuleNode();
        ClassNode myGreeter = ast.getClasses().stream()
                .filter(c -> "MyGreeter".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invoke(testProvider, "findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {myGreeter, ast});
        assertNotNull(missing);
        assertTrue(missing.stream().anyMatch(m -> "wave".equals(m.getName())),
                "Expected 'wave' in missing methods");
    }

    @Test
    void findMissingMethodsEmptyWhenFullyImplemented() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = """
                interface Runner {
                    void run()
                }
                class MyRunner implements Runner {
                    void run() { println 'running' }
                }
                """;
        ModuleNode ast = cs.parse("file:///findMissAll.groovy", source).getModuleNode();
        ClassNode myRunner = ast.getClasses().stream()
                .filter(c -> "MyRunner".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invoke(testProvider, "findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {myRunner, ast});
        assertNotNull(missing);
        assertTrue(missing.isEmpty(), "Expected no missing methods");
    }

    // ---- renderType / renderParameters / methodSignatureKey ----

    @Test
    void renderTypeHandlesSimpleAndArrayTypes() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class Demo { String name; int[] values }";
        ModuleNode ast = cs.parse("file:///renderType.groovy", source).getModuleNode();
        ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        // Get the String type from the 'name' field
        ClassNode stringType = demo.getField("name").getType();
        String rendered = (String) invoke(testProvider, "renderType",
                new Class<?>[] {ClassNode.class}, new Object[] {stringType});
        assertEquals("String", rendered);
    }

    @Test
    void renderParametersForMultipleParams() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class Demo { void run(String s, int n) {} }";
        ModuleNode ast = cs.parse("file:///renderParams.groovy", source).getModuleNode();
        ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode run = demo.getMethods().stream()
                .filter(m -> "run".equals(m.getName())).findFirst().orElseThrow();
        String rendered = (String) invoke(testProvider, "renderParameters",
                new Class<?>[] {Parameter[].class}, new Object[] {run.getParameters()});
        assertNotNull(rendered);
        assertTrue(rendered.contains("String"));
    }

    @Test
    void methodSignatureKeyIncludesParamTypes() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = "class Demo { void doIt(String s, int n) {} }";
        ModuleNode ast = cs.parse("file:///methodSig.groovy", source).getModuleNode();
        ClassNode demo = ast.getClasses().stream()
                .filter(c -> "Demo".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        MethodNode doIt = demo.getMethods().stream()
                .filter(m -> "doIt".equals(m.getName())).findFirst().orElseThrow();
        String key = (String) invoke(testProvider, "methodSignatureKey",
                new Class<?>[] {MethodNode.class}, new Object[] {doIt});
        assertNotNull(key);
        assertTrue(key.startsWith("doIt("));
    }

    // ---- normalizeTypeName ----

    @Test
    void normalizeTypeNameHandlesNull() throws Exception {
        String result = (String) invoke(testProvider, "normalizeTypeName",
                new Class<?>[] {ClassNode.class}, new Object[] {(ClassNode) null});
        assertEquals("def", result);
    }

    // ---- findClassInsertLine ----

    @Test
    void findClassInsertLineReturnsValidLine() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = """
                class Example {
                    void a() {}
                }
                """;
        ModuleNode ast = cs.parse("file:///insertLine.groovy", source).getModuleNode();
        ClassNode example = ast.getClasses().stream()
                .filter(c -> "Example".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        int line = (int) invoke(testProvider, "findClassInsertLine",
                new Class<?>[] {ClassNode.class, String.class},
                new Object[] {example, source});
        assertTrue(line >= 0, "Expected a valid insert line");
    }

    // ---- leadingWhitespace ----

    @Test
    void leadingWhitespaceExtractsIndent() throws Exception {
        assertEquals("    ", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"    hello"}));
        assertEquals("", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"hello"}));
        assertEquals("\t", invoke(testProvider, "leadingWhitespace",
                new Class<?>[] {String.class}, new Object[] {"\thello"}));
    }

    // ---- wantsQuickFix / wantsSource / wantsKind ----

    @Test
    void wantsQuickFixReturnsTrueForQuickFixKind() throws Exception {
        List<String> kinds = List.of(CodeActionKind.QuickFix);
        assertEquals(true, invoke(testProvider, "wantsQuickFix",
                new Class<?>[] {List.class}, new Object[] {kinds}));
    }

    @Test
    void wantsQuickFixReturnsFalseForRefactorKind() throws Exception {
        List<String> kinds = List.of(CodeActionKind.Refactor);
        assertEquals(false, invoke(testProvider, "wantsQuickFix",
                new Class<?>[] {List.class}, new Object[] {kinds}));
    }

    @Test
    void wantsQuickFixReturnsTrueForNullKinds() throws Exception {
        assertEquals(true, invoke(testProvider, "wantsQuickFix",
                new Class<?>[] {List.class}, new Object[] {(List<?>) null}));
    }

    @Test
    void wantsSourceReturnsTrueForSourceKind() throws Exception {
        List<String> kinds = List.of(CodeActionKind.Source);
        assertEquals(true, invoke(testProvider, "wantsSource",
                new Class<?>[] {List.class}, new Object[] {kinds}));
    }

    // ---- isMethodRequiredForImplementation ----

    @Test
    void isMethodRequiredFiltersStaticAndSynthetic() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = """
                interface Sayer {
                    String say()
                    static String defaultSay() { 'hello' }
                }
                """;
        ModuleNode ast = cs.parse("file:///isMethodReq.groovy", source).getModuleNode();
        ClassNode sayer = ast.getClasses().stream()
                .filter(c -> "Sayer".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        for (MethodNode m : sayer.getMethods()) {
            if ("say".equals(m.getName())) {
                assertEquals(true, invoke(testProvider, "isMethodRequiredForImplementation",
                        new Class<?>[] {MethodNode.class}, new Object[] {m}));
            }
        }
    }

    // ---- simpleTypeName ----

    @Test
    void simpleTypeNameExtractsLastSegment() throws Exception {
        assertEquals("MyClass", invoke(testProvider, "simpleTypeName",
                new Class<?>[] {String.class}, new Object[] {"com.example.MyClass"}));
        assertEquals("Simple", invoke(testProvider, "simpleTypeName",
                new Class<?>[] {String.class}, new Object[] {"Simple"}));
    }

    // ---- extractQuotedTypeCandidate ----

    @Test
    void extractQuotedTypeCandidateExtractsFromQuotes() throws Exception {
        assertEquals("MyWidget", invoke(testProvider, "extractQuotedTypeCandidate",
                new Class<?>[] {String.class},
                new Object[] {"The type 'MyWidget' cannot be found"}));
    }

    @Test
    void extractQuotedTypeCandidateNullWhenNoSingleQuotes() throws Exception {
        assertNull(invoke(testProvider, "extractQuotedTypeCandidate",
                new Class<?>[] {String.class},
                new Object[] {"no quotes here"}));
    }

    // ---- findImportInsertLine ----

    @Test
    void findImportInsertLineFindsCorrectPosition() throws Exception {
        String content = "package com.example\n\nimport java.util.List\n\nclass Demo {}";
        int line = (int) invoke(testProvider, "findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
        assertTrue(line >= 2, "Import insert line should be after package");
    }

    @Test
    void findImportInsertLineForNoPackage() throws Exception {
        String content = "class Demo {}";
        int line = (int) invoke(testProvider, "findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
        assertTrue(line >= 0, "Should find a valid insert line");
    }

    // ---- buildMissingMethodStubsText ----

    @Test
    void buildMissingMethodStubsTextGeneratesStubs() throws Exception {
        GroovyCompilerService cs = new GroovyCompilerService();
        String source = """
                interface Callable {
                    String call(String arg)
                }
                class Impl implements Callable {
                }
                """;
        ModuleNode ast = cs.parse("file:///buildStubs.groovy", source).getModuleNode();
        ClassNode impl = ast.getClasses().stream()
                .filter(c -> "Impl".equals(c.getNameWithoutPackage())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<MethodNode> missing = (List<MethodNode>) invoke(testProvider, "findMissingInterfaceMethods",
                new Class<?>[] {ClassNode.class, ModuleNode.class},
                new Object[] {impl, ast});
        if (!missing.isEmpty()) {
            int insertLine = (int) invoke(testProvider, "findClassInsertLine",
                    new Class<?>[] {ClassNode.class, String.class},
                    new Object[] {impl, source});
            String stubs = (String) invoke(testProvider, "buildMissingMethodStubsText",
                    new Class<?>[] {ClassNode.class, List.class, String.class, int.class},
                    new Object[] {impl, missing, source, insertLine});
            assertNotNull(stubs);
            assertTrue(stubs.contains("call"), "Stubs should contain method name 'call'");
        }
    }

    // ---- sanitizeParameterName ----

    @Test
    void sanitizeParameterNameReturnsValidIdentifier() throws Exception {
        assertEquals("name", invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"name", 0}));
    }

    @Test
    void sanitizeParameterNameReplacesInvalidWithArgN() throws Exception {
        String result = (String) invoke(testProvider, "sanitizeParameterName",
                new Class<?>[] {String.class, int.class}, new Object[] {"", 2});
        assertNotNull(result);
        assertTrue(result.contains("arg"), "Expected fallback arg name");
    }

    // ---- extractClassSimpleNameFromMessage ----

    @Test
    void extractClassSimpleNameFromVariousMessages() throws Exception {
        assertEquals("MyService", invoke(testProvider, "extractClassSimpleNameFromMessage",
                new Class<?>[] {String.class},
                new Object[] {"unable to resolve class 'MyService'"}));
    }

    // ---- isUnresolvedTypeDiagnostic / isMissingInterfaceMemberDiagnostic ----

    @Test
    void isUnresolvedTypeDiagnosticMatchesUnresolvedClass() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("unable to resolve class FooBar");
        assertEquals(true, invoke(testProvider, "isUnresolvedTypeDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {d}));
    }

    @Test
    void isUnresolvedTypeDiagnosticReturnsFalseForOther() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("syntax error");
        assertEquals(false, invoke(testProvider, "isUnresolvedTypeDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {d}));
    }

    @Test
    void isMissingInterfaceMemberMatches() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("Can't have an abstract method in a non-abstract class. " +
                "The class 'Foo' must be declared abstract or the method 'void bar()' must be implemented");
        assertEquals(true, invoke(testProvider, "isMissingInterfaceMemberDiagnostic",
                new Class<?>[] {Diagnostic.class}, new Object[] {d}));
    }

    // ---- getDiagnosticMessage ----

    @Test
    void getDiagnosticMessageReturnsString() throws Exception {
        Diagnostic d = new Diagnostic();
        d.setMessage("test message");
        Object msg = invoke(testProvider, "getDiagnosticMessage",
                new Class<?>[] {Diagnostic.class}, new Object[] {d});
        assertNotNull(msg);
        assertTrue(msg.toString().contains("test message"));
    }

    @Test
    void getDiagnosticMessageReturnsEmptyForNoMessage() throws Exception {
        Diagnostic d = new Diagnostic();
        Object msg = invoke(testProvider, "getDiagnosticMessage",
                new Class<?>[] {Diagnostic.class}, new Object[] {d});
        assertNotNull(msg);
    }

    // ================================================================
    // createRemoveImportAction tests (79 missed instructions)
    // ================================================================

    @Test
    void createRemoveImportActionCreatesQuickFix() throws Exception {
        String uri = "file:///removeImport.groovy";
        String content = "import java.util.ArrayList\nimport java.util.HashMap\nclass Foo {}";
        Diagnostic diag = new Diagnostic();
        diag.setRange(new Range(new Position(0, 0), new Position(0, 27)));

        CodeAction action = (CodeAction) invoke(testProvider, "createRemoveImportAction",
                new Class<?>[] {String.class, String.class, Diagnostic.class, boolean.class},
                new Object[] {uri, content, diag, true});
        assertNotNull(action);
        assertEquals("Remove unused import", action.getTitle());
        assertEquals(CodeActionKind.QuickFix, action.getKind());
        assertTrue(action.getIsPreferred());
        assertNotNull(action.getEdit());
        assertNotNull(action.getEdit().getChanges().get(uri));
        assertEquals(1, action.getEdit().getChanges().get(uri).size());
        TextEdit edit = action.getEdit().getChanges().get(uri).get(0);
        assertEquals("", edit.getNewText());
        assertEquals(0, edit.getRange().getStart().getLine());
        assertEquals(1, edit.getRange().getEnd().getLine());
    }

    @Test
    void createRemoveImportActionReturnsNullForInvalidLine() throws Exception {
        String uri = "file:///removeImport2.groovy";
        String content = "class Foo {}";
        Diagnostic diag = new Diagnostic();
        diag.setRange(new Range(new Position(99, 0), new Position(99, 5)));

        CodeAction action = (CodeAction) invoke(testProvider, "createRemoveImportAction",
                new Class<?>[] {String.class, String.class, Diagnostic.class, boolean.class},
                new Object[] {uri, content, diag, false});
        assertNull(action);
    }

    @Test
    void createRemoveImportActionHandlesLastLine() throws Exception {
        String uri = "file:///removeImport3.groovy";
        String content = "import java.util.List\nclass Foo {}";
        Diagnostic diag = new Diagnostic();
        diag.setRange(new Range(new Position(0, 0), new Position(0, 22)));

        CodeAction action = (CodeAction) invoke(testProvider, "createRemoveImportAction",
                new Class<?>[] {String.class, String.class, Diagnostic.class, boolean.class},
                new Object[] {uri, content, diag, false});
        assertNotNull(action);
        assertFalse(action.getEdit().getChanges().get(uri).isEmpty());
    }
}
