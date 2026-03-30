package org.eclipse.groovy.ls.core.providers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

final class ScopedTypeLookupSupport {

    private ScopedTypeLookupSupport() {
    }

    static IType findType(IJavaProject project, String candidateFqn, String sourceUri)
            throws JavaModelException {
        if (project == null || candidateFqn == null || candidateFqn.isBlank()) {
            return null;
        }

        IType scoped = findTypeInScopedSources(project, candidateFqn, sourceUri);
        if (scoped != null) {
            return scoped;
        }

        IType resolved = project.findType(candidateFqn);
        if (resolved == null && candidateFqn.indexOf('$') >= 0) {
            resolved = project.findType(candidateFqn.replace('$', '.'));
        }
        return resolved;
    }

    private static IType findTypeInScopedSources(IJavaProject project, String candidateFqn, String sourceUri) {
        try {
            List<org.eclipse.jdt.core.IPackageFragmentRoot> roots = SearchScopeHelper.getSourceRoots(project, sourceUri);
            if (roots.isEmpty()) {
                return null;
            }

            String normalizedCandidate = normalizeFqn(candidateFqn);
            int lastDot = normalizedCandidate.lastIndexOf('.');
            String packageName = lastDot > 0 ? normalizedCandidate.substring(0, lastDot) : null;
            String simpleName = lastDot >= 0 ? normalizedCandidate.substring(lastDot + 1) : normalizedCandidate;
            if (simpleName == null || simpleName.isBlank()) {
                return null;
            }

            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                    roots.toArray(new IJavaElement[0]),
                    IJavaSearchScope.SOURCES);

            AtomicReference<IType> exactMatch = new AtomicReference<>();
            AtomicReference<IType> fallbackMatch = new AtomicReference<>();

            JdtSearchSupport.searchAllTypeNames(
                    packageName != null && !packageName.isBlank() ? packageName.toCharArray() : null,
                    SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                    simpleName.toCharArray(),
                    SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameMatchRequestor() {
                        @Override
                        public void acceptTypeNameMatch(TypeNameMatch match) {
                            if (match == null || match.getType() == null) {
                                return;
                            }

                            IType matchedType = match.getType();
                            String matchedFqn = normalizeFqn(matchedType.getFullyQualifiedName('.'));
                            if (normalizedCandidate.equals(matchedFqn)) {
                                exactMatch.compareAndSet(null, matchedType);
                            } else {
                                fallbackMatch.compareAndSet(null, matchedType);
                            }
                        }
                    },
                    IJavaSearchConstants.FORCE_IMMEDIATE_SEARCH,
                    new NullProgressMonitor());

            return exactMatch.get() != null ? exactMatch.get() : fallbackMatch.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeFqn(String typeName) {
        return typeName == null ? null : typeName.replace('$', '.');
    }
}