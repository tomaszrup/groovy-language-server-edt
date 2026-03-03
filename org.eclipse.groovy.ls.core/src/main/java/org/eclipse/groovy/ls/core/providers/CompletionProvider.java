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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;

/**
 * Provides code completion for Groovy documents.
 * <p>
 * JDT's built-in {@code codeComplete()} uses a Java-only parser that cannot
 * handle Groovy syntax (always returns 0 proposals). This provider therefore
 * implements its own completion engine:
 * <ul>
 *   <li><b>Dot completion</b>: Uses {@code codeSelect()} (routed through
 *       {@link MinimalCodeSelectHelper} and the Groovy AST) to resolve the
 *       expression before the dot, then enumerates members of the resolved
 *       type via the JDT Java model and its type hierarchy.</li>
 *   <li><b>Type name completion</b>: Uses {@link SearchEngine#searchAllTypeNames}
 *       to find types matching the prefix (handles {@code @Annotation} too).</li>
 *   <li><b>Keyword completion</b>: Provides Groovy keywords.</li>
 *   <li><b>Fallback</b>: Uses the Groovy AST when JDT is unavailable.</li>
 * </ul>
 */
public class CompletionProvider {

    private static final class TypeSearchContext {
        final boolean annotationOnly;
        final String currentPackage;
        final Set<String> existingImports;
        final int importInsertLine;

        TypeSearchContext(boolean annotationOnly, String currentPackage,
                          Set<String> existingImports, int importInsertLine) {
            this.annotationOnly = annotationOnly;
            this.currentPackage = currentPackage;
            this.existingImports = existingImports;
            this.importInsertLine = importInsertLine;
        }
    }

    private static final String JAVA_LANG_PACKAGE = "java.lang.";
    private static final String JAVA_UTIL_PACKAGE = "java.util.";
    private static final String JAVA_IO_PACKAGE = "java.io.";
    private static final String JAVA_NET_PACKAGE = "java.net.";
    private static final String JAVA_MATH_PACKAGE = "java.math.";
    private static final String GROOVY_LANG_PACKAGE = "groovy.lang.";
    private static final String GROOVY_UTIL_PACKAGE = "groovy.util.";
    private static final String TRAIT_DETAIL_SUFFIX = " (trait)";
    private static final String OBJECT_TYPE_NAME = "Object";
    private static final String IMPORT_PREFIX = "import ";
    private static final String PACKAGE_PREFIX = "package ";

    private final DocumentManager documentManager;

    /**
     * Find a usable IJavaProject. The working copy's own project may be stale/non-existent
     * (e.g. due to URI encoding issues with special characters in paths).
     * Falls back to iterating all open workspace projects.
     */
    private static IJavaProject findWorkingProject(ICompilationUnit workingCopy) {
        IJavaProject project = getProjectFromWorkingCopy(workingCopy);
        return project != null ? project : findOpenWorkspaceJavaProject();
    }

    private static IJavaProject getProjectFromWorkingCopy(ICompilationUnit workingCopy) {
        if (workingCopy == null) {
            return null;
        }
        try {
            IJavaProject project = workingCopy.getJavaProject();
            return project != null && project.exists() ? project : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static IJavaProject findOpenWorkspaceJavaProject() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                IJavaProject javaProject = toJavaProject(project);
                if (javaProject != null) {
                    return javaProject;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static IJavaProject toJavaProject(IProject project) {
        try {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
                return null;
            }
            IJavaProject javaProject = JavaCore.create(project);
            return javaProject != null && javaProject.exists() ? javaProject : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Groovy auto-import packages (always available without explicit import). */
    private static final String[] GROOVY_AUTO_PACKAGES = {
        JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE, JAVA_IO_PACKAGE, JAVA_NET_PACKAGE,
        GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE, JAVA_MATH_PACKAGE
    };

    /** Maximum number of type search results. */
    private static final int MAX_TYPE_RESULTS = 100;

    /** Maximum number of member results per type hierarchy. */
    private static final int MAX_MEMBER_RESULTS = 300;

    /** java.lang.Object method names — these are always present; push them down in sorting. */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString", "hashCode", "equals", "getClass", "notify", "notifyAll",
            "wait", "clone", "finalize");

    /** Commit characters for method completions. */
    private static final List<String> METHOD_COMMIT_CHARS = List.of(".", "(");

    /** Commit characters for field/property completions. */
    private static final List<String> FIELD_COMMIT_CHARS = List.of(".");

    /** Commit characters for type completions. */
    private static final List<String> TYPE_COMMIT_CHARS = List.of(".");

    /** Commit characters for keyword completions. */
    private static final List<String> KEYWORD_COMMIT_CHARS = List.of(" ");

    private static final Gson GSON = new Gson();
    private static final String PARAM_COUNT_KEY = "paramCount";

    public CompletionProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Compute completion items at the cursor position.
     */
    public List<CompletionItem> getCompletions(CompletionParams params) {
        List<CompletionItem> items = new ArrayList<>();

        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        GroovyLanguageServerPlugin.logInfo("[completion] Request at " + uri
                + " line=" + position.getLine() + " char=" + position.getCharacter());

        String content = documentManager.getContent(uri);
        if (content == null) {
            GroovyLanguageServerPlugin.logInfo("[completion] No content for URI");
            return items;
        }

        int offset = positionToOffset(content, position);

        // Extract the identifier prefix being typed
        String prefix = extractPrefix(content, offset);
        int prefixStart = offset - prefix.length();

        // Check if there is a dot immediately before the prefix
        boolean isDotCompletion = (prefixStart > 0 && content.charAt(prefixStart - 1) == '.');
        boolean isAnnotationCompletion = isAnnotationContext(content, offset, prefixStart);

        GroovyLanguageServerPlugin.logInfo("[completion] prefix='" + prefix
            + "' isDot=" + isDotCompletion
            + " isAnnotation=" + isAnnotationCompletion
            + " offset=" + offset);

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            GroovyLanguageServerPlugin.logInfo("[completion] No working copy, using fallback");
            return getFallbackCompletions(uri, position);
        }

        try {
            if (isDotCompletion) {
                int dotPos = prefixStart - 1; // position of the '.'
                items.addAll(getDotCompletions(workingCopy, uri, content, dotPos, prefix));
                // Never add keywords/types after a dot — only member completions
            } else if (isAnnotationCompletion) {
                // After '@', only annotation types are valid.
                items.addAll(getTypeCompletions(workingCopy, uri, content, prefix, true));
            } else {
                // Non-dot context: identifiers + types + keywords
                items.addAll(getIdentifierCompletions(workingCopy, uri, prefix));
                if (!prefix.isEmpty()) {
                    items.addAll(getTypeCompletions(workingCopy, uri, content, prefix, false));
                }
                items.addAll(getKeywordCompletions(prefix));
            }

            GroovyLanguageServerPlugin.logInfo("[completion] Returning " + items.size() + " items");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Failed", e);
        }

        return items;
    }

    /**
     * Resolve additional details for a completion item.
     * <p>
     * Lazily loads Javadoc/Groovydoc documentation for the item by looking up
     * the JDT element from the stored data (declaring type FQN, element name,
     * parameter signatures).
     */
    public CompletionItem resolveCompletionItem(CompletionItem item) {
        if (item.getData() == null) {
            return item;
        }

        try {
            JsonObject data = parseItemData(item);
            if (data == null) {
                return item;
            }

            String documentation = resolveDocumentation(data);
            if (documentation != null && !documentation.isEmpty()) {
                MarkupContent markup = new MarkupContent();
                markup.setKind(MarkupKind.MARKDOWN);
                markup.setValue(documentation);
                item.setDocumentation(markup);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] resolveCompletionItem failed", e);
        }

        return item;
    }

    private JsonObject parseItemData(CompletionItem item) {
        JsonElement dataElement;
        if (item.getData() instanceof JsonElement je) {
            dataElement = je;
        } else {
            dataElement = GSON.toJsonTree(item.getData());
        }
        return dataElement.isJsonObject() ? dataElement.getAsJsonObject() : null;
    }

    private String resolveDocumentation(JsonObject data) throws JavaModelException {
        String kind = getJsonString(data, "kind");
        if (kind == null) {
            return null;
        }

        String fqn = getJsonString(data, "fqn");
        String elementName = getJsonString(data, "name");

        IJavaProject project = findOpenWorkspaceJavaProject();
        if (project == null || fqn == null) {
            return null;
        }

        IType type = project.findType(fqn);
        if (type == null || !type.exists()) {
            return null;
        }

        return resolveDocumentationForKind(kind, type, elementName, data);
    }

    private String resolveDocumentationForKind(String kind, IType type,
                                                String elementName, JsonObject data) {
        switch (kind) {
            case "method": {
                if (elementName == null) return null;
                IMethod method = findMethodInType(type, elementName, data);
                return method != null ? getJavadocForMember(method) : null;
            }
            case "field": {
                if (elementName == null) return null;
                IField field = type.getField(elementName);
                return (field != null && field.exists()) ? getJavadocForMember(field) : null;
            }
            case "type":
                return getJavadocForMember(type);
            default:
                return null;
        }
    }

    private IMethod findMethodInType(IType type, String name, JsonObject data) {
        try {
            // Try to match by parameter count first
            int paramCount = data.has(PARAM_COUNT_KEY) ? data.get(PARAM_COUNT_KEY).getAsInt() : -1;
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(name)
                        && (paramCount < 0 || method.getParameterTypes().length == paramCount)) {
                    return method;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Get Javadoc for a JDT member, falling back to source JAR resolution.
     */
    private String getJavadocForMember(IJavaElement element) {
        try {
            // Try JDT attached Javadoc first
            String attached = getAttachedJavadoc(element);
            if (attached != null) {
                return attached;
            }

            // Try source JAR approach
            return getJavadocFromSource(element);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Javadoc resolution failed for " + element.getElementName(), e);
        }
        return null;
    }

    private String getAttachedJavadoc(IJavaElement element) throws JavaModelException {
        if (element instanceof org.eclipse.jdt.core.IMember member) {
            String attached = member.getAttachedJavadoc(null);
            if (attached != null && !attached.isEmpty()) {
                return attached;
            }
        }
        return null;
    }

    private String getJavadocFromSource(IJavaElement element) {
        IType ownerType = resolveOwnerType(element);
        if (ownerType == null) {
            return null;
        }

        String source = readSourceForType(ownerType);
        if (source == null || source.isEmpty()) {
            return null;
        }

        if (element instanceof IType) {
            return SourceJarHelper.extractJavadoc(source, ownerType.getElementName());
        }
        return SourceJarHelper.extractMemberJavadoc(source, element.getElementName());
    }

    private IType resolveOwnerType(IJavaElement element) {
        if (element instanceof IType t) {
            return t;
        }
        IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
        return ancestor instanceof IType t ? t : null;
    }

    private String readSourceForType(IType ownerType) {
        String fqn = ownerType.getFullyQualifiedName();
        String source = null;

        if (ownerType.getClassFile() != null) {
            java.io.File sourcesJar = SourceJarHelper.findSourcesJar(ownerType);
            if (sourcesJar != null) {
                source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
            }
        }

        if (source == null || source.isEmpty()) {
            source = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        }

        return source;
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    // =========================================================================
    // Dot Completion  (expression.member)
    // =========================================================================

    /** Dummy identifier inserted after the dot to make the AST parseable. */
    private static final String DOT_COMPLETION_PLACEHOLDER = "__z__";

    /**
     * Provide completions after a dot by resolving the expression before
     * the dot to a type and listing its members.
     */
    private List<CompletionItem> getDotCompletions(ICompilationUnit workingCopy,
                                                    String lspUri, String content,
                                                    int dotPos, String prefix) {
        List<CompletionItem> items = new ArrayList<>();

        // When the prefix is empty (cursor right after the dot), the source
        // contains an incomplete expression like "foo." which breaks the
        // Groovy parser and causes codeSelect to return 0 elements.
        // Temporarily insert a dummy identifier after the dot so the parser
        // produces a valid AST. Restore only AFTER all fallback attempts,
        // so that AST-based fallbacks also see a valid AST.
        boolean patched = patchContentForDotCompletion(workingCopy, content, dotPos, prefix);

        try {
            // Walk backwards from the dot to find the identifier before it
            int exprEnd = dotPos; // content[dotPos] == '.'
            int exprStart = exprEnd - 1;
            while (exprStart >= 0 && Character.isJavaIdentifierPart(content.charAt(exprStart))) {
                exprStart--;
            }
            exprStart++;

            if (exprStart >= exprEnd) {
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] No identifier before dot at " + dotPos);
                return items;
            }

            String exprName = content.substring(exprStart, exprEnd);
            GroovyLanguageServerPlugin.logInfo("[completion] Dot on '" + exprName + "'");

            IJavaElement[] elements = workingCopy.codeSelect(exprStart, exprEnd - exprStart);
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] codeSelect returned " + elements.length + " element(s)");

            if (elements.length > 0) {
                IJavaElement element = elements[0];
                GroovyLanguageServerPlugin.logInfo("[completion]   element: "
                        + element.getClass().getSimpleName()
                        + " '" + element.getElementName() + "'");

                IJavaProject project = findWorkingProject(workingCopy);
                IType type = resolveElementType(element, project);

                if (type != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion]   resolved type: " + type.getFullyQualifiedName());
                    // If the expression starts with lowercase, it's a variable/field reference,
                    // not a type name — don't restrict to static members.
                    boolean staticOnly = (element instanceof IType)
                            && !exprName.isEmpty()
                            && Character.isUpperCase(exprName.charAt(0));
                    addMembersOfType(type, prefix, staticOnly, items);
                } else {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion]   could not resolve element type");
                }
            }

            // If codeSelect failed (e.g. broken AST after typing dot),
            // try direct JDT model lookup for the field
            if (items.isEmpty()) {
                IJavaProject proj = findWorkingProject(workingCopy);
                IType fieldType = findFieldTypeDirectly(workingCopy, lspUri, exprName, proj);
                if (fieldType != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion] Direct field lookup resolved: "
                            + fieldType.getFullyQualifiedName());
                    addMembersOfType(fieldType, prefix, false, items);
                }
            }

            // AST-based dot completion fallback: resolve the expression type via the AST
            if (items.isEmpty()) {
                addAstDotCompletions(workingCopy, lspUri, exprName, prefix, items);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Dot completion failed", e);
        } finally {
            // Restore original content after ALL resolution attempts are done
            if (patched) {
                restoreOriginalContent(workingCopy, content);
            }
        }

        return items;
    }

