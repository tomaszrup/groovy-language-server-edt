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

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.model.GroovyCompilationUnit;
import org.codehaus.jdt.groovy.model.ICodeSelectHelper;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * {@link ICodeSelectHelper} that uses the Groovy AST to resolve the element
 * at the cursor, then maps it to JDT {@link IJavaElement}s.
 * <p>
 * Handles:
 * <ul>
 *   <li>Type references (imports, extends, implements, variable types, casts,
 *       constructor calls, annotations)</li>
 *   <li>Method declarations (cursor on method name → IMethod)</li>
 *   <li>Field/property declarations (cursor on field name → IField)</li>
 * </ul>
 */
public class MinimalCodeSelectHelper implements ICodeSelectHelper {

    private static final String LOG_FIELDS = " fields=";
    private static final String LOG_METHODS = " methods=";
    private static final String PKG_GROOVY_LANG = "groovy.lang.";
    private static final String PKG_GROOVY_UTIL = "groovy.util.";
    private static final String PKG_JAVA_LANG = "java.lang.";
    private static final String PKG_JAVA_UTIL = "java.util.";
    private static final String PKG_JAVA_IO = "java.io.";
    private static final String PKG_JAVA_NET = "java.net.";
    private static final String PKG_JAVA_MATH = "java.math.";
    private static final String[] TRAIT_AUTO_PACKAGES = {
        PKG_GROOVY_LANG,
        PKG_GROOVY_UTIL,
        PKG_JAVA_LANG,
        PKG_JAVA_UTIL
    };
    private static final String[] DEFAULT_AUTO_PACKAGES = {
        PKG_JAVA_LANG,
        PKG_JAVA_UTIL,
        PKG_JAVA_IO,
        PKG_JAVA_NET,
        PKG_GROOVY_LANG,
        PKG_GROOVY_UTIL,
        PKG_JAVA_MATH
    };

    @Override
    public IJavaElement[] select(GroovyCompilationUnit unit, int offset, int length) {
        try {
            ModuleNode module = unit.getModuleNode();
            if (module == null) {
                return new IJavaElement[0];
            }

            IJavaProject javaProject = unit.getJavaProject();
            if (javaProject == null) {
                return new IJavaElement[0];
            }

            // Extract the word at the cursor position
            String source = getUnitSource(unit);
            if (source == null) {
                return new IJavaElement[0];
            }

            String word = extractWordAt(source, offset);
            if (word == null || word.isEmpty()) {
                return new IJavaElement[0];
            }

            GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolving word '" + word + "' at offset " + offset);

            List<IJavaElement> results = new ArrayList<>();

            // 1. Check import statements — most precise resolution
            String fqnFromImport = resolveFromImports(module, word);
            if (fqnFromImport != null) {
                IType type = javaProject.findType(fqnFromImport);
                if (type != null) {
                    results.add(type);
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved via import: " + fqnFromImport);
                    return results.toArray(new IJavaElement[0]);
                }
            }

            // 2. Check if cursor is on a class declaration or type reference in the AST
            String fqnFromAST = resolveTypeFromAST(module, word);
            if (fqnFromAST != null) {
                IType type = javaProject.findType(fqnFromAST);
                if (type != null) {
                    results.add(type);
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved type from AST: " + fqnFromAST);
                    return results.toArray(new IJavaElement[0]);
                }
            }

            // 3. Check if cursor is on a method/field declaration within the current file
            IJavaElement localElement = resolveLocalDeclaration(unit, module, word, offset, source);
            if (localElement != null) {
                results.add(localElement);
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved local declaration: " + localElement.getElementName());
                return results.toArray(new IJavaElement[0]);
            }

            // 4. Last resort — try to find a type by simple name using imports as context
            IType typeFromSearch = searchTypeBySimpleName(javaProject, module, word);
            if (typeFromSearch != null) {
                results.add(typeFromSearch);
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved by type search: " + typeFromSearch.getFullyQualifiedName());
                return results.toArray(new IJavaElement[0]);
            }

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] Failed", e);
        }

