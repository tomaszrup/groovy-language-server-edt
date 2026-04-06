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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroovyWorkspaceServiceFallbackRenameOrchestrationTest {

    @TempDir
    Path tempDir;

    @Test
    void applyGroovyOnlyFallbackEditsRenamesMovedTypeAndUpdatesWorkspaceReferences() throws Exception {
        Path oldPath = writeGroovyFile("src/main/groovy/com/example/foo/MyClass.groovy", """
                package com.example.foo

                class MyClass {
                }
                """);
        Path consumerPath = writeGroovyFile("src/main/groovy/com/example/other/Consumer.groovy", """
            package com.example.other

            import com.example.foo.MyClass

                class Consumer {
                    MyClass value
                }
                """);

        String oldUri = DocumentManager.normalizeUri(oldPath.toUri().toString());
        String consumerUri = DocumentManager.normalizeUri(consumerPath.toUri().toString());
        String newUri = DocumentManager.normalizeUri(
                tempDir.resolve("src/main/groovy/com/example/bar/RenamedThing.groovy").toUri().toString());

        GroovyWorkspaceService service = new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
        seedWorkspaceGroovyFilesCache(service, List.of(oldPath, consumerPath));

        Map<String, List<TextEdit>> changes = new HashMap<>();
        invoke(service, "applyGroovyOnlyFallbackEdits",
                new Class<?>[] { String.class, String.class, String.class, String.class, Map.class },
                new Object[] { oldUri, newUri, "MyClass", "RenamedThing", changes });

        List<TextEdit> movedFileEdits = changes.get(oldUri);
        assertNotNull(movedFileEdits);
        assertTrue(movedFileEdits.stream().anyMatch(edit -> "RenamedThing".equals(edit.getNewText())));
        List<TextEdit> consumerEdits = changes.get(consumerUri);
        assertNotNull(consumerEdits);
        assertTrue(consumerEdits.stream().anyMatch(edit -> edit.getNewText().contains("com.example.bar.RenamedThing")));
        assertTrue(consumerEdits.stream().anyMatch(edit -> "RenamedThing".equals(edit.getNewText())));
    }

    @Test
    void applyGroovyOnlyFallbackEditsSkipsWhenTypeAndPackageDoNotChange() throws Exception {
        Path oldPath = writeGroovyFile("src/main/groovy/com/example/foo/MyClass.groovy", """
                package com.example.foo

                class MyClass {
                }
                """);

        String oldUri = DocumentManager.normalizeUri(oldPath.toUri().toString());
        GroovyWorkspaceService service = new GroovyWorkspaceService(new GroovyLanguageServer(), new DocumentManager());
        seedWorkspaceGroovyFilesCache(service, List.of(oldPath));

        Map<String, List<TextEdit>> changes = new HashMap<>();
        invoke(service, "applyGroovyOnlyFallbackEdits",
                new Class<?>[] { String.class, String.class, String.class, String.class, Map.class },
                new Object[] { oldUri, oldUri, "MyClass", "MyClass", changes });

        assertEquals(Map.of(), changes);
    }

    private Path writeGroovyFile(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    @SuppressWarnings("unchecked")
    private void seedWorkspaceGroovyFilesCache(GroovyWorkspaceService service, List<Path> paths) throws Exception {
        Field cacheField = GroovyWorkspaceService.class.getDeclaredField("cachedWorkspaceGroovyFiles");
        cacheField.setAccessible(true);
        ((AtomicReference<List<Path>>) cacheField.get(service)).set(List.copyOf(paths));

        Field timestampField = GroovyWorkspaceService.class.getDeclaredField("workspaceGroovyFilesCacheTimestampMs");
        timestampField.setAccessible(true);
        timestampField.setLong(service, System.currentTimeMillis());
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}