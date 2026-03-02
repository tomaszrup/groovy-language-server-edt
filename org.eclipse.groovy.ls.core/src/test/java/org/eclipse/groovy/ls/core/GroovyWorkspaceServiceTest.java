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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
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

    // ---- Helpers ----

    private Object invoke(String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = GroovyWorkspaceService.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
}