        return new IJavaElement[0];
    }

    private String getUnitSource(GroovyCompilationUnit unit) {
        try {
            return unit.getSource();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if the word matches any import. Returns the FQN if found.
     */
    private String resolveFromImports(ModuleNode module, String word) {
        // Check regular imports
        for (ImportNode imp : module.getImports()) {
            ClassNode type = imp.getType();
            if (type != null) {
                String simpleName = type.getNameWithoutPackage();
                // Handle aliased imports
                String alias = imp.getAlias();
                if (alias != null && alias.equals(word)) {
                    return type.getName();
                }
                if (simpleName.equals(word)) {
                    return type.getName();
                }
            }
        }

        // Check static imports (e.g., import static org.junit.Assert.assertEquals)
        for (ImportNode imp : module.getStaticImports().values()) {
            ClassNode type = imp.getType();
            if (type != null && type.getNameWithoutPackage().equals(word)) {
                return type.getName();
            }
        }

        return null;
    }

    /**
     * Walk the AST to find the type reference that the cursor is on.
     * Checks class declarations, extends/implements, field types, method return types,
     * parameter types, constructor calls, class expressions, etc.
     */
    private String resolveTypeFromAST(ModuleNode module, String word) {
        String resolvedFromDeclarations = resolveTypeFromClassDeclarations(module, word);
        if (resolvedFromDeclarations != null) {
            return resolvedFromDeclarations;
        }
        return resolveTypeFromExpressions(module, word);
    }

    private String resolveTypeFromClassDeclarations(ModuleNode module, String word) {
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) {
                continue;
            }

            String resolved = resolveClassHierarchyType(module, classNode, word);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveFieldType(classNode, module, word);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveMethodType(classNode, module, word);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolvePropertyType(classNode, module, word);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String resolveClassHierarchyType(ModuleNode module, ClassNode classNode, String word) {
        String resolved = resolveNamedTypeReference(module, classNode.getUnresolvedSuperClass(), word);
        if (resolved != null) {
            return resolved;
        }
        for (ClassNode iface : classNode.getInterfaces()) {
            resolved = resolveNamedTypeReference(module, iface, word);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String resolveNamedTypeReference(ModuleNode module, ClassNode typeNode, String word) {
        if (typeNode == null || !word.equals(typeNode.getNameWithoutPackage())) {
            return null;
        }
        String name = typeNode.getName();
        if (name.contains(".")) {
            return name;
        }
        String fqn = resolveFromImports(module, word);
        if (fqn != null) {
            return fqn;
        }
        String moduleQualifiedName = qualifyByModulePackage(module, word);
        if (moduleQualifiedName != null) {
            return moduleQualifiedName;
        }
        return resolveFromModuleClasses(module, word);
    }

    private String resolveFromModuleClasses(ModuleNode module, String simpleName) {
        for (ClassNode classNode : module.getClasses()) {
            if (simpleName.equals(classNode.getNameWithoutPackage())) {
                return classNode.getName();
            }
        }
        return simpleName;
    }

    private String resolveFieldType(ClassNode classNode, ModuleNode module, String word) {
        for (FieldNode field : classNode.getFields()) {
            ClassNode fieldType = field.getType();
            if (fieldType != null && word.equals(fieldType.getNameWithoutPackage())) {
                return resolveClassNodeName(module, fieldType, word);
            }
        }
        return null;
    }

    private String resolveMethodType(ClassNode classNode, ModuleNode module, String word) {
        for (MethodNode method : classNode.getMethods()) {
            ClassNode returnType = method.getReturnType();
            if (returnType != null && word.equals(returnType.getNameWithoutPackage())) {
                return resolveClassNodeName(module, returnType, word);
            }
            for (Parameter param : method.getParameters()) {
                ClassNode paramType = param.getType();
                if (paramType != null && word.equals(paramType.getNameWithoutPackage())) {
                    return resolveClassNodeName(module, paramType, word);
                }
            }
        }
        return null;
    }

    private String resolvePropertyType(ClassNode classNode, ModuleNode module, String word) {
        for (PropertyNode prop : classNode.getProperties()) {
            ClassNode propType = prop.getType();
            if (propType != null && word.equals(propType.getNameWithoutPackage())) {
                return resolveClassNodeName(module, propType, word);
            }
        }
        return null;
    }

    private String resolveTypeFromExpressions(ModuleNode module, String word) {
        TypeAtOffsetFinder finder = new TypeAtOffsetFinder(word, module);
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) {
                continue;
            }
            finder.visitClass(classNode);
            if (finder.resolvedFQN != null) {
                return finder.resolvedFQN;
            }
        }
        return null;
    }

    private String qualifyByModulePackage(ModuleNode module, String word) {
        String pkg = module.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if (pkg.endsWith(".")) {
            pkg = pkg.substring(0, pkg.length() - 1);
        }
        return pkg + "." + word;
    }

    /**
     * Resolve a ClassNode name, preferring import resolution.
     */
    private String resolveClassNodeName(ModuleNode module, ClassNode node, String word) {
        String name = node.getName();
        if (!name.contains(".") || name.startsWith("[")) {
            String fqn = resolveFromImports(module, word);
            if (fqn != null) return fqn;
        }
        return name;
    }

    /**
     * Try to find a local method or field declaration matching the word.
     */
    private IJavaElement resolveLocalDeclaration(GroovyCompilationUnit unit,
            ModuleNode module, String word, int offset, String source) {
        try {
            IType[] types = unit.getTypes();
            if (types == null) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] unit.getTypes() returned null");
                return null;
            }

            GroovyLanguageServerPlugin.logInfo("[codeSelect] unit has " + types.length + " types");
            IJavaElement directMember = resolveDirectMemberDeclaration(types, word);
            if (directMember != null) {
                return directMember;
            }

            return resolveTraitMemberDeclaration(unit, module, types, word, offset, source);
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveLocalDeclaration failed", e);
        }
        return null;
    }

    private IJavaElement resolveDirectMemberDeclaration(IType[] types, String word) throws JavaModelException {
        for (IType type : types) {
            logTypeSummary(type);
            IJavaElement member = findMemberInType(type, word);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    private void logTypeSummary(IType type) throws JavaModelException {
        GroovyLanguageServerPlugin.logInfo("[codeSelect]   type: " + type.getElementName()
                + LOG_FIELDS + type.getFields().length + LOG_METHODS + type.getMethods().length);
    }

    private IJavaElement findMemberInType(IType type, String word) throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            if (word.equals(method.getElementName())) {
                return method;
            }
        }

        for (org.eclipse.jdt.core.IField field : type.getFields()) {
            if (word.equals(field.getElementName())) {
                return field;
            }
        }

        return null;
    }

    private IJavaElement resolveTraitMemberDeclaration(GroovyCompilationUnit unit, ModuleNode module,
            IType[] types, String word, int offset, String source) throws JavaModelException {
        if (source == null || word == null || word.isEmpty()) {
            return null;
        }

        int targetLine = offsetToLine(source, offset);
        ClassNode owner = findEnclosingClass(module, targetLine);
        if (owner == null) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] No enclosing class found at line " + targetLine);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] Enclosing class: " + owner.getName()
                + " interfaces=" + (owner.getInterfaces() != null ? owner.getInterfaces().length : 0));

        ClassNode[] interfaces = owner.getInterfaces();
        if (interfaces == null || interfaces.length == 0) {
            return null;
        }

        for (ClassNode iface : interfaces) {
            String ifaceSimple = iface.getNameWithoutPackage();
            if (ifaceSimple == null || ifaceSimple.isEmpty()) {
                continue;
            }

            GroovyLanguageServerPlugin.logInfo("[codeSelect] Checking interface: " + ifaceSimple
                    + " (name=" + iface.getName() + ")");

            IType traitType = resolveTraitType(unit, module, types, owner, iface, ifaceSimple);

            IJavaElement fromTraitType = resolveTraitTypeMember(traitType, word);
            if (fromTraitType != null) {
                return fromTraitType;
            }

            IJavaElement fromAst = resolveTraitAstMember(unit, module, types, ifaceSimple, traitType, word);
            if (fromAst != null) {
                return fromAst;
            }
        }

        return null;
    }

    private IType resolveTraitType(GroovyCompilationUnit unit, ModuleNode module, IType[] types,
            ClassNode owner, ClassNode iface, String ifaceSimple) {
        IType traitType = findTypeInUnit(types, ifaceSimple);
        GroovyLanguageServerPlugin.logInfo("[codeSelect] findTypeInUnit(" + ifaceSimple + ") = "
                + (traitType != null ? traitType.getElementName() : "null"));
        if (traitType != null) {
            return traitType;
        }

        IJavaProject javaProject = unit.getJavaProject();
        if (javaProject == null) {
            return null;
        }

        try {
            traitType = resolveTraitTypeFromProject(javaProject, module, owner, iface, ifaceSimple);
        } catch (JavaModelException e) {
            traitType = null;
        }
        return traitType;
    }

    private IType resolveTraitTypeFromProject(IJavaProject javaProject, ModuleNode module,
            ClassNode owner, ClassNode iface, String ifaceSimple) throws JavaModelException {
        IType traitType = resolveTraitTypeByDeclaredName(javaProject, iface);
        if (traitType != null) {
            return traitType;
        }

        traitType = resolveTraitTypeByOwnerPackage(javaProject, owner, ifaceSimple);
        if (traitType != null) {
            return traitType;
        }

        traitType = resolveTypeByPackages(javaProject, ifaceSimple, TRAIT_AUTO_PACKAGES);
        if (traitType != null) {
            return traitType;
        }

        traitType = resolveTraitTypeByExplicitImports(javaProject, module, ifaceSimple);
        if (traitType != null) {
            return traitType;
        }

        return resolveTraitTypeByStarImports(javaProject, module, ifaceSimple);
    }

    private IType resolveTraitTypeByDeclaredName(IJavaProject javaProject, ClassNode iface) throws JavaModelException {
        String fqn = iface.getName();
        if (fqn != null && fqn.contains(".")) {
            return javaProject.findType(fqn);
        }
        return null;
    }

    private IType resolveTraitTypeByOwnerPackage(IJavaProject javaProject, ClassNode owner, String ifaceSimple)
            throws JavaModelException {
        String ownerPkg = owner.getPackageName();
        if (ownerPkg != null && !ownerPkg.isEmpty()) {
            return javaProject.findType(ownerPkg + "." + ifaceSimple);
        }
        return null;
    }

    private IType resolveTypeByPackages(IJavaProject javaProject, String simpleName, String[] packages)
            throws JavaModelException {
        for (String pkg : packages) {
            IType type = javaProject.findType(pkg + simpleName);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private IType resolveTraitTypeByExplicitImports(IJavaProject javaProject, ModuleNode module, String ifaceSimple)
            throws JavaModelException {
        if (module == null) {
            return null;
        }
        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && ifaceSimple.equals(impType.getNameWithoutPackage())) {
                IType type = javaProject.findType(impType.getName());
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    private IType resolveTraitTypeByStarImports(IJavaProject javaProject, ModuleNode module, String ifaceSimple)
            throws JavaModelException {
        if (module == null) {
            return null;
        }
        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType type = javaProject.findType(pkgName + ifaceSimple);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    private IJavaElement resolveTraitTypeMember(IType traitType, String word) throws JavaModelException {
        if (traitType == null) {
            return null;
        }

        logTraitTypeDetails(traitType);

        org.eclipse.jdt.core.IField traitField = findFieldByName(traitType, word);
        if (traitField != null) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field: " + word);
            return traitField;
        }

        String cap = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        IMethod getter = findMethodByName(traitType, "get" + cap);
        if (getter != null) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait getter: get" + cap);
            return getter;
        }

        IMethod isser = findMethodByName(traitType, "is" + cap);
        if (isser != null) {
            return isser;
        }

        IMethod setter = findMethodByName(traitType, "set" + cap);
        if (setter != null) {
            return setter;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] JDT IType has no member '" + word
                + "', checking AST directly...");
        return null;
    }

    private void logTraitTypeDetails(IType traitType) throws JavaModelException {
        GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait IType: " + traitType.getElementName()
                + LOG_FIELDS + traitType.getFields().length
                + LOG_METHODS + traitType.getMethods().length);
        for (org.eclipse.jdt.core.IField field : traitType.getFields()) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect]   field: " + field.getElementName());
        }
        for (IMethod method : traitType.getMethods()) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect]   method: " + method.getElementName());
        }
    }

    private IJavaElement resolveTraitAstMember(GroovyCompilationUnit unit, ModuleNode module,
            IType[] types, String ifaceSimple, IType traitType, String word) throws JavaModelException {
        ClassNode resolvedTraitNode = findTraitClassNodeInModule(module, ifaceSimple);
        if (resolvedTraitNode == null) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Could not find trait '" + ifaceSimple + "' in AST module");
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] AST trait node: " + resolvedTraitNode.getName()
                + LOG_FIELDS + resolvedTraitNode.getFields().size()
                + " props=" + resolvedTraitNode.getProperties().size()
                + LOG_METHODS + resolvedTraitNode.getMethods().size());

        IJavaElement directMember = resolveDirectTraitAstMember(resolvedTraitNode, types, ifaceSimple, traitType, word);
        if (directMember != null) {
            return directMember;
        }

        return resolveTraitFieldHelperMember(unit, module, resolvedTraitNode, types, ifaceSimple, traitType, word);
    }

    private IJavaElement resolveDirectTraitAstMember(ClassNode resolvedTraitNode, IType[] types,
            String ifaceSimple, IType traitType, String word) throws JavaModelException {
        if (hasPropertyNamed(resolvedTraitNode, word)) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait property in AST: " + word);
            if (traitType != null) {
                org.eclipse.jdt.core.IField field = findFieldByName(traitType, word);
                if (field != null) {
                    return field;
                }
            }
            return fallbackTraitElement(traitType, types, ifaceSimple);
        }

        if (hasFieldNamed(resolvedTraitNode, word)) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field in AST: " + word);
            return fallbackTraitElement(traitType, types, ifaceSimple);
        }

        if (hasMethodNamed(resolvedTraitNode, word)) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait method in AST: " + word);
            return fallbackTraitElement(traitType, types, ifaceSimple);
        }

        return null;
    }

    private boolean hasPropertyNamed(ClassNode classNode, String name) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (name.equals(prop.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFieldNamed(ClassNode classNode, String name) {
        for (FieldNode field : classNode.getFields()) {
            if (name.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMethodNamed(ClassNode classNode, String name) {
        for (MethodNode method : classNode.getMethods()) {
            if (name.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private IJavaElement resolveTraitFieldHelperMember(GroovyCompilationUnit unit, ModuleNode module,
            ClassNode resolvedTraitNode, IType[] types, String ifaceSimple, IType traitType, String word)
            {
        ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(resolvedTraitNode, module);
        if (helperNode == null) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] FieldHelper node: " + helperNode.getName()
                + LOG_FIELDS + helperNode.getFields().size()
                + LOG_METHODS + helperNode.getMethods().size());
        for (FieldNode helperField : helperNode.getFields()) {
            GroovyLanguageServerPlugin.logInfo("[codeSelect]   helper field: " + helperField.getName());
        }

        IJavaElement fromField = resolveTraitFieldHelperField(unit, module, helperNode, types, ifaceSimple, traitType, word);
        if (fromField != null) {
            return fromField;
        }

        return resolveTraitFieldHelperAccessor(unit, module, helperNode, types, ifaceSimple, traitType, word);
    }

    private IJavaElement resolveTraitFieldHelperField(GroovyCompilationUnit unit, ModuleNode module,
            ClassNode helperNode, IType[] types, String ifaceSimple, IType traitType, String word) {
        for (FieldNode helperField : helperNode.getFields()) {
            if (TraitMemberResolver.isTraitFieldMatch(helperField.getName(), word)) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field in FieldHelper: "
                        + helperField.getName() + " matches '" + word + "'");
                ClassNode fieldTypeNode = helperField.getType();
                if (fieldTypeNode != null) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] FieldHelper field type: " + fieldTypeNode.getName());
                    IType fieldIType = resolveClassNodeToIType(fieldTypeNode, module, unit.getJavaProject());
                    if (fieldIType != null) {
                        GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved field type IType: " + fieldIType.getFullyQualifiedName());
                        return fieldIType;
                    }
                }
                return fallbackTraitElement(traitType, types, ifaceSimple);
            }
        }
        return null;
    }

    private IJavaElement resolveTraitFieldHelperAccessor(GroovyCompilationUnit unit, ModuleNode module,
            ClassNode helperNode, IType[] types, String ifaceSimple, IType traitType, String word) {
        String cap = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        for (MethodNode helperMethod : helperNode.getMethods()) {
            String methodName = helperMethod.getName();
            if (isMatchingAccessorName(methodName, cap)) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait accessor in FieldHelper: " + methodName);
                IType returnIType = resolveAccessorReturnType(helperMethod, module, unit);
                if (returnIType != null) {
                    return returnIType;
                }
                return fallbackTraitElement(traitType, types, ifaceSimple);
            }
        }
        return null;
    }

    private boolean isMatchingAccessorName(String methodName, String cap) {
        return methodName.equals("get" + cap) || methodName.equals("set" + cap) || methodName.equals("is" + cap);
    }

    private IType resolveAccessorReturnType(MethodNode helperMethod, ModuleNode module, GroovyCompilationUnit unit) {
        String methodName = helperMethod.getName();
        if (!(methodName.startsWith("get") || methodName.startsWith("is"))) {
            return null;
        }
        ClassNode returnType = helperMethod.getReturnType();
        if (returnType == null) {
            return null;
        }
        return resolveClassNodeToIType(returnType, module, unit.getJavaProject());
    }

    private IJavaElement fallbackTraitElement(IType traitType, IType[] types, String ifaceSimple) {
        if (traitType != null) {
            return traitType;
        }
        return findTypeInUnit(types, ifaceSimple);
    }

    /**
     * Find a ClassNode by simple name in the module's classes.
     */
    private ClassNode findTraitClassNodeInModule(ModuleNode module, String simpleName) {
        if (module == null || simpleName == null) return null;
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            if (simpleName.equals(classNode.getNameWithoutPackage())) {
                return classNode;
            }
        }
        return null;
    }

    private IType findTypeInUnit(IType[] types, String simpleName) {
        for (IType type : types) {
            if (simpleName.equals(type.getElementName())) {
                return type;
            }
        }
        return null;
    }

    private org.eclipse.jdt.core.IField findFieldByName(IType type, String name) throws JavaModelException {
        org.eclipse.jdt.core.IField[] fields = type.getFields();
        if (fields == null) {
            return null;
        }
        for (org.eclipse.jdt.core.IField field : fields) {
            if (name.equals(field.getElementName())) {
                return field;
            }
        }
        return null;
    }

    /**
     * Resolve a Groovy AST ClassNode to a JDT IType using the module's imports and project.
     */
    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module, IJavaProject project) {
        if (typeNode == null || project == null) {
            return null;
        }
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            IType resolved = resolveClassNodeByQualifiedName(project, typeName);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveClassNodeByImports(project, module, typeName);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveClassNodeByModulePackage(project, module, typeName);
            if (resolved != null) {
                return resolved;
            }

            return resolveTypeByPackages(project, typeName, DEFAULT_AUTO_PACKAGES);
        } catch (JavaModelException e) {
            return null;
        }
    }

    private IType resolveClassNodeByQualifiedName(IJavaProject project, String typeName) throws JavaModelException {
        if (typeName.contains(".")) {
            return project.findType(typeName);
        }
        return null;
    }

    private IType resolveClassNodeByImports(IJavaProject project, ModuleNode module, String typeName)
            throws JavaModelException {
        if (module == null) {
            return null;
        }

        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                IType type = project.findType(impType.getName());
                if (type != null) {
                    return type;
                }
            }
        }

        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType type = project.findType(pkgName + typeName);
                if (type != null) {
                    return type;
                }
            }
        }

        return null;
    }

    private IType resolveClassNodeByModulePackage(IJavaProject project, ModuleNode module, String typeName)
            throws JavaModelException {
        if (module == null) {
            return null;
        }

        String pkg = module.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if (pkg.endsWith(".")) {
            pkg = pkg.substring(0, pkg.length() - 1);
        }
        return project.findType(pkg + "." + typeName);
    }

    private IMethod findMethodByName(IType type, String name) throws JavaModelException {
        IMethod[] methods = type.getMethods();
        if (methods == null) {
            return null;
        }
        for (IMethod method : methods) {
            if (name.equals(method.getElementName())) {
                return method;
            }
        }
        return null;
    }

    private ClassNode findEnclosingClass(ModuleNode module, int line1Based) {
        ClassNode best = null;
        for (ClassNode classNode : module.getClasses()) {
            int start = classNode.getLineNumber();
            int end = classNode.getLastLineNumber();
            boolean insideClassRange = start > 0 && end >= start && line1Based >= start && line1Based <= end;
            if (insideClassRange && (best == null || start >= best.getLineNumber())) {
                best = classNode;
            }
        }
        return best;
    }

    private int offsetToLine(String source, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, source.length()));
        int line = 1;
        for (int i = 0; i < safeOffset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Search for a type by simple name, checking default Groovy auto-imports
     * and java.lang.
     */
    private IType searchTypeBySimpleName(IJavaProject project, ModuleNode module, String word) {
        // Only try if the word starts with uppercase (likely a type name)
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
            return null;
        }

        // Check common auto-import packages
        try {
            IType type = resolveTypeByPackages(project, word, DEFAULT_AUTO_PACKAGES);
            if (type != null) {
                return type;
            }

            // Check star imports from the module
            for (ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType importedType = project.findType(pkgName + word);
                    if (importedType != null) {
                        return importedType;
                    }
                }
            }
        } catch (JavaModelException e) {
            return null;
        }

        return null;
    }

    /**
     * Extract the identifier word at the given offset.
     */
    private String extractWordAt(String content, int offset) {
        Integer normalizedOffset = normalizeOffsetToIdentifier(content, offset);
        if (normalizedOffset == null) {
            return null;
        }

        int start = normalizedOffset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = normalizedOffset;
        while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
            end++;
        }
        if (start == end) {
            return null;
        }
        return content.substring(start, end);
    }

    private Integer normalizeOffsetToIdentifier(String content, int offset) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        int normalizedOffset = offset;
        if (normalizedOffset < 0 || normalizedOffset >= content.length()) {
            if (normalizedOffset > 0 && normalizedOffset <= content.length()
                    && Character.isJavaIdentifierPart(content.charAt(normalizedOffset - 1))) {
                normalizedOffset = normalizedOffset - 1;
            } else {
                return null;
            }
        }

        if (!Character.isJavaIdentifierPart(content.charAt(normalizedOffset))) {
            if (normalizedOffset > 0 && Character.isJavaIdentifierPart(content.charAt(normalizedOffset - 1))) {
                normalizedOffset = normalizedOffset - 1;
            } else {
                return null;
            }
        }

        return normalizedOffset;
    }

    /**
     * AST visitor that finds ClassExpression / ConstructorCallExpression nodes
     * at a given offset and resolves them to FQNs.
     */
    private static class TypeAtOffsetFinder extends ClassCodeVisitorSupport {
        private final String targetWord;
        private final ModuleNode module;
        String resolvedFQN;

        TypeAtOffsetFinder(String word, ModuleNode module) {
            this.targetWord = word;
            this.module = module;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitClassExpression(ClassExpression expr) {
            if (resolvedFQN != null) return;
            if (expr.getType() != null && expr.getType().getNameWithoutPackage().equals(targetWord)) {
                resolvedFQN = resolveTypeName(expr.getType());
            }
            super.visitClassExpression(expr);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (resolvedFQN != null) return;
            ClassNode type = call.getType();
            if (type != null && type.getNameWithoutPackage().equals(targetWord)) {
                resolvedFQN = resolveTypeName(type);
            }
            super.visitConstructorCallExpression(call);
        }

        private String resolveTypeName(ClassNode type) {
            String name = type.getName();
            if (name.contains(".")) {
                return name;
            }
            // Try imports
            for (ImportNode imp : module.getImports()) {
                ClassNode impType = imp.getType();
                if (impType != null && impType.getNameWithoutPackage().equals(targetWord)) {
                    return impType.getName();
                }
            }
            return name;
        }
    }
}
