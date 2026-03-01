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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
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
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
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

    private final DocumentManager documentManager;

    /**
     * Find a usable IJavaProject. The working copy's own project may be stale/non-existent
     * (e.g. due to URI encoding issues with special characters in paths).
     * Falls back to iterating all open workspace projects.
     */
    private static IJavaProject findWorkingProject(ICompilationUnit workingCopy) {
        // Try the working copy's own project first
        if (workingCopy != null) {
            try {
                IJavaProject p = workingCopy.getJavaProject();
                if (p != null && p.exists()) {
                    return p;
                }
            } catch (Exception e) {
                // fall through
            }
        }
        // Fallback: iterate all open workspace projects
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject proj : projects) {
                if (!proj.isOpen()) continue;
                if (proj.hasNature(JavaCore.NATURE_ID)) {
                    IJavaProject jp = JavaCore.create(proj);
                    if (jp != null && jp.exists()) {
                        return jp;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** Groovy auto-import packages (always available without explicit import). */
    private static final String[] GROOVY_AUTO_PACKAGES = {
        "java.lang.", "java.util.", "java.io.", "java.net.",
        "groovy.lang.", "groovy.util.", "java.math."
    };

    /** Maximum number of type search results. */
    private static final int MAX_TYPE_RESULTS = 100;

    /** Maximum number of member results per type hierarchy. */
    private static final int MAX_MEMBER_RESULTS = 300;

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
     */
    public CompletionItem resolveCompletionItem(CompletionItem item) {
        return item;
    }

    // =========================================================================
    // Dot Completion  (expression.member)
    // =========================================================================

    /**
     * Provide completions after a dot by resolving the expression before
     * the dot to a type and listing its members.
     */
    private List<CompletionItem> getDotCompletions(ICompilationUnit workingCopy,
                                                    String lspUri, String content,
                                                    int dotPos, String prefix) {
        List<CompletionItem> items = new ArrayList<>();

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

            // codeSelect resolves the identifier via MinimalCodeSelectHelper
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
                            && exprName.length() > 0
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
        }

        return items;
    }

    /**
     * Resolve an {@link IJavaElement} (field, local variable, method, type) to
     * the {@link IType} it represents or evaluates to.
     */
    private IType resolveElementType(IJavaElement element, IJavaProject project)
            throws JavaModelException {

        if (element instanceof IType) {
            return (IType) element;
        }

        String typeSig = null;
        IType declaringType = null;

        if (element instanceof IField) {
            IField field = (IField) element;
            typeSig = field.getTypeSignature();
            declaringType = field.getDeclaringType();
        } else if (element instanceof ILocalVariable) {
            ILocalVariable local = (ILocalVariable) element;
            typeSig = local.getTypeSignature();
            IJavaElement parent = local.getParent();
            if (parent instanceof IMember) {
                declaringType = ((IMember) parent).getDeclaringType();
            }
        } else if (element instanceof IMethod) {
            IMethod method = (IMethod) element;
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

        // Build supertype hierarchy
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(null);
        IType[] supertypes = hierarchy.getAllSupertypes(type);

        List<IType> chain = new ArrayList<>();
        chain.add(type);
        chain.addAll(Arrays.asList(supertypes));

        for (int ti = 0; ti < chain.size() && items.size() < MAX_MEMBER_RESULTS; ti++) {
            IType t = chain.get(ti);
            String sortPrefix = (ti == 0) ? "0" : "1";

            // Methods
            for (IMethod method : t.getMethods()) {
                if (items.size() >= MAX_MEMBER_RESULTS) break;
                String name = method.getElementName();
                if (name.startsWith("<")) continue;
                if (!matchesPrefix(name, prefix)) continue;
                if (staticOnly && !Flags.isStatic(method.getFlags())) continue;

                String key = name + "/" + method.getParameterTypes().length;
                if (seen.contains(key)) continue;
                seen.add(key);

                items.add(methodToCompletionItem(method, name, t, sortPrefix));
            }

            // Fields
            for (IField field : t.getFields()) {
                if (items.size() >= MAX_MEMBER_RESULTS) break;
                String name = field.getElementName();
                if (name.startsWith("$") || name.startsWith("__")) continue;
                if (!matchesPrefix(name, prefix)) continue;
                if (staticOnly && !Flags.isStatic(field.getFlags())) continue;

                String key = "f:" + name;
                if (seen.contains(key)) continue;
                seen.add(key);

                CompletionItem item = new CompletionItem(name);
                item.setKind(Flags.isEnum(field.getFlags())
                        ? CompletionItemKind.EnumMember : CompletionItemKind.Field);
                try {
                    item.setDetail(Signature.toString(field.getTypeSignature())
                            + " — " + t.getElementName());
                } catch (Exception ignored) {}
                item.setInsertText(name);
                item.setSortText(sortPrefix + "_" + name);
                items.add(item);
            }
        }

        GroovyLanguageServerPlugin.logInfo("[completion] Added " + items.size()
                + " members from " + type.getElementName()
                + " hierarchy (" + chain.size() + " types)");
    }

    /**
     * Convert an {@link IMethod} to a {@link CompletionItem}.
     */
    private CompletionItem methodToCompletionItem(IMethod method, String name,
                                                   IType owner, String sortPrefix) {
        CompletionItem item = new CompletionItem();
        try {
            // Label: name(ParamType1 p1, ParamType2 p2)
            String[] paramTypes = method.getParameterTypes();
            String[] paramNames = method.getParameterNames();
            StringBuilder label = new StringBuilder(name).append('(');
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) label.append(", ");
                label.append(Signature.toString(paramTypes[i]));
                if (i < paramNames.length) {
                    label.append(' ').append(paramNames[i]);
                }
            }
            label.append(')');
            item.setLabel(label.toString());

            // Detail: returnType — DeclaringClass
            String returnType = Signature.toString(method.getReturnType());
            item.setDetail(returnType + " — " + owner.getElementName());

            // Kind
            item.setKind(method.isConstructor()
                    ? CompletionItemKind.Constructor : CompletionItemKind.Method);

            // Insert text as snippet with parameter placeholders
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

            item.setFilterText(name);
            item.setSortText(sortPrefix + "_" + name);
        } catch (Exception e) {
            item.setLabel(name);
            item.setInsertText(name);
        }
        return item;
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
        // Use the original LSP URI for AST lookup (avoids encoding mismatch)
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        if (ast == null) {
            // Fallback: get module node directly from GroovyCompilationUnit
            ast = getModuleFromWorkingCopy(workingCopy);
        }
        if (ast == null) return null;

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            ClassNode[] interfaces = classNode.getInterfaces();
            if (interfaces == null) continue;

            for (ClassNode ifaceRef : interfaces) {
                // Try JDT-based resolution first
                IType traitType = resolveTraitType(ifaceRef, classNode, ast, project);
                if (traitType != null) {
                    try {
                        // Direct field
                        IField traitField = traitType.getField(fieldName);
                        if (traitField != null && traitField.exists()) {
                            String typeSig = traitField.getTypeSignature();
                            String typeName = Signature.toString(typeSig);
                            GroovyLanguageServerPlugin.logInfo(
                                    "[completion] Trait field lookup: " + fieldName + " -> " + typeName);
                            return resolveTypeName(typeName, traitType, project);
                        }

                        // Property accessor: getFieldName()
                        String cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                        for (IMethod method : traitType.getMethods()) {
                            String mname = method.getElementName();
                            if ((mname.equals("get" + cap) || mname.equals("is" + cap))
                                    && method.getParameterTypes().length == 0) {
                                String returnSig = method.getReturnType();
                                String returnName = Signature.toString(returnSig);
                                GroovyLanguageServerPlugin.logInfo(
                                        "[completion] Trait property lookup: " + fieldName + " -> " + returnName);
                                return resolveTypeName(returnName, traitType, project);
                            }
                        }
                    } catch (JavaModelException e) {
                        // ignore, try AST fallback
                    }
                }

                // AST-based fallback: check the Groovy AST for trait property types
                ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                        ifaceRef, ast, documentManager);
                if (resolvedTraitNode != null && resolvedTraitNode.getLineNumber() >= 0) {
                    // Check properties
                    for (PropertyNode prop : resolvedTraitNode.getProperties()) {
                        if (fieldName.equals(prop.getName())) {
                            ClassNode propType = prop.getType();
                            if (propType != null) {
                                String typeName = propType.getName();
                                GroovyLanguageServerPlugin.logInfo(
                                        "[completion] Trait AST property: " + fieldName + " -> " + typeName);
                                return resolveTypeNameFromAST(typeName, propType, ast, project);
                            }
                        }
                    }
                    // Check fields
                    for (FieldNode field : resolvedTraitNode.getFields()) {
                        if (fieldName.equals(field.getName())) {
                            ClassNode fieldType = field.getType();
                            if (fieldType != null) {
                                String typeName = fieldType.getName();
                                GroovyLanguageServerPlugin.logInfo(
                                        "[completion] Trait AST field: " + fieldName + " -> " + typeName);
                                return resolveTypeNameFromAST(typeName, fieldType, ast, project);
                            }
                        }
                    }

                    // Check $Trait$FieldHelper — at CONVERSION phase, trait fields
                    // are moved here with mangled names
                    ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(
                            resolvedTraitNode, ast);
                    if (helperNode != null) {
                        for (FieldNode hField : helperNode.getFields()) {
                            if (TraitMemberResolver.isTraitFieldMatch(hField.getName(), fieldName)) {
                                ClassNode fieldType = hField.getType();
                                if (fieldType != null) {
                                    String typeName = fieldType.getName();
                                    GroovyLanguageServerPlugin.logInfo(
                                            "[completion] Trait FieldHelper field: " + fieldName + " -> " + typeName);
                                    return resolveTypeNameFromAST(typeName, fieldType, ast, project);
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolve a type name from the Groovy AST to a JDT IType.
     * Tries FQN, imports, same-package, and auto-imports.
     */
    private IType resolveTypeNameFromAST(String typeName, ClassNode typeNode,
                                          ModuleNode module, IJavaProject project) {
        try {
            // If already FQN
            if (typeName.contains(".")) {
                IType t = project.findType(typeName);
                if (t != null) return t;
            }

            // Try imports
            for (ImportNode imp : module.getImports()) {
                ClassNode impType = imp.getType();
                if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                    IType t = project.findType(impType.getName());
                    if (t != null) return t;
                }
            }

            // Try same package
            String pkg = module.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                IType t = project.findType(pkg + "." + typeName);
                if (t != null) return t;
            }

            // Try auto-imports
            String[] autoPackages = { "java.lang.", "java.util.", "java.io.", "groovy.lang.", "groovy.util." };
            for (String autoPkg : autoPackages) {
                IType t = project.findType(autoPkg + typeName);
                if (t != null) return t;
            }

            // Try star imports
            for (ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType t = project.findType(pkgName + typeName);
                    if (t != null) return t;
                }
            }
        } catch (JavaModelException e) {
            // ignore
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
            for (IType type : workingCopy.getTypes()) {
                // Fields (high priority — local declarations)
                for (IField field : type.getFields()) {
                    String name = field.getElementName();
                    if (name.startsWith("$") || name.startsWith("__")) continue;
                    if (!matchesPrefix(name, prefix)) continue;

                    CompletionItem item = new CompletionItem(name);
                    item.setKind(CompletionItemKind.Field);
                    try {
                        item.setDetail(Signature.toString(field.getTypeSignature()));
                    } catch (Exception ignored) {}
                    item.setInsertText(name);
                    item.setFilterText(name);
                    item.setSortText("1_" + name);
                    items.add(item);
                }

                // Methods from the current type
                for (IMethod method : type.getMethods()) {
                    String name = method.getElementName();
                    if (name.startsWith("<")) continue;
                    if (!matchesPrefix(name, prefix)) continue;

                    CompletionItem mi = methodToCompletionItem(method, name, type, "2");
                    items.add(mi);
                }
            }

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

    /**
     * Add completions for members inherited from traits via the JDT model.
     * Resolves each interface/trait type in the project and lists its members.
     */
    private void addTraitIdentifierCompletions(ICompilationUnit workingCopy,
                                                String lspUri,
                                                String prefix,
                                                List<CompletionItem> items) {
        // Use the original LSP URI for AST lookup (avoids encoding mismatch)
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        if (ast == null) {
            // Fallback: get module node directly from GroovyCompilationUnit
            ast = getModuleFromWorkingCopy(workingCopy);
        }
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);
        if (project == null) return;

        Set<String> seen = new HashSet<>();
        // Collect already-seen names from existing items
        for (CompletionItem existing : items) {
            seen.add(existing.getFilterText() != null ? existing.getFilterText() : existing.getLabel());
        }

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            ClassNode[] interfaces = classNode.getInterfaces();
            if (interfaces == null) continue;

            for (ClassNode ifaceRef : interfaces) {
                IType traitType = resolveTraitType(ifaceRef, classNode, ast, project);
                boolean jdtResolved = (traitType != null);

                if (jdtResolved) {
                    try {
                        // Fields from the trait
                        for (IField field : traitType.getFields()) {
                            String name = field.getElementName();
                            if (name.startsWith("$") || name.startsWith("__")) continue;
                            if (!matchesPrefix(name, prefix)) continue;
                            if (!seen.add(name)) continue;

                            CompletionItem item = new CompletionItem(name);
                            item.setKind(CompletionItemKind.Field);
                            try {
                                item.setDetail(Signature.toString(field.getTypeSignature()) + " (trait)");
                            } catch (Exception ignored) {}
                            item.setInsertText(name);
                            item.setFilterText(name);
                            item.setSortText("1_" + name);
                            items.add(item);
                        }

                        // Methods from the trait
                        for (IMethod method : traitType.getMethods()) {
                            String name = method.getElementName();
                            if (name.startsWith("<") || name.startsWith("$")) continue;
                            if (!matchesPrefix(name, prefix)) continue;
                            String key = name + "/" + method.getParameterTypes().length;
                            if (!seen.add(key)) continue;

                            CompletionItem mi = methodToCompletionItem(method, name, traitType, "2");
                            items.add(mi);
                        }

                        // Also expose Groovy properties: trait field 'foo' generates getFoo()/setFoo()
                        // Add property-style completions for getter methods
                        for (IMethod method : traitType.getMethods()) {
                            String mname = method.getElementName();
                            if (mname.startsWith("get") && mname.length() > 3
                                    && method.getParameterTypes().length == 0) {
                                String propName = Character.toLowerCase(mname.charAt(3)) + mname.substring(4);
                                if (!matchesPrefix(propName, prefix)) continue;
                                if (!seen.add(propName)) continue;

                                CompletionItem item = new CompletionItem(propName);
                                item.setKind(CompletionItemKind.Property);
                                try {
                                    item.setDetail(Signature.toString(method.getReturnType()) + " (trait property)");
                                } catch (Exception ignored) {}
                                item.setInsertText(propName);
                                item.setFilterText(propName);
                                item.setSortText("1_" + propName);
                                items.add(item);
                            }
                        }
                    } catch (JavaModelException e) {
                        // ignore
                    }
                }

                // AST-based fallback: also check the Groovy AST for trait members.
                // This is essential when JDT doesn't expose trait fields (same-file traits,
                // or when the IType model doesn't include trait properties at CONVERSION phase).
                ClassNode resolvedTraitNode = TraitMemberResolver.resolveTraitClassNode(
                        ifaceRef, ast, documentManager);
                if (resolvedTraitNode != null && resolvedTraitNode.getLineNumber() >= 0) {
                    // Properties from the AST
                    for (PropertyNode prop : resolvedTraitNode.getProperties()) {
                        String name = prop.getName();
                        if (!matchesPrefix(name, prefix)) continue;
                        if (!seen.add(name)) continue;

                        CompletionItem item = new CompletionItem(name);
                        item.setKind(CompletionItemKind.Property);
                        String typeName = prop.getType() != null
                                ? prop.getType().getNameWithoutPackage() : "Object";
                        item.setDetail(typeName + " (trait property)");
                        item.setInsertText(name);
                        item.setFilterText(name);
                        item.setSortText("1_" + name);
                        items.add(item);
                    }

                    // Fields from the AST (that aren't already covered by properties)
                    for (FieldNode field : resolvedTraitNode.getFields()) {
                        String name = field.getName();
                        if (name.startsWith("$") || name.startsWith("__")) continue;
                        if (!matchesPrefix(name, prefix)) continue;
                        if (!seen.add(name)) continue;

                        CompletionItem item = new CompletionItem(name);
                        item.setKind(CompletionItemKind.Field);
                        String typeName = field.getType() != null
                                ? field.getType().getNameWithoutPackage() : "Object";
                        item.setDetail(typeName + " (trait)");
                        item.setInsertText(name);
                        item.setFilterText(name);
                        item.setSortText("1_" + name);
                        items.add(item);
                    }

                    // Methods from the AST (user-declared, not synthetic)
                    for (MethodNode method : resolvedTraitNode.getMethods()) {
                        String name = method.getName();
                        if (name.startsWith("<") || name.startsWith("$")) continue;
                        if (method.getLineNumber() < 0) continue;
                        if (!matchesPrefix(name, prefix)) continue;
                        String key = name + "/" + method.getParameters().length;
                        if (!seen.add(key)) continue;

                        CompletionItem mi = new CompletionItem(name);
                        mi.setKind(CompletionItemKind.Method);
                        String retType = method.getReturnType() != null
                                ? method.getReturnType().getNameWithoutPackage() : "void";
                        mi.setDetail(retType + " (trait)");
                        StringBuilder insertText = new StringBuilder(name).append("(");
                        org.codehaus.groovy.ast.Parameter[] params = method.getParameters();
                        for (int i = 0; i < params.length; i++) {
                            if (i > 0) insertText.append(", ");
                            insertText.append(params[i].getName());
                        }
                        insertText.append(")");
                        mi.setInsertText(insertText.toString());
                        mi.setFilterText(name);
                        mi.setSortText("2_" + name);
                        items.add(mi);
                    }

                    // Check $Trait$FieldHelper — at CONVERSION phase, trait fields
                    // are moved here with mangled names
                    ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(
                            resolvedTraitNode, ast);
                    if (helperNode != null) {
                        for (FieldNode hField : helperNode.getFields()) {
                            String rawName = hField.getName();
                            // FieldHelper fields have mangled names like
                            // $0x0002com_example_pkg__fieldName — demangle first
                            String name = TraitMemberResolver.demangleTraitFieldName(rawName);
                            // Skip if demangling didn't change anything (not a trait field)
                            if (name.equals(rawName)) continue;
                            if (!matchesPrefix(name, prefix)) continue;
                            if (!seen.add(name)) continue;

                            CompletionItem item = new CompletionItem(name);
                            item.setKind(CompletionItemKind.Field);
                            String typeName = hField.getType() != null
                                    ? hField.getType().getNameWithoutPackage() : "Object";
                            item.setDetail(typeName + " (trait)");
                            item.setInsertText(name);
                            item.setFilterText(name);
                            item.setSortText("1_" + name);
                            items.add(item);
                        }
                    }
                }
            }
        }
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
            if (classNode.getLineNumber() < 0) continue;

            // Properties (Groovy 'def foo = ...')
            for (PropertyNode prop : classNode.getProperties()) {
                String name = prop.getName();
                if (!matchesPrefix(name, prefix)) continue;
                if (!seen.add(name)) continue;

                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                String typeName = prop.getType() != null
                        ? prop.getType().getNameWithoutPackage() : "Object";
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }

            // Fields
            for (FieldNode field : classNode.getFields()) {
                String name = field.getName();
                if (name.startsWith("$") || name.startsWith("__")) continue;
                if (!matchesPrefix(name, prefix)) continue;
                if (!seen.add(name)) continue;

                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                String typeName = field.getType() != null
                        ? field.getType().getNameWithoutPackage() : "Object";
                item.setDetail(typeName);
                item.setInsertText(name);
                item.setFilterText(name);
                item.setSortText("1_" + name);
                items.add(item);
            }

            // Methods (user-declared)
            for (MethodNode method : classNode.getMethods()) {
                String name = method.getName();
                if (name.startsWith("<") || name.startsWith("$")) continue;
                if (method.getLineNumber() < 0) continue;
                if (!matchesPrefix(name, prefix)) continue;
                String key = name + "/" + method.getParameters().length;
                if (!seen.add(key)) continue;

                CompletionItem mi = new CompletionItem(name);
                mi.setKind(CompletionItemKind.Method);
                String retType = method.getReturnType() != null
                        ? method.getReturnType().getNameWithoutPackage() : "void";
                mi.setDetail(retType);
                Parameter[] params = method.getParameters();
                if (params != null && params.length > 0) {
                    StringBuilder snippet = new StringBuilder(name).append('(');
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) snippet.append(", ");
                        snippet.append("${").append(i + 1).append(':')
                                .append(params[i].getName()).append('}');
                    }
                    snippet.append(')');
                    mi.setInsertText(snippet.toString());
                    mi.setInsertTextFormat(InsertTextFormat.Snippet);
                } else {
                    mi.setInsertText(name + "()");
                }
                mi.setFilterText(name);
                mi.setSortText("2_" + name);
                items.add(mi);
            }
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
        ModuleNode ast = (lspUri != null) ? documentManager.getGroovyAST(lspUri) : null;
        if (ast == null) {
            ast = getModuleFromWorkingCopy(workingCopy);
        }
        if (ast == null) return;

        IJavaProject project = findWorkingProject(workingCopy);

        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;

            // Check if exprName is a field/property in this class
            ClassNode exprType = null;
            for (PropertyNode prop : classNode.getProperties()) {
                if (prop.getName().equals(exprName)) {
                    exprType = prop.getType();
                    break;
                }
            }
            if (exprType == null) {
                for (FieldNode field : classNode.getFields()) {
                    if (field.getName().equals(exprName)) {
                        exprType = field.getType();
                        break;
                    }
                }
            }

            // Also check traits for the field
            if (exprType == null) {
                ClassNode[] interfaces = classNode.getInterfaces();
                if (interfaces != null) {
                    for (ClassNode ifaceRef : interfaces) {
                        ClassNode traitNode = TraitMemberResolver.resolveTraitClassNode(
                                ifaceRef, ast, documentManager);
                        if (traitNode != null) {
                            for (PropertyNode prop : traitNode.getProperties()) {
                                if (prop.getName().equals(exprName)) {
                                    exprType = prop.getType();
                                    break;
                                }
                            }
                            if (exprType != null) break;
                            for (FieldNode field : traitNode.getFields()) {
                                if (field.getName().equals(exprName)) {
                                    exprType = field.getType();
                                    break;
                                }
                            }
                            if (exprType != null) break;
                        }
                    }
                }
            }

            // If we resolved the type, try to look up members via JDT
            if (exprType != null && project != null) {
                String typeFqn = exprType.getName();
                try {
                    IType jdtType = project.findType(typeFqn);
                    if (jdtType == null) {
                        // Try Groovy auto-import packages
                        for (String pkg : GROOVY_AUTO_PACKAGES) {
                            jdtType = project.findType(pkg + exprType.getNameWithoutPackage());
                            if (jdtType != null) break;
                        }
                    }
                    if (jdtType != null) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[completion] AST dot fallback resolved: "
                                + jdtType.getFullyQualifiedName());
                        addMembersOfType(jdtType, prefix, false, items);
                    }
                } catch (Exception e) {
                    GroovyLanguageServerPlugin.logError(
                            "[completion] AST dot fallback type lookup failed", e);
                }
            }
        }
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
            if (result instanceof ModuleNode) {
                return (ModuleNode) result;
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

        try {
            String fqn = ifaceRef.getName();
            // Try FQN directly
            if (fqn != null && fqn.contains(".")) {
                IType t = project.findType(fqn);
                if (t != null) return t;
            }
            // Try same package as owner
            String ownerPkg = owner.getPackageName();
            if (ownerPkg != null && !ownerPkg.isEmpty()) {
                IType t = project.findType(ownerPkg + "." + ifaceSimple);
                if (t != null) return t;
            }
            // Try explicit imports
            if (module != null) {
                for (org.codehaus.groovy.ast.ImportNode imp : module.getImports()) {
                    ClassNode impType = imp.getType();
                    if (impType != null && ifaceSimple.equals(impType.getNameWithoutPackage())) {
                        IType t = project.findType(impType.getName());
                        if (t != null) return t;
                    }
                }
                // Try star imports
                for (org.codehaus.groovy.ast.ImportNode starImport : module.getStarImports()) {
                    String pkgName = starImport.getPackageName();
                    if (pkgName != null) {
                        IType t = project.findType(pkgName + ifaceSimple);
                        if (t != null) return t;
                    }
                }
            }
            // Try Groovy auto-import packages
            String[] autoPackages = { "groovy.lang.", "groovy.util.", "java.lang.", "java.util." };
            for (String pkg : autoPackages) {
                IType t = project.findType(pkg + ifaceSimple);
                if (t != null) return t;
            }
        } catch (JavaModelException e) {
            // ignore
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

                            if (annotationOnly
                                    && !isAnnotationTypeCandidate(project, fqn, modifiers)) {
                                return;
                            }
                            if (seenSimpleNames.contains(simpleName)) {
                                return;
                            }
                            seenSimpleNames.add(simpleName);

                            CompletionItem item = new CompletionItem(simpleName);
                            if (Flags.isAnnotation(modifiers)) {
                                item.setKind(CompletionItemKind.Class);
                            } else if (Flags.isInterface(modifiers)) {
                                item.setKind(CompletionItemKind.Interface);
                            } else if (Flags.isEnum(modifiers)) {
                                item.setKind(CompletionItemKind.Enum);
                            } else {
                                item.setKind(CompletionItemKind.Class);
                            }
                            item.setDetail(pkg.isEmpty() ? simpleName : pkg + "." + simpleName);
                            item.setInsertText(simpleName);
                            item.setFilterText(simpleName);
                                if (shouldAutoImportType(fqn, pkg, currentPackage, existingImports)) {
                                TextEdit addImport = new TextEdit(
                                    new org.eclipse.lsp4j.Range(
                                        new Position(importInsertLine, 0),
                                        new Position(importInsertLine, 0)),
                                    "import " + fqn + "\n");
                                item.setAdditionalTextEdits(
                                    java.util.Collections.singletonList(addImport));
                                }
                            item.setSortText((annotationOnly ? "4_" : "5_") + simpleName);
                            items.add(item);
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

    private void addImportedAnnotationCompletions(IJavaProject project,
                                                  Set<String> existingImports,
                                                  String prefix,
                                                  List<CompletionItem> items,
                                                  Set<String> seenSimpleNames) {
        for (String fqn : existingImports) {
            int lastDot = fqn.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
            if (!matchesPrefix(simpleName, prefix)) {
                continue;
            }

            try {
                IType type = project.findType(fqn);
                if (type == null || !type.exists() || !type.isAnnotation()) {
                    continue;
                }
            } catch (JavaModelException e) {
                continue;
            }

            if (seenSimpleNames.contains(simpleName)) {
                continue;
            }
            seenSimpleNames.add(simpleName);

            CompletionItem item = new CompletionItem(simpleName);
            item.setKind(CompletionItemKind.Class);
            item.setDetail(fqn);
            item.setInsertText(simpleName);
            item.setFilterText(simpleName);
            item.setSortText("3_" + simpleName);
            items.add(item);
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
        String className = classNode.getNameWithoutPackage();
        if (className != null && (prefix.isEmpty() || className.startsWith(prefix))) {
            CompletionItem item = new CompletionItem(className);
            item.setKind(CompletionItemKind.Class);
            item.setDetail(classNode.getName());
            item.setSortText("2_" + className);
            items.add(item);
        }

        for (MethodNode method : classNode.getMethods()) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage());
                Parameter[] params = method.getParameters();
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
                item.setSortText("3_" + name);
                items.add(item);
            }
        }

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

        // Walk implemented interfaces/traits to collect inherited members
        addTraitMemberCompletions(classNode, prefix, items);
    }

    /**
     * Add completion items for members inherited from traits/interfaces.
     */
    private void addTraitMemberCompletions(ClassNode classNode, String prefix,
                                            List<CompletionItem> items) {
        ModuleNode module = classNode.getModule();

        for (MethodNode method : TraitMemberResolver.collectTraitMethods(classNode, module, documentManager)) {
            String name = method.getName();
            if (name != null && !name.startsWith("<")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Method);
                item.setDetail(method.getReturnType().getNameWithoutPackage() + " (trait)");
                Parameter[] params = method.getParameters();
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
                item.setSortText("3_" + name);
                items.add(item);
            }
        }

        for (FieldNode field : TraitMemberResolver.collectTraitFields(classNode, module, documentManager)) {
            String name = field.getName();
            if (name != null && !name.startsWith("$")
                    && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Field);
                item.setDetail(field.getType().getNameWithoutPackage() + " (trait)");
                item.setInsertText(name);
                item.setSortText("4_" + name);
                items.add(item);
            }
        }

        for (PropertyNode prop : TraitMemberResolver.collectTraitProperties(classNode, module, documentManager)) {
            String name = prop.getName();
            if (name != null && (prefix.isEmpty() || name.startsWith(prefix))) {
                CompletionItem item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Property);
                item.setDetail(prop.getType().getNameWithoutPackage() + " (trait)");
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
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }

            String rest = trimmed.substring("import ".length()).trim();
            if (rest.startsWith("static ")) {
                continue;
            }

            if (rest.endsWith(";")) {
                rest = rest.substring(0, rest.length() - 1).trim();
            }

            if (!rest.isEmpty() && rest.indexOf('.') > 0 && !rest.endsWith(".*")) {
                imports.add(rest);
            }
        }

        return imports;
    }

    private boolean shouldAutoImportType(String fqn, String packageName,
                                         String currentPackage, Set<String> existingImports) {
        if (fqn == null || fqn.isEmpty()) {
            return false;
        }
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (existingImports.contains(fqn)) {
            return false;
        }
        if (packageName.equals(currentPackage)) {
            return false;
        }
        if (isAutoImportedPackage(packageName)) {
            return false;
        }
        return true;
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
            if (trimmed.startsWith("package ")) {
                String pkg = trimmed.substring("package ".length()).trim();
                if (pkg.endsWith(";")) {
                    pkg = pkg.substring(0, pkg.length() - 1).trim();
                }
                return pkg;
            }
            if (trimmed.startsWith("import ") || trimmed.startsWith("class ")
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
            if (trimmed.startsWith("package ")) {
                packageLine = i;
            }
            if (trimmed.startsWith("import ")) {
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
        if (prefixStart > 0 && content.charAt(prefixStart - 1) == '@') {
            return true;
        }
        if (offset > 0 && content.charAt(offset - 1) == '@') {
            return true;
        }
        if (prefixStart >= 0 && prefixStart < content.length() && content.charAt(prefixStart) == '@') {
            return true;
        }
        return false;
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
