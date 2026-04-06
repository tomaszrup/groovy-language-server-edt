package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DiagnosticsProviderExecutionTest {

    @Test
    void collectFromWorkingCopyAddsOnlyUnfilteredProblems() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        ICompilationUnit workingCopy = mock(ICompilationUnit.class);
        org.eclipse.jdt.core.dom.CompilationUnit ast = mock(org.eclipse.jdt.core.dom.CompilationUnit.class);
        IProblem keptProblem = mock(IProblem.class);
        IProblem skippedProblem = mock(IProblem.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        List<Diagnostic> diagnostics = new ArrayList<>();

        when(workingCopy.reconcile(anyInt(), anyBoolean(), anyBoolean(), any(), isNull())).thenReturn(ast);
        when(workingCopy.getJavaProject()).thenReturn(javaProject);
        when(ast.getProblems()).thenReturn(new IProblem[] { keptProblem, skippedProblem });

        when(keptProblem.getID()).thenReturn(-1);
        when(keptProblem.getMessage()).thenReturn("kept problem");
        when(keptProblem.isError()).thenReturn(true);
        when(keptProblem.isWarning()).thenReturn(false);
        when(keptProblem.getSourceStart()).thenReturn(0);
        when(keptProblem.getSourceEnd()).thenReturn(3);
        when(keptProblem.getSourceLineNumber()).thenReturn(1);

        when(skippedProblem.getID()).thenReturn(67108964);
        when(skippedProblem.getMessage()).thenReturn("Groovy run script false positive");

        Method method = DiagnosticsProvider.class.getDeclaredMethod(
                "collectFromWorkingCopy",
                ICompilationUnit.class,
                List.class,
                PositionUtils.LineIndex.class);
        method.setAccessible(true);
        method.invoke(provider, workingCopy, diagnostics, PositionUtils.buildLineIndex("keep me\n"));

        assertEquals(1, diagnostics.size());
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("kept problem"));
        assertEquals(DiagnosticSeverity.Error, diagnostics.get(0).getSeverity());
    }

    @Test
    void collectFromMarkersAddsOnlyUnfilteredMarkers() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IFile file = mock(IFile.class);
        IProject project = mock(IProject.class);
        IMarker keptMarker = mock(IMarker.class);
        IMarker skippedMarker = mock(IMarker.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        ICompilationUnit contextUnit = mock(ICompilationUnit.class);
        List<Diagnostic> diagnostics = new ArrayList<>();

        when(file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
                .thenReturn(new IMarker[] { keptMarker, skippedMarker });
        when(file.getProject()).thenReturn(project);
        when(keptMarker.getAttribute(IMarker.MESSAGE, "")).thenReturn("marker problem");
        when(keptMarker.getAttribute("id", -1)).thenReturn(-1);
        when(keptMarker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)).thenReturn(IMarker.SEVERITY_WARNING);
        when(keptMarker.getAttribute(IMarker.CHAR_START, -1)).thenReturn(0);
        when(keptMarker.getAttribute(IMarker.CHAR_END, -1)).thenReturn(6);
        when(keptMarker.getAttribute(IMarker.MESSAGE, "Unknown error")).thenReturn("marker problem");

        when(skippedMarker.getAttribute(IMarker.MESSAGE, "")).thenReturn("skip me");
        when(skippedMarker.getAttribute("id", -1)).thenReturn(67108964);

        try (MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class)) {
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);
            javaCore.when(() -> JavaCore.create(file)).thenReturn((IJavaElement) contextUnit);

            Method method = DiagnosticsProvider.class.getDeclaredMethod(
                    "collectFromMarkers",
                    IFile.class,
                    List.class,
                    PositionUtils.LineIndex.class);
            method.setAccessible(true);
            method.invoke(provider, file, diagnostics, PositionUtils.buildLineIndex("marker problem\n"));
        }

        assertEquals(1, diagnostics.size());
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("marker problem"));
        assertEquals(DiagnosticSeverity.Warning, diagnostics.get(0).getSeverity());
    }

    @Test
    void isGroovyRuntimeIndirectReferenceRecognizesKnownPrefixes() throws Exception {
        Method method = DiagnosticsProvider.class.getDeclaredMethod(
                "isGroovyRuntimeIndirectReference", String.class);
        method.setAccessible(true);

        boolean groovyRuntime = (boolean) method.invoke(
                null,
                "The type groovy.lang.GroovyObject cannot be resolved. It is indirectly referenced from required .class files");
        boolean unrelated = (boolean) method.invoke(
                null,
                "The type com.example.Other cannot be resolved. It is indirectly referenced from required .class files");

        assertTrue(groovyRuntime);
        assertFalse(unrelated);
    }

    @Test
    void publishSyntaxDiagnosticsImmediatePublishesAsyncDiagnostics() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsSyntaxImmediate.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        LanguageClient client = mock(LanguageClient.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PublishDiagnosticsParams> published = new AtomicReference<>();
        doAnswer(invocation -> {
            published.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(client).publishDiagnostics(any(PublishDiagnosticsParams.class));
        provider.connect(client);

        try {
            provider.publishSyntaxDiagnosticsImmediate(uri);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(published.get());
            assertEquals(uri, published.get().getUri());
            assertFalse(published.get().getDiagnostics().isEmpty());
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void publishDiagnosticsImmediatePublishesNoClasspathWarningAndSyntaxErrors() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsImmediate.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        LanguageClient client = mock(LanguageClient.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PublishDiagnosticsParams> published = new AtomicReference<>();
        doAnswer(invocation -> {
            published.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(client).publishDiagnostics(any(PublishDiagnosticsParams.class));
        provider.connect(client);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> true);

        try {
            provider.publishDiagnosticsImmediate(uri);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(published.get());
            assertEquals(uri, published.get().getUri());
            assertTrue(published.get().getDiagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.getCode() != null
                        && diagnostic.getCode().isLeft()
                        && "groovy.noClasspath".equals(diagnostic.getCode().getLeft())));
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void collectFromSyntaxErrorsAddsStandaloneCompilerDiagnostic() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<SyntaxException> errors = List.of(new SyntaxException("unexpected token", 2, 4, 2, 8));

        Method method = DiagnosticsProvider.class.getDeclaredMethod(
                "collectFromSyntaxErrors",
                List.class,
                List.class,
                standaloneCompilerModeClass());
        method.setAccessible(true);
        method.invoke(provider, errors, diagnostics, normalFallbackMode());

        assertEquals(1, diagnostics.size());
        Diagnostic diagnostic = diagnostics.get(0);
        assertTrue(String.valueOf(diagnostic.getMessage()).contains("unexpected token"));
        assertEquals(1, diagnostic.getRange().getStart().getLine());
        assertEquals(3, diagnostic.getRange().getStart().getCharacter());
        assertEquals("groovy", diagnostic.getSource());
    }

    @Test
    void collectBuildInProgressDiagnosticsFallsBackToGroovyCompiler() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsBuildInProgress.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");
        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        List<Diagnostic> diagnostics = new ArrayList<>();

        try {
            Method method = DiagnosticsProvider.class.getDeclaredMethod(
                    "collectBuildInProgressDiagnostics",
                    String.class,
                    standaloneCompilerModeClass(),
                    List.class);
            method.setAccessible(true);
            method.invoke(provider, uri, normalFallbackMode(), diagnostics);

            assertFalse(diagnostics.isEmpty());
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void collectMarkerDiagnosticsFindsWorkspaceFileByUri() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        IWorkspace workspace = mock(IWorkspace.class);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IFile file = mock(IFile.class);
        IProject project = mock(IProject.class);
        IMarker marker = mock(IMarker.class);
        IJavaProject javaProject = mock(IJavaProject.class);
        ICompilationUnit contextUnit = mock(ICompilationUnit.class);
        List<Diagnostic> diagnostics = new ArrayList<>();
        String uri = "file:///workspace/MarkerLookup.groovy";

        when(workspace.getRoot()).thenReturn(root);
        when(root.findFilesForLocationURI(URI.create(uri))).thenReturn(new IFile[] { file });
        when(file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)).thenReturn(new IMarker[] { marker });
        when(file.getProject()).thenReturn(project);
        when(marker.getAttribute(IMarker.MESSAGE, "")).thenReturn("workspace marker");
        when(marker.getAttribute("id", -1)).thenReturn(-1);
        when(marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)).thenReturn(IMarker.SEVERITY_ERROR);
        when(marker.getAttribute(IMarker.CHAR_START, -1)).thenReturn(0);
        when(marker.getAttribute(IMarker.CHAR_END, -1)).thenReturn(8);
        when(marker.getAttribute(IMarker.MESSAGE, "Unknown error")).thenReturn("workspace marker");

        try (MockedStatic<ResourcesPlugin> resourcesPlugin = Mockito.mockStatic(ResourcesPlugin.class);
                MockedStatic<JavaCore> javaCore = Mockito.mockStatic(JavaCore.class)) {
            resourcesPlugin.when(ResourcesPlugin::getWorkspace).thenReturn(workspace);
            javaCore.when(() -> JavaCore.create(project)).thenReturn(javaProject);
            javaCore.when(() -> JavaCore.create(file)).thenReturn((IJavaElement) contextUnit);

            Method method = DiagnosticsProvider.class.getDeclaredMethod(
                    "collectMarkerDiagnostics",
                    String.class,
                    List.class,
                    PositionUtils.LineIndex.class);
            method.setAccessible(true);
            method.invoke(provider, uri, diagnostics, PositionUtils.buildLineIndex("workspace marker\n"));
        }

        assertEquals(1, diagnostics.size());
        assertTrue(String.valueOf(diagnostics.get(0).getMessage()).contains("workspace marker"));
    }

    @Test
    void collectClasspathDiagnosticsFallsBackToGroovyCompilerWithoutWorkingCopy() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsClasspathFallback.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");
        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        List<Diagnostic> diagnostics = new ArrayList<>();

        try {
            Method method = DiagnosticsProvider.class.getDeclaredMethod(
                    "collectClasspathDiagnostics",
                    String.class,
                    standaloneCompilerModeClass(),
                    List.class,
                    PositionUtils.LineIndex.class);
            method.setAccessible(true);
            method.invoke(provider, uri, normalFallbackMode(), diagnostics, PositionUtils.buildLineIndex("class Broken { def x = }\n"));

            assertFalse(diagnostics.isEmpty());
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void publishDiagnosticsAfterChangePublishesSyntaxPreview() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsAfterChange.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        LanguageClient client = mock(LanguageClient.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PublishDiagnosticsParams> published = new AtomicReference<>();
        doAnswer(invocation -> {
            published.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(client).publishDiagnostics(any(PublishDiagnosticsParams.class));
        provider.connect(client);

        try {
            provider.publishDiagnosticsAfterChange(uri);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(uri, published.get().getUri());
            assertFalse(published.get().getDiagnostics().isEmpty());
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void collectSyntaxDiagnosticsAddsNoClasspathWarningWhenClasspathMissing() throws Exception {
        DocumentManager documentManager = new DocumentManager();
        String uri = "file:///DiagnosticsSyntaxOnlyNoClasspath.groovy";
        documentManager.didOpen(uri, "class Broken { def x = }\n");

        DiagnosticsProvider provider = new DiagnosticsProvider(documentManager);
        provider.setClasspathChecker(ignored -> false);
        provider.setInitializationCompleteSupplier(() -> true);

        try {
            Method method = DiagnosticsProvider.class.getDeclaredMethod("collectSyntaxDiagnostics", String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Diagnostic> diagnostics = (List<Diagnostic>) method.invoke(provider, uri);

            assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.getCode() != null
                    && diagnostic.getCode().isLeft()
                    && "groovy.noClasspath".equals(diagnostic.getCode().getLeft())));
        } finally {
            provider.shutdown();
            documentManager.didClose(uri);
        }
    }

    @Test
    void standaloneCompilerModeBooleanSwitchesBetweenStartupAndFallback() throws Exception {
        DiagnosticsProvider provider = new DiagnosticsProvider(new DocumentManager());
        Method method = DiagnosticsProvider.class.getDeclaredMethod("standaloneCompilerMode", boolean.class);
        method.setAccessible(true);

        assertEquals("STARTUP_FILTERED", String.valueOf(method.invoke(provider, false)));
        assertEquals("NORMAL_FALLBACK", String.valueOf(method.invoke(provider, true)));
    }

    private static Class<?> standaloneCompilerModeClass() throws ClassNotFoundException {
        return Class.forName("org.eclipse.groovy.ls.core.providers.DiagnosticsProvider$StandaloneCompilerMode");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object normalFallbackMode() throws ClassNotFoundException {
        return Enum.valueOf((Class<? extends Enum>) standaloneCompilerModeClass(), "NORMAL_FALLBACK");
    }
}