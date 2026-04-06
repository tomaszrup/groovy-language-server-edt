package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DefinitionProviderJdtResolutionTest {

    @Test
    void resolveViaJdtReturnsLocationFromCodeSelect() throws Exception {
        DocumentManager documentManager = mock(DocumentManager.class);
        DefinitionProvider provider = new DefinitionProvider(documentManager);
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        IMethod methodElement = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);
        String uri = "file:///workspace/Demo.groovy";
        String content = "class Demo { void work() {} }\nnew Demo().work()\n";
        int offset = content.indexOf("work()", content.indexOf("new Demo()"));

        when(documentManager.getWorkingCopy(uri)).thenReturn(workingCopy);
        when(documentManager.getContent(uri)).thenReturn(content);
        when(documentManager.cachedCodeSelect(workingCopy, offset)).thenReturn(new IJavaElement[] { methodElement });
        when(documentManager.remapToWorkingCopyElement(methodElement)).thenReturn(methodElement);
        when(documentManager.resolveElementUri(methodElement)).thenReturn(uri);
        when(methodElement.getElementName()).thenReturn("work");
        when(methodElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(java.net.URI.create(uri));
        when(resource.getName()).thenReturn("Demo.groovy");
        when(methodElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(offset);
        when(nameRange.getLength()).thenReturn(4);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveViaJdt",
                String.class,
                Position.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Location> locations = (List<Location>) method.invoke(provider, uri, new Position(1, content.indexOf("work()", content.indexOf("new Demo()")) - content.lastIndexOf('\n', offset) - 1), null);

        assertEquals(1, locations.size());
        assertEquals(uri, locations.get(0).getUri());
        assertEquals(1, locations.get(0).getRange().getStart().getLine());
    }

    @Test
    void canResolveSourceUncachedUsesWorkspaceProjectsAndRemembersSuccess() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        IProject firstProject = mock(IProject.class);
        IProject secondProject = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        org.eclipse.jdt.core.IJavaProject firstJavaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        org.eclipse.jdt.core.IJavaProject secondJavaProject = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType resolvedType = mock(IType.class);

        when(workspace.getRoot()).thenReturn(root);
        when(root.getProjects()).thenReturn(new IProject[] { firstProject, secondProject });
        when(firstProject.isOpen()).thenReturn(true);
        when(secondProject.isOpen()).thenReturn(true);
        when(firstJavaProject.exists()).thenReturn(true);
        when(secondJavaProject.exists()).thenReturn(true);
        when(secondJavaProject.findType("demo.Helper")).thenReturn(resolvedType);

        try (MockedStatic<ResourcesPlugin> resources = Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class)) {
            resources.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(firstProject)).thenReturn(firstJavaProject);
            javaCore.when(() -> JavaCore.create(secondProject)).thenReturn(secondJavaProject);

            Method method = DefinitionProvider.class.getDeclaredMethod(
                    "canResolveSourceUncached",
                    String.class,
                    Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(provider, "demo.Helper", null);

            assertTrue(result);

            Field field = DefinitionProvider.class.getDeclaredField("currentDefinitionProject");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<IProject> remembered = (AtomicReference<IProject>) field.get(provider);
            assertSame(secondProject, remembered.get());
        }
    }

    @Test
    void canResolveSourceUncachedReturnsFalseWhenWorkspaceProjectsDoNotResolve() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        IProject project = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        org.eclipse.jdt.core.IJavaProject javaProject = mock(org.eclipse.jdt.core.IJavaProject.class);

        when(workspace.getRoot()).thenReturn(root);
        when(root.getProjects()).thenReturn(new IProject[] { project });
        when(project.isOpen()).thenReturn(true);
        when(javaProject.exists()).thenReturn(true);

        try (MockedStatic<ResourcesPlugin> resources = Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class)) {
            resources.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);

            Method method = DefinitionProvider.class.getDeclaredMethod(
                    "canResolveSourceUncached",
                    String.class,
                    Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(provider, "demo.Missing", null);

            assertFalse(result);
        }
    }

    @Test
    void searchLinkedFoldersOnDiskFindsSourceUnderLinkedFolder(@TempDir Path tempDir) throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        Path sourceFile = tempDir.resolve("src/main/groovy/demo/Helper.groovy");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Helper {}\n");

        IProject project = mock(IProject.class);
        IFolder folder = mock(IFolder.class);

        when(project.members()).thenReturn(new IResource[] { folder });
        when(folder.getLocation()).thenReturn(new org.eclipse.core.runtime.Path(tempDir.toString()));

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "searchLinkedFoldersOnDisk",
                IProject.class,
                String.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, project, "demo/Helper", null);

        assertNotNull(result);
        assertEquals(sourceFile.toFile().toURI().toString(), result.getUri());
    }

    @Test
    void toLocationFromStubBuildsVirtualGroovySourceUri() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(mock(DocumentManager.class));
        IType type = mock(IType.class);
        IPackageFragment fragment = mock(IPackageFragment.class);

        when(type.getPackageFragment()).thenReturn(fragment);
        when(fragment.getElementName()).thenReturn("com.example");
        when(type.getFlags()).thenReturn(0);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getElementName()).thenReturn("Helper");
        when(type.getSuperclassName()).thenReturn("Object");
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFields()).thenReturn(new org.eclipse.jdt.core.IField[0]);
        when(type.getMethods()).thenReturn(new IMethod[0]);

        Method method = DefinitionProvider.class.getDeclaredMethod("toLocationFromStub", IType.class, String.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, type, "com.example.Helper");

        assertNotNull(result);
        assertEquals("groovy-source:///com/example/Helper.groovy", result.getUri());
        assertTrue(SourceJarHelper.resolveSourceContent(result.getUri()).contains("class Helper"));
    }
}