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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.groovy.ls.core.DocumentManager;

/**
 * Utility for resolving members inherited from Groovy traits.
 * <p>
 * Because the Groovy compiler only runs through the CONVERSION phase
 * (to avoid classpath requirements), trait members are NOT injected into
 * implementing classes' {@link ClassNode}s. This resolver manually walks
 * the interfaces (which include traits) of a class and collects their
 * declared members from the AST — either from the same file or from
 * other open documents managed by the {@link DocumentManager}.
 */
public final class TraitMemberResolver {

    private TraitMemberResolver() {
    }

    /**
     * Resolve the full {@link ClassNode} declaration for an interface/trait
     * reference. The {@code ifaceRef} from {@code owner.getInterfaces()} is
     * typically an unresolved stub — this method finds the real declaration.
     *
     * @param ifaceRef        the interface reference from the AST
     * @param currentModule   the current file's AST (checked first)
     * @param documentManager the document manager for cross-file lookup
     * @return the resolved ClassNode declaration, or the original reference if not found
     */
    public static ClassNode resolveTraitClassNode(ClassNode ifaceRef,
                                                   ModuleNode currentModule,
                                                   DocumentManager documentManager) {
        String simpleName = ifaceRef.getNameWithoutPackage();
        if (simpleName == null || simpleName.isEmpty()) {
            return ifaceRef;
        }

        // 1) Check the current module first
        ClassNode resolved = findClassInModule(currentModule, simpleName);
        if (resolved != null) {
            return resolved;
        }

        ClassNode openDocumentMatch = findTraitInOpenDocuments(
                ifaceRef,
                simpleName,
                currentModule,
                documentManager);
        if (openDocumentMatch != null) {
            return openDocumentMatch;
        }

        return ifaceRef; // Return the original if we can't resolve it
    }

    /**
     * Collect all methods declared in the traits/interfaces implemented by
     * the given class. This does NOT include the class's own methods.
     *
     * @param owner           the class whose trait methods to collect
     * @param currentModule   the current file's AST
     * @param documentManager the document manager for cross-file lookup (may be null)
     * @return list of methods from implemented traits
     */
    public static List<MethodNode> collectTraitMethods(ClassNode owner,
                                                        ModuleNode currentModule,
                                                        DocumentManager documentManager) {
        List<MethodNode> methods = new ArrayList<>();
        ClassNode[] interfaces = owner.getInterfaces();
        if (interfaces == null || interfaces.length == 0) {
            return methods;
        }

        Set<String> visited = new HashSet<>();
        for (ClassNode ifaceRef : interfaces) {
            collectTraitMethodsRecursive(ifaceRef, currentModule, documentManager, methods, visited);
        }
        return methods;
    }

    /**
     * Collect all fields declared in the traits/interfaces implemented by
     * the given class.
     *
     * @param owner           the class whose trait fields to collect
     * @param currentModule   the current file's AST
     * @param documentManager the document manager for cross-file lookup (may be null)
     * @return list of fields from implemented traits
     */
    public static List<FieldNode> collectTraitFields(ClassNode owner,
                                                      ModuleNode currentModule,
                                                      DocumentManager documentManager) {
        List<FieldNode> fields = new ArrayList<>();
        ClassNode[] interfaces = owner.getInterfaces();
        if (interfaces == null || interfaces.length == 0) {
            return fields;
        }

        Set<String> visited = new HashSet<>();
        for (ClassNode ifaceRef : interfaces) {
            collectTraitFieldsRecursive(ifaceRef, currentModule, documentManager, fields, visited);
        }
        return fields;
    }

    /**
     * Collect all properties declared in the traits/interfaces implemented by
     * the given class.
     *
     * @param owner           the class whose trait properties to collect
     * @param currentModule   the current file's AST
     * @param documentManager the document manager for cross-file lookup (may be null)
     * @return list of properties from implemented traits
     */
    public static List<PropertyNode> collectTraitProperties(ClassNode owner,
                                                             ModuleNode currentModule,
                                                             DocumentManager documentManager) {
        List<PropertyNode> properties = new ArrayList<>();
        ClassNode[] interfaces = owner.getInterfaces();
        if (interfaces == null || interfaces.length == 0) {
            return properties;
        }

        Set<String> visited = new HashSet<>();
        for (ClassNode ifaceRef : interfaces) {
            collectTraitPropertiesRecursive(ifaceRef, currentModule, documentManager, properties, visited);
        }
        return properties;
    }

