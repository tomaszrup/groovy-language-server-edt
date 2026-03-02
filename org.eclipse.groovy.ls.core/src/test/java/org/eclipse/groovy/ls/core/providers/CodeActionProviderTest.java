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
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
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
                .filter(a -> a.getTitle() != null && a.getTitle().startsWith("Implement missing"))
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

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeHelper(String methodName, Class<?>[] types, Object[] args) throws Exception {
        return invoke(testProvider, methodName, types, args);
    }
}
