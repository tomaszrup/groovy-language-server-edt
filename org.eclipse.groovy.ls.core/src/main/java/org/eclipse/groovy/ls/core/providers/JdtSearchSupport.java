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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.core.search.TypeNameRequestor;

public final class JdtSearchSupport {

    private static final SearchParticipant[] DEFAULT_SEARCH_PARTICIPANTS =
            new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()};

    private static final ThreadLocal<SearchEngine> SEARCH_ENGINE =
            ThreadLocal.withInitial(SearchEngine::new);

    private JdtSearchSupport() {
    }

    public static void search(SearchPattern pattern,
            IJavaSearchScope scope,
            SearchRequestor requestor,
            IProgressMonitor progressMonitor) throws CoreException {
        SEARCH_ENGINE.get().search(
                pattern,
                DEFAULT_SEARCH_PARTICIPANTS,
                scope,
                requestor,
                progressMonitor);
    }

    public static String readContent(DocumentManager documentManager,
            String targetUri,
            IResource resource,
            Map<String, String> contentCache) {
        if (contentCache != null && contentCache.containsKey(targetUri)) {
            return contentCache.get(targetUri);
        }

        String content = documentManager.getContent(targetUri);
        if (content == null && resource instanceof IFile file) {
            try (java.io.InputStream is = file.getContents()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                content = null;
            }
        }

        if (contentCache != null) {
            contentCache.put(targetUri, content);
        }
        return content;
    }

    public static void searchAllTypeNames(char[] packageName,
            int packageMatchRule,
            char[] typeName,
            int typeMatchRule,
            int searchFor,
            IJavaSearchScope scope,
            TypeNameRequestor requestor,
            int waitingPolicy,
            IProgressMonitor progressMonitor) throws CoreException {
        SEARCH_ENGINE.get().searchAllTypeNames(
                packageName,
                packageMatchRule,
                typeName,
                typeMatchRule,
                searchFor,
                scope,
                requestor,
                waitingPolicy,
                progressMonitor);
    }

    public static void searchAllTypeNames(char[] packageName,
            int packageMatchRule,
            char[] typeName,
            int typeMatchRule,
            int searchFor,
            IJavaSearchScope scope,
            TypeNameMatchRequestor requestor,
            int waitingPolicy,
            IProgressMonitor progressMonitor) throws CoreException {
        SEARCH_ENGINE.get().searchAllTypeNames(
                packageName,
                packageMatchRule,
                typeName,
                typeMatchRule,
                searchFor,
                scope,
                requestor,
                waitingPolicy,
                progressMonitor);
    }
}