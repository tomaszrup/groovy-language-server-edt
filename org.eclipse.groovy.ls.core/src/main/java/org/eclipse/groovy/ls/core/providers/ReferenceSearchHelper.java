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

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

final class ReferenceSearchHelper {

    private ReferenceSearchHelper() {
    }

    /**
     * Check whether an element has at least one source reference in scope.
     * The search is cancelled as soon as the first match is found.
     */
    static boolean hasReferences(IJavaElement element, String uri) {
        try {
            SearchPattern pattern = SearchPattern.createPattern(
                    element, IJavaSearchConstants.REFERENCES);
            if (pattern == null) {
                return false;
            }

            org.eclipse.jdt.core.IJavaProject javaProject = element.getJavaProject();
            IJavaSearchScope scope = SearchScopeHelper.createSourceScope(javaProject, uri);

            boolean[] found = {false};
            SearchEngine engine = new SearchEngine();

            org.eclipse.core.runtime.IProgressMonitor cancelOnFound =
                    new org.eclipse.core.runtime.NullProgressMonitor() {
                        @Override
                        public boolean isCanceled() {
                            return found[0];
                        }
                    };

            engine.search(pattern,
                    new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                    scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                            found[0] = true;
                        }
                    },
                    cancelOnFound);

            return found[0];
        } catch (org.eclipse.core.runtime.OperationCanceledException ignored) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}