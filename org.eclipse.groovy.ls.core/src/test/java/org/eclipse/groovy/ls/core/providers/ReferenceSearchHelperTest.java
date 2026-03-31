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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceSearchHelperTest {

    @BeforeEach
    void clearReferenceCaches() {
        ReferenceSearchHelper.clearCaches();
    }

    @Test
    void hasReferencesFallsBackToGroovyProjectTextSearch() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """);

        try {
            assertTrue(ReferenceSearchHelper.hasReferences(
                    fixture.method, fixture.declarationUri, fixture.documentManager));
        } finally {
            fixture.close();
        }
    }

    @Test
    void referenceExistenceForUnusedDeclarationFallsBackToGroovyProjectTextSearch() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """);

        try {
            ReferenceSearchHelper.ReferenceExistence result =
                    ReferenceSearchHelper.referenceExistenceForUnusedDeclaration(
                            fixture.method, fixture.declarationUri, fixture.documentManager);

            assertEquals(ReferenceSearchHelper.ReferenceExistence.FOUND, result);
            verify(fixture.rootResource, times(1)).accept(any(IResourceVisitor.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void referenceExistenceForUnusedDeclarationReturnsIndeterminateWhenFileCapExceeded() throws Exception {
        String symbolName = "rareSymbol";
        String fixtureId = Long.toHexString(System.nanoTime());
        DocumentManager documentManager = new DocumentManager();
        String declarationUri = DocumentManager.normalizeUri(
                "file:///c:/workspace/project-" + fixtureId + "/src/test/groovy/DeclSpec.groovy");
        String declarationContent = "class DeclSpec {\n    void rareSymbol() {}\n}\n";
        documentManager.didOpen(declarationUri, declarationContent);

        IFile declarationFile = mockGroovyFile(declarationUri);

        // Create enough filler files (with content that does NOT contain the symbol)
        // to exceed the text-fallback existence cap.
        int fillerCount = ReferenceSearchHelper.MAX_TEXT_FALLBACK_FILES_FOR_EXISTENCE + 5;
        String fillerContent = "class Filler { void other() {} }\n";
        List<IFile> fillerFiles = new java.util.ArrayList<>();
        for (int i = 0; i < fillerCount; i++) {
            String fillerUri = DocumentManager.normalizeUri(
                    "file:///c:/workspace/project-" + fixtureId
                            + "/src/test/groovy/Filler" + i + ".groovy");
            IFile fillerFile = mockGroovyFile(fillerUri);
            when(fillerFile.getModificationStamp()).thenReturn(1L);
            when(fillerFile.getContents()).thenAnswer(inv ->
                    new ByteArrayInputStream(fillerContent.getBytes(StandardCharsets.UTF_8)));
            fillerFiles.add(fillerFile);
        }

        // Usage file placed after all fillers — beyond the cap
        String usageUri = DocumentManager.normalizeUri(
                "file:///c:/workspace/project-" + fixtureId + "/src/test/groovy/UseSpec.groovy");
        String usageContent = "class UseSpec {\n    void run() { rareSymbol() }\n}\n";
        documentManager.didOpen(usageUri, usageContent);
        IFile usageFile = mockGroovyFile(usageUri);

        IResource rootResource = mock(IResource.class);
        doAnswer(invocation -> {
            IResourceVisitor visitor = invocation.getArgument(0);
            visitor.visit(declarationFile);
            for (IFile filler : fillerFiles) {
                visitor.visit(filler);
            }
            visitor.visit(usageFile);  // beyond the cap
            return null;
        }).when(rootResource).accept(any(IResourceVisitor.class));

        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_SOURCE);
        when(root.getPath()).thenReturn(new Path("/project/src/test/groovy"));
        when(root.getResource()).thenReturn(rootResource);

        IJavaProject javaProject = mock(IJavaProject.class);
        when(javaProject.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[]{root});

        ISourceRange nameRange = mock(ISourceRange.class);
        int declOffset = declarationContent.indexOf(symbolName);
        when(nameRange.getOffset()).thenReturn(declOffset);
        when(nameRange.getLength()).thenReturn(symbolName.length());

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn(symbolName);
        when(method.getNameRange()).thenReturn(nameRange);
        when(method.getResource()).thenReturn(declarationFile);
        when(method.getJavaProject()).thenReturn(javaProject);

        try {
            ReferenceSearchHelper.ReferenceExistence result =
                    ReferenceSearchHelper.referenceExistenceForUnusedDeclaration(
                            method, declarationUri, documentManager);

            // The cap is exceeded before reaching the usage file, so the result
            // should be INDETERMINATE (safe: avoids incorrectly fading the declaration).
            assertEquals(ReferenceSearchHelper.ReferenceExistence.INDETERMINATE, result);
        } finally {
            documentManager.didClose(declarationUri);
            documentManager.didClose(usageUri);
        }
    }

    @Test
    void findReferenceLocationsFallsBackAndSkipsDeclarationName() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}

                    void callInsideFile() {
                        sharedHelper()
                    }
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """);

        try {
            List<Location> locations = ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            assertEquals(2, locations.size());

            Position declarationPosition = PositionUtils.offsetToPosition(
                    fixture.declarationContent, fixture.declarationOffset);

            assertTrue(locations.stream().noneMatch(location ->
                    fixture.declarationUri.equals(location.getUri())
                            && declarationPosition.equals(location.getRange().getStart())));
            assertTrue(locations.stream().anyMatch(location ->
                    fixture.usageUri.equals(location.getUri())));
        } finally {
            fixture.close();
        }
    }

    @Test
    void findReferenceLocationsDoesNotMatchIdentifiersContainingMethodName() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        def sharedHelper$count = 1
                        println sharedHelper$count
                    }
                }
                """);

        try {
            List<Location> locations = ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            assertTrue(locations.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void findReferenceLocationsCachesScopedGroovyFilesBetweenCalls() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """);

        try {
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            verify(fixture.rootResource, times(1)).accept(any(IResourceVisitor.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void findReferenceLocationsCachesClosedFileContentsByModificationStamp() throws Exception {
        TestFixture fixture = createFixture(
                Long.toHexString(System.nanoTime()),
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """,
                false);

        try {
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            verify(fixture.usageFile, times(1)).getContents();
        } finally {
            fixture.close();
        }
    }

    @Test
    void invalidateFileContentCacheForcesClosedFileContentReload() throws Exception {
        TestFixture fixture = createFixture(
                Long.toHexString(System.nanoTime()),
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """,
                false);

        try {
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            verify(fixture.usageFile, times(1)).getContents();

            ReferenceSearchHelper.invalidateFileContentCache(fixture.usageUri);
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            verify(fixture.usageFile, times(2)).getContents();
        } finally {
            fixture.close();
        }
    }

    @Test
    void clearCachesForcesScopedGroovyFilesRescan() throws Exception {
        TestFixture fixture = createFixture(
                "sharedHelper",
                """
                class SupportSpec {
                    void sharedHelper() {}
                }
                """,
                """
                class UseSpec {
                    void runIt() {
                        sharedHelper()
                    }
                }
                """);

        try {
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);
            verify(fixture.rootResource, times(1)).accept(any(IResourceVisitor.class));

            ReferenceSearchHelper.clearCaches();
            ReferenceSearchHelper.findReferenceLocations(
                    fixture.method, fixture.declarationUri, fixture.documentManager);

            verify(fixture.rootResource, times(2)).accept(any(IResourceVisitor.class));
        } finally {
            fixture.close();
        }
    }

    private TestFixture createFixture(
            String symbolName, String declarationContent, String usageContent) throws Exception {
        return createFixture(Long.toHexString(System.nanoTime()),
                symbolName, declarationContent, usageContent, true);
    }

    private TestFixture createFixture(
            String fixtureId,
            String symbolName,
            String declarationContent,
            String usageContent,
            boolean openUsageDocument) throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String declarationUri = DocumentManager.normalizeUri(
            "file:///c:/workspace/project-" + fixtureId + "/src/test/groovy/SupportSpec.groovy");
        String usageUri = DocumentManager.normalizeUri(
            "file:///c:/workspace/project-" + fixtureId + "/src/test/groovy/UseSpec.groovy");

        documentManager.didOpen(declarationUri, declarationContent);
        if (openUsageDocument) {
            documentManager.didOpen(usageUri, usageContent);
        }

        IFile declarationFile = mockGroovyFile(declarationUri);
        IFile usageFile = mockGroovyFile(usageUri);
        when(usageFile.getModificationStamp()).thenReturn(1L);
        when(usageFile.getContents()).thenAnswer(invocation ->
                new ByteArrayInputStream(usageContent.getBytes(StandardCharsets.UTF_8)));

        IResource rootResource = mock(IResource.class);
        doAnswer(invocation -> {
            IResourceVisitor visitor = invocation.getArgument(0);
            visitor.visit(declarationFile);
            visitor.visit(usageFile);
            return null;
        }).when(rootResource).accept(any(IResourceVisitor.class));

        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_SOURCE);
        when(root.getPath()).thenReturn(new Path("/project/src/test/groovy"));
        when(root.getResource()).thenReturn(rootResource);

        IJavaProject javaProject = mock(IJavaProject.class);
        when(javaProject.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[]{root});

        ISourceRange nameRange = mock(ISourceRange.class);
        int declarationOffset = declarationContent.indexOf(symbolName);
        when(nameRange.getOffset()).thenReturn(declarationOffset);
        when(nameRange.getLength()).thenReturn(symbolName.length());

        IMethod method = mock(IMethod.class);
        when(method.getElementName()).thenReturn(symbolName);
        when(method.getNameRange()).thenReturn(nameRange);
        when(method.getResource()).thenReturn(declarationFile);
        when(method.getJavaProject()).thenReturn(javaProject);

        return new TestFixture(documentManager, method, declarationUri, usageUri,
                declarationContent, declarationOffset, rootResource, usageFile,
                openUsageDocument);
    }

    private IFile mockGroovyFile(String uri) throws Exception {
        IFile file = mock(IFile.class);
        when(file.getLocationURI()).thenReturn(new URI(uri));
        when(file.getFileExtension()).thenReturn("groovy");
        return file;
    }

    private static final class TestFixture {
        private final DocumentManager documentManager;
        private final IMethod method;
        private final String declarationUri;
        private final String usageUri;
        private final String declarationContent;
        private final int declarationOffset;
        private final IResource rootResource;
        private final IFile usageFile;
        private final boolean usageDocumentOpen;

        private TestFixture(
                DocumentManager documentManager,
                IMethod method,
                String declarationUri,
                String usageUri,
                String declarationContent,
                int declarationOffset,
                IResource rootResource,
                IFile usageFile,
                boolean usageDocumentOpen) {
            this.documentManager = documentManager;
            this.method = method;
            this.declarationUri = declarationUri;
            this.usageUri = usageUri;
            this.declarationContent = declarationContent;
            this.declarationOffset = declarationOffset;
            this.rootResource = rootResource;
            this.usageFile = usageFile;
            this.usageDocumentOpen = usageDocumentOpen;
        }

        private void close() {
            documentManager.didClose(declarationUri);
            if (usageDocumentOpen) {
                documentManager.didClose(usageUri);
            }
        }
    }
}
