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
import java.util.Collections;
import java.util.List;

import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Detects unused type and method declarations in Groovy documents.
 * <p>
 * Unused declarations (those with zero references) are reported as
 * {@link DiagnosticSeverity#Hint} diagnostics with the
 * {@link DiagnosticTag#Unnecessary} tag, causing the editor to render them
 * with reduced opacity (faded text).
 * <p>
 * Test methods are excluded from this analysis since they are invoked by the
 * test runner and will never have explicit code references.
 */
public class UnusedDeclarationDetector {

    /** Diagnostic code used for unused declarations. */
    public static final String DIAG_CODE_UNUSED_DECLARATION = "groovy.unusedDeclaration";

    private static final String DIAGNOSTIC_SOURCE = "groovy";

    /**
     * Annotation simple names that mark a method as a test entry point.
     * These methods are executed by test runners and should not be flagged
     * as unused even when they have zero references.
     */
    private static final String[] TEST_ANNOTATIONS = {
        "Test",
        "ParameterizedTest",
        "RepeatedTest",
        "TestFactory",
        "TestTemplate",
        "BeforeEach",
        "AfterEach",
        "BeforeAll",
        "AfterAll",
        "Before",
        "After",
        "BeforeClass",
        "AfterClass",
        "Ignore",
        "Disabled",
        "Suite",
        "RunWith",
        "Specification",  // Spock
        "Unroll",         // Spock
    };

    /**
     * FQN prefixes for test annotations (matched against annotation element names
     * that might be fully qualified).
     */
    private static final String[] TEST_ANNOTATION_FQ_PREFIXES = {
        "org.junit.",
        "org.junit.jupiter.",
        "org.testng.",
        "spock.",
    };

    /**
     * Annotation simple names that mark a method as a framework entry point.
     * These methods are invoked by the framework (e.g., Spring, Jakarta/CDI)
     * at runtime and should not be flagged as unused.
     */
    private static final String[] FRAMEWORK_ANNOTATIONS = {
        // Spring
        "Bean",
        "PostConstruct",
        "PreDestroy",
        "EventListener",
        "Scheduled",
        "Async",
        "RequestMapping",
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "DeleteMapping",
        "PatchMapping",
        "ExceptionHandler",
        "InitBinder",
        "ModelAttribute",
        "ResponseBody",
        "Transactional",
        // Jakarta / javax
        "Inject",
        "Produces",
        "Observes",
        // Micronaut
        "Singleton",
        "Factory",
    };

    /**
     * FQN prefixes for framework annotations.
     */
    private static final String[] FRAMEWORK_ANNOTATION_FQ_PREFIXES = {
        "org.springframework.",
        "jakarta.annotation.",
        "javax.annotation.",
        "jakarta.inject.",
        "javax.inject.",
        "jakarta.enterprise.",
        "javax.enterprise.",
        "io.micronaut.",
    };

    /**
     * Annotation simple names that mark a <em>type</em> as framework-managed.
     * When a class carries one of these annotations the framework instantiates
     * the bean (calling its constructor), so constructors in such classes
     * should not be flagged as unused.
     */
    private static final String[] FRAMEWORK_TYPE_ANNOTATIONS = {
        // Spring stereotypes
        "Component",
        "Service",
        "Repository",
        "Controller",
        "RestController",
        "Configuration",
        "SpringBootApplication",
        // Jakarta / javax CDI
        "ApplicationScoped",
        "RequestScoped",
        "SessionScoped",
        "Dependent",
        "Singleton",
        // Micronaut
        "Factory",
    };

    /** Maximum number of JDT search operations per detectUnusedDeclarations() call. */
    private static final int MAX_SEARCHES_PER_FILE = 20;

    private UnusedDeclarationDetector() {
        // utility class
    }

    /**
     * Detect unused types and methods in the given compilation unit.
     *
     * @param uri             the document URI
     * @param documentManager the document manager
     * @return diagnostics for unused declarations (empty list if none)
     */
    public static List<Diagnostic> detectUnusedDeclarations(
            String uri, DocumentManager documentManager) {

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return Collections.emptyList();
        }

        String content = documentManager.getContent(uri);
        if (content == null) {
            return Collections.emptyList();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        int[] searchBudget = {MAX_SEARCHES_PER_FILE};

        try {
            IType[] types = workingCopy.getTypes();
            for (IType type : types) {
                if (searchBudget[0] <= 0 || Thread.currentThread().isInterrupted()) break;
                collectUnusedDeclarations(type, content, uri, diagnostics, searchBudget);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Unused declaration detection failed for " + uri, e);
        }

        return diagnostics;
    }

    private static void collectUnusedDeclarations(
            IType type, String content, String uri,
            List<Diagnostic> diagnostics,
            int[] searchBudget)
            throws JavaModelException {

        if (searchBudget[0] <= 0 || Thread.currentThread().isInterrupted()) return;

        // Check the type itself — but skip if it's in a test class context
        if (!isTestType(type)) {
            Boolean unreferenced = isUnreferenced(type, uri);
            searchBudget[0]--;
            if (Boolean.TRUE.equals(unreferenced)) {
                Diagnostic diag = createUnusedDiagnostic(type, content);
                if (diag != null) {
                    diagnostics.add(diag);
                }
            }
        } else {
            searchBudget[0]--;
        }

        // Check methods
        boolean frameworkType = isFrameworkManagedType(type);
        for (IMethod method : type.getMethods()) {
            if (searchBudget[0] <= 0 || Thread.currentThread().isInterrupted()) break;
            if (isTestMethod(method) || isMainMethod(method) || isFrameworkMethod(method)) {
                continue;
            }
            // Constructors in framework-managed types (e.g. @Component) are
            // invoked by the framework at runtime — never flag them as unused.
            if (frameworkType && method.isConstructor()) {
                continue;
            }
            Boolean unreferenced = isUnreferenced(method, uri);
            searchBudget[0]--;
            if (Boolean.TRUE.equals(unreferenced)) {
                Diagnostic diag = createUnusedDiagnostic(method, content);
                if (diag != null) {
                    diagnostics.add(diag);
                }
            }
        }

        // Recurse into inner types
        for (IType innerType : type.getTypes()) {
            if (searchBudget[0] <= 0 || Thread.currentThread().isInterrupted()) break;
            collectUnusedDeclarations(innerType, content, uri, diagnostics, searchBudget);
        }
    }

    /**
     * Check if an element has zero references in the enclosing project.
     * <p>
     * Scoped to the project (not the whole workspace) to avoid scanning
     * all subprojects in large multi-project builds — a private member
     * can only be referenced within its own project anyway.
     * The search is cancelled as soon as the first match is found.
     * When reference existence cannot be determined cheaply, the detector
     * skips fading instead of falling back to a whole-project text scan.
     */
    private static Boolean isUnreferenced(IJavaElement element, String uri) {
        return switch (ReferenceSearchHelper.referenceExistenceForUnusedDeclaration(element, uri)) {
            case FOUND -> Boolean.FALSE;
            case NOT_FOUND -> Boolean.TRUE;
            case INDETERMINATE -> null;
        };
    }

    /**
     * Determine if a method is a test method (should not be faded).
     */
    private static boolean isTestMethod(IMethod method) {
        try {
            // Check annotations on the method
            for (IAnnotation annotation : method.getAnnotations()) {
                if (isTestAnnotation(annotation)) {
                    return true;
                }
            }

            // Check if declaring type is a test type — methods in test classes
            // named "test*" are likely JUnit 3 tests
            IType declaringType = method.getDeclaringType();
            if (declaringType != null && isTestType(declaringType)) {
                // Spock specifications: all methods are framework-managed
                // (feature methods use string names, plus setup/cleanup/helpers)
                if (isSpockSpecification(declaringType)) {
                    return true;
                }
                String name = method.getElementName();
                if (name.startsWith("test")) {
                    return true;
                }
                // setup/tearDown in test classes
                if ("setUp".equals(name) || "tearDown".equals(name)) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            // If we can't determine, err on the side of not fading
            return true;
        }
    }

    /**
     * Determine if a type is a test class.
     */
    private static boolean isTestType(IType type) {
        try {
            // Check annotations on the type
            for (IAnnotation annotation : type.getAnnotations()) {
                if (isTestAnnotation(annotation)) {
                    return true;
                }
            }

            // Check superclass names for common test base classes
            String superclassName = type.getSuperclassName();

            if (superclassName != null
                    && (superclassName.equals("TestCase")
                        || superclassName.equals("junit.framework.TestCase")
                        || superclassName.equals("GroovyTestCase")
                        || superclassName.equals("groovy.test.GroovyTestCase")
                        || superclassName.equals("Specification")
                        || superclassName.equals("spock.lang.Specification"))) {
                return true;
            }

            // Check if the source path suggests a test directory
            org.eclipse.core.resources.IResource resource = type.getResource();
            if (resource != null) {
                String path = resource.getFullPath().toString();
                if (path.contains("/test/") || path.contains("/tests/")
                        || path.contains("\\test\\") || path.contains("\\tests\\")) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            return true; // err on the side of not fading
        }
    }

    /**
     * Check if a type is a Spock specification (extends Specification).
     * In Spock, all methods inside a specification are framework-managed:
     * feature methods use string names, and lifecycle/helper methods are
     * invoked by the Spock runner.
     */
    private static boolean isSpockSpecification(IType type) {
        try {
            String superclassName = type.getSuperclassName();
            return superclassName != null
                    && (superclassName.equals("Specification")
                        || superclassName.equals("spock.lang.Specification"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if an annotation is a test-related annotation.
     */
    private static boolean isTestAnnotation(IAnnotation annotation) {
        String name = annotation.getElementName();

        // Check simple name
        for (String testAnnotation : TEST_ANNOTATIONS) {
            if (testAnnotation.equals(name)) {
                return true;
            }
        }

        // Check FQN prefixes
        for (String prefix : TEST_ANNOTATION_FQ_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determine if a method is a framework-managed entry point (should not be faded).
     */
    private static boolean isFrameworkMethod(IMethod method) {
        try {
            for (IAnnotation annotation : method.getAnnotations()) {
                if (isFrameworkAnnotation(annotation)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // err on the side of not fading
        }
    }

    /**
     * Check if an annotation is a framework entry-point annotation.
     */
    private static boolean isFrameworkAnnotation(IAnnotation annotation) {
        String name = annotation.getElementName();

        for (String frameworkAnnotation : FRAMEWORK_ANNOTATIONS) {
            if (frameworkAnnotation.equals(name)) {
                return true;
            }
        }

        for (String prefix : FRAMEWORK_ANNOTATION_FQ_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determine if a type is a framework-managed bean (e.g. annotated with
     * {@code @Component}, {@code @Service}, etc.).  Constructors in such
     * types are invoked by the framework and should not be flagged as unused.
     */
    private static boolean isFrameworkManagedType(IType type) {
        try {
            for (IAnnotation annotation : type.getAnnotations()) {
                String name = annotation.getElementName();
                for (String typeAnnotation : FRAMEWORK_TYPE_ANNOTATIONS) {
                    if (typeAnnotation.equals(name)) {
                        return true;
                    }
                }
                for (String prefix : FRAMEWORK_ANNOTATION_FQ_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return true; // err on the side of not fading
        }
    }

    /**
     * Check if a method is a main method entry point.
     */
    private static boolean isMainMethod(IMethod method) {
        try {
            if ("main".equals(method.getElementName())
                    && org.eclipse.jdt.core.Flags.isStatic(method.getFlags())) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Create an Unnecessary diagnostic for an unused declaration.
     */
    private static Diagnostic createUnusedDiagnostic(IJavaElement element, String content) {
        try {
            if (!(element instanceof ISourceReference sourceRef)) {
                return null;
            }

            ISourceRange nameRange = sourceRef.getNameRange();
            if (nameRange == null || nameRange.getOffset() < 0) {
                return null;
            }

            int startOffset = nameRange.getOffset();
            int endOffset = startOffset + nameRange.getLength();

            PositionUtils.LineIndex lineIndex = PositionUtils.buildLineIndex(content);
            Position start = lineIndex.offsetToPosition(startOffset);
            Position end = lineIndex.offsetToPosition(endOffset);

            String kind = element instanceof IType ? "type" : "method";

            Diagnostic diag = new Diagnostic();
            diag.setRange(new Range(start, end));
            diag.setSeverity(DiagnosticSeverity.Hint);
            diag.setMessage("The " + kind + " '" + element.getElementName()
                    + "' is never used");
            diag.setSource(DIAGNOSTIC_SOURCE);
            diag.setCode(DIAG_CODE_UNUSED_DECLARATION);
            diag.setTags(Collections.singletonList(DiagnosticTag.Unnecessary));
            return diag;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "Failed to create unused diagnostic for " + element.getElementName(), e);
            return null;
        }
    }

    private static Position offsetToPosition(String content, int offset) {
        return PositionUtils.offsetToPosition(content, offset);
    }
}