    /**
     * Find the URI of the document containing the given trait's declaration.
     *
     * @param ifaceRef        the interface/trait reference
     * @param currentUri      the current document URI
     * @param currentModule   the current document's AST
     * @param documentManager the document manager
     * @return the URI where the trait is declared, or null if not found
     */
    public static String findTraitDeclarationUri(ClassNode ifaceRef,
                                                  String currentUri,
                                                  ModuleNode currentModule,
                                                  DocumentManager documentManager) {
        String simpleName = ifaceRef.getNameWithoutPackage();
        if (simpleName == null || simpleName.isEmpty()) {
            return null;
        }

        // Check current module
        if (findClassInModule(currentModule, simpleName) != null) {
            return currentUri;
        }

        return findTraitDeclarationUriInOpenDocuments(
                ifaceRef,
                simpleName,
                currentModule,
                documentManager);
    }

    // ---- Trait $Trait$FieldHelper support ----

    /**
     * Find the {@code $Trait$FieldHelper} inner class for a trait.
     * At Phases.CONVERSION, Groovy moves trait fields into this helper class.
     * The helper class FQN is {@code <traitFQN>$Trait$FieldHelper}.
     *
     * @param traitNode the trait's ClassNode
     * @param module    the module to search in
     * @return the FieldHelper ClassNode, or null if not found
     */
    public static ClassNode findFieldHelperNode(ClassNode traitNode, ModuleNode module) {
        if (traitNode == null || module == null) return null;
        String helperFqn = traitNode.getName() + "$Trait$FieldHelper";
        for (ClassNode cn : module.getClasses()) {
            if (helperFqn.equals(cn.getName())) {
                return cn;
            }
        }
        return null;
    }

    /**
     * Demangle a trait FieldHelper field name to extract the original field name.
     * At CONVERSION phase, trait fields in $Trait$FieldHelper are named like:
     * {@code com_example_demo_TraitName__fieldName}
     *
     * @param mangledName the field name from $Trait$FieldHelper
     * @return the original field name, or the input if not mangled
     */
    public static String demangleTraitFieldName(String mangledName) {
        if (mangledName == null) return null;
        int idx = mangledName.lastIndexOf("__");
        if (idx >= 0 && idx + 2 < mangledName.length()) {
            return mangledName.substring(idx + 2);
        }
        return mangledName;
    }

    /**
     * Check if a FieldHelper field name matches a target field name.
     * Handles both exact match and Groovy's mangled naming convention.
     */
    public static boolean isTraitFieldMatch(String fieldName, String targetName) {
        if (fieldName == null || targetName == null) return false;
        if (targetName.equals(fieldName)) return true;
        // Check mangled pattern: <prefix>__<targetName>
        String suffix = "__" + targetName;
        return fieldName.endsWith(suffix);
    }

    /**
     * Find a field in a trait, checking both the trait ClassNode itself
     * and its $Trait$FieldHelper inner class.
     *
     * @param traitNode  the trait ClassNode (resolved)
     * @param fieldName  the field name to find
     * @param module     the module containing the trait
     * @return the matching FieldNode, or null
     */
    public static FieldNode findTraitField(ClassNode traitNode, String fieldName, ModuleNode module) {
        if (traitNode == null || fieldName == null) return null;

        // 1. Check the trait ClassNode directly
        for (PropertyNode prop : traitNode.getProperties()) {
            if (fieldName.equals(prop.getName())) {
                return prop.getField();
            }
        }
        for (FieldNode field : traitNode.getFields()) {
            if (fieldName.equals(field.getName())) {
                return field;
            }
        }

        // 2. Check the $Trait$FieldHelper inner class
        ClassNode helper = findFieldHelperNode(traitNode, module);
        if (helper != null) {
            for (FieldNode field : helper.getFields()) {
                if (isTraitFieldMatch(field.getName(), fieldName)) {
                    return field;
                }
            }
        }

        return null;
    }

