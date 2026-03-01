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

import java.lang.reflect.Method;
import java.nio.file.Path;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GroovyWorkspaceService} text-manipulation helpers
 * related to rename/move operations: import edits, package declaration
 * edits, type declaration renames, URI parsing, and offset conversion.
 */
class GroovyWorkspaceServiceRenameHelpersTest {

    // ---- createImportReplaceEdit ----

    @Test
    void createImportReplaceEditReplacesOldFqnWithNew() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nimport com.old.MyClass\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportReplaceEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { content, "com.old.MyClass", "com.newpkg.MyClass" });

        assertNotNull(edit);
        assertEquals("com.newpkg.MyClass", edit.getNewText());
        // The edit should target line 2 (0-based) where the import is
        assertEquals(2, edit.getRange().getStart().getLine());
    }

    @Test
    void createImportReplaceEditReturnsNullWhenImportNotFound() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportReplaceEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { content, "com.old.Missing", "com.new.Missing" });

        assertNull(edit);
    }

    // ---- createImportRemovalEdit ----

    @Test
    void createImportRemovalEditRemovesEntireLine() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nimport com.old.Unused\nimport com.old.Used\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportRemovalEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { content, "com.old.Unused" });

        assertNotNull(edit);
        assertEquals("", edit.getNewText());
        // Should remove exactly the import line (one line)
        assertTrue(edit.getRange().getEnd().getLine() > edit.getRange().getStart().getLine()
                || edit.getRange().getEnd().getCharacter() > edit.getRange().getStart().getCharacter(),
                "Removal range must be non-empty");
    }

    @Test
    void createImportRemovalEditReturnsNullWhenImportNotPresent() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "class A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportRemovalEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { content, "com.nonexistent.Type" });

        assertNull(edit);
    }

    // ---- createImportInsertEdit ----

    @Test
    void createImportInsertEditInsertsAfterLastImport() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nimport java.util.List\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportInsertEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { content, "java.util.Map" });

        assertNotNull(edit);
        assertEquals("import java.util.Map\n", edit.getNewText());
        // Insert after the last import line (line 2), so at line 3
        assertEquals(3, edit.getRange().getStart().getLine());
    }

    @Test
    void createImportInsertEditInsertsAfterPackageWhenNoImports() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "createImportInsertEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { content, "java.util.List" });

        assertNotNull(edit);
        assertEquals("import java.util.List\n", edit.getNewText());
        // package is line 0, insert at line 2 (package + 2)
        assertEquals(2, edit.getRange().getStart().getLine());
    }

    // ---- findTypeDeclarationRenameEdit ----

    @Test
    void findTypeDeclarationRenameEditRenamesClass() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "package foo\n\nclass OldName {\n  String field\n}\n";

        TextEdit edit = (TextEdit) invoke(service, "findTypeDeclarationRenameEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { source, "OldName", "NewName" });

        assertNotNull(edit);
        assertEquals("NewName", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditRenamesInterface() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "interface OldIface {\n  void doIt()\n}\n";

        TextEdit edit = (TextEdit) invoke(service, "findTypeDeclarationRenameEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { source, "OldIface", "NewIface" });

        assertNotNull(edit);
        assertEquals("NewIface", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditRenamesTrait() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "trait OldTrait {\n  String name\n}\n";

        TextEdit edit = (TextEdit) invoke(service, "findTypeDeclarationRenameEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { source, "OldTrait", "NewTrait" });

        assertNotNull(edit);
        assertEquals("NewTrait", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditRenamesEnum() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "enum OldEnum { A, B, C }\n";

        TextEdit edit = (TextEdit) invoke(service, "findTypeDeclarationRenameEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { source, "OldEnum", "NewEnum" });

        assertNotNull(edit);
        assertEquals("NewEnum", edit.getNewText());
    }

    @Test
    void findTypeDeclarationRenameEditReturnsNullIfNotFound() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "class Something {}\n";

        TextEdit edit = (TextEdit) invoke(service, "findTypeDeclarationRenameEdit",
                new Class<?>[] { String.class, String.class, String.class },
                new Object[] { source, "Missing", "Replacement" });

        assertNull(edit);
    }

    // ---- offsetToPosition ----

    @Test
    void offsetToPositionFirstLine() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "hello world\n";

        Position pos = (Position) invoke(service, "offsetToPosition",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 5 });

        assertEquals(0, pos.getLine());
        assertEquals(5, pos.getCharacter());
    }

    @Test
    void offsetToPositionSecondLine() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "abc\ndef\n";

        Position pos = (Position) invoke(service, "offsetToPosition",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 5 });

        assertEquals(1, pos.getLine());
        assertEquals(1, pos.getCharacter());
    }

    @Test
    void offsetToPositionAtNewline() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "abc\ndef\n";

        Position pos = (Position) invoke(service, "offsetToPosition",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 3 });

        // offset 3 is '\n' char, still on line 0
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    @Test
    void offsetToPositionClampsBeyondEnd() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "abc";

        Position pos = (Position) invoke(service, "offsetToPosition",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 999 });

        // Should clamp to end of content
        assertEquals(0, pos.getLine());
        assertEquals(3, pos.getCharacter());
    }

    // ---- isSourceFileUri / isGroovyFileUri / isJavaFileUri ----

    @Test
    void isSourceFileUriRecognizesGroovyAndJava() throws Exception {
        GroovyWorkspaceService service = createService();

        assertTrue((boolean) invoke(service, "isSourceFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/A.groovy" }));
        assertTrue((boolean) invoke(service, "isSourceFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/B.java" }));
        assertFalse((boolean) invoke(service, "isSourceFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/C.txt" }));
    }

    @Test
    void isGroovyFileUriMatchesGroovyExtension() throws Exception {
        GroovyWorkspaceService service = createService();

        assertTrue((boolean) invoke(service, "isGroovyFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/A.groovy" }));
        assertFalse((boolean) invoke(service, "isGroovyFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/B.java" }));
    }

    @Test
    void isJavaFileUriMatchesJavaExtension() throws Exception {
        GroovyWorkspaceService service = createService();

        assertTrue((boolean) invoke(service, "isJavaFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/B.java" }));
        assertFalse((boolean) invoke(service, "isJavaFileUri",
                new Class<?>[] { String.class }, new Object[] { "file:///test/A.groovy" }));
    }

    // ---- baseNameFromUri / fileNameFromUri / fileExtensionFromUri ----

    @Test
    void baseNameFromUriExtractsNameWithoutExtension() throws Exception {
        GroovyWorkspaceService service = createService();

        assertEquals("MyClass", invoke(service, "baseNameFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/MyClass.groovy" }));
        assertNull(invoke(service, "baseNameFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/.hidden" }));
    }

    @Test
    void fileNameFromUriExtractsFileName() throws Exception {
        GroovyWorkspaceService service = createService();

        assertEquals("MyClass.groovy", invoke(service, "fileNameFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/MyClass.groovy" }));
    }

    @Test
    void fileExtensionFromUriExtractsExtension() throws Exception {
        GroovyWorkspaceService service = createService();

        assertEquals("groovy", invoke(service, "fileExtensionFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/A.groovy" }));
        assertEquals("java", invoke(service, "fileExtensionFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/B.java" }));
        assertNull(invoke(service, "fileExtensionFromUri",
                new Class<?>[] { String.class }, new Object[] { "file:///src/noext" }));
    }

    // ---- packageFromQualifiedName ----

    @Test
    void packageFromQualifiedNameExtractsPackage() throws Exception {
        GroovyWorkspaceService service = createService();

        assertEquals("com.example", invoke(service, "packageFromQualifiedName",
                new Class<?>[] { String.class }, new Object[] { "com.example.MyClass" }));
        assertEquals("", invoke(service, "packageFromQualifiedName",
                new Class<?>[] { String.class }, new Object[] { "MyClass" }));
        assertEquals("", invoke(service, "packageFromQualifiedName",
                new Class<?>[] { String.class }, new Object[] { "" }));
        assertEquals("", invoke(service, "packageFromQualifiedName",
                new Class<?>[] { String.class }, new Object[] { (String) null }));
    }

    // ---- packageNameForPath ----

    @Test
    void packageNameForPathConvertsPathToPackage() throws Exception {
        GroovyWorkspaceService service = createService();

        assertEquals("com.example.demo", invoke(service, "packageNameForPath",
                new Class<?>[] { Path.class }, new Object[] { Path.of("com", "example", "demo") }));
        assertEquals("", invoke(service, "packageNameForPath",
                new Class<?>[] { Path.class }, new Object[] { Path.of("") }));
    }

    // ---- isInsideImportLine ----

    @Test
    void isInsideImportLineReturnsTrueOnImportStatement() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nimport java.util.List\n\nclass A {}\n";

        // Offset inside "import java.util.List" (line starts at offset 13)
        boolean result = (boolean) invoke(service, "isInsideImportLine",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 20 });

        assertTrue(result);
    }

    @Test
    void isInsideImportLineReturnsFalseOnClassDeclaration() throws Exception {
        GroovyWorkspaceService service = createService();
        String content = "package foo\n\nimport java.util.List\n\nclass A {}\n";

        // Offset inside "class A {}" (offset ~35)
        boolean result = (boolean) invoke(service, "isInsideImportLine",
                new Class<?>[] { String.class, int.class },
                new Object[] { content, 37 });

        assertFalse(result);
    }

    // ---- findGroovyPackageDeclarationMoveEdit (additional scenarios) ----

    @Test
    void findGroovyPackageDeclarationMoveEditRemovesPackageWhenNewPackageIsBlank() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "package old.pkg\n\nclass A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { source, "" });

        assertNotNull(edit);
        assertEquals("", edit.getNewText());
    }

    @Test
    void findGroovyPackageDeclarationMoveEditReturnsNullForNullSource() throws Exception {
        GroovyWorkspaceService service = createService();

        TextEdit edit = (TextEdit) invoke(service, "findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { null, "new.pkg" });

        assertNull(edit);
    }

    @Test
    void findGroovyPackageDeclarationMoveEditReturnsNullWhenNoPackageAndNewPackageBlank() throws Exception {
        GroovyWorkspaceService service = createService();
        String source = "class A {}\n";

        TextEdit edit = (TextEdit) invoke(service, "findGroovyPackageDeclarationMoveEdit",
                new Class<?>[] { String.class, String.class },
                new Object[] { source, "" });

        assertNull(edit);
    }

    // ---- mergeEdits ----

    @Test
    void mergeEditsCombinesTwoMaps() throws Exception {
        GroovyWorkspaceService service = createService();

        java.util.Map<String, java.util.List<TextEdit>> target = new java.util.HashMap<>();
        target.put("file:///a.groovy", new java.util.ArrayList<>(java.util.List.of(
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 1)), "x"))));

        java.util.Map<String, java.util.List<TextEdit>> source = new java.util.HashMap<>();
        source.put("file:///a.groovy", new java.util.ArrayList<>(java.util.List.of(
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(1, 0), new Position(1, 1)), "y"))));
        source.put("file:///b.groovy", new java.util.ArrayList<>(java.util.List.of(
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 1)), "z"))));

        invoke(service, "mergeEdits",
                new Class<?>[] { java.util.Map.class, java.util.Map.class },
                new Object[] { target, source });

        assertEquals(2, target.size());
        assertEquals(2, target.get("file:///a.groovy").size());
        assertEquals(1, target.get("file:///b.groovy").size());
    }

    // ---- countTextEdits ----

    @Test
    void countTextEditsReturnsTotalCount() throws Exception {
        GroovyWorkspaceService service = createService();

        java.util.Map<String, java.util.List<TextEdit>> map = new java.util.HashMap<>();
        map.put("file:///a.groovy", java.util.List.of(
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 1)), "x"),
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(1, 0), new Position(1, 1)), "y")));
        map.put("file:///b.groovy", java.util.List.of(
                new TextEdit(new org.eclipse.lsp4j.Range(new Position(0, 0), new Position(0, 1)), "z")));

        int count = (int) invoke(service, "countTextEdits",
                new Class<?>[] { java.util.Map.class },
                new Object[] { map });

        assertEquals(3, count);
    }

    // ---- Helpers ----

    private GroovyWorkspaceService createService() {
        return new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
