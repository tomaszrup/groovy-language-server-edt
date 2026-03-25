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
import static org.mockito.Mockito.when;

import java.net.URI;
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
import org.junit.jupiter.api.Test;

class ReferenceSearchHelperTest {

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

    private TestFixture createFixture(
            String symbolName, String declarationContent, String usageContent) throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String declarationUri = DocumentManager.normalizeUri(
            "file:///c:/workspace/project/src/test/groovy/SupportSpec.groovy");
        String usageUri = DocumentManager.normalizeUri(
            "file:///c:/workspace/project/src/test/groovy/UseSpec.groovy");

        documentManager.didOpen(declarationUri, declarationContent);
        documentManager.didOpen(usageUri, usageContent);

        IFile declarationFile = mockGroovyFile(declarationUri);
        IFile usageFile = mockGroovyFile(usageUri);

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
                declarationContent, declarationOffset);
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

        private TestFixture(
                DocumentManager documentManager,
                IMethod method,
                String declarationUri,
                String usageUri,
                String declarationContent,
                int declarationOffset) {
            this.documentManager = documentManager;
            this.method = method;
            this.declarationUri = declarationUri;
            this.usageUri = usageUri;
            this.declarationContent = declarationContent;
            this.declarationOffset = declarationOffset;
        }

        private void close() {
            documentManager.didClose(declarationUri);
            documentManager.didClose(usageUri);
        }
    }
}