    /**
     * Collect all field names from a trait, including those in $Trait$FieldHelper.
     * Returns demangled field names paired with their FieldNodes.
     *
     * @param traitNode the resolved trait ClassNode
     * @param module    the module containing the trait
     * @return list of FieldNode entries (with potentially mangled names — use demangleTraitFieldName)
     */
    public static List<FieldNode> collectAllTraitFields(ClassNode traitNode, ModuleNode module) {
        List<FieldNode> result = new ArrayList<>();
        if (traitNode == null) return result;

        // Direct fields from trait
        for (FieldNode field : traitNode.getFields()) {
            if (!field.getName().startsWith("$") && !field.getName().startsWith("__")) {
                result.add(field);
            }
        }

        // Fields from $Trait$FieldHelper
        ClassNode helper = findFieldHelperNode(traitNode, module);
        if (helper != null) {
            for (FieldNode field : helper.getFields()) {
                String name = field.getName();
                // Skip internal fields
                if (name.startsWith("$") || name.startsWith("__")) continue;
                result.add(field);
            }
        }

        return result;
    }

    // ---- Private helpers ----

    private static ClassNode findClassInModule(ModuleNode module, String simpleName) {
        if (module == null) {
            return null;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            if (simpleName.equals(classNode.getNameWithoutPackage())) {
                return classNode;
            }
        }
        return null;
    }

    private static void collectTraitMethodsRecursive(ClassNode ifaceRef,
                                                      ModuleNode currentModule,
                                                      DocumentManager documentManager,
                                                      List<MethodNode> methods,
                                                      Set<String> visited) {
        String key = ifaceRef.getName();
        if (key == null || !visited.add(key)) {
            return;
        }

        ClassNode resolved = resolveTraitClassNode(ifaceRef, currentModule, documentManager);
        for (MethodNode method : resolved.getMethods()) {
            if (method.getLineNumber() >= 0 || isTraitDeclaredMethod(method)) {
                methods.add(method);
            }
        }

        // Recurse into traits that this trait itself implements
        ClassNode[] superInterfaces = resolved.getInterfaces();
        if (superInterfaces != null) {
            for (ClassNode superIface : superInterfaces) {
                collectTraitMethodsRecursive(superIface, currentModule, documentManager, methods, visited);
            }
        }
    }

    private static void collectTraitFieldsRecursive(ClassNode ifaceRef,
                                                     ModuleNode currentModule,
                                                     DocumentManager documentManager,
                                                     List<FieldNode> fields,
                                                     Set<String> visited) {
        String key = ifaceRef.getName();
        if (key == null || !visited.add(key)) {
            return;
        }

        ClassNode resolved = resolveTraitClassNode(ifaceRef, currentModule, documentManager);
        addVisibleTraitFields(fields, resolved);

        ClassNode helper = findFieldHelperAcrossModules(resolved, currentModule, documentManager);
        addUniqueHelperFields(fields, helper);

        collectTraitFieldsFromSuperInterfaces(resolved, currentModule, documentManager, fields, visited);
    }

    private static ClassNode findTraitInOpenDocuments(ClassNode ifaceRef,
            String simpleName,
            ModuleNode currentModule,
            DocumentManager documentManager) {
        if (documentManager == null) {
            return null;
        }

        String fqn = ifaceRef.getName();
        for (String uri : documentManager.getOpenDocumentUris()) {
            // Use cached ASTs only — never trigger on-demand parsing here.
            // In large workspaces this loop covers many files; parsing each
            // uncached file would cause severe CPU spikes and hover latency.
            ModuleNode otherModule = documentManager.getCachedGroovyAST(uri);
            ClassNode matched = findMatchingClass(otherModule, currentModule, fqn, simpleName);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private static String findTraitDeclarationUriInOpenDocuments(ClassNode ifaceRef,
            String simpleName,
            ModuleNode currentModule,
            DocumentManager documentManager) {
        if (documentManager == null) {
            return null;
        }

        String fqn = ifaceRef.getName();
        for (String uri : documentManager.getOpenDocumentUris()) {
            ModuleNode otherModule = documentManager.getCachedGroovyAST(uri);
            if (findMatchingClass(otherModule, currentModule, fqn, simpleName) != null) {
                return uri;
            }
        }
        return null;
    }

    private static ClassNode findMatchingClass(ModuleNode module,
            ModuleNode currentModule,
            String fqn,
            String simpleName) {
        if (module == null || module == currentModule) {
            return null;
        }

        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) {
                continue;
            }
            if (matchesTraitReference(classNode, fqn, simpleName)) {
                return classNode;
            }
        }
        return null;
    }

