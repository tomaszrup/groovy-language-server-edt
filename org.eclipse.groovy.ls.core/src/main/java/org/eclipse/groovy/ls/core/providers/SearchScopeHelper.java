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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;

/**
 * Creates test-scope-aware {@link IJavaSearchScope} instances.
 * <p>
 * When the originating file lives under {@code src/test/}, the returned scope
 * is narrowed to the project's <em>test</em> source roots only.  This prevents
 * references/code-lens/fading from counting usages that exist only in main
 * source when the declaration itself is a test-only artefact.
 * <p>
 * For files under {@code src/main/} (or any non-test path), the full project
 * sources scope is returned — main code may legitimately be referenced from
 * both main and test sources.
 */
@SuppressWarnings("unused")
public final class SearchScopeHelper {

    private SearchScopeHelper() { /* utility */ }

    /**
     * Create a search scope appropriate for the given project and file URI.
     *
     * @param javaProject the enclosing project (may be {@code null})
     * @param uri         the URI of the file being inspected (may be {@code null})
     * @return a search scope limited to test source roots when the file is in
     *         {@code src/test/}, or the full project sources otherwise
     */
    public static IJavaSearchScope createSourceScope(IJavaProject javaProject, String uri) {
        if (javaProject == null) {
            return SearchEngine.createWorkspaceScope();
        }

        List<IPackageFragmentRoot> roots = getSourceRoots(javaProject, uri);
        if (!roots.isEmpty()) {
            return SearchEngine.createJavaSearchScope(
                    roots.toArray(new IJavaElement[0]),
                    IJavaSearchScope.SOURCES);
        }

        return SearchEngine.createJavaSearchScope(
                new IJavaElement[]{javaProject},
                IJavaSearchScope.SOURCES);
    }

    /**
     * Check whether a URI refers to a test source file (standard
     * Gradle/Maven {@code src/test/} convention, normalised for both
     * forward and back slashes).
     */
    static boolean isTestFileUri(String uri) {
        if (uri == null) return false;
        String normalised = uri.replace('\\', '/');
        return normalised.contains("/src/test/");
    }

    static List<IPackageFragmentRoot> getSourceRoots(IJavaProject javaProject, String uri) {
        if (javaProject == null) {
            return List.of();
        }

        List<IPackageFragmentRoot> sourceRoots = collectSourceRoots(javaProject);
        if (sourceRoots.isEmpty()) {
            return List.of();
        }

        if (uri != null && isTestFileUri(uri)) {
            List<IPackageFragmentRoot> testRoots = filterTestSourceRoots(sourceRoots);
            if (!testRoots.isEmpty()) {
                return testRoots;
            }
        }

        return sourceRoots;
    }

    /**
     * Build a scope that covers only the test source roots of a project.
     *
     * @return a test-only scope, or {@code null} if no test roots were found
     */
    private static IJavaSearchScope createTestSourceScope(IJavaProject javaProject) {
        try {
            List<IPackageFragmentRoot> testRoots = filterTestSourceRoots(collectSourceRoots(javaProject));
            if (testRoots.isEmpty()) {
                return null;
            }
            return SearchEngine.createJavaSearchScope(
                    testRoots.toArray(new IJavaElement[0]),
                    IJavaSearchScope.SOURCES);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to create test source scope for " + javaProject.getElementName(), e);
            return null;
        }
    }

    private static List<IPackageFragmentRoot> collectSourceRoots(IJavaProject javaProject) {
        try {
            IPackageFragmentRoot[] roots = javaProject.getPackageFragmentRoots();
            List<IPackageFragmentRoot> sourceRoots = new ArrayList<>();
            for (IPackageFragmentRoot root : roots) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    sourceRoots.add(root);
                }
            }
            return sourceRoots;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to collect source roots for " + javaProject.getElementName(), e);
            return List.of();
        }
    }

    private static List<IPackageFragmentRoot> filterTestSourceRoots(List<IPackageFragmentRoot> roots) {
        List<IPackageFragmentRoot> testRoots = new ArrayList<>();
        for (IPackageFragmentRoot root : roots) {
            String path = root.getPath().toString().replace('\\', '/');
            if (path.contains("/src/test/")) {
                testRoots.add(root);
            }
        }
        return testRoots;
    }
}
