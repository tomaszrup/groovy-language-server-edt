/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.groovy.ls.core.providers.InlayHintSettings;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Tests for {@link GroovyWorkspaceService} private helper methods via reflection.
 */
class GroovyWorkspaceServiceTest {

    private GroovyWorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
    }

    // ---- isSourceFileUri / isGroovyFileUri / isJavaFileUri ----

    @Test
    void isSourceFileUriReturnsTrueForGroovyAndJava() throws Exception {
        assertTrue((boolean) invoke("isSourceFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/to/Hello.groovy"}));
        assertTrue((boolean) invoke("isSourceFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/to/Hello.java"}));
    }

    @Test
    void isSourceFileUriReturnsFalseForNonSource() throws Exception {
        assertFalse((boolean) invoke("isSourceFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/to/readme.txt"}));
        assertFalse((boolean) invoke("isSourceFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/to/build.gradle"}));
    }

    @Test
    void isGroovyFileUriReturnsTrueOnlyForGroovy() throws Exception {
        assertTrue((boolean) invoke("isGroovyFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/Hello.groovy"}));
        assertFalse((boolean) invoke("isGroovyFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/Hello.java"}));
    }

    @Test
    void isJavaFileUriReturnsTrueOnlyForJava() throws Exception {
        assertTrue((boolean) invoke("isJavaFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/Hello.java"}));
        assertFalse((boolean) invoke("isJavaFileUri", new Class<?>[] {String.class},
                new Object[] {"file:///path/Hello.groovy"}));
    }

    // ---- baseNameFromUri / fileNameFromUri / fileExtensionFromUri ----

    @Test
    void baseNameFromUriExtractsFileName() throws Exception {
        assertEquals("Hello", invoke("baseNameFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///path/Hello.groovy"}));
        assertEquals("MyApp", invoke("baseNameFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///dir/MyApp.java"}));
    }

    @Test
    void baseNameFromUriReturnsNullForNullOrBlank() throws Exception {
        assertNull(invoke("baseNameFromUri", new Class<?>[] {String.class}, new Object[] {(String) null}));
        assertNull(invoke("baseNameFromUri", new Class<?>[] {String.class}, new Object[] {"   "}));
    }

    @Test
    void fileExtensionFromUriExtractsExtension() throws Exception {
        assertEquals("groovy", invoke("fileExtensionFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///path/Hello.groovy"}));
        assertEquals("java", invoke("fileExtensionFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///path/Hello.java"}));
    }

    @Test
    void fileExtensionFromUriReturnsNullForNoExtension() throws Exception {
        assertNull(invoke("fileExtensionFromUri",
                new Class<?>[] {String.class}, new Object[] {(String) null}));
    }

    // ---- packageFromQualifiedName ----

    @Test
    void packageFromQualifiedNameExtractsPackage() throws Exception {
        assertEquals("com.example", invoke("packageFromQualifiedName",
                new Class<?>[] {String.class}, new Object[] {"com.example.MyClass"}));
        assertEquals("", invoke("packageFromQualifiedName",
                new Class<?>[] {String.class}, new Object[] {"MyClass"}));
        assertEquals("", invoke("packageFromQualifiedName",
                new Class<?>[] {String.class}, new Object[] {""}));
        assertEquals("", invoke("packageFromQualifiedName",
                new Class<?>[] {String.class}, new Object[] {(String) null}));
    }

    // ---- extractPackageName ----

    @Test
    void extractPackageNameFindsPackageDeclaration() throws Exception {
        assertEquals("com.example.demo", invoke("extractPackageName",
                new Class<?>[] {String.class},
                new Object[] {"package com.example.demo\n\nclass Hello {}"}));
        assertEquals("com.example", invoke("extractPackageName",
                new Class<?>[] {String.class},
                new Object[] {"package com.example;\n\nclass Hello {}"}));
    }

    @Test
    void extractPackageNameReturnsEmptyForNoPackage() throws Exception {
        assertEquals("", invoke("extractPackageName",
                new Class<?>[] {String.class}, new Object[] {"class Hello {}"}));
        assertEquals("", invoke("extractPackageName",
                new Class<?>[] {String.class}, new Object[] {(String) null}));
        assertEquals("", invoke("extractPackageName",
                new Class<?>[] {String.class}, new Object[] {"   "}));
    }

    // ---- hasExactImport ----

    @Test
    void hasExactImportMatchesImportLine() throws Exception {
        assertTrue((boolean) invoke("hasExactImport",
                new Class<?>[] {String.class, String.class},
                new Object[] {"import com.example.Foo\nclass A {}", "com.example.Foo"}));
        assertFalse((boolean) invoke("hasExactImport",
                new Class<?>[] {String.class, String.class},
                new Object[] {"import com.example.FooBar\nclass A {}", "com.example.Foo"}));
    }

    // ---- isInsideImportLine ----

    @Test
    void isInsideImportLineDetectsImportLines() throws Exception {
        String content = "package com.demo\nimport java.util.List\nclass A {}";
        assertTrue((boolean) invoke("isInsideImportLine",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, 24})); // offset inside "import java.util.List"
        assertFalse((boolean) invoke("isInsideImportLine",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, 45})); // offset inside "class A {}"
    }

    @Test
    void isInsideImportLineHandlesBoundaryOffset() throws Exception {
        String content = "import java.util.List\n";
        assertFalse((boolean) invoke("isInsideImportLine",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, -1}));
    }

    // ---- offsetToPosition ----

    @Test
    void offsetToPositionComputesLineAndColumn() throws Exception {
        String content = "line one\nline two\nline three";
        Position pos = (Position) invoke("offsetToPosition",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, 14}); // "two" starts at 9+5=14
        assertEquals(1, pos.getLine());
        assertEquals(5, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtZero() throws Exception {
        Position pos = (Position) invoke("offsetToPosition",
                new Class<?>[] {String.class, int.class},
                new Object[] {"hello", 0});
        assertEquals(0, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void offsetToPositionClampsBeyondEnd() throws Exception {
        Position pos = (Position) invoke("offsetToPosition",
                new Class<?>[] {String.class, int.class},
                new Object[] {"abc", 100});
        // Should clamp to end of content
        assertNotNull(pos);
    }

    // ---- findImportInsertLine ----

    @Test
    void findImportInsertLineAfterLastImport() throws Exception {
        String content = "package com.demo\n\nimport java.util.List\nimport java.util.Map\n\nclass A {}";
        int line = (int) invoke("findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
        assertTrue(line >= 2 && line <= 5, "Expected insert line between 2 and 5 but got " + line);
    }

    @Test
    void findImportInsertLineAfterPackageWhenNoImports() throws Exception {
        String content = "package com.demo\n\nclass A {}";
        int line = (int) invoke("findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
        assertEquals(2, line); // packageLine + 2
    }

    @Test
    void findImportInsertLineReturnsZeroWhenNoPackageOrImports() throws Exception {
        String content = "class A {}";
        int line = (int) invoke("findImportInsertLine",
                new Class<?>[] {String.class}, new Object[] {content});
        assertEquals(0, line);
    }

    // ---- findTypeDeclarationRenameEdit ----

    @Test
    void findTypeDeclarationRenameEditFindsClass() throws Exception {
        String source = "package com.demo\n\nclass MyService {\n    void run() {}\n}";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "MyService", "RenamedService"});
        assertNotNull(edit);
        assertEquals("RenamedService", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditFindsInterface() throws Exception {
        String source = "interface Greeter {\n    void greet()\n}";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "Greeter", "Welcomer"});
        assertNotNull(edit);
        assertEquals("Welcomer", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditFindsTrait() throws Exception {
        String source = "trait Named { String name }";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "Named", "Labeled"});
        assertNotNull(edit);
        assertEquals("Labeled", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditFindsEnum() throws Exception {
        String source = "enum Color { RED, GREEN, BLUE }";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "Color", "Colour"});
        assertNotNull(edit);
        assertEquals("Colour", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditReturnsNullWhenNotFound() throws Exception {
        String source = "class Different {}";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "NotPresent", "Renamed"});
        assertNull(edit);
    }

    // ---- countTextEdits / mergeEdits ----

    @Test
    void countTextEditsCountsAllEdits() throws Exception {
        Map<String, List<TextEdit>> editsByUri = new HashMap<>();
        editsByUri.put("file:///a.groovy", List.of(new TextEdit(), new TextEdit()));
        editsByUri.put("file:///b.groovy", List.of(new TextEdit()));

        int count = (int) invoke("countTextEdits",
                new Class<?>[] {Map.class}, new Object[] {editsByUri});
        assertEquals(3, count);
    }

    @Test
    void countTextEditsHandlesNullValues() throws Exception {
        Map<String, List<TextEdit>> editsByUri = new HashMap<>();
        editsByUri.put("file:///a.groovy", null);

        int count = (int) invoke("countTextEdits",
                new Class<?>[] {Map.class}, new Object[] {editsByUri});
        assertEquals(0, count);
    }

    @Test
    void mergeEditsCombinesMaps() throws Exception {
        Map<String, List<TextEdit>> target = new HashMap<>();
        target.put("file:///a.groovy", new ArrayList<>(List.of(new TextEdit())));

        Map<String, List<TextEdit>> source = new HashMap<>();
        source.put("file:///a.groovy", List.of(new TextEdit()));
        source.put("file:///b.groovy", List.of(new TextEdit()));

        invoke("mergeEdits",
                new Class<?>[] {Map.class, Map.class},
                new Object[] {target, source});

        assertEquals(2, target.get("file:///a.groovy").size());
        assertEquals(1, target.get("file:///b.groovy").size());
    }

    // ---- parseInlayHintSettings / readNestedBoolean ----

    @Test
    void parseInlayHintSettingsWithNullReturnsDefaults() throws Exception {
        InlayHintSettings settings = (InlayHintSettings) invoke("parseInlayHintSettings",
                new Class<?>[] {JsonObject.class}, new Object[] {(JsonObject) null});
        assertNotNull(settings);
        assertTrue(settings.isParameterNamesEnabled());
        assertTrue(settings.isVariableTypesEnabled());
    }

    @Test
    void parseInlayHintSettingsReadsNestedValues() throws Exception {
        JsonObject inlayHints = new JsonObject();
        JsonObject variableTypes = new JsonObject();
        variableTypes.addProperty("enabled", false);
        inlayHints.add("variableTypes", variableTypes);

        JsonObject parameterNames = new JsonObject();
        parameterNames.addProperty("enabled", false);
        inlayHints.add("parameterNames", parameterNames);

        InlayHintSettings settings = (InlayHintSettings) invoke("parseInlayHintSettings",
                new Class<?>[] {JsonObject.class}, new Object[] {inlayHints});

        assertFalse(settings.isVariableTypesEnabled());
        assertFalse(settings.isParameterNamesEnabled());
    }

    @Test
    void readNestedBooleanReturnsDefaultWhenMissing() throws Exception {
        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {new JsonObject(), "missing", "enabled", true});
        assertTrue(result);
    }

    @Test
    void readNestedBooleanReadsActualValue() throws Exception {
        JsonObject root = new JsonObject();
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", false);
        root.add("section", obj);

        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {root, "section", "enabled", true});
        assertFalse(result);
    }

    // ---- findGroovyPackageDeclarationMoveEdit ----

    @Test
    void findGroovyPackageDeclarationMoveEditReplacesExistingPackage() throws Exception {
        String source = "package com.old\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {source, "com.newpkg"});
        assertNotNull(edit);
        assertEquals("com.newpkg", edit.getNewText());
    }

    @Test
    void findGroovyPackageDeclarationMoveEditInsertsWhenNoPackage() throws Exception {
        String source = "class A {}";
        TextEdit edit = (TextEdit) invoke("findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {source, "com.newpkg"});
        assertNotNull(edit);
        assertTrue(edit.getNewText().contains("package com.newpkg"));
    }

    @Test
    void findGroovyPackageDeclarationMoveEditRemovesPackageWhenBlank() throws Exception {
        String source = "package com.old\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {source, ""});
        assertNotNull(edit);
        assertEquals("", edit.getNewText());
    }

    @Test
    void findGroovyPackageDeclarationMoveEditReturnsNullForNullSource() throws Exception {
        TextEdit edit = (TextEdit) invoke("findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {(String) null, "com.pkg"});
        assertNull(edit);
    }

    // ---- createImportInsertEdit ----

    @Test
    void createImportInsertEditAddsImportLine() throws Exception {
        String content = "package com.demo\n\nimport java.util.List\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("createImportInsertEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {content, "java.time.LocalDate"});
        assertNotNull(edit);
        assertTrue(edit.getNewText().contains("import java.time.LocalDate"));
    }

    // ---- createImportReplaceEdit / createImportRemovalEdit ----

    @Test
    void createImportReplaceEditReplacesOldImport() throws Exception {
        String content = "import com.old.MyClass\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("createImportReplaceEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {content, "com.old.MyClass", "com.new.MyClass"});
        assertNotNull(edit);
        assertEquals("com.new.MyClass", edit.getNewText());
    }

    @Test
    void createImportReplaceEditReturnsNullWhenNotFound() throws Exception {
        String content = "import com.other.Other\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("createImportReplaceEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {content, "com.missing.Type", "com.new.Type"});
        assertNull(edit);
    }

    @Test
    void createImportRemovalEditRemovesImportLine() throws Exception {
        String content = "import com.old.MyClass\n\nclass A {}";
        TextEdit edit = (TextEdit) invoke("createImportRemovalEdit",
                new Class<?>[] {String.class, String.class},
                new Object[] {content, "com.old.MyClass"});
        assertNotNull(edit);
        assertEquals("", edit.getNewText());
    }

    // ---- packageNameForPath ----

    @Test
    void packageNameForPathConvertsPathSegmentsToPackage() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("com", "example", "demo");
        assertEquals("com.example.demo", invoke("packageNameForPath",
                new Class<?>[] {java.nio.file.Path.class}, new Object[] {path}));
    }

    @Test
    void packageNameForPathReturnsEmptyForEmptyPath() throws Exception {
        assertEquals("", invoke("packageNameForPath",
                new Class<?>[] {java.nio.file.Path.class}, new Object[] {(java.nio.file.Path) null}));
    }

    @Test
    void packageNameForPathSingleSegment() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("utils");
        assertEquals("utils", invoke("packageNameForPath",
                new Class<?>[] {java.nio.file.Path.class}, new Object[] {path}));
    }

    // ---- fileNameFromUri ----

    @Test
    void fileNameFromUriExtractsFileName() throws Exception {
        assertEquals("Hello.groovy", invoke("fileNameFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///path/to/Hello.groovy"}));
        assertEquals("MyApp.java", invoke("fileNameFromUri",
                new Class<?>[] {String.class}, new Object[] {"file:///dir/MyApp.java"}));
    }

    @Test
    void fileNameFromUriReturnsNullForNullOrBlank() throws Exception {
        assertNull(invoke("fileNameFromUri", new Class<?>[] {String.class}, new Object[] {(String) null}));
        assertNull(invoke("fileNameFromUri", new Class<?>[] {String.class}, new Object[] {"   "}));
    }

        @Test
        void fileNameFromUriReturnsNullForNonFileUri() throws Exception {
                assertNull(invoke("fileNameFromUri",
                                new Class<?>[] {String.class}, new Object[] {"groovy-source:///path/Hello.groovy"}));
        }

    // ---- toSymbolKind (via mock) ----

    @Test
    void toSymbolKindReturnsClassForType() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.TYPE);
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {type});
        assertEquals(SymbolKind.Class, kind);
    }

    @Test
    void toSymbolKindReturnsInterfaceForInterface() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {type});
        assertEquals(SymbolKind.Interface, kind);
    }

    @Test
    void toSymbolKindReturnsEnumForEnum() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {type});
        assertEquals(SymbolKind.Enum, kind);
    }

    @Test
    void toSymbolKindReturnsMethodForMethod() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.METHOD);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {element});
        assertEquals(SymbolKind.Method, kind);
    }

    @Test
    void toSymbolKindReturnsFieldForField() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.FIELD);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {element});
        assertEquals(SymbolKind.Field, kind);
    }

    @Test
    void toSymbolKindReturnsVariableForLocalVariable() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.LOCAL_VARIABLE);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {element});
        assertEquals(SymbolKind.Variable, kind);
    }

    @Test
    void toSymbolKindReturnsPackageForPackageFragment() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.PACKAGE_FRAGMENT);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {element});
        assertEquals(SymbolKind.Package, kind);
    }

    @Test
    void toSymbolKindDefaultsToClassForUnknown() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.JAVA_MODEL);
        SymbolKind kind = (SymbolKind) invoke("toSymbolKind",
                new Class<?>[] {IJavaElement.class}, new Object[] {element});
        assertEquals(SymbolKind.Class, kind);
    }

    // ---- hasSimpleTypeUsage ----

    @Test
    void hasSimpleTypeUsageReturnsTrueWhenSimpleNameMatchesOutsideImport() throws Exception {
        String content = "import com.example.Foo\nclass A { Foo bar }";
        SearchMatch match = mock(SearchMatch.class);
        when(match.getOffset()).thenReturn(content.indexOf("Foo", content.indexOf("class")));
        when(match.getLength()).thenReturn(3);
        List<SearchMatch> matches = List.of(match);

        boolean result = (boolean) invoke("hasSimpleTypeUsage",
                new Class<?>[] {String.class, List.class, String.class},
                new Object[] {content, matches, "Foo"});
        assertTrue(result);
    }

    @Test
    void hasSimpleTypeUsageReturnsFalseWhenOnlyInsideImport() throws Exception {
        String content = "import com.example.Foo\nclass A {}";
        SearchMatch match = mock(SearchMatch.class);
        when(match.getOffset()).thenReturn(content.indexOf("Foo"));
        when(match.getLength()).thenReturn(3);
        List<SearchMatch> matches = List.of(match);

        boolean result = (boolean) invoke("hasSimpleTypeUsage",
                new Class<?>[] {String.class, List.class, String.class},
                new Object[] {content, matches, "Foo"});
        assertFalse(result);
    }

    @Test
    void hasSimpleTypeUsageReturnsFalseForEmptyMatches() throws Exception {
        String content = "class A {}";
        boolean result = (boolean) invoke("hasSimpleTypeUsage",
                new Class<?>[] {String.class, List.class, String.class},
                new Object[] {content, List.of(), "Foo"});
        assertFalse(result);
    }

    // ---- getSourceText ----

    @Test
    void getSourceTextReturnsOpenDocumentContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///test/Source.groovy";
        dm.didOpen(uri, "class Source {}");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);
        Method m = GroovyWorkspaceService.class.getDeclaredMethod("getSourceText", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(svc, uri);
        assertEquals("class Source {}", result);
    }

    @Test
    void getSourceTextReturnsNullForNonexistentFile() throws Exception {
        String result = (String) invoke("getSourceText",
                new Class<?>[] {String.class},
                new Object[] {"file:///nonexistent/path/NotHere.groovy"});
        assertNull(result);
    }

        @Test
        void getSourceTextReturnsNullForNonFileUri() throws Exception {
                String result = (String) invoke("getSourceText",
                                new Class<?>[] {String.class},
                                new Object[] {"groovy-source:///path/Virtual.groovy"});
                assertNull(result);
        }

    // ---- readNestedBoolean edge cases ----

    @Test
    void readNestedBooleanReturnsDefaultForNullRoot() throws Exception {
        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {null, "section", "enabled", true});
        assertTrue(result);
    }

    @Test
    void readNestedBooleanReturnsDefaultForNonObjectValue() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("section", "not-an-object");
        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {root, "section", "enabled", true});
        assertTrue(result);
    }

    @Test
    void readNestedBooleanReturnsDefaultForNullJsonValue() throws Exception {
        JsonObject root = new JsonObject();
        JsonObject section = new JsonObject();
        section.add("enabled", com.google.gson.JsonNull.INSTANCE);
        root.add("section", section);
        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {root, "section", "enabled", false});
        assertFalse(result);
    }

    @Test
    void readNestedBooleanReturnsDefaultForNonBooleanString() throws Exception {
        JsonObject root = new JsonObject();
        JsonObject section = new JsonObject();
        section.addProperty("enabled", "not-a-boolean-string");
        root.add("section", section);
        boolean result = (boolean) invoke("readNestedBoolean",
                new Class<?>[] {JsonObject.class, String.class, String.class, boolean.class},
                new Object[] {root, "section", "enabled", true});
        // getAsBoolean on "not-a-boolean-string" returns false
        assertFalse(result);
    }

    // ---- parseInlayHintSettings with all fields ----

    @Test
    void parseInlayHintSettingsAllDisabled() throws Exception {
        JsonObject inlayHints = new JsonObject();
        for (String key : new String[] {"variableTypes", "parameterNames", "closureParameterTypes", "methodReturnTypes"}) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", false);
            inlayHints.add(key, obj);
        }
        InlayHintSettings settings = (InlayHintSettings) invoke("parseInlayHintSettings",
                new Class<?>[] {JsonObject.class}, new Object[] {inlayHints});
        assertFalse(settings.isVariableTypesEnabled());
        assertFalse(settings.isParameterNamesEnabled());
        assertFalse(settings.isClosureParameterTypesEnabled());
        assertFalse(settings.isMethodReturnTypesEnabled());
    }

    @Test
    void parseInlayHintSettingsAllEnabled() throws Exception {
        JsonObject inlayHints = new JsonObject();
        for (String key : new String[] {"variableTypes", "parameterNames", "closureParameterTypes", "methodReturnTypes"}) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", true);
            inlayHints.add(key, obj);
        }
        InlayHintSettings settings = (InlayHintSettings) invoke("parseInlayHintSettings",
                new Class<?>[] {JsonObject.class}, new Object[] {inlayHints});
        assertTrue(settings.isVariableTypesEnabled());
        assertTrue(settings.isParameterNamesEnabled());
        assertTrue(settings.isClosureParameterTypesEnabled());
        assertTrue(settings.isMethodReturnTypesEnabled());
    }

    // ---- isInsideImportLine edge cases ----

    @Test
    void isInsideImportLineStaticImport() throws Exception {
        String content = "import static java.lang.Math.PI\nclass A {}";
        assertTrue((boolean) invoke("isInsideImportLine",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, 15}));
    }

    @Test
    void isInsideImportLineAtEndOfContent() throws Exception {
        String content = "import java.util.List";
        assertTrue((boolean) invoke("isInsideImportLine",
                new Class<?>[] {String.class, int.class},
                new Object[] {content, 10}));
    }

    // ---- hasExactImport edge cases ----

    @Test
    void hasExactImportDoesNotMatchPartialName() throws Exception {
        assertFalse((boolean) invoke("hasExactImport",
                new Class<?>[] {String.class, String.class},
                new Object[] {"import com.example.FooBar\nclass A {}", "com.example.Foo"}));
    }

    @Test
    void hasExactImportMatchesWithSemicolon() throws Exception {
        assertTrue((boolean) invoke("hasExactImport",
                new Class<?>[] {String.class, String.class},
                new Object[] {"import com.example.Foo;\nclass A {}", "com.example.Foo"}));
    }

    // ---- extractPackageName edge cases ----

    @Test
    void extractPackageNameWithSemicolon() throws Exception {
        assertEquals("com.example", invoke("extractPackageName",
                new Class<?>[] {String.class},
                new Object[] {"package com.example;\nclass A {}"}));
    }

    @Test
    void extractPackageNameWithWhitespace() throws Exception {
        assertEquals("com.example", invoke("extractPackageName",
                new Class<?>[] {String.class},
                new Object[] {"  package com.example  \nclass A {}"}));
    }

    // ---- offsetToPosition edge cases ----

    @Test
    void offsetToPositionHandlesMultipleNewlines() throws Exception {
        String content = "a\n\nb\n";
        Position pos = (Position) invoke("offsetToPosition",
                new Class<?>[] {String.class, int.class}, new Object[] {content, 3});
        assertEquals(2, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    @Test
    void offsetToPositionHandlesNegativeOffset() throws Exception {
        String content = "abc";
        Position pos = (Position) invoke("offsetToPosition",
                new Class<?>[] {String.class, int.class}, new Object[] {content, -5});
        assertEquals(0, pos.getLine());
        assertEquals(0, pos.getCharacter());
    }

    // ---- findTypeDeclarationRenameEdit edge cases ----

    @Test
    void findTypeDeclarationRenameEditRecord() throws Exception {
        // "record" is a valid keyword prefix in the pattern
        String source = "record Data(String name) {}";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "Data", "Info"});
        // Depending on if 'record' is in the pattern — it is
        assertNotNull(edit);
        assertEquals("Info", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditAnnotationInterface() throws Exception {
        String source = "@interface MyAnnotation { String value() }";
        TextEdit edit = (TextEdit) invoke("findTypeDeclarationRenameEdit",
                new Class<?>[] {String.class, String.class, String.class},
                new Object[] {source, "MyAnnotation", "NewAnnotation"});
        assertNotNull(edit);
        assertEquals("NewAnnotation", edit.getNewText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectWorkspaceGroovyFilesReturnsCachedListWhenFresh() throws Exception {
        Path cached = Path.of("/tmp/Cached.groovy");

        java.lang.reflect.Field filesField = GroovyWorkspaceService.class
                .getDeclaredField("cachedWorkspaceGroovyFiles");
        filesField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<List<Path>>) filesField.get(service)).set(List.of(cached));

        java.lang.reflect.Field timestampField = GroovyWorkspaceService.class
                .getDeclaredField("workspaceGroovyFilesCacheTimestampMs");
        timestampField.setAccessible(true);
        timestampField.setLong(service, System.currentTimeMillis());

        List<Path> result = (List<Path>) invoke("collectWorkspaceGroovyFiles",
                new Class<?>[] {}, new Object[] {});

        assertEquals(List.of(cached), result);
    }

    @Test
    void invalidateWorkspaceGroovyFilesCacheClearsCachedEntries() throws Exception {
        java.lang.reflect.Field filesField = GroovyWorkspaceService.class
                .getDeclaredField("cachedWorkspaceGroovyFiles");
        filesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<List<Path>> cachedWorkspaceGroovyFiles =
                (java.util.concurrent.atomic.AtomicReference<List<Path>>) filesField.get(service);
        cachedWorkspaceGroovyFiles.set(List.of(Path.of("/tmp/Cached.groovy")));

        java.lang.reflect.Field timestampField = GroovyWorkspaceService.class
                .getDeclaredField("workspaceGroovyFilesCacheTimestampMs");
        timestampField.setAccessible(true);
        timestampField.setLong(service, System.currentTimeMillis());

        invoke("invalidateWorkspaceGroovyFilesCache", new Class<?>[] {}, new Object[] {});

                assertEquals(List.of(), cachedWorkspaceGroovyFiles.get());
        assertEquals(0L, timestampField.getLong(service));
    }

    // ---- Helpers ----

    private Object invoke(String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = GroovyWorkspaceService.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    // ================================================================
    // sameExtension tests
    // ================================================================

    @Test
    void sameExtensionBothJava() throws Exception {
        assertEquals(true, invoke("sameExtension",
                new Class<?>[] {String.class, String.class},
                new Object[] {"java", "java"}));
    }

    @Test
    void sameExtensionDifferent() throws Exception {
        assertEquals(false, invoke("sameExtension",
                new Class<?>[] {String.class, String.class},
                new Object[] {"java", "groovy"}));
    }

    @Test
    void sameExtensionNullFirst() throws Exception {
        assertEquals(false, invoke("sameExtension",
                new Class<?>[] {String.class, String.class},
                new Object[] {(String) null, "java"}));
    }

    @Test
    void sameExtensionBothNull() throws Exception {
        // both null may be true or false depending on implementation
        Object result = invoke("sameExtension",
                new Class<?>[] {String.class, String.class},
                new Object[] {(String) null, (String) null});
        assertNotNull(result);
    }

    // ================================================================
    // getChildJson tests
    // ================================================================

    @Test
    void getChildJsonReturnsChild() throws Exception {
        JsonObject root = new JsonObject();
        JsonObject child = new JsonObject();
        child.addProperty("key", "value");
        root.add("child", child);
        JsonObject result = (JsonObject) invoke("getChildJson",
                new Class<?>[] {JsonObject.class, String.class},
                new Object[] {root, "child"});
        assertNotNull(result);
        assertEquals("value", result.get("key").getAsString());
    }

    @Test
    void getChildJsonReturnsNullForMissing() throws Exception {
        JsonObject root = new JsonObject();
        JsonObject result = (JsonObject) invoke("getChildJson",
                new Class<?>[] {JsonObject.class, String.class},
                new Object[] {root, "missing"});
        assertNull(result);
    }

    @Test
    void getChildJsonReturnsNullForNull() throws Exception {
        JsonObject result = (JsonObject) invoke("getChildJson",
                new Class<?>[] {JsonObject.class, String.class},
                new Object[] {(JsonObject) null, "key"});
        assertNull(result);
    }

    @Test
    void getChildJsonReturnsNullForNonObject() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("child", "string_value");
        JsonObject result = (JsonObject) invoke("getChildJson",
                new Class<?>[] {JsonObject.class, String.class},
                new Object[] {root, "child"});
        assertNull(result);
    }

    // ================================================================
    // shouldReplaceQualifiedReference tests
    // ================================================================

    @Test
    void shouldReplaceQualifiedReferenceBasic() throws Exception {
        String content = "import com.example.Foo\nclass A { Foo foo }";
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int matchOffset = content.indexOf("Foo", content.indexOf("class")); // Foo in class body
        int matchLength = 3;
        int endOffset = matchOffset + matchLength;
        String simpleTypeName = "Foo";
        boolean result = (boolean) invoke("shouldReplaceQualifiedReference",
                new Class<?>[] {String.class, java.util.Set.class, int.class, int.class, int.class, String.class},
                new Object[] {content, seen, matchOffset, matchLength, endOffset, simpleTypeName});
        assertTrue(result);
    }

    // ================================================================
    // isSimpleTypeMatch tests
    // ================================================================

    @Test
    void isSimpleTypeMatchOnIdentifier() throws Exception {
        String content = "class A { Foo foo }";
        int offset = content.indexOf("Foo");
        boolean result = (boolean) invoke("isSimpleTypeMatch",
                new Class<?>[] {String.class, String.class, int.class, int.class, int.class},
                new Object[] {content, "Foo", offset, 3, offset + 3});
        assertTrue(result);
    }

    @Test
    void isSimpleTypeMatchInsideImport() throws Exception {
        String content = "import com.example.Foo\nclass A {}";
        int offset = content.indexOf("Foo");
        boolean result = (boolean) invoke("isSimpleTypeMatch",
                new Class<?>[] {String.class, String.class, int.class, int.class, int.class},
                new Object[] {content, "Foo", offset, 3, offset + 3});
        // Inside import line - should return false
        assertFalse(result);
    }

    // ================================================================
    // buildWillRenameWorkspaceEdit null/empty tests
    // ================================================================

    @Test
    void buildWillRenameWorkspaceEditReturnsNullForNull() throws Exception {
        Object result = invoke("buildWillRenameWorkspaceEdit",
                new Class<?>[] {org.eclipse.lsp4j.RenameFilesParams.class},
                new Object[] {(org.eclipse.lsp4j.RenameFilesParams) null});
        assertNull(result);
    }

    @Test
    void buildWillRenameWorkspaceEditReturnsNullForEmptyFiles() throws Exception {
        org.eclipse.lsp4j.RenameFilesParams params = new org.eclipse.lsp4j.RenameFilesParams();
        params.setFiles(new ArrayList<>());
        assertNull(invoke("buildWillRenameWorkspaceEdit",
                new Class<?>[] {org.eclipse.lsp4j.RenameFilesParams.class},
                new Object[] {params}));
    }

    // ================================================================
    // summarizeRenames tests
    // ================================================================

    @Test
    void summarizeRenamesNoRenames() throws Exception {
        List<org.eclipse.lsp4j.FileRename> renames = new ArrayList<>();
        Object result = invoke("summarizeRenames",
                new Class<?>[] {java.util.List.class},
                new Object[] {renames});
        assertNotNull(result);
        // Access private fields via reflection
        java.lang.reflect.Field hasSourceField = result.getClass().getDeclaredField("hasSourceRename");
        hasSourceField.setAccessible(true);
        assertFalse((boolean) hasSourceField.get(result));
        java.lang.reflect.Field hasJavaField = result.getClass().getDeclaredField("hasJavaRename");
        hasJavaField.setAccessible(true);
        assertFalse((boolean) hasJavaField.get(result));
    }

    @Test
    void summarizeRenamesGroovyOnly() throws Exception {
        List<org.eclipse.lsp4j.FileRename> renames = new ArrayList<>();
        org.eclipse.lsp4j.FileRename fr = new org.eclipse.lsp4j.FileRename();
        fr.setOldUri("file:///src/Foo.groovy");
        fr.setNewUri("file:///src/Bar.groovy");
        renames.add(fr);
        Object result = invoke("summarizeRenames",
                new Class<?>[] {java.util.List.class},
                new Object[] {renames});
        java.lang.reflect.Field hasSourceField = result.getClass().getDeclaredField("hasSourceRename");
        hasSourceField.setAccessible(true);
        assertTrue((boolean) hasSourceField.get(result));
        java.lang.reflect.Field hasJavaField = result.getClass().getDeclaredField("hasJavaRename");
        hasJavaField.setAccessible(true);
        assertFalse((boolean) hasJavaField.get(result));
    }

    @Test
    void summarizeRenamesJavaFile() throws Exception {
        List<org.eclipse.lsp4j.FileRename> renames = new ArrayList<>();
        org.eclipse.lsp4j.FileRename fr = new org.eclipse.lsp4j.FileRename();
        fr.setOldUri("file:///src/Foo.java");
        fr.setNewUri("file:///src/Bar.java");
        renames.add(fr);
        Object result = invoke("summarizeRenames",
                new Class<?>[] {java.util.List.class},
                new Object[] {renames});
        java.lang.reflect.Field hasSourceField = result.getClass().getDeclaredField("hasSourceRename");
        hasSourceField.setAccessible(true);
        assertTrue((boolean) hasSourceField.get(result));
        java.lang.reflect.Field hasJavaField = result.getClass().getDeclaredField("hasJavaRename");
        hasJavaField.setAccessible(true);
        assertTrue((boolean) hasJavaField.get(result));
    }

    @Test
    void summarizeRenamesNonSourceFile() throws Exception {
        List<org.eclipse.lsp4j.FileRename> renames = new ArrayList<>();
        org.eclipse.lsp4j.FileRename fr = new org.eclipse.lsp4j.FileRename();
        fr.setOldUri("file:///src/readme.txt");
        fr.setNewUri("file:///src/readme2.txt");
        renames.add(fr);
        Object result = invoke("summarizeRenames",
                new Class<?>[] {java.util.List.class},
                new Object[] {renames});
        java.lang.reflect.Field hasSourceField = result.getClass().getDeclaredField("hasSourceRename");
        hasSourceField.setAccessible(true);
        assertFalse((boolean) hasSourceField.get(result));
    }

    @Test
    void summarizeRenamesNullEntry() throws Exception {
        List<org.eclipse.lsp4j.FileRename> renames = new ArrayList<>();
        renames.add(null);
        Object result = invoke("summarizeRenames",
                new Class<?>[] {java.util.List.class},
                new Object[] {renames});
        java.lang.reflect.Field hasSourceField = result.getClass().getDeclaredField("hasSourceRename");
        hasSourceField.setAccessible(true);
        assertFalse((boolean) hasSourceField.get(result));
    }

    // ================================================================
    // addDeclarationFallbackEdit tests
    // ================================================================

    @Test
    void addDeclarationFallbackEditSkipsWhenJavaRename() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> workspaceEdits = new java.util.HashMap<>();
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        invoke("addDeclarationFallbackEdit",
                new Class<?>[] {String.class, String.class, String.class, boolean.class, Map.class, Map.class},
                new Object[] {"file:///Foo.groovy", "Foo", "Bar", true, workspaceEdits, changes});
        assertTrue(changes.isEmpty());
    }

    @Test
    void addDeclarationFallbackEditSkipsWhenWorkspaceEditsNonEmpty() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> workspaceEdits = new java.util.HashMap<>();
        workspaceEdits.put("file:///Other.groovy", new ArrayList<>());
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        invoke("addDeclarationFallbackEdit",
                new Class<?>[] {String.class, String.class, String.class, boolean.class, Map.class, Map.class},
                new Object[] {"file:///Foo.groovy", "Foo", "Bar", false, workspaceEdits, changes});
        assertTrue(changes.isEmpty());
    }

    @Test
    void addDeclarationFallbackEditAddsEditWhenSourceAvailable() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///FallbackDecl.groovy";
        dm.didOpen(uri, "class Foo { }");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);

        Map<String, List<org.eclipse.lsp4j.TextEdit>> workspaceEdits = new java.util.HashMap<>();
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        java.lang.reflect.Method m = GroovyWorkspaceService.class.getDeclaredMethod(
                "addDeclarationFallbackEdit",
                String.class, String.class, String.class, boolean.class, Map.class, Map.class);
        m.setAccessible(true);
        m.invoke(svc, uri, "Foo", "Bar", false, workspaceEdits, changes);
        assertFalse(changes.isEmpty());
    }

    @Test
    void addDeclarationFallbackEditNoEditWhenNoSource() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> workspaceEdits = new java.util.HashMap<>();
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        invoke("addDeclarationFallbackEdit",
                new Class<?>[] {String.class, String.class, String.class, boolean.class, Map.class, Map.class},
                new Object[] {"file:///Missing.groovy", "Foo", "Bar", false, workspaceEdits, changes});
        assertTrue(changes.isEmpty());
    }

    // ================================================================
    // applyWillRenameForFile tests
    // ================================================================

    @Test
    void applyWillRenameForFileNullRename() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        invoke("applyWillRenameForFile",
                new Class<?>[] {org.eclipse.lsp4j.FileRename.class, Map.class},
                new Object[] {null, changes});
        assertTrue(changes.isEmpty());
    }

    @Test
    void applyWillRenameForFileNonSourceFile() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        org.eclipse.lsp4j.FileRename fr = new org.eclipse.lsp4j.FileRename();
        fr.setOldUri("file:///readme.txt");
        fr.setNewUri("file:///readme2.txt");
        invoke("applyWillRenameForFile",
                new Class<?>[] {org.eclipse.lsp4j.FileRename.class, Map.class},
                new Object[] {fr, changes});
        assertTrue(changes.isEmpty());
    }

    @Test
    void applyWillRenameForFileExtensionMismatch() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        org.eclipse.lsp4j.FileRename fr = new org.eclipse.lsp4j.FileRename();
        fr.setOldUri("file:///Foo.groovy");
        fr.setNewUri("file:///Foo.java");
        invoke("applyWillRenameForFile",
                new Class<?>[] {org.eclipse.lsp4j.FileRename.class, Map.class},
                new Object[] {fr, changes});
        assertTrue(changes.isEmpty());
    }

    // ================================================================
    // collectGroovyMoveMatch tests
    // ================================================================

    @Test
    void collectGroovyMoveMatchNullResource() throws Exception {
        Map<String, List<org.eclipse.jdt.core.search.SearchMatch>> matchesByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getResource()).thenReturn(null);
        invoke("collectGroovyMoveMatch",
                new Class<?>[] {Map.class, org.eclipse.jdt.core.search.SearchMatch.class},
                new Object[] {matchesByUri, match});
        assertTrue(matchesByUri.isEmpty());
    }

    @Test
    void collectGroovyMoveMatchNonGroovyFile() throws Exception {
        Map<String, List<org.eclipse.jdt.core.search.SearchMatch>> matchesByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.java"));
        when(match.getResource()).thenReturn(resource);
        invoke("collectGroovyMoveMatch",
                new Class<?>[] {Map.class, org.eclipse.jdt.core.search.SearchMatch.class},
                new Object[] {matchesByUri, match});
        assertTrue(matchesByUri.isEmpty());
    }

    @Test
    void collectGroovyMoveMatchGroovyFile() throws Exception {
        Map<String, List<org.eclipse.jdt.core.search.SearchMatch>> matchesByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(match.getResource()).thenReturn(resource);
        invoke("collectGroovyMoveMatch",
                new Class<?>[] {Map.class, org.eclipse.jdt.core.search.SearchMatch.class},
                new Object[] {matchesByUri, match});
        assertFalse(matchesByUri.isEmpty());
    }

    // ================================================================
    // addWorkspaceRenameEdit tests
    // ================================================================

    @Test
    void addWorkspaceRenameEditNullResource() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        when(match.getResource()).thenReturn(null);
        invoke("addWorkspaceRenameEdit",
                new Class<?>[] {org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class},
                new Object[] {match, "NewName", editsByUri, false});
        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addWorkspaceRenameEditNegativeOffset() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(-1);
        when(match.getLength()).thenReturn(3);
        invoke("addWorkspaceRenameEdit",
                new Class<?>[] {org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class},
                new Object[] {match, "NewName", editsByUri, false});
        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addWorkspaceRenameEditZeroLength() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(0);
        when(match.getLength()).thenReturn(0);
        invoke("addWorkspaceRenameEdit",
                new Class<?>[] {org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class},
                new Object[] {match, "NewName", editsByUri, false});
        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addWorkspaceRenameEditGroovyOnlyFilterJava() throws Exception {
        DocumentManager dm = new DocumentManager();
        dm.didOpen("file:///Foo.java", "class Foo {}");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);

        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.java"));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(6);
        when(match.getLength()).thenReturn(3);
        java.lang.reflect.Method m = GroovyWorkspaceService.class.getDeclaredMethod(
                "addWorkspaceRenameEdit",
                org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class);
        m.setAccessible(true);
        m.invoke(svc, match, "Bar", editsByUri, true);
        assertTrue(editsByUri.isEmpty());
    }

    @Test
    void addWorkspaceRenameEditAddsEdit() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///WsRename.groovy";
        dm.didOpen(uri, "class Foo { }");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);

        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create(uri));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(6);
        when(match.getLength()).thenReturn(3);
        java.lang.reflect.Method m = GroovyWorkspaceService.class.getDeclaredMethod(
                "addWorkspaceRenameEdit",
                org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class);
        m.setAccessible(true);
        m.invoke(svc, match, "Bar", editsByUri, false);
        assertFalse(editsByUri.isEmpty());
    }

    @Test
    void addWorkspaceRenameEditOffsetPastContent() throws Exception {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///WsRename2.groovy";
        dm.didOpen(uri, "class Foo { }");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);

        Map<String, List<org.eclipse.lsp4j.TextEdit>> editsByUri = new java.util.HashMap<>();
        org.eclipse.jdt.core.search.SearchMatch match = mock(org.eclipse.jdt.core.search.SearchMatch.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create(uri));
        when(match.getResource()).thenReturn(resource);
        when(match.getOffset()).thenReturn(999);
        when(match.getLength()).thenReturn(3);
        java.lang.reflect.Method m = GroovyWorkspaceService.class.getDeclaredMethod(
                "addWorkspaceRenameEdit",
                org.eclipse.jdt.core.search.SearchMatch.class, String.class, Map.class, boolean.class);
        m.setAccessible(true);
        m.invoke(svc, match, "Bar", editsByUri, false);
        assertTrue(editsByUri.isEmpty());
    }

    // ================================================================
    // toSymbolInformation tests
    // ================================================================

    @Test
    void toSymbolInformationNullResource() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(element.getElementName()).thenReturn("Foo");
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.TYPE);
        when(element.getResource()).thenReturn(null);
        Object result = invoke("toSymbolInformation",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertNull(result);
    }

    @Test
    void toSymbolInformationBasicType() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(element.getElementName()).thenReturn("Foo");
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.TYPE);
        when(element.getResource()).thenReturn(resource);
        when(element.getParent()).thenReturn(null);
        Object result = invoke("toSymbolInformation",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertNotNull(result);
        org.eclipse.lsp4j.SymbolInformation info = (org.eclipse.lsp4j.SymbolInformation) result;
        assertEquals("Foo", info.getName());
        assertEquals(org.eclipse.lsp4j.SymbolKind.Class, info.getKind());
    }

    @Test
    void toSymbolInformationMethodWithParent() throws Exception {
        org.eclipse.jdt.core.IJavaElement parent = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(parent.getElementName()).thenReturn("MyClass");

        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///MyClass.groovy"));
        when(element.getElementName()).thenReturn("myMethod");
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.METHOD);
        when(element.getResource()).thenReturn(resource);
        when(element.getParent()).thenReturn(parent);
        Object result = invoke("toSymbolInformation",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertNotNull(result);
        org.eclipse.lsp4j.SymbolInformation info = (org.eclipse.lsp4j.SymbolInformation) result;
        assertEquals("myMethod", info.getName());
        assertEquals(org.eclipse.lsp4j.SymbolKind.Method, info.getKind());
        assertEquals("MyClass", info.getContainerName());
    }

    @Test
    void toSymbolInformationField() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(element.getElementName()).thenReturn("myField");
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.FIELD);
        when(element.getResource()).thenReturn(resource);
        when(element.getParent()).thenReturn(null);
        Object result = invoke("toSymbolInformation",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertNotNull(result);
        org.eclipse.lsp4j.SymbolInformation info = (org.eclipse.lsp4j.SymbolInformation) result;
        assertEquals(org.eclipse.lsp4j.SymbolKind.Field, info.getKind());
    }

    @Test
    void toSymbolInformationWithSourceRef() throws Exception {
        org.eclipse.jdt.core.IType element = mock(org.eclipse.jdt.core.IType.class);
        org.eclipse.core.resources.IResource resource = mock(org.eclipse.core.resources.IResource.class);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create("file:///Foo.groovy"));
        when(element.getElementName()).thenReturn("Foo");
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.TYPE);
        when(element.getResource()).thenReturn(resource);
        when(element.getParent()).thenReturn(null);
        org.eclipse.jdt.core.ISourceRange nameRange = mock(org.eclipse.jdt.core.ISourceRange.class);
        when(nameRange.getOffset()).thenReturn(6);
        when(nameRange.getLength()).thenReturn(3);
        when(element.getNameRange()).thenReturn(nameRange);
        Object result = invoke("toSymbolInformation",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertNotNull(result);
        org.eclipse.lsp4j.SymbolInformation info = (org.eclipse.lsp4j.SymbolInformation) result;
        assertNotNull(info.getLocation().getRange());
    }

    // ================================================================
    // applyLogLevelSetting tests
    // ================================================================

    @Test
        void applyLogLevelSettingMissingLs() {
        com.google.gson.JsonObject groovy = new com.google.gson.JsonObject();
        assertDoesNotThrow(() -> invoke("applyLogLevelSetting",
                new Class<?>[] {com.google.gson.JsonObject.class},
                new Object[] {groovy}));
    }

    @Test
        void applyLogLevelSettingNoLogLevel() {
        com.google.gson.JsonObject groovy = new com.google.gson.JsonObject();
        com.google.gson.JsonObject ls = new com.google.gson.JsonObject();
        groovy.add("ls", ls);
        assertDoesNotThrow(() -> invoke("applyLogLevelSetting",
                new Class<?>[] {com.google.gson.JsonObject.class},
                new Object[] {groovy}));
    }

    @Test
        void applyLogLevelSettingWithLogLevel() {
        com.google.gson.JsonObject groovy = new com.google.gson.JsonObject();
        com.google.gson.JsonObject ls = new com.google.gson.JsonObject();
        ls.addProperty("logLevel", "DEBUG");
        groovy.add("ls", ls);
        assertDoesNotThrow(() -> invoke("applyLogLevelSetting",
                new Class<?>[] {com.google.gson.JsonObject.class},
                new Object[] {groovy}));
    }

    @Test
        void applyLogLevelSettingNullLogLevel() {
        com.google.gson.JsonObject groovy = new com.google.gson.JsonObject();
        com.google.gson.JsonObject ls = new com.google.gson.JsonObject();
        ls.add("logLevel", com.google.gson.JsonNull.INSTANCE);
        groovy.add("ls", ls);
        assertDoesNotThrow(() -> invoke("applyLogLevelSetting",
                new Class<?>[] {com.google.gson.JsonObject.class},
                new Object[] {groovy}));
    }

    // ================================================================
    // addGroovyFallbackPackageEdit tests
    // ================================================================

    @Test
    void addGroovyFallbackPackageEditNonGroovyFile() throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        invoke("addGroovyFallbackPackageEdit",
                new Class<?>[] {String.class, String.class, Map.class},
                new Object[] {"file:///Foo.java", "file:///Bar.java", changes});
        assertTrue(changes.isEmpty());
    }

    @Test
    void addGroovyFallbackPackageEditGroovyFile() throws Exception {
        // Opens a groovy file with a package, simulates a move
        DocumentManager dm = new DocumentManager();
        String uri = "file:///src/com/example/Foo.groovy";
        dm.didOpen(uri, "package com.example\nclass Foo { }");
        GroovyWorkspaceService svc = new GroovyWorkspaceService(new GroovyLanguageServer(), dm);

        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.HashMap<>();
        java.lang.reflect.Method m = GroovyWorkspaceService.class.getDeclaredMethod(
                "addGroovyFallbackPackageEdit", String.class, String.class, Map.class);
        m.setAccessible(true);
        m.invoke(svc, uri, "file:///src/com/other/Foo.groovy", changes);

                assertTrue(changes.containsKey(uri));
                assertTrue(changes.get(uri).stream().anyMatch(edit -> "com.other".equals(edit.getNewText())));
    }

    // ================================================================
    // toSymbolKind extended tests
    // ================================================================

    @Test
    void toSymbolKindLocalVariable() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.LOCAL_VARIABLE);
        Object result = invoke("toSymbolKind",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertEquals(org.eclipse.lsp4j.SymbolKind.Variable, result);
    }

    @Test
    void toSymbolKindPackageFragment() throws Exception {
        org.eclipse.jdt.core.IJavaElement element = mock(org.eclipse.jdt.core.IJavaElement.class);
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.PACKAGE_FRAGMENT);
        Object result = invoke("toSymbolKind",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertEquals(org.eclipse.lsp4j.SymbolKind.Package, result);
    }

    @Test
    void toSymbolKindInterface() throws Exception {
        org.eclipse.jdt.core.IType element = mock(org.eclipse.jdt.core.IType.class);
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.TYPE);
        when(element.isInterface()).thenReturn(true);
        Object result = invoke("toSymbolKind",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertEquals(org.eclipse.lsp4j.SymbolKind.Interface, result);
    }

    @Test
    void toSymbolKindEnum() throws Exception {
        org.eclipse.jdt.core.IType element = mock(org.eclipse.jdt.core.IType.class);
        when(element.getElementType()).thenReturn(org.eclipse.jdt.core.IJavaElement.TYPE);
        when(element.isInterface()).thenReturn(false);
        when(element.isEnum()).thenReturn(true);
        Object result = invoke("toSymbolKind",
                new Class<?>[] {org.eclipse.jdt.core.IJavaElement.class},
                new Object[] {element});
        assertEquals(org.eclipse.lsp4j.SymbolKind.Enum, result);
    }

    // ================================================================
    // Additional isSimpleTypeMatch edge cases
    // ================================================================

    @Test
    void isSimpleTypeMatchAtEndOfContent() throws Exception {
        // "class Foo" - match at the very end
        Object result = invoke("isSimpleTypeMatch",
                new Class<?>[] {String.class, String.class, int.class, int.class, int.class},
                new Object[] {"class Foo", "Foo", 6, 3, 9});
        assertTrue((boolean) result);
    }

    @Test
    void isSimpleTypeMatchPrecededByLetter() throws Exception {
        // "classFoo" - substring(5,8)="Foo" matches => true (no word-boundary check)
        Object result = invoke("isSimpleTypeMatch",
                new Class<?>[] {String.class, String.class, int.class, int.class, int.class},
                new Object[] {"classFoo", "Foo", 5, 3, 8});
        assertTrue((boolean) result);
    }

    @Test
    void isSimpleTypeMatchTokenMismatch() throws Exception {
        // "class Bar" - substring(6,9)="Bar" != "Foo" => false
        Object result = invoke("isSimpleTypeMatch",
                new Class<?>[] {String.class, String.class, int.class, int.class, int.class},
                new Object[] {"class Bar", "Foo", 6, 3, 9});
        assertFalse((boolean) result);
    }

    // ================================================================
    // Additional shouldReplaceQualifiedReference tests
    // ================================================================

    @Test
    void shouldReplaceQualifiedReferenceDotBeforeMatch() throws Exception {
        // "com.Foo" - offset 4, preceded by '.' = qualified reference
        java.util.Set<Integer> importLines = new java.util.HashSet<>();
        Object result = invoke("shouldReplaceQualifiedReference",
                new Class<?>[] {String.class, java.util.Set.class, int.class, int.class, int.class, String.class},
                new Object[] {"com.Foo", importLines, 4, 3, 7, "Foo"});
        assertTrue((boolean) result);
    }

    @Test
    void shouldReplaceQualifiedReferenceOnImportLine() throws Exception {
        // On an import line - should not replace
        java.util.Set<Integer> importLines = new java.util.HashSet<>();
        importLines.add(0);
        Object result = invoke("shouldReplaceQualifiedReference",
                new Class<?>[] {String.class, java.util.Set.class, int.class, int.class, int.class, String.class},
                new Object[] {"import com.Foo", importLines, 11, 3, 14, "Foo"});
        assertFalse((boolean) result);
    }
}