    private static boolean matchesTraitReference(ClassNode classNode, String fqn, String simpleName) {
        if (fqn != null && fqn.contains(".") && fqn.equals(classNode.getName())) {
            return true;
        }
        return simpleName.equals(classNode.getNameWithoutPackage());
    }

    private static void addVisibleTraitFields(List<FieldNode> fields, ClassNode traitClass) {
        for (FieldNode field : traitClass.getFields()) {
            if (isVisibleTraitField(field.getName())) {
                fields.add(field);
            }
        }
    }

    private static boolean isVisibleTraitField(String fieldName) {
        return !fieldName.startsWith("$") && !fieldName.startsWith("__");
    }

    private static ClassNode findFieldHelperAcrossModules(ClassNode traitNode,
            ModuleNode currentModule,
            DocumentManager documentManager) {
        ClassNode helper = findFieldHelperNode(traitNode, currentModule);
        if (helper != null || documentManager == null) {
            return helper;
        }

        for (String uri : documentManager.getOpenDocumentUris()) {
            ModuleNode otherModule = documentManager.getCachedGroovyAST(uri);
            if (otherModule != null && otherModule != currentModule) {
                helper = findFieldHelperNode(traitNode, otherModule);
                if (helper != null) {
                    return helper;
                }
            }
        }
        return null;
    }

    private static void addUniqueHelperFields(List<FieldNode> fields, ClassNode helper) {
        if (helper == null) {
            return;
        }

        for (FieldNode field : helper.getFields()) {
            String name = field.getName();
            if (!isVisibleTraitField(name)) {
                continue;
            }

            String demangled = demangleTraitFieldName(name);
            if (!containsDemangledField(fields, demangled)) {
                fields.add(field);
            }
        }
    }

    private static boolean containsDemangledField(List<FieldNode> fields, String demangledName) {
        for (FieldNode existing : fields) {
            String existingName = existing.getName();
            if (demangledName.equals(existingName)
                    || demangledName.equals(demangleTraitFieldName(existingName))) {
                return true;
            }
        }
        return false;
    }

    private static void collectTraitFieldsFromSuperInterfaces(ClassNode resolved,
            ModuleNode currentModule,
            DocumentManager documentManager,
            List<FieldNode> fields,
            Set<String> visited) {
        ClassNode[] superInterfaces = resolved.getInterfaces();
        if (superInterfaces == null) {
            return;
        }

        for (ClassNode superIface : superInterfaces) {
            collectTraitFieldsRecursive(superIface, currentModule, documentManager, fields, visited);
        }
    }

    private static void collectTraitPropertiesRecursive(ClassNode ifaceRef,
                                                         ModuleNode currentModule,
                                                         DocumentManager documentManager,
                                                         List<PropertyNode> properties,
                                                         Set<String> visited) {
        String key = ifaceRef.getName();
        if (key == null || !visited.add(key)) {
            return;
        }

        ClassNode resolved = resolveTraitClassNode(ifaceRef, currentModule, documentManager);
        for (PropertyNode prop : resolved.getProperties()) {
            properties.add(prop);
        }

        ClassNode[] superInterfaces = resolved.getInterfaces();
        if (superInterfaces != null) {
            for (ClassNode superIface : superInterfaces) {
                collectTraitPropertiesRecursive(superIface, currentModule, documentManager, properties, visited);
            }
        }
    }

    /**
     * Heuristic: a method is "trait-declared" if it's not a synthetic method
     * like those generated by the compiler.
     */
    private static boolean isTraitDeclaredMethod(MethodNode method) {
        String name = method.getName();
        return name != null && !name.startsWith("<") && !name.startsWith("$");
    }
}
