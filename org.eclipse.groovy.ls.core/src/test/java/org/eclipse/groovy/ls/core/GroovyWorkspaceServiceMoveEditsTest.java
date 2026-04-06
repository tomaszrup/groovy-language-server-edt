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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroovyWorkspaceServiceMoveEditsTest {

    @TempDir
    Path tempDir;

    @Test
    void buildGroovyFileMoveEditsRewritesImportAndQualifiedReferenceInOtherFile() throws Exception {
        GroovyWorkspaceService service = createService();
        Object moveContext = createMoveContext(
                "file:///workspace/src/oldpkg/Foo.groovy",
                "file:///workspace/src/newpkg/Foo.groovy",
                "oldpkg",
                "newpkg",
                "oldpkg.Foo",
                "newpkg.Foo");

        String content = "package consumer\n\n"
                + "import oldpkg.Foo\n\n"
                + "class Use {\n"
                + "  oldpkg.Foo qualified\n"
                + "  Foo simple\n"
                + "}\n";
        String uri = "file:///workspace/src/consumer/Use.groovy";

        List<TextEdit> edits = invokePrivate(service, "buildGroovyFileMoveEdits",
                new Class<?>[] { String.class, String.class, moveContext.getClass(), List.class },
                new Object[] { uri, content, moveContext, List.of(
                        matchAt(content, "import oldpkg.Foo", "oldpkg.Foo"),
                        matchAt(content, "oldpkg.Foo qualified", "oldpkg.Foo"),
                        matchAt(content, "Foo simple", "Foo")) });

        assertEquals(2, edits.size());
        assertEquals(2, edits.stream().filter(edit -> "newpkg.Foo".equals(edit.getNewText())).count());
    }

    @Test
    void buildMoveEditsFromMatchesReadsFileAndAddsImportForMovedType() throws Exception {
        GroovyWorkspaceService service = createService();
        Path consumerFile = tempDir.resolve("Use.groovy");
        String content = "package consumer\n\n"
                + "class Use {\n"
                + "  Foo simple\n"
                + "}\n";
        Files.writeString(consumerFile, content);

        String uri = DocumentManager.normalizeUri(consumerFile.toUri().toString());
        Object moveContext = createMoveContext(
                "file:///workspace/src/consumer/Foo.groovy",
                "file:///workspace/src/moved/Foo.groovy",
                "consumer",
                "moved",
                "consumer.Foo",
                "moved.Foo");

        Map<String, List<SearchMatch>> matchesByUri = new HashMap<>();
        matchesByUri.put(uri, List.of(matchAt(content, "Foo simple", "Foo")));

        Map<String, List<TextEdit>> edits = invokePrivate(service, "buildMoveEditsFromMatches",
                new Class<?>[] { Map.class, moveContext.getClass() },
                new Object[] { matchesByUri, moveContext });

        assertEquals(1, edits.size());
        assertTrue(edits.containsKey(uri));
        assertEquals("import moved.Foo\n", edits.get(uri).get(0).getNewText());
    }

    @Test
    void buildGroovyFileMoveEditsSkipsImportRewriteForMovedFileItself() throws Exception {
        GroovyWorkspaceService service = createService();
        String uri = "file:///workspace/src/oldpkg/Foo.groovy";
        Object moveContext = createMoveContext(
                uri,
                "file:///workspace/src/newpkg/Foo.groovy",
                "oldpkg",
                "newpkg",
                "oldpkg.Foo",
                "newpkg.Foo");

        String content = "package oldpkg\n\nclass Foo {\n  oldpkg.Foo other\n}\n";

        List<TextEdit> edits = invokePrivate(service, "buildGroovyFileMoveEdits",
                new Class<?>[] { String.class, String.class, moveContext.getClass(), List.class },
                new Object[] { uri, content, moveContext, List.of(matchAt(content, "oldpkg.Foo other", "oldpkg.Foo")) });

        assertEquals(1, edits.size());
        assertEquals("newpkg.Foo", edits.get(0).getNewText());
    }

    @Test
    void addPackageMoveEditsAddsPackageDeclarationEditForMovedGroovyFile() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String oldUri = "file:///workspace/src/oldpkg/Foo.groovy";
        documentManager.didOpen(oldUri, "package oldpkg\n\nclass Foo {}\n");

        GroovyWorkspaceService service = new GroovyWorkspaceService(new GroovyLanguageServer(), documentManager);
        Object moveContext = createMoveContext(
                oldUri,
                "file:///workspace/src/newpkg/Foo.groovy",
                "oldpkg",
                "newpkg",
                "oldpkg.Foo",
                "newpkg.Foo");

        Map<String, List<TextEdit>> changes = new HashMap<>();
        invokePrivate(service, "addPackageMoveEdits",
                new Class<?>[] { moveContext.getClass(), Map.class },
                new Object[] { moveContext, changes });

        assertEquals(1, changes.size());
        assertTrue(changes.containsKey(oldUri));
        assertEquals(1, changes.get(oldUri).size());
        assertEquals("newpkg", changes.get(oldUri).get(0).getNewText());
    }

    @Test
    void applyResolvedMoveContextEditsAddsDeclarationFallbackWhenRenameHasNoWorkspaceEdits() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String oldUri = "file:///workspace/src/pkg/Foo.groovy";
        documentManager.didOpen(oldUri, "package pkg\n\nclass Foo {}\n");

        GroovyWorkspaceService service = new GroovyWorkspaceService(new GroovyLanguageServer(), documentManager);
        Object moveContext = createMoveContext(
                oldUri,
                "file:///workspace/src/pkg/Bar.groovy",
                "pkg",
                "pkg",
                "pkg.Foo",
                "pkg.Bar");

        Map<String, List<TextEdit>> changes = new HashMap<>();
        invokePrivate(service, "applyResolvedMoveContextEdits",
                new Class<?>[] { moveContext.getClass(), String.class, String.class, String.class, Map.class },
                new Object[] { moveContext, "groovy", "Foo", "Bar", changes });

        assertEquals(1, changes.size());
        assertTrue(changes.containsKey(oldUri));
        assertEquals(1, changes.get(oldUri).size());
        assertEquals("Bar", changes.get(oldUri).get(0).getNewText());
    }

    @Test
    void applyResolvedMoveContextEditsReturnsWithoutChangesWhenTypeAndPackageStaySame() throws Exception {
        GroovyWorkspaceService service = createService();
        Object moveContext = createMoveContext(
                "file:///workspace/src/pkg/Foo.groovy",
                "file:///workspace/src/pkg/Foo.groovy",
                "pkg",
                "pkg",
                "pkg.Foo",
                "pkg.Foo");

        Map<String, List<TextEdit>> changes = new HashMap<>();
        invokePrivate(service, "applyResolvedMoveContextEdits",
                new Class<?>[] { moveContext.getClass(), String.class, String.class, String.class, Map.class },
                new Object[] { moveContext, "groovy", "Foo", "Foo", changes });

        assertFalse(changes.containsKey("file:///workspace/src/pkg/Foo.groovy"));
        assertTrue(changes.isEmpty());
    }

    private GroovyWorkspaceService createService() {
        return new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
    }

    private SearchMatch matchAt(String content, String anchor, String token) {
        SearchMatch match = mock(SearchMatch.class);
        int anchorOffset = content.indexOf(anchor);
        int tokenOffset = content.indexOf(token, anchorOffset);
        when(match.getOffset()).thenReturn(tokenOffset);
        when(match.getLength()).thenReturn(token.length());
        return match;
    }

    private Object createMoveContext(
            String oldUri,
            String newUri,
            String oldPackage,
            String newPackage,
            String oldQualifiedName,
            String newQualifiedName) throws Exception {
        Class<?> contextClass = Class.forName("org.eclipse.groovy.ls.core.GroovyWorkspaceService$TypeMoveContext");
        Constructor<?> constructor = contextClass.getDeclaredConstructor(
                String.class,
                String.class,
                IType.class,
                String.class,
                String.class,
                String.class,
                String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                oldUri,
                newUri,
                mock(IType.class),
                oldPackage,
                newPackage,
                oldQualifiedName,
                newQualifiedName);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }
}