    /**
     * Temporarily patch the working copy buffer by inserting a dummy identifier
     * after the dot, so the Groovy parser can produce a valid AST.
     * Returns {@code true} if the patching succeeded and the caller must restore.
     */
    private boolean patchContentForDotCompletion(ICompilationUnit workingCopy,
                                                  String content, int dotPos,
                                                  String prefix) {
        if (!prefix.isEmpty()) {
            return false;
        }
        try {
            String patchedContent = content.substring(0, dotPos + 1)
                    + DOT_COMPLETION_PLACEHOLDER
                    + content.substring(dotPos + 1);
            documentManager.reconcileWithContent(workingCopy, patchedContent);
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Patched content for dot completion");
            return true;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] Failed to patch content for dot completion", e);
            return false;
        }
    }

    /**
     * Restore the original source content in the working copy buffer.
     */
    private void restoreOriginalContent(ICompilationUnit workingCopy,
                                         String originalContent) {
        try {
            documentManager.reconcileWithContent(workingCopy, originalContent);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] Failed to restore content after dot completion", e);
        }
    }

    /**
     * Resolve an {@link IJavaElement} (field, local variable, method, type) to
     * the {@link IType} it represents or evaluates to.
     */
    private IType resolveElementType(IJavaElement element, IJavaProject project)
            throws JavaModelException {

        if (element instanceof IType typeElement) {
            return typeElement;
        }

        String typeSig = null;
        IType declaringType = null;

        if (element instanceof IField field) {
            typeSig = field.getTypeSignature();
            declaringType = field.getDeclaringType();
        } else if (element instanceof ILocalVariable local) {
            typeSig = local.getTypeSignature();
            IJavaElement parent = local.getParent();
            if (parent instanceof IMember memberParent) {
                declaringType = memberParent.getDeclaringType();
            }
        } else if (element instanceof IMethod method) {
            typeSig = method.getReturnType();
            declaringType = method.getDeclaringType();
        }

        if (typeSig == null) return null;

        String typeName = Signature.toString(typeSig);
        GroovyLanguageServerPlugin.logInfo(
                "[completion]   typeSig='" + typeSig + "' → '" + typeName + "'");

        return resolveTypeName(typeName, declaringType, project);
    }

    /**
     * Resolve a type name (simple or qualified) to an {@link IType}.
     */
    private IType resolveTypeName(String typeName, IType declaringType,
                                   IJavaProject project) throws JavaModelException {
        // 1. Direct lookup (works for fully-qualified names)
        IType type = project.findType(typeName);
        if (type != null) return type;

        // 2. Resolve through declaring type's import context
        if (declaringType != null) {
            String[][] resolved = declaringType.resolveType(typeName);
            if (resolved != null && resolved.length > 0) {
                String fqn = resolved[0][0].isEmpty()
                        ? resolved[0][1]
                        : resolved[0][0] + "." + resolved[0][1];
                type = project.findType(fqn);
                if (type != null) return type;
            }
        }

        // 3. Try Groovy auto-import packages
        for (String pkg : GROOVY_AUTO_PACKAGES) {
            type = project.findType(pkg + typeName);
            if (type != null) return type;
        }

        return null;
    }

    /**
     * Add methods and fields of the given type (and its supertypes).
     */
    private void addMembersOfType(IType type, String prefix, boolean staticOnly,
                                   List<CompletionItem> items) throws JavaModelException {
        Set<String> seen = new HashSet<>();

        List<IType> chain = buildTypeHierarchyChain(type);
        for (int typeIndex = 0; typeIndex < chain.size() && items.size() < MAX_MEMBER_RESULTS; typeIndex++) {
            IType currentType = chain.get(typeIndex);
            String sortPrefix = (typeIndex == 0) ? "0" : "1";
            addMethodMembers(currentType, prefix, staticOnly, sortPrefix, seen, items);
            addFieldMembers(currentType, prefix, staticOnly, sortPrefix, seen, items);
        }

        GroovyLanguageServerPlugin.logInfo("[completion] Added " + items.size()
                + " members from " + type.getElementName()
                + " hierarchy (" + chain.size() + " types)");
    }

    private List<IType> buildTypeHierarchyChain(IType type) throws JavaModelException {
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(null);
        IType[] supertypes = hierarchy.getAllSupertypes(type);
        List<IType> chain = new ArrayList<>();
        chain.add(type);
        chain.addAll(Arrays.asList(supertypes));
        return chain;
    }

    private void addMethodMembers(IType ownerType, String prefix, boolean staticOnly,
                                  String sortPrefix, Set<String> seen,
                                  List<CompletionItem> items) throws JavaModelException {
        for (IMethod method : ownerType.getMethods()) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                break;
            }
            if (shouldIncludeMethod(method, prefix, staticOnly, seen)) {
                String name = method.getElementName();
                String key = name + "/" + method.getParameterTypes().length;
                seen.add(key);
                items.add(methodToCompletionItem(method, name, ownerType, sortPrefix));
            }
        }
    }

    private boolean shouldIncludeMethod(IMethod method, String prefix,
                                        boolean staticOnly, Set<String> seen)
            throws JavaModelException {
        String name = method.getElementName();
        if (name.startsWith("<") || !matchesPrefix(name, prefix)) {
            return false;
        }
        if (staticOnly && !Flags.isStatic(method.getFlags())) {
            return false;
        }
        String key = name + "/" + method.getParameterTypes().length;
        return !seen.contains(key);
    }

    private void addFieldMembers(IType ownerType, String prefix, boolean staticOnly,
                                 String sortPrefix, Set<String> seen,
                                 List<CompletionItem> items) throws JavaModelException {
        for (IField field : ownerType.getFields()) {
            if (items.size() >= MAX_MEMBER_RESULTS) {
                break;
            }
            if (shouldIncludeField(field, prefix, staticOnly, seen)) {
                String name = field.getElementName();
                seen.add("f:" + name);
                items.add(buildFieldCompletionItem(field, name, ownerType, sortPrefix));
            }
        }
    }

    private boolean shouldIncludeField(IField field, String prefix,
                                       boolean staticOnly, Set<String> seen)
            throws JavaModelException {
        String name = field.getElementName();
        if (name.startsWith("$") || name.startsWith("__") || !matchesPrefix(name, prefix)) {
            return false;
        }
        if (staticOnly && !Flags.isStatic(field.getFlags())) {
            return false;
        }
        return !seen.contains("f:" + name);
    }

    private CompletionItem buildFieldCompletionItem(IField field, String name,
                                                    IType ownerType, String sortPrefix)
            throws JavaModelException {
        CompletionItem item = new CompletionItem(name);
        boolean isEnum = Flags.isEnum(field.getFlags());
        item.setKind(isEnum ? CompletionItemKind.EnumMember : CompletionItemKind.Field);
        item.setDetail(resolveFieldDetail(field, ownerType));
        item.setInsertText(name);
        item.setFilterText(name);
        item.setCommitCharacters(FIELD_COMMIT_CHARS);

        // Deprecation marking
        boolean deprecated = Flags.isDeprecated(field.getFlags());
        if (deprecated) {
            item.setTags(List.of(CompletionItemTag.Deprecated));
            item.setSortText(sortPrefix + "z_" + name);
        } else {
            item.setSortText(sortPrefix + "_" + name);
        }

        // Store data for lazy resolution
        JsonObject data = new JsonObject();
        data.addProperty("kind", "field");
        data.addProperty("fqn", ownerType.getFullyQualifiedName());
        data.addProperty("name", name);
        item.setData(data);

        return item;
    }

    private String resolveFieldDetail(IField field, IType ownerType) {
        try {
            return Signature.toString(field.getTypeSignature()) + " — " + ownerType.getElementName();
        } catch (Exception e) {
            return ownerType.getElementName();
        }
    }

    /**
     * Convert an {@link IMethod} to a {@link CompletionItem}.
     */
    private CompletionItem methodToCompletionItem(IMethod method, String name,
                                                   IType owner, String sortPrefix) {
        CompletionItem item = new CompletionItem();
        try {
            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();

            item.setLabel(buildMethodLabel(name, paramTypes, paramNames));
            item.setDetail(Signature.toString(method.getReturnType()) + " — " + owner.getElementName());
            item.setKind(method.isConstructor()
                    ? CompletionItemKind.Constructor : CompletionItemKind.Method);
            setMethodInsertText(item, name, paramNames);
            item.setFilterText(name);
            item.setCommitCharacters(METHOD_COMMIT_CHARS);
            setMethodSortText(item, method, name, sortPrefix);

            // Store data for lazy resolution
            JsonObject data = new JsonObject();
            data.addProperty("kind", "method");
            data.addProperty("fqn", owner.getFullyQualifiedName());
            data.addProperty("name", name);
            data.addProperty(PARAM_COUNT_KEY, method.getParameterTypes().length);
            item.setData(data);
        } catch (Exception e) {
            item.setLabel(name);
            item.setInsertText(name);
        }
        return item;
    }

    private String buildMethodLabel(String name, String[] paramTypes, String[] paramNames) {
        StringBuilder label = new StringBuilder(name).append('(');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) label.append(", ");
            label.append(Signature.toString(paramTypes[i]));
            if (i < paramNames.length) {
                label.append(' ').append(paramNames[i]);
            }
        }
        label.append(')');
        return label.toString();
    }

    private void setMethodInsertText(CompletionItem item, String name, String[] paramNames) {
        if (paramNames.length > 0) {
            StringBuilder snippet = new StringBuilder(name).append('(');
            for (int i = 0; i < paramNames.length; i++) {
                if (i > 0) snippet.append(", ");
                snippet.append("${").append(i + 1).append(':')
                        .append(paramNames[i]).append('}');
            }
            snippet.append(')');
            item.setInsertText(snippet.toString());
            item.setInsertTextFormat(InsertTextFormat.Snippet);
        } else {
            item.setInsertText(name + "()");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
        }
    }

    private void setMethodSortText(CompletionItem item, IMethod method,
                                    String name, String sortPrefix) throws JavaModelException {
        boolean deprecated = Flags.isDeprecated(method.getFlags());
        if (deprecated) {
            item.setTags(List.of(CompletionItemTag.Deprecated));
            item.setSortText(sortPrefix + "z_" + name);
        } else if (OBJECT_METHODS.contains(name)) {
            item.setSortText("1y_" + name);
        } else {
            item.setSortText(sortPrefix + "_" + name);
        }
    }

    // =========================================================================
    // Direct Field Lookup  (bypass broken AST)
    // =========================================================================

    /**
     * Find the type of a field by directly looking it up in the JDT model.
     * This works even when the Groovy AST is broken (null ModuleNode),
     * e.g. right after typing a dot on an incomplete expression.
     */
    private IType findFieldTypeDirectly(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project) {
        try {
            for (IType type : workingCopy.getTypes()) {
                // Check fields
                IField field = type.getField(fieldName);
                if (field != null && field.exists()) {
                    String typeSig = field.getTypeSignature();
                    String typeName = Signature.toString(typeSig);
                    GroovyLanguageServerPlugin.logInfo(
                            "[completion] Direct field lookup: " + fieldName + " -> " + typeName);
                    return resolveTypeName(typeName, type, project);
                }
                // Check no-arg methods (in case 'foo.' refers to a method result)
                for (IMethod method : type.getMethods()) {
                    if (method.getElementName().equals(fieldName)
                            && method.getParameterTypes().length == 0) {
                        String returnSig = method.getReturnType();
                        String returnName = Signature.toString(returnSig);
                        GroovyLanguageServerPlugin.logInfo(
                                "[completion] Direct method lookup: " + fieldName + "() -> " + returnName);
                        return resolveTypeName(returnName, type, project);
                    }
                }
            }

            // Check trait-inherited fields/methods for the field name
            IType traitFieldType = findFieldTypeInTraits(workingCopy, lspUri, fieldName, project);
            if (traitFieldType != null) {
                return traitFieldType;
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("[completion] Direct field lookup failed", e);
        }
        return null;
    }

    /**
     * Search trait types for a field/property matching the given name.
     * Returns the resolved type of the field, or null if not found.
     * Uses both JDT and Groovy AST for resolution.
     */
    private IType findFieldTypeInTraits(ICompilationUnit workingCopy, String lspUri,
                                         String fieldName, IJavaProject project) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) return null;

        for (ClassNode classNode : ast.getClasses()) {
            IType resolved = findFieldTypeInClassTraits(classNode, ast, fieldName, project);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private ModuleNode resolveAst(ICompilationUnit workingCopy, String lspUri) {
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        return ast != null ? ast : getModuleFromWorkingCopy(workingCopy);
    }

    private IType findFieldTypeInClassTraits(ClassNode classNode, ModuleNode ast,
                                             String fieldName, IJavaProject project) {
        if (classNode.getLineNumber() < 0) {
            return null;
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return null;
        }

        for (ClassNode ifaceRef : interfaces) {
            IType resolved = findFieldTypeInTraitInterface(ifaceRef, classNode, ast, fieldName, project);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private IType findFieldTypeInTraitInterface(ClassNode ifaceRef, ClassNode classNode,
                                                ModuleNode ast, String fieldName,
                                                IJavaProject project) {
        IType jdtResolved = resolveFieldTypeFromTraitJdt(ifaceRef, classNode, ast, fieldName, project);
        if (jdtResolved != null) {
            return jdtResolved;
        }
        return resolveFieldTypeFromTraitAst(ifaceRef, ast, fieldName, project);
    }

    private IType resolveFieldTypeFromTraitJdt(ClassNode ifaceRef, ClassNode classNode,
                                               ModuleNode ast, String fieldName,
                                               IJavaProject project) {
        IType traitType = resolveTraitType(ifaceRef, classNode, ast, project);
        if (traitType == null) {
            return null;
        }

        try {
            IType directFieldType = resolveTraitDirectFieldType(traitType, fieldName, project);
            if (directFieldType != null) {
                return directFieldType;
            }
            return resolveTraitAccessorType(traitType, fieldName, project);
        } catch (JavaModelException e) {
            return null;
        }
    }

    private IType resolveTraitDirectFieldType(IType traitType, String fieldName,
                                              IJavaProject project)
            throws JavaModelException {
        IField traitField = traitType.getField(fieldName);
        if (traitField == null || !traitField.exists()) {
            return null;
        }

        String typeName = Signature.toString(traitField.getTypeSignature());
        GroovyLanguageServerPlugin.logInfo(
                "[completion] Trait field lookup: " + fieldName + " -> " + typeName);
        return resolveTypeName(typeName, traitType, project);
    }

    private IType resolveTraitAccessorType(IType traitType, String fieldName,
                                           IJavaProject project)
            throws JavaModelException {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (IMethod method : traitType.getMethods()) {
            if (isTraitAccessorForField(method, capitalized)) {
                String returnName = Signature.toString(method.getReturnType());
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait property lookup: " + fieldName + " -> " + returnName);
                return resolveTypeName(returnName, traitType, project);
            }
        }
        return null;
    }

    private boolean isTraitAccessorForField(IMethod method, String capitalizedFieldName) {
        String methodName = method.getElementName();
        boolean matchesName = methodName.equals("get" + capitalizedFieldName)
                || methodName.equals("is" + capitalizedFieldName);
        return matchesName && method.getParameterTypes().length == 0;
    }

    private IType resolveFieldTypeFromTraitAst(ClassNode ifaceRef, ModuleNode ast,
                                               String fieldName, IJavaProject project) {
        ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                ifaceRef, ast, documentManager);
        if (resolvedTraitNode == null || resolvedTraitNode.getLineNumber() < 0) {
            return null;
        }

        IType propertyType = resolveTraitAstPropertyType(resolvedTraitNode, fieldName, ast, project);
        if (propertyType != null) {
            return propertyType;
        }

        IType fieldType = resolveTraitAstFieldType(resolvedTraitNode, fieldName, ast, project);
        if (fieldType != null) {
            return fieldType;
        }

        return resolveTraitFieldHelperType(resolvedTraitNode, fieldName, ast, project);
    }

    private IType resolveTraitAstPropertyType(ClassNode traitNode, String fieldName,
                                              ModuleNode ast, IJavaProject project) {
        for (PropertyNode prop : traitNode.getProperties()) {
            if (fieldName.equals(prop.getName()) && prop.getType() != null) {
                String typeName = prop.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait AST property: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project);
            }
        }
        return null;
    }

    private IType resolveTraitAstFieldType(ClassNode traitNode, String fieldName,
                                           ModuleNode ast, IJavaProject project) {
        for (FieldNode field : traitNode.getFields()) {
            if (fieldName.equals(field.getName()) && field.getType() != null) {
                String typeName = field.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait AST field: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project);
            }
        }
        return null;
    }

    private IType resolveTraitFieldHelperType(ClassNode traitNode, String fieldName,
                                              ModuleNode ast, IJavaProject project) {
        ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(traitNode, ast);
        if (helperNode == null) {
            return null;
        }

        for (FieldNode helperField : helperNode.getFields()) {
            if (TraitMemberResolver.isTraitFieldMatch(helperField.getName(), fieldName)
                    && helperField.getType() != null) {
                String typeName = helperField.getType().getName();
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] Trait FieldHelper field: " + fieldName + " -> " + typeName);
                return resolveTypeNameFromAST(typeName, ast, project);
            }
        }
        return null;
    }

    /**
     * Resolve a type name from the Groovy AST to a JDT IType.
     * Tries FQN, imports, same-package, and auto-imports.
     */
    private IType resolveTypeNameFromAST(String typeName,
                                          ModuleNode module, IJavaProject project) {
        try {
            IType resolved = resolveQualifiedType(typeName, project);
            if (resolved != null) return resolved;

            resolved = resolveImportedAstType(typeName, module, project);
            if (resolved != null) return resolved;

            resolved = resolveSamePackageAstType(typeName, module, project);
            if (resolved != null) return resolved;

            resolved = resolveAstAutoImportType(typeName, project);
            if (resolved != null) return resolved;

            return resolveStarImportedAstType(typeName, module, project);
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    private IType resolveQualifiedType(String typeName, IJavaProject project)
            throws JavaModelException {
        return typeName.contains(".") ? project.findType(typeName) : null;
    }

    private IType resolveImportedAstType(String typeName, ModuleNode module,
                                         IJavaProject project) throws JavaModelException {
        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                IType resolved = project.findType(impType.getName());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private IType resolveSamePackageAstType(String typeName, ModuleNode module,
                                            IJavaProject project) throws JavaModelException {
        String pkg = normalizePackageName(module.getPackageName());
        return (pkg != null && !pkg.isEmpty()) ? project.findType(pkg + "." + typeName) : null;
    }

    private IType resolveAstAutoImportType(String typeName, IJavaProject project)
            throws JavaModelException {
        String[] autoPackages = {
            JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE, JAVA_IO_PACKAGE,
            GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE
        };
        for (String autoPkg : autoPackages) {
            IType resolved = project.findType(autoPkg + typeName);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private IType resolveStarImportedAstType(String typeName, ModuleNode module,
                                             IJavaProject project) throws JavaModelException {
        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType resolved = project.findType(pkgName + typeName);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Identifier Completion  (in-scope fields, methods, properties)
    // =========================================================================

    /**
     * Provide completions for in-scope identifiers (fields, methods) from
     * the current compilation unit. Gives the user access to local
     * declarations when typing without a dot.
     */
    private List<CompletionItem> getIdentifierCompletions(ICompilationUnit workingCopy,
                                                          String lspUri, String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        if (prefix.isEmpty()) return items;

        try {
            addTypeIdentifierCompletions(workingCopy, prefix, items);

            // Also add members inherited from traits/interfaces
            addTraitIdentifierCompletions(workingCopy, lspUri, prefix, items);

            // AST-based fallback: add own-class fields/properties/methods from Groovy AST
            // when JDT model returns empty (e.g., getModuleNode() returns null)
            if (items.isEmpty()) {
                addOwnClassAstCompletions(lspUri, workingCopy, prefix, items);
            }

            GroovyLanguageServerPlugin.logInfo("[completion] Identifier completions for '"
                    + prefix + "': " + items.size() + " results");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Identifier completions failed", e);
        }

        return items;
    }

    private void addTypeIdentifierCompletions(ICompilationUnit workingCopy,
                                              String prefix,
                                              List<CompletionItem> items)
            throws JavaModelException {
        for (IType type : workingCopy.getTypes()) {
            addTypeFieldIdentifierCompletions(type, prefix, items);
            addTypeMethodIdentifierCompletions(type, prefix, items);
        }
    }

    private void addTypeFieldIdentifierCompletions(IType type,
                                                   String prefix,
                                                   List<CompletionItem> items)
            throws JavaModelException {
        for (IField field : type.getFields()) {
            String name = field.getElementName();
            if (isTypeFieldIdentifierCandidate(name, prefix)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(resolveFieldTypeSignature(field));
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private boolean isTypeFieldIdentifierCandidate(String name, String prefix) {
        return !name.startsWith("$") && !name.startsWith("__") && matchesPrefix(name, prefix);
    }

    private String resolveFieldTypeSignature(IField field) {
        try {
            return Signature.toString(field.getTypeSignature());
        } catch (Exception e) {
            return OBJECT_TYPE_NAME;
        }
    }

    private void addTypeMethodIdentifierCompletions(IType type,
                                                    String prefix,
                                                    List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            String name = method.getElementName();
            if (!name.startsWith("<") && matchesPrefix(name, prefix)) {
                CompletionItem mi = methodToCompletionItem(method, name, type, "2");
                items.add(mi);
            }
        }
    }

    /**
     * Add completions for members inherited from traits via the JDT model.
     * Resolves each interface/trait type in the project and lists its members.
     */
    private void addTraitIdentifierCompletions(ICompilationUnit workingCopy,
                                                String lspUri,
                                                String prefix,
                                                List<CompletionItem> items) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);
        if (project == null) return;

        Set<String> seen = collectSeenCompletionNames(items);

        for (ClassNode classNode : ast.getClasses()) {
            addTraitCompletionsForClass(classNode, ast, prefix, project, seen, items);
        }
    }

    private Set<String> collectSeenCompletionNames(List<CompletionItem> items) {
        Set<String> seen = new HashSet<>();
        for (CompletionItem existing : items) {
            seen.add(existing.getFilterText() != null ? existing.getFilterText() : existing.getLabel());
        }
        return seen;
    }

    private void addTraitCompletionsForClass(ClassNode classNode, ModuleNode ast,
                                             String prefix, IJavaProject project,
                                             Set<String> seen, List<CompletionItem> items) {
        if (classNode.getLineNumber() < 0) {
            return;
        }
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return;
        }

        for (ClassNode ifaceRef : interfaces) {
            IType traitType = resolveTraitType(ifaceRef, classNode, ast, project);
            addTraitJdtIdentifierCompletions(traitType, prefix, seen, items);
            addTraitAstIdentifierCompletions(ifaceRef, ast, prefix, seen, items);
        }
    }

    private void addTraitJdtIdentifierCompletions(IType traitType, String prefix,
                                                  Set<String> seen, List<CompletionItem> items) {
        if (traitType == null) {
            return;
        }
        try {
            addTraitJdtFieldCompletions(traitType, prefix, seen, items);
            addTraitJdtMethodCompletions(traitType, prefix, seen, items);
            addTraitJdtPropertyCompletions(traitType, prefix, seen, items);
        } catch (JavaModelException e) {
            // ignore
        }
    }

    private void addTraitJdtFieldCompletions(IType traitType, String prefix,
                                             Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IField field : traitType.getFields()) {
            String name = field.getElementName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(resolveFieldTypeSignature(field) + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitJdtMethodCompletions(IType traitType, String prefix,
                                              Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : traitType.getMethods()) {
            String name = method.getElementName();
            String key = name + "/" + method.getParameterTypes().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem completion = methodToCompletionItem(method, name, traitType, "2");
                items.add(completion);
            }
        }
    }

    private void addTraitJdtPropertyCompletions(IType traitType, String prefix,
                                                Set<String> seen, List<CompletionItem> items)
            throws JavaModelException {
        for (IMethod method : traitType.getMethods()) {
            String methodName = method.getElementName();
            if (methodName.startsWith("get") && methodName.length() > 3
                    && method.getParameterTypes().length == 0) {
                String propertyName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                if (matchesPrefix(propertyName, prefix) && seen.add(propertyName)) {
                    CompletionItem item = new CompletionItem(propertyName);
                    item.setKind(CompletionItemKind.Property);
                    item.setDetail(resolveMethodReturnType(method) + " (trait property)");
                    item.setInsertText(propertyName);
                    item.setFilterText(propertyName);
                    item.setSortText("1_" + propertyName);
                    items.add(item);
                }
            }
        }
    }

    private String resolveMethodReturnType(IMethod method) {
        try {
            return Signature.toString(method.getReturnType());
        } catch (Exception e) {
            return OBJECT_TYPE_NAME;
        }
    }

    private void addTraitAstIdentifierCompletions(ClassNode ifaceRef, ModuleNode ast,
                                                  String prefix, Set<String> seen,
                                                  List<CompletionItem> items) {
        ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                ifaceRef, ast, documentManager);
        if (resolvedTraitNode == null || resolvedTraitNode.getLineNumber() < 0) {
            return;
        }

        addTraitAstPropertyCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitAstFieldCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitAstMethodCompletions(resolvedTraitNode, prefix, seen, items);
        addTraitFieldHelperCompletions(resolvedTraitNode, ast, prefix, seen, items);
    }

    private void addTraitAstPropertyCompletions(ClassNode traitNode, String prefix,
                                                Set<String> seen, List<CompletionItem> items) {
        for (PropertyNode prop : traitNode.getProperties()) {
            String name = prop.getName();
            if (matchesPrefix(name, prefix) && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                String typeName = prop.getType() != null
                        ? prop.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + " (trait property)");
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitAstFieldCompletions(ClassNode traitNode, String prefix,
                                             Set<String> seen, List<CompletionItem> items) {
        for (FieldNode field : traitNode.getFields()) {
            String name = field.getName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                String typeName = field.getType() != null
                        ? field.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitAstMethodCompletions(ClassNode traitNode, String prefix,
                                              Set<String> seen, List<CompletionItem> items) {
        for (MethodNode method : traitNode.getMethods()) {
            String name = method.getName();
            String key = name + "/" + method.getParameters().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && method.getLineNumber() >= 0
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                String returnType = method.getReturnType() != null
                        ? method.getReturnType().getNameWithoutPackage() : "void";
                item.setDetail(returnType + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(buildAstMethodInvocation(name, method.getParameters()));
                item.setFilterText(name);
                item.setSortText("2_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitFieldHelperCompletions(ClassNode traitNode, ModuleNode ast,
                                                String prefix, Set<String> seen,
                                                List<CompletionItem> items) {
        ClassNode helperNode = findTraitFieldHelperNode(traitNode, ast);
        if (helperNode == null) {
            return;
        }
        for (FieldNode helperField : helperNode.getFields()) {
            String rawName = helperField.getName();
            String demangledName = TraitMemberResolver.demangleTraitFieldName(rawName);
            if (!demangledName.equals(rawName)
                    && matchesPrefix(demangledName, prefix)
                    && seen.add(demangledName)) {
                CompletionItem item = new CompletionItem(demangledName);
                item.setKind(CompletionItemKind.Field);
                String typeName = helperField.getType() != null
                        ? helperField.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(demangledName);
                item.setFilterText(demangledName);
                item.setSortText("1_" + demangledName);
                items.add(item);
            }
        }
    }

    private String buildAstMethodInvocation(String name, Parameter[] parameters) {
        StringBuilder invocation = new StringBuilder(name).append("(");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                invocation.append(", ");
            }
            invocation.append(parameters[i].getName());
        }
        invocation.append(")");
        return invocation.toString();
    }

    /**
     * Find a trait FieldHelper node, searching in:
     * 1) current module, 2) trait's own module, 3) other open documents.
     */
    private ClassNode findTraitFieldHelperNode(ClassNode traitNode, ModuleNode currentModule) {
        if (traitNode == null) return null;

        ClassNode helper = TraitMemberResolver.findFieldHelperNode(traitNode, currentModule);
        if (helper != null) {
            return helper;
        }

        ModuleNode traitModule = traitNode.getModule();
        if (traitModule != null && traitModule != currentModule) {
            helper = TraitMemberResolver.findFieldHelperNode(traitNode, traitModule);
            if (helper != null) {
                return helper;
            }
        }

        for (String uri : documentManager.getOpenDocumentUris()) {
            ModuleNode otherModule = documentManager.getGroovyAST(uri);
            if (otherModule == null || otherModule == currentModule || otherModule == traitModule) {
                continue;
            }
            helper = TraitMemberResolver.findFieldHelperNode(traitNode, otherModule);
            if (helper != null) {
                return helper;
            }
        }

        return null;
    }

    /**
     * Add own-class fields, properties, and methods from the Groovy AST
     * when the JDT model returns empty (e.g., getModuleNode() is null).
     */
    private void addOwnClassAstCompletions(String lspUri, ICompilationUnit workingCopy,
                                            String prefix, List<CompletionItem> items) {
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        if (ast == null) {
            ast = getModuleFromWorkingCopy(workingCopy);
        }
        if (ast == null) return;

        Set<String> seen = new HashSet<>();
        for (CompletionItem existing : items) {
            seen.add(existing.getFilterText() != null ? existing.getFilterText() : existing.getLabel());
        }

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() >= 0) {
                addOwnClassAstCompletionsForClass(classNode, prefix, seen, items);
            }
        }
    }

    private void addOwnClassAstCompletionsForClass(ClassNode classNode,
                                                    String prefix,
                                                    Set<String> seen,
                                                    List<CompletionItem> items) {
        addOwnAstPropertyCompletions(classNode, prefix, seen, items);
        addOwnAstFieldCompletions(classNode, prefix, seen, items);
        addOwnAstMethodCompletions(classNode, prefix, seen, items);
    }

    private void addOwnAstPropertyCompletions(ClassNode classNode,
                                              String prefix,
                                              Set<String> seen,
                                              List<CompletionItem> items) {
        for (PropertyNode prop : classNode.getProperties()) {
            String name = prop.getName();
            if (matchesPrefix(name, prefix) && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                String typeName = prop.getType() != null
                    ? prop.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addOwnAstFieldCompletions(ClassNode classNode,
                                           String prefix,
                                           Set<String> seen,
                                           List<CompletionItem> items) {
        for (FieldNode field : classNode.getFields()) {
            String name = field.getName();
            if (!name.startsWith("$") && !name.startsWith("__")
                    && matchesPrefix(name, prefix)
                    && seen.add(name)) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                String typeName = field.getType() != null
                    ? field.getType().getNameWithoutPackage() : OBJECT_TYPE_NAME;
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }
        }
    }

    private void addOwnAstMethodCompletions(ClassNode classNode,
                                            String prefix,
                                            Set<String> seen,
                                            List<CompletionItem> items) {
        for (MethodNode method : classNode.getMethods()) {
            String name = method.getName();
            String key = name + "/" + method.getParameters().length;
            if (!name.startsWith("<") && !name.startsWith("$")
                    && method.getLineNumber() >= 0
                    && matchesPrefix(name, prefix)
                    && seen.add(key)) {
                CompletionItem mi = new CompletionItem(name);
                mi.setKind(CompletionItemKind.Method);
                String retType = method.getReturnType() != null
                        ? method.getReturnType().getNameWithoutPackage() : "void";
                mi.setDetail(retType);
                applyAstMethodInsertText(mi, name, method.getParameters());
                mi.setFilterText(name);
                mi.setSortText("2_" + name);
                items.add(mi);
            }
        }
    }

    private void applyAstMethodInsertText(CompletionItem item, String name, Parameter[] params) {
        if (params != null && params.length > 0) {
            StringBuilder snippet = new StringBuilder(name).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) snippet.append(", ");
                snippet.append("${").append(i + 1).append(':')
                        .append(params[i].getName()).append('}');
            }
            snippet.append(')');
            item.setInsertText(snippet.toString());
            item.setInsertTextFormat(InsertTextFormat.Snippet);
        } else {
            item.setInsertText(name + "()");
        }
    }

    /**
     * AST-based dot completion fallback: resolve the expression type via the
     * Groovy AST and list members. Handles fields/properties from traits and
     * own class when JDT codeSelect and direct model lookup both fail.
     */
    private void addAstDotCompletions(ICompilationUnit workingCopy, String lspUri,
                                       String exprName, String prefix,
                                       List<CompletionItem> items) {
        ModuleNode ast = resolveAst(workingCopy, lspUri);
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() >= 0) {
                ClassNode exprType = resolveAstExpressionType(classNode, ast, exprName);
                addAstResolvedTypeMembers(exprType, project, prefix, items);
                if (!items.isEmpty()) return;
            }
        }

        // Script-level local variables: check the module statement block directly
        ClassNode scriptType = resolveLocalVariableTypeInBlock(
                ast.getStatementBlock(), exprName);
        addAstResolvedTypeMembers(scriptType, project, prefix, items);
    }

    private ClassNode resolveAstExpressionType(ClassNode classNode, ModuleNode ast, String exprName) {
        ClassNode exprType = resolveClassMemberExpressionType(classNode, exprName);
        if (exprType != null) return exprType;

        exprType = resolveTraitMemberExpressionType(classNode, ast, exprName);
        if (exprType != null) return exprType;

        // Check local variables inside method bodies
        return resolveLocalVariableTypeInClass(classNode, exprName);
    }

    /**
     * Walk all methods (including the synthetic {@code run()} in script classes)
     * looking for a local variable declaration matching {@code varName}, and
     * return the declared/inferred type.
     */
    private ClassNode resolveLocalVariableTypeInClass(ClassNode classNode, String varName) {
        for (MethodNode method : classNode.getMethods()) {
            ClassNode type = resolveLocalVariableTypeInBlock(
                    methodBodyBlock(method), varName);
            if (type != null) return type;
        }
        // Also check constructors
        for (var ctor : classNode.getDeclaredConstructors()) {
            ClassNode type = resolveLocalVariableTypeInBlock(
                    methodBodyBlock(ctor), varName);
            if (type != null) return type;
        }
        return null;
    }

    /**
     * Extract the block statement from a method's code.
     */
    private BlockStatement methodBodyBlock(MethodNode method) {
        Statement code = method.getCode();
        return (code instanceof BlockStatement block) ? block : null;
    }

    /**
     * Search a {@link BlockStatement} for a {@code DeclarationExpression} whose
     * variable name matches {@code varName} and return the resolved type.
     */
    private ClassNode resolveLocalVariableTypeInBlock(BlockStatement block, String varName) {
        if (block == null) return null;

        for (Statement stmt : block.getStatements()) {
            ClassNode resolved = resolveVariableFromStatement(stmt, varName);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private ClassNode resolveVariableFromStatement(Statement stmt, String varName) {
        if (!(stmt instanceof ExpressionStatement exprStmt)) return null;
        if (!(exprStmt.getExpression() instanceof DeclarationExpression decl)) return null;

        Expression left = decl.getLeftExpression();
        if (!(left instanceof VariableExpression varExpr)) return null;
        if (!varName.equals(varExpr.getName())) return null;

        // Found the declaration — determine the type
        Expression initializer = decl.getRightExpression();

        // "def x = new Foo()" → use the constructor call type directly
        if (initializer instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }

        // Typed declaration (e.g. "String x = ...") → use the declared type
        ClassNode originType = varExpr.getOriginType();
        if (originType != null
                && !"java.lang.Object".equals(originType.getName())) {
            return originType;
        }

        // Fallback: use the initializer expression's inferred type
        return initializer != null ? initializer.getType() : null;
    }

    private ClassNode resolveClassMemberExpressionType(ClassNode classNode, String exprName) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (exprName.equals(prop.getName())) {
                return prop.getType();
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (exprName.equals(field.getName())) {
                return field.getType();
            }
        }
        return null;
    }

    private ClassNode resolveTraitMemberExpressionType(ClassNode classNode, ModuleNode ast,
                                                       String exprName) {
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces == null) {
            return null;
        }

        for (ClassNode ifaceRef : interfaces) {
            ClassNode traitNode = TraitMemberResolver.resolveTraitClassNode(ifaceRef, ast, documentManager);
            ClassNode resolvedType = resolveClassMemberExpressionType(traitNode, exprName);
            if (resolvedType != null) {
                return resolvedType;
            }
        }
        return null;
    }

    private void addAstResolvedTypeMembers(ClassNode exprType, IJavaProject project,
                                           String prefix, List<CompletionItem> items) {
        if (exprType == null || project == null) {
            return;
        }
        try {
            IType jdtType = resolveAstTypeInProject(project, exprType);
            if (jdtType != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[completion] AST dot fallback resolved: " + jdtType.getFullyQualifiedName());
                addMembersOfType(jdtType, prefix, false, items);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[completion] AST dot fallback type lookup failed", e);
        }
    }

    private IType resolveAstTypeInProject(IJavaProject project, ClassNode exprType)
            throws JavaModelException {
        IType jdtType = project.findType(exprType.getName());
        if (jdtType != null) {
            return jdtType;
        }
        String simpleName = exprType.getNameWithoutPackage();
        for (String pkg : GROOVY_AUTO_PACKAGES) {
            IType autoImported = project.findType(pkg + simpleName);
            if (autoImported != null) {
                return autoImported;
            }
        }
        return null;
    }

    /**
     * Get the Groovy ModuleNode directly from a working copy via reflection.
     * Works even when the URI-based lookup in DocumentManager fails due to URI format mismatch.
     */
    private ModuleNode getModuleFromWorkingCopy(ICompilationUnit workingCopy) {
        if (workingCopy == null) return null;
        try {
            java.lang.reflect.Method getModuleNode =
                    workingCopy.getClass().getMethod("getModuleNode");
            Object result = getModuleNode.invoke(workingCopy);
            if (result instanceof ModuleNode moduleNode) {
                return moduleNode;
            }
        } catch (Exception e) {
            // Not a GroovyCompilationUnit or reflection failed
        }
        return null;
    }

    /**
     * Resolve an interface/trait ClassNode reference to a JDT IType in the project.
     */
    private IType resolveTraitType(ClassNode ifaceRef, ClassNode owner,
                                    ModuleNode module, IJavaProject project) {
        String ifaceSimple = ifaceRef.getNameWithoutPackage();
        if (ifaceSimple == null || ifaceSimple.isEmpty()) return null;

        String ownerPkg = normalizePackageName(owner != null ? owner.getPackageName() : null);
        if ((ownerPkg == null || ownerPkg.isEmpty()) && module != null) {
            ownerPkg = normalizePackageName(module.getPackageName());
        }

        try {
            IType resolved = resolveTraitTypeByFqn(ifaceRef, project);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByOwnerPackage(ifaceSimple, ownerPkg, project);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByImports(ifaceSimple, module, project);
            if (resolved != null) return resolved;

            resolved = resolveTraitTypeByAutoImports(ifaceSimple, project);
            if (resolved != null) return resolved;

            return searchTypeBySimpleName(project, ifaceSimple, ownerPkg);
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    private IType resolveTraitTypeByFqn(ClassNode ifaceRef, IJavaProject project)
            throws JavaModelException {
        String fqn = ifaceRef.getName();
        return (fqn != null && fqn.contains(".")) ? project.findType(fqn) : null;
    }

    private IType resolveTraitTypeByOwnerPackage(String ifaceSimple, String ownerPkg,
                                                 IJavaProject project)
            throws JavaModelException {
        return (ownerPkg != null && !ownerPkg.isEmpty())
                ? project.findType(ownerPkg + "." + ifaceSimple)
                : null;
    }

    private IType resolveTraitTypeByImports(String ifaceSimple, ModuleNode module,
                                            IJavaProject project) throws JavaModelException {
        if (module == null) {
            return null;
        }
        for (org.codehaus.groovy.ast.ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && ifaceSimple.equals(impType.getNameWithoutPackage())) {
                IType resolved = project.findType(impType.getName());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        for (org.codehaus.groovy.ast.ImportNode starImport : module.getStarImports()) {
            String pkgName = normalizePackageName(starImport.getPackageName());
            if (pkgName != null && !pkgName.isEmpty()) {
                IType resolved = project.findType(pkgName + "." + ifaceSimple);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private IType resolveTraitTypeByAutoImports(String ifaceSimple, IJavaProject project)
            throws JavaModelException {
        String[] autoPackages = {
            GROOVY_LANG_PACKAGE, GROOVY_UTIL_PACKAGE, JAVA_LANG_PACKAGE, JAVA_UTIL_PACKAGE
        };
        for (String pkg : autoPackages) {
            IType resolved = project.findType(pkg + ifaceSimple);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String normalizePackageName(String packageName) {
        if (packageName == null) return null;
        String normalized = packageName.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private IType searchTypeBySimpleName(IJavaProject project,
                                          String simpleName,
                                          String preferredPackage) {
        if (simpleName == null || simpleName.isEmpty()) return null;

        final String normalizedPreferredPackage = normalizePackageName(preferredPackage);
        final String[] preferredFqn = new String[1];
        final String[] firstFqn = new String[1];

        try {
            SearchEngine engine = new SearchEngine();
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                    new IJavaElement[] { project },
                    IJavaSearchScope.SOURCES
                            | IJavaSearchScope.APPLICATION_LIBRARIES
                            | IJavaSearchScope.REFERENCED_PROJECTS);

            engine.searchAllTypeNames(
                    null,
                    SearchPattern.R_PATTERN_MATCH,
                    simpleName.toCharArray(),
                    SearchPattern.R_EXACT_MATCH,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameRequestor() {
                        @Override
                        public void acceptType(int modifiers, char[] packageName,
                                               char[] simpleTypeName,
                                               char[][] enclosingTypeNames, String path) {
                            String foundSimpleName = new String(simpleTypeName);
                            if (!simpleName.equals(foundSimpleName)) {
                                return;
                            }

                            String pkg = new String(packageName);
                            String fqn = pkg.isEmpty() ? foundSimpleName : pkg + "." + foundSimpleName;

                            if (firstFqn[0] == null) {
                                firstFqn[0] = fqn;
                            }

                            if (normalizedPreferredPackage != null
                                    && !normalizedPreferredPackage.isEmpty()
                                    && normalizedPreferredPackage.equals(pkg)) {
                                preferredFqn[0] = fqn;
                            }
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    null);

            String chosen = (preferredFqn[0] != null) ? preferredFqn[0] : firstFqn[0];
            if (chosen != null) {
                return project.findType(chosen);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logInfo(
                    "[completion] Trait type search fallback failed for '"
                            + simpleName + "': " + e.getMessage());
        }

        return null;
    }

    // =========================================================================
    // Type Search Completion  (type names, annotations)
    // =========================================================================

    /**
     * Search for types matching the prefix using the JDT search engine.
     */
    private List<CompletionItem> getTypeCompletions(ICompilationUnit workingCopy,
                                 String uri,
                                 String content,
                                                     String prefix,
                                                     boolean annotationOnly) {
        List<CompletionItem> items = new ArrayList<>();
        if (prefix.isEmpty() && !annotationOnly) return items;

        try {
            IJavaProject project = findWorkingProject(workingCopy);
            if (project == null) return items;
            Set<String> existingImports = getExistingImports(uri, content);
            String currentPackage = getCurrentPackageName(content);
            int importInsertLine = findImportInsertLine(content);
            Set<String> seenSimpleNames = new HashSet<>();
                TypeSearchContext searchContext = new TypeSearchContext(
                    annotationOnly, currentPackage, existingImports, importInsertLine);

            if (annotationOnly) {
                addImportedAnnotationCompletions(project, existingImports, prefix, items, seenSimpleNames);
            }

            SearchEngine engine = new SearchEngine();
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                    new IJavaElement[]{project},
                    IJavaSearchScope.SOURCES
                        | IJavaSearchScope.APPLICATION_LIBRARIES
                        | IJavaSearchScope.SYSTEM_LIBRARIES);

            engine.searchAllTypeNames(
                    null, // any package
                    SearchPattern.R_PATTERN_MATCH,
                    (prefix.isEmpty() ? "*" : prefix).toCharArray(),
                    prefix.isEmpty() ? SearchPattern.R_PATTERN_MATCH : SearchPattern.R_PREFIX_MATCH,
                    IJavaSearchConstants.TYPE,
                    scope,
                    new TypeNameRequestor() {
                        @Override
                        public void acceptType(int modifiers, char[] packageName,
                                              char[] simpleTypeName,
                                              char[][] enclosingTypeNames, String path) {
                            if (items.size() >= MAX_TYPE_RESULTS) return;

                            String simpleName = new String(simpleTypeName);
                            String pkg = new String(packageName);
                            String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;

                            if (isSearchResultEligible(simpleName, fqn, modifiers, annotationOnly,
                                    project, seenSimpleNames)) {
                                CompletionItem item = buildTypeSearchItem(simpleName, pkg, modifiers,
                                    fqn, searchContext);
                                items.add(item);
                            }
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    null);

            GroovyLanguageServerPlugin.logInfo("[completion] Type search for '"
                    + prefix + "': " + items.size() + " results");
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[completion] Type search failed", e);
        }

        return items;
    }

    private boolean isSearchResultEligible(String simpleName, String fqn, int modifiers,
                                           boolean annotationOnly, IJavaProject project,
                                           Set<String> seenSimpleNames) {
        if (annotationOnly && !isAnnotationTypeCandidate(project, fqn, modifiers)) {
            return false;
        }
        if (seenSimpleNames.contains(simpleName)) {
            return false;
        }
        seenSimpleNames.add(simpleName);
        return true;
    }

    private CompletionItem buildTypeSearchItem(String simpleName, String pkg, int modifiers,
                                               String fqn,
                                               TypeSearchContext searchContext) {
        CompletionItem item = new CompletionItem(simpleName);
        item.setKind(resolveTypeKind(modifiers));
        item.setDetail(pkg.isEmpty() ? simpleName : pkg + "." + simpleName);
        item.setInsertText(simpleName);
        item.setFilterText(simpleName);
        item.setCommitCharacters(TYPE_COMMIT_CHARS);
        if (shouldAutoImportType(fqn, pkg, searchContext.currentPackage,
                searchContext.existingImports)) {
            item.setAdditionalTextEdits(
                    java.util.Collections.singletonList(createImportEdit(searchContext.importInsertLine, fqn)));
        }

        // Deprecation marking
        boolean deprecated = Flags.isDeprecated(modifiers);
        String sortPrefix = searchContext.annotationOnly ? "4_" : "5_";
        if (deprecated) {
            item.setTags(List.of(CompletionItemTag.Deprecated));
            item.setSortText(sortPrefix + "z_" + simpleName);
        } else {
            item.setSortText(sortPrefix + simpleName);
        }

        // Store data for lazy resolution
        JsonObject data = new JsonObject();
        data.addProperty("kind", "type");
        data.addProperty("fqn", fqn);
        item.setData(data);

        return item;
    }

    private CompletionItemKind resolveTypeKind(int modifiers) {
        if (Flags.isInterface(modifiers)) {
            return CompletionItemKind.Interface;
        }
        if (Flags.isEnum(modifiers)) {
            return CompletionItemKind.Enum;
        }
        return CompletionItemKind.Class;
    }

    private TextEdit createImportEdit(int importInsertLine, String fqn) {
        return new TextEdit(
                new org.eclipse.lsp4j.Range(
                        new Position(importInsertLine, 0),
                        new Position(importInsertLine, 0)),
                IMPORT_PREFIX + fqn + "\n");
    }

    private void addImportedAnnotationCompletions(IJavaProject project,
                                                  Set<String> existingImports,
                                                  String prefix,
                                                  List<CompletionItem> items,
                                                  Set<String> seenSimpleNames) {
        for (String fqn : existingImports) {
            addImportedAnnotationCompletion(project, fqn, prefix, items, seenSimpleNames);
        }
    }

    private void addImportedAnnotationCompletion(IJavaProject project,
                                                 String fqn,
                                                 String prefix,
                                                 List<CompletionItem> items,
                                                 Set<String> seenSimpleNames) {
        int lastDot = fqn.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        if (!matchesPrefix(simpleName, prefix) || !isAnnotationImport(project, fqn)) {
            return;
        }
        if (!seenSimpleNames.add(simpleName)) {
            return;
        }

        CompletionItem item = new CompletionItem(simpleName);
        item.setKind(CompletionItemKind.Class);
        item.setDetail(fqn);
        item.setInsertText(simpleName);
        item.setFilterText(simpleName);
        item.setSortText("3_" + simpleName);
        items.add(item);
    }

    private boolean isAnnotationImport(IJavaProject project, String fqn) {
        try {
            IType type = project.findType(fqn);
            return type != null && type.exists() && type.isAnnotation();
        } catch (JavaModelException e) {
            return false;
        }
    }

    private boolean isAnnotationTypeCandidate(IJavaProject project, String fqn, int modifiers) {
        if (Flags.isAnnotation(modifiers)) {
            return true;
        }

        try {
            IType type = project.findType(fqn);
            return type != null && type.exists() && type.isAnnotation();
        } catch (JavaModelException e) {
            return false;
        }
    }

    // =========================================================================
    // Keyword Completion
    // =========================================================================

    /** Groovy keywords for completion. */
    private static final String[] GROOVY_KEYWORDS = {
        "abstract", "as", "assert", "boolean", "break", "byte",
        "case", "catch", "char", "class", "const", "continue",
        "def", "default", "do", "double", "else", "enum", "extends",
        "false", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "in", "instanceof", "int",
        "interface", "long", "native", "new", "null",
        "package", "private", "protected", "public", "return",
        "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "trait", "transient", "true", "try",
        "var", "void", "volatile", "while",
    };

    private List<CompletionItem> getKeywordCompletions(String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        for (String keyword : GROOVY_KEYWORDS) {
            if (prefix.isEmpty() || keyword.startsWith(prefix)) {
                CompletionItem item = new CompletionItem(keyword);
                item.setKind(CompletionItemKind.Keyword);
                item.setInsertText(keyword);
                item.setFilterText(keyword);
                item.setCommitCharacters(KEYWORD_COMMIT_CHARS);
                item.setSortText("9_" + keyword);
                items.add(item);
            }
        }
        return items;
    }

    // =========================================================================
    // Fallback Completion  (no JDT working copy)
    // =========================================================================

    /**
     * Provide fallback completions using Groovy keywords and the Groovy AST
     * when no JDT working copy is available.
     */
    private List<CompletionItem> getFallbackCompletions(String uri, Position position) {
        List<CompletionItem> items = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) return items;

        int offset = positionToOffset(content, position);
        String prefix = extractPrefix(content, offset);
        int prefixStart = offset - prefix.length();
        boolean isAnnotationCompletion = isAnnotationContext(content, offset, prefixStart);

        if (isAnnotationCompletion) {
            // No JDT available: avoid suggesting non-annotation identifiers/keywords in '@' context.
            return items;
        }

        // Keywords
        items.addAll(getKeywordCompletions(prefix));

        // Identifiers from the Groovy AST
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast != null) {
            for (ClassNode classNode : ast.getClasses()) {
                addClassCompletions(classNode, prefix, items);
            }
        }

        return items;
    }

    /**
     * Add completion items for members of a Groovy class node (fallback mode).
     */
    private void addClassCompletions(ClassNode classNode, String prefix,
                                      List<CompletionItem> items) {
        addFallbackClassNameCompletion(classNode, prefix, items);
        addFallbackMethodCompletions(classNode, prefix, items);
        addFallbackFieldCompletions(classNode, prefix, items);
        addFallbackPropertyCompletions(classNode, prefix, items);
        addTraitMemberCompletions(classNode, prefix, items);
    }

    private void addFallbackClassNameCompletion(ClassNode classNode, String prefix,
                                                List<CompletionItem> items) {
        String className = classNode.getNameWithoutPackage();
        if (className != null && (prefix.isEmpty() || className.startsWith(prefix))) {
            CompletionItem item = new CompletionItem(className);
            item.setKind(CompletionItemKind.Class);
            item.setDetail(classNode.getName());
            item.setSortText("2_" + className);
            items.add(item);
        }
    }

    private void addFallbackMethodCompletions(ClassNode classNode, String prefix,
                                              List<CompletionItem> items) {
        for (MethodNode method : classNode.getMethods()) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage());
                applyAstMethodInsertText(item, name, method.getParameters());
                item.setSortText("3_" + name);
                items.add(item);
            }
        }
    }

    private void addFallbackFieldCompletions(ClassNode classNode, String prefix,
                                             List<CompletionItem> items) {
        for (FieldNode field : classNode.getFields()) {
            String name = field.getName();
            if (name != null && !name.startsWith("$")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(field.getType().getNameWithoutPackage());
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    private void addFallbackPropertyCompletions(ClassNode classNode, String prefix,
                                                List<CompletionItem> items) {
        for (PropertyNode prop : classNode.getProperties()) {
            String name = prop.getName();
            if (name != null && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                item.setDetail(prop.getType().getNameWithoutPackage());
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    /**
     * Add completion items for members inherited from traits/interfaces.
     */
    private void addTraitMemberCompletions(ClassNode classNode, String prefix,
                                            List<CompletionItem> items) {
        ModuleNode module = classNode.getModule();
        addTraitMethodCompletions(classNode, module, prefix, items);
        addTraitFieldCompletions(classNode, module, prefix, items);
        addTraitPropertyCompletions(classNode, module, prefix, items);
    }

    private void addTraitMethodCompletions(ClassNode classNode,
                                           ModuleNode module,
                                           String prefix,
                                           List<CompletionItem> items) {
        for (MethodNode method : TraitMemberResolver.collectTraitMethods(classNode, module, documentManager)) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                applyAstMethodInsertText(item, name, method.getParameters());
                item.setSortText("3_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitFieldCompletions(ClassNode classNode,
                                          ModuleNode module,
                                          String prefix,
                                          List<CompletionItem> items) {
        for (FieldNode field : TraitMemberResolver.collectTraitFields(classNode, module, documentManager)) {
            String name = field.getName();
            if (name != null && !name.startsWith("$")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(field.getType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    private void addTraitPropertyCompletions(ClassNode classNode,
                                             ModuleNode module,
                                             String prefix,
                                             List<CompletionItem> items) {
        for (PropertyNode prop : TraitMemberResolver.collectTraitProperties(classNode, module, documentManager)) {
            String name = prop.getName();
            if (name != null && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                item.setDetail(prop.getType().getNameWithoutPackage() + TRAIT_DETAIL_SUFFIX);
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Case-insensitive prefix match. */
    private boolean matchesPrefix(String name, String prefix) {
        if (prefix.isEmpty()) return true;
        return name.toLowerCase().startsWith(prefix.toLowerCase());
    }

    /**
     * Extract the identifier prefix being typed at the given offset.
     */
    private String extractPrefix(String content, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        return content.substring(start, offset);
    }

    private Set<String> getExistingImports(String uri, String content) {
        Set<String> imports = new HashSet<>();
        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast != null) {
            for (ImportNode imp : ast.getImports()) {
                if (imp.getType() != null) {
                    imports.add(imp.getType().getName());
                }
            }
        }

        imports.addAll(parseImportsFromContent(content));

        return imports;
    }

    private Set<String> parseImportsFromContent(String content) {
        Set<String> imports = new HashSet<>();
        if (content == null || content.isEmpty()) {
            return imports;
        }

        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            String extractedImport = extractNonStaticImport(line.trim());
            if (extractedImport != null) {
                imports.add(extractedImport);
            }
        }

        return imports;
    }

    private String extractNonStaticImport(String trimmedLine) {
        if (!trimmedLine.startsWith(IMPORT_PREFIX)) {
            return null;
        }

        String importTarget = trimmedLine.substring(IMPORT_PREFIX.length()).trim();
        if (importTarget.startsWith("static ")) {
            return null;
        }
        if (importTarget.endsWith(";")) {
            importTarget = importTarget.substring(0, importTarget.length() - 1).trim();
        }
        if (importTarget.isEmpty() || importTarget.indexOf('.') < 0 || importTarget.endsWith(".*")) {
            return null;
        }
        return importTarget;
    }

    private boolean shouldAutoImportType(String fqn, String packageName,
                                         String currentPackage, Set<String> existingImports) {
        return fqn != null
                && !fqn.isEmpty()
                && packageName != null
                && !packageName.isEmpty()
                && !existingImports.contains(fqn)
                && !packageName.equals(currentPackage)
                && !isAutoImportedPackage(packageName);
    }

    private boolean isAutoImportedPackage(String packageName) {
        for (String autoPkg : GROOVY_AUTO_PACKAGES) {
            String normalized = autoPkg.endsWith(".")
                    ? autoPkg.substring(0, autoPkg.length() - 1)
                    : autoPkg;
            if (normalized.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private String getCurrentPackageName(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(PACKAGE_PREFIX)) {
                String pkg = trimmed.substring(PACKAGE_PREFIX.length()).trim();
                if (pkg.endsWith(";")) {
                    pkg = pkg.substring(0, pkg.length() - 1).trim();
                }
                return pkg;
            }
            if (trimmed.startsWith(IMPORT_PREFIX) || trimmed.startsWith("class ")
                    || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")
                    || trimmed.startsWith("trait ")) {
                break;
            }
        }

        return "";
    }

    private int findImportInsertLine(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        String[] lines = content.split("\n", -1);
        int lastImportLine = -1;
        int packageLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(PACKAGE_PREFIX)) {
                packageLine = i;
            }
            if (trimmed.startsWith(IMPORT_PREFIX)) {
                lastImportLine = i;
            }
            if (trimmed.startsWith("class ") || trimmed.startsWith("interface ")
                    || trimmed.startsWith("enum ") || trimmed.startsWith("trait ")
                    || trimmed.startsWith("@") || trimmed.startsWith("def ")
                    || trimmed.startsWith("public ") || trimmed.startsWith("abstract ")
                    || trimmed.startsWith("final ")) {
                break;
            }
        }

        if (lastImportLine >= 0) {
            return lastImportLine + 1;
        }
        if (packageLine >= 0) {
            return packageLine + 2;
        }
        return 0;
    }

    /**
     * Detect whether completion is requested in annotation context (around '@').
     * Handles cursor positions both after and on the '@' character.
     */
    private boolean isAnnotationContext(String content, int offset, int prefixStart) {
        return (prefixStart > 0 && content.charAt(prefixStart - 1) == '@')
                || (offset > 0 && content.charAt(offset - 1) == '@')
                || (prefixStart >= 0 && prefixStart < content.length()
                        && content.charAt(prefixStart) == '@');
    }

    /**
     * Convert an LSP position (line/column) to a character offset.
     */
    private int positionToOffset(String content, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < content.length() && line < position.getLine()) {
            if (content.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), content.length());
    }
}
