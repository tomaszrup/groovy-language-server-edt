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
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.model.GroovyCompilationUnit;
import org.codehaus.jdt.groovy.model.ICodeSelectHelper;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

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
@SuppressWarnings("unused")
public class MinimalCodeSelectHelper implements ICodeSelectHelper {

    private static final String LOG_FIELDS = " fields=";
    private static final String LOG_METHODS = " methods=";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
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
            SelectionContext context = createSelectionContext(unit, offset);
            if (context == null) {
                return new IJavaElement[0];
            }

            GroovyLanguageServerPlugin.logInfo(
                    "[codeSelect] Resolving word '" + context.word() + "' at offset " + offset);

            IJavaElement resolved = resolveSelectedElement(unit, context);
            return resolved == null ? new IJavaElement[0] : new IJavaElement[] {resolved};
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] Failed", e);
        }

        return new IJavaElement[0];
    }

    private SelectionContext createSelectionContext(GroovyCompilationUnit unit, int offset) {
        ModuleNode module = unit.getModuleNode();
        IJavaProject javaProject = unit.getJavaProject();
        String source = getUnitSource(unit);
        if (module == null || javaProject == null || source == null) {
            return null;
        }

        String word = extractWordAt(source, offset);
        if (word == null || word.isEmpty()) {
            return null;
        }

        return new SelectionContext(module, javaProject, source, resolveUnitSourceUri(unit), word, offset,
                Character.isLowerCase(word.charAt(0)));
    }

    private IJavaElement resolveSelectedElement(GroovyCompilationUnit unit, SelectionContext context)
            throws JavaModelException {
        IJavaElement importElement = resolveImportedElement(context);
        if (importElement != null) {
            return importElement;
        }

        IJavaElement astType = resolveAstTypeElement(context);
        if (astType != null) {
            return astType;
        }

        IJavaElement localDeclaration = resolveLocalDeclaration(unit, context.module(), context.word(),
                context.offset(), context.source());
        if (localDeclaration != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[codeSelect] Resolved local declaration: " + localDeclaration.getElementName());
            return localDeclaration;
        }

        IJavaElement dotCallElement = resolveDotMethodCall(context.module(), context.javaProject(), context.source(),
                context.offset(), context.word(), context.sourceUri());
        if (dotCallElement != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[codeSelect] Resolved dot method call: " + dotCallElement.getElementName());
            return dotCallElement;
        }

        IJavaElement localVarType = resolveLocalVariableType(context.module(), context.javaProject(), context.word(),
                context.sourceUri());
        if (localVarType != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[codeSelect] Resolved local variable type: " + localVarType.getElementName());
            return localVarType;
        }

        return resolveTypeSearchElement(context);
    }

    private IJavaElement resolveImportedElement(SelectionContext context) throws JavaModelException {
        String fqnFromImport = resolveFromImports(context.module(), context.word());
        if (fqnFromImport == null) {
            return null;
        }

        IType type = ScopedTypeLookupSupport.findType(context.javaProject(), fqnFromImport, context.sourceUri());
        if (type == null) {
            return null;
        }

        if (!type.getElementName().equals(context.word())) {
            IJavaElement member = findStaticMemberInType(type, context.word());
            if (member != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[codeSelect] Resolved static import member: " + context.word() + " in " + fqnFromImport);
                return member;
            }
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved via import: " + fqnFromImport);
        return type;
    }

    private IJavaElement resolveAstTypeElement(SelectionContext context) throws JavaModelException {
        if (context.startsLowerCase()) {
            return null;
        }

        String fqnFromAST = resolveTypeFromAST(context.module(), context.word());
        if (fqnFromAST == null) {
            return null;
        }

        IType type = ScopedTypeLookupSupport.findType(context.javaProject(), fqnFromAST, context.sourceUri());
        if (type == null) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved type from AST: " + fqnFromAST);
        return type;
    }

    private IJavaElement resolveTypeSearchElement(SelectionContext context) {
        if (context.startsLowerCase()) {
            return null;
        }

        IType typeFromSearch = searchTypeBySimpleName(context.javaProject(), context.module(), context.word(),
                context.sourceUri());
        if (typeFromSearch == null) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[codeSelect] Resolved by type search: " + typeFromSearch.getFullyQualifiedName());
        return typeFromSearch;
    }

    private String resolveUnitSourceUri(GroovyCompilationUnit unit) {
        if (unit == null || unit.getResource() == null || unit.getResource().getLocationURI() == null) {
            return null;
        }
        return unit.getResource().getLocationURI().toString();
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
        String regularImport = resolveRegularImport(module, word);
        if (regularImport != null) {
            return regularImport;
        }

        String staticImport = resolveStaticImport(module, word);
        return staticImport != null ? staticImport : resolveStaticStarImport(module, word);
    }

    private String resolveRegularImport(ModuleNode module, String word) {
        for (ImportNode imp : module.getImports()) {
            ClassNode type = imp.getType();
            if (type == null) {
                continue;
            }

            String alias = imp.getAlias();
            if ((alias != null && alias.equals(word)) || type.getNameWithoutPackage().equals(word)) {
                return type.getName();
            }
        }
        return null;
    }

    private String resolveStaticImport(ModuleNode module, String word) {
        for (ImportNode imp : module.getStaticImports().values()) {
            ClassNode type = imp.getType();
            if (type == null) {
                continue;
            }

            String fieldName = imp.getFieldName();
            if (type.getNameWithoutPackage().equals(word) || (fieldName != null && fieldName.equals(word))) {
                return type.getName();
            }
        }
        return null;
    }

    private String resolveStaticStarImport(ModuleNode module, String word) {
        for (ImportNode imp : module.getStaticStarImports().values()) {
            ClassNode type = imp.getType();
            if (type != null && type.getNameWithoutPackage().equals(word)) {
                return type.getName();
            }
        }
        return null;
    }

    /**
     * Find a method or field by name in a type (for static import member resolution).
     */
    private IJavaElement findStaticMemberInType(IType type, String memberName) {
        try {
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(memberName)) {
                    return method;
                }
            }
            IField field = type.getField(memberName);
            if (field != null && field.exists()) {
                return field;
            }
        } catch (JavaModelException e) {
            // fall through
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
        return resolveTypeByPackages(javaProject, simpleName, packages, null);
    }

    private IType resolveTypeByPackages(IJavaProject javaProject, String simpleName, String[] packages,
            String sourceUri)
            throws JavaModelException {
        for (String pkg : packages) {
            IType type = ScopedTypeLookupSupport.findType(javaProject, pkg + simpleName, sourceUri);
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
        return resolveClassNodeToIType(typeNode, module, project, null);
    }

    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module, IJavaProject project,
            String sourceUri) {
        if (typeNode == null || project == null) {
            return null;
        }
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }

            IType resolved = resolveClassNodeByQualifiedName(project, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveClassNodeByImports(project, module, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            resolved = resolveClassNodeByModulePackage(project, module, typeName, sourceUri);
            if (resolved != null) {
                return resolved;
            }

            return resolveTypeByPackages(project, typeName, DEFAULT_AUTO_PACKAGES, sourceUri);
        } catch (JavaModelException e) {
            return null;
        }
    }

    private IType resolveClassNodeByQualifiedName(IJavaProject project, String typeName) throws JavaModelException {
        return resolveClassNodeByQualifiedName(project, typeName, null);
    }

    private IType resolveClassNodeByQualifiedName(IJavaProject project, String typeName, String sourceUri)
            throws JavaModelException {
        if (typeName.contains(".")) {
            return ScopedTypeLookupSupport.findType(project, typeName, sourceUri);
        }
        return null;
    }

    private IType resolveClassNodeByImports(IJavaProject project, ModuleNode module, String typeName)
            throws JavaModelException {
        return resolveClassNodeByImports(project, module, typeName, null);
    }

    private IType resolveClassNodeByImports(IJavaProject project, ModuleNode module, String typeName,
            String sourceUri)
            throws JavaModelException {
        if (module == null) {
            return null;
        }

        for (ImportNode imp : module.getImports()) {
            ClassNode impType = imp.getType();
            if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                IType type = ScopedTypeLookupSupport.findType(project, impType.getName(), sourceUri);
                if (type != null) {
                    return type;
                }
            }
        }

        for (ImportNode starImport : module.getStarImports()) {
            String pkgName = starImport.getPackageName();
            if (pkgName != null) {
                IType type = ScopedTypeLookupSupport.findType(project, pkgName + typeName, sourceUri);
                if (type != null) {
                    return type;
                }
            }
        }

        return null;
    }

    private IType resolveClassNodeByModulePackage(IJavaProject project, ModuleNode module, String typeName)
            throws JavaModelException {
        return resolveClassNodeByModulePackage(project, module, typeName, null);
    }

    private IType resolveClassNodeByModulePackage(IJavaProject project, ModuleNode module, String typeName,
            String sourceUri)
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
        return ScopedTypeLookupSupport.findType(project, pkg + "." + typeName, sourceUri);
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
        int safeOffset = Math.clamp(offset, 0, source.length());
        int line = 1;
        for (int i = 0; i < safeOffset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Convert a 0-based offset to a 1-based column number (matching Groovy AST conventions).
     */
    private int offsetToColumn(String source, int offset) {
        int safeOffset = Math.clamp(offset, 0, source.length());
        int col = 1;
        for (int i = safeOffset - 1; i >= 0; i--) {
            if (source.charAt(i) == '\n') break;
            col++;
        }
        return col;
    }

    /**
     * Search for a type by simple name, checking default Groovy auto-imports
     * and java.lang.
     */
    // =========================================================================
    // 3b. Dot method call resolution  (expr.method())
    // =========================================================================

    /**
     * When the cursor is on a method name after a dot (e.g., {@code foo.value()}),
     * resolve the receiver expression type and find the method within it.
     */
    private IJavaElement resolveDotMethodCall(ModuleNode module, IJavaProject project,
                                              String source, int offset, String word) {
        return resolveDotMethodCall(module, project, source, offset, word, null);
    }

    private IJavaElement resolveDotMethodCall(ModuleNode module, IJavaProject project,
                                              String source, int offset, String word, String sourceUri) {
        try {
            // Check if the word is preceded by a dot
            int wordStart = offset;
            while (wordStart > 0 && Character.isJavaIdentifierPart(source.charAt(wordStart - 1))) {
                wordStart--;
            }
            if (wordStart <= 0 || source.charAt(wordStart - 1) != '.') {
                return null; // Not a dot-expression
            }

            IType receiverType = null;

            String receiverChain = extractQualifiedReceiverChain(source, wordStart);
            if (receiverChain != null && !receiverChain.isBlank()) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Dot call: receiver='" + receiverChain
                        + "' member='" + word + "'");
                receiverType = resolveQualifiedReceiverType(module, project, receiverChain);
            }

            // Fallback: AST-based resolution for complex receivers like "new Foo().method()"
            if (receiverType == null) {
                receiverType = resolveReceiverTypeFromAst(module, project, offset, word, source, sourceUri);
            }

            if (receiverType == null) {
                return null;
            }

            GroovyLanguageServerPlugin.logInfo("[codeSelect] Receiver type: " + receiverType.getFullyQualifiedName());

            IType nestedType = findNestedType(receiverType, word, project);
            if (nestedType != null) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Found nested type '" + word
                        + "' in " + receiverType.getFullyQualifiedName());
                return nestedType;
            }

            // Find the method in the receiver type
            IMethod method = findMethodByNameInHierarchy(receiverType, word);
            if (method != null) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Found method '" + word
                        + "' in " + receiverType.getFullyQualifiedName());
                return method;
            }

            // Also check for property access (Groovy: "foo.bar" → "foo.getBar()")
            org.eclipse.jdt.core.IField field = findFieldByName(receiverType, word);
            if (field != null) {
                return field;
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveDotMethodCall failed", e);
        }
        return null;
    }

    private String extractQualifiedReceiverChain(String source, int wordStart) {
        int dotPos = wordStart - 1;
        int receiverStart = dotPos - 1;
        while (receiverStart >= 0) {
            char current = source.charAt(receiverStart);
            if (!Character.isJavaIdentifierPart(current) && current != '.') {
                break;
            }
            receiverStart--;
        }
        receiverStart++;

        if (receiverStart >= dotPos) {
            return null;
        }

        String chain = source.substring(receiverStart, dotPos);
        return chain.isBlank() ? null : chain;
    }

    private IType resolveQualifiedReceiverType(ModuleNode module, IJavaProject project, String receiverChain) {
        if (receiverChain == null || receiverChain.isBlank()) {
            return null;
        }

        String[] segments = receiverChain.split("\\.");
        if (segments.length == 0) {
            return null;
        }

        IType currentType = resolveExpressionType(module, project, segments[0]);
        for (int index = 1; currentType != null && index < segments.length; index++) {
            currentType = resolveQualifiedMemberType(currentType, segments[index], project);
        }
        return currentType;
    }

    private IType resolveQualifiedMemberType(IType receiverType, String memberName, IJavaProject project) {
        if (receiverType == null || memberName == null || memberName.isBlank()) {
            return null;
        }

        try {
            IType nestedType = findNestedType(receiverType, memberName, project);
            if (nestedType != null) {
                return nestedType;
            }

            IField field = findFieldByName(receiverType, memberName);
            if (field != null) {
                return resolveMemberSignatureType(field.getTypeSignature(), receiverType);
            }

                IMethod method = findMethodByNameInHierarchy(receiverType, memberName);
            if (method != null) {
                return resolveMemberSignatureType(method.getReturnType(), receiverType);
            }
        } catch (JavaModelException e) {
            return null;
        }

        return null;
    }

    private IType resolveMemberSignatureType(String typeSignature, IType declaringType)
            throws JavaModelException {
        if (typeSignature == null || declaringType == null) {
            return null;
        }

        String typeName = Signature.toString(typeSignature);
        int genericStart = typeName.indexOf('<');
        if (genericStart >= 0) {
            typeName = typeName.substring(0, genericStart);
        }
        int arrayStart = typeName.indexOf('[');
        if (arrayStart >= 0) {
            typeName = typeName.substring(0, arrayStart);
        }
        typeName = typeName.trim();

        String[][] resolvedNames = declaringType.resolveType(typeName);
        if (resolvedNames != null && resolvedNames.length > 0) {
            String fqn = resolvedNames[0][0].isEmpty()
                    ? resolvedNames[0][1]
                    : resolvedNames[0][0] + "." + resolvedNames[0][1];
            return declaringType.getJavaProject().findType(fqn);
        }

        return declaringType.getJavaProject().findType(typeName);
    }

    private IType findNestedType(IType receiverType, String simpleName, IJavaProject project)
            throws JavaModelException {
        if (receiverType == null || simpleName == null || simpleName.isBlank()) {
            return null;
        }

        IType memberType = findMemberType(receiverType, simpleName);
        if (memberType != null) {
            return memberType;
        }

        IType direct = receiverType.getType(simpleName);
        if (direct != null && direct.exists()) {
            return direct;
        }

        IJavaProject javaProject = receiverType.getJavaProject() != null ? receiverType.getJavaProject() : project;
        if (javaProject == null) {
            return null;
        }

        IType binaryType = findNestedProjectType(javaProject, receiverType.getFullyQualifiedName('$'), "$", simpleName);
        return binaryType != null
                ? binaryType
                : findNestedProjectType(javaProject, receiverType.getFullyQualifiedName(), ".", simpleName);
    }

    private IType findMemberType(IType receiverType, String simpleName) throws JavaModelException {
        IType[] memberTypes = receiverType.getTypes();
        if (memberTypes == null) {
            return null;
        }

        for (IType memberType : memberTypes) {
            if (simpleName.equals(memberType.getElementName())) {
                return memberType;
            }
        }
        return null;
    }

    private IType findNestedProjectType(IJavaProject javaProject, String receiverFqn, String separator,
            String simpleName) throws JavaModelException {
        if (receiverFqn == null || receiverFqn.isBlank()) {
            return null;
        }
        return javaProject.findType(receiverFqn + separator + simpleName);
    }

    /**
     * Walk the AST to find a MethodCallExpression at the given offset whose method
     * name matches {@code word}, then resolve the receiver's type via JDT.
     * This handles complex receiver expressions like {@code new Foo().method()}.
     */
    private IType resolveReceiverTypeFromAst(ModuleNode module, IJavaProject project,
                                              int offset, String methodName, String source) {
        return resolveReceiverTypeFromAst(module, project, offset, methodName, source, null);
    }

    private IType resolveReceiverTypeFromAst(ModuleNode module, IJavaProject project,
                                              int offset, String methodName, String source,
                                              String sourceUri) {
        MethodCallExpression found = findMethodCallAtOffset(module, offset, methodName, source);
        if (found == null) return null;

        Expression objectExpr = found.getObjectExpression();
        IType jdtReceiverType = resolveObjectExpressionIType(objectExpr, module, project, sourceUri);
        if (jdtReceiverType != null) {
            return jdtReceiverType;
        }

        ClassNode receiverClassNode = helpfulClassNode(resolveObjectExpressionType(objectExpr, module));
        if (receiverClassNode == null) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[codeSelect] AST fallback: receiver ClassNode='"
                + receiverClassNode.getName() + "' for method '" + methodName + "'");

        try {
            return resolveClassNodeToIType(receiverClassNode, module, project, sourceUri);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] AST fallback type resolution failed", e);
            return null;
        }
    }

    /**
     * Resolve the type of an object expression (the receiver part of a method call).
     */
    private ClassNode resolveObjectExpressionType(Expression objectExpr, ModuleNode module) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnType(nestedCall, module);
        }
        if (objectExpr instanceof VariableExpression varExpr) {
            String varName = varExpr.getName();
            if ("this".equals(varName)) return null;
            ClassNode varType = resolveLocalVariableClassNode(module, varName);
            if (!isUnhelpfulClassNode(varType)) {
                return varType;
            }
            ClassNode exprType = varExpr.getType();
            if (!isUnhelpfulClassNode(exprType)) {
                return exprType;
            }
        }
        if (objectExpr instanceof ClassExpression classExpr) {
            return classExpr.getType();
        }
        return null;
    }

    private IType resolveObjectExpressionIType(Expression objectExpr, ModuleNode module, IJavaProject project) {
        return resolveObjectExpressionIType(objectExpr, module, project, null);
    }

    private IType resolveObjectExpressionIType(Expression objectExpr, ModuleNode module, IJavaProject project,
            String sourceUri) {
        try {
            IType directType = resolveDirectObjectExpressionIType(objectExpr, module, project, sourceUri);
            if (directType != null) {
                return directType;
            }
            if (objectExpr instanceof VariableExpression varExpr) {
                return resolveVariableExpressionIType(varExpr, module, project, sourceUri);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveObjectExpressionIType failed", e);
        }
        return null;
    }

        private IType resolveDirectObjectExpressionIType(Expression objectExpr, ModuleNode module, IJavaProject project,
            String sourceUri) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return resolveClassNodeToIType(ctorCall.getType(), module, project, sourceUri);
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnIType(nestedCall, module, project, sourceUri);
        }
        if (objectExpr instanceof PropertyExpression propertyExpression) {
            return resolvePropertyExpressionIType(propertyExpression, module, project, sourceUri);
        }
        if (objectExpr instanceof ClassExpression classExpr) {
            return resolveClassNodeToIType(classExpr.getType(), module, project, sourceUri);
        }
        return null;
    }

        private IType resolvePropertyExpressionIType(PropertyExpression propertyExpression, ModuleNode module,
            IJavaProject project, String sourceUri) {
        IType ownerType = resolveObjectExpressionIType(propertyExpression.getObjectExpression(), module, project,
                sourceUri);
        return ownerType == null ? null
                : resolveQualifiedMemberType(ownerType, propertyExpression.getPropertyAsString(), project);
    }

        private IType resolveVariableExpressionIType(VariableExpression varExpr, ModuleNode module, IJavaProject project,
            String sourceUri) {
        String receiverVarName = varExpr.getName();
        if (!"this".equals(receiverVarName)) {
            IType localVariableType = resolveLocalVariableIType(module, receiverVarName, project, sourceUri);
            if (localVariableType != null) {
                return localVariableType;
            }
        }

        return resolveClassNodeIType(varExpr.getType(), module, project, sourceUri);
    }

    private IType resolveLocalVariableIType(ModuleNode module, String varName, IJavaProject project, String sourceUri) {
        ClassNode varType = resolveLocalVariableClassNode(module, varName);
        return resolveClassNodeIType(varType, module, project, sourceUri);
    }

    private IType resolveClassNodeIType(ClassNode classNode, ModuleNode module, IJavaProject project,
            String sourceUri) {
        return isUnhelpfulClassNode(classNode) ? null : resolveClassNodeToIType(classNode, module, project, sourceUri);
    }

    private IType resolveMethodCallReturnIType(MethodCallExpression methodCall, ModuleNode module, IJavaProject project) {
        return resolveMethodCallReturnIType(methodCall, module, project, null);
    }

    private IType resolveMethodCallReturnIType(MethodCallExpression methodCall, ModuleNode module, IJavaProject project,
            String sourceUri) {
        String methodName = methodCall.getMethodAsString();
        if (methodName == null || methodName.isBlank()) {
            return null;
        }

        try {
            IType receiverType = resolveObjectExpressionIType(methodCall.getObjectExpression(), module, project, sourceUri);
            if (receiverType == null) {
                ClassNode receiverClassNode = resolveObjectExpressionType(methodCall.getObjectExpression(), module);
                if (!isUnhelpfulClassNode(receiverClassNode)) {
                    receiverType = resolveClassNodeToIType(receiverClassNode, module, project, sourceUri);
                }
            }
            if (receiverType == null) {
                return null;
            }

            IMethod method = findMethodByNameInHierarchy(receiverType, methodName);
            if (method == null) {
                return null;
            }

            IType contextType = method.getDeclaringType() != null ? method.getDeclaringType() : receiverType;
            return resolveMemberSignatureType(method.getReturnType(), contextType);
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveMethodCallReturnIType failed", e);
            return null;
        }
    }

    /**
     * Walk the AST to find a MethodCallExpression at the given offset with the given method name.
     */
    private MethodCallExpression findMethodCallAtOffset(ModuleNode module, int offset,
                                                         String methodName, String source) {
        int targetLine = offsetToLine(source, offset);
        int targetCol = offsetToColumn(source, offset);

        final MethodCallExpression[] result = new MethodCallExpression[1];

        ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (result[0] != null) {
                    return;
                }
                if (matchesMethodCallAtPosition(call, methodName, targetLine, targetCol)) {
                    result[0] = call;
                    return;
                }
                super.visitMethodCallExpression(call);
            }
        };

        visitClassMethodCalls(module, visitor, result);
        if (result[0] == null) {
            visitScriptMethodCalls(module, visitor, result);
        }

        return result[0];
    }

    private boolean matchesMethodCallAtPosition(MethodCallExpression call, String methodName, int targetLine,
            int targetCol) {
        if (!methodName.equals(call.getMethodAsString())) {
            return false;
        }

        Expression methodExpr = call.getMethod();
        int methodLine = methodExpr.getLineNumber();
        int methodCol = methodExpr.getColumnNumber();
        int lastMethodCol = methodExpr.getLastColumnNumber();
        return methodLine == targetLine && targetCol >= methodCol && targetCol <= lastMethodCol;
    }

    private void visitClassMethodCalls(ModuleNode module, ClassCodeVisitorSupport visitor,
            MethodCallExpression[] result) {
        for (ClassNode classNode : module.getClasses()) {
            if (result[0] != null) {
                break;
            }
            visitor.visitClass(classNode);
        }
    }

    private void visitScriptMethodCalls(ModuleNode module, ClassCodeVisitorSupport visitor,
            MethodCallExpression[] result) {
        BlockStatement stmtBlock = module.getStatementBlock();
        if (stmtBlock == null) {
            return;
        }
        for (Statement stmt : stmtBlock.getStatements()) {
            if (result[0] != null) {
                break;
            }
            stmt.visit(visitor);
        }
    }

    /**
     * Resolve an expression name (variable, type, or constructor call receiver) to its JDT IType.
     */
    private IType resolveExpressionType(ModuleNode module, IJavaProject project, String name) {
        try {
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                IType type = resolveClassNameToType(module, project, name);
                if (type != null) {
                    return type;
                }
            }

            ClassNode varType = resolveLocalVariableClassNode(module, name);
            if (!isUnhelpfulClassNode(varType)) {
                return resolveClassNodeToIType(varType, module, project);
            }

            for (ClassNode classNode : module.getClasses()) {
                ClassNode memberType = resolveClassMemberType(classNode, name);
                if (memberType != null) {
                    return resolveClassNodeToIType(memberType, module, project);
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveExpressionType failed for " + name, e);
        }
        return null;
    }

    /**
     * Resolve a class name (simple or imported) to an IType.
     */
    private IType resolveClassNameToType(ModuleNode module, IJavaProject project, String name) {
        try {
            String fqn = resolveFromImports(module, name);
            if (fqn != null) {
                IType type = project.findType(fqn);
                if (type != null) return type;
            }
            // Try AST resolution
            String astFqn = resolveTypeFromAST(module, name);
            if (astFqn != null) {
                IType type = project.findType(astFqn);
                if (type != null) return type;
            }
            // Try auto-import packages
            return resolveTypeByPackages(project, name, DEFAULT_AUTO_PACKAGES);
        } catch (JavaModelException e) {
            return null;
        }
    }

    /**
     * Walk the AST to find a local variable declaration and resolve its initializer type.
     * Handles {@code def x = new Foo()}, {@code def x = new Foo().bar()}, and typed declarations.
     */
    private ClassNode resolveLocalVariableClassNode(ModuleNode module, String varName) {
        for (ClassNode classNode : module.getClasses()) {
            ClassNode classVariableType = resolveClassVariableType(classNode, varName, module);
            if (classVariableType != null) {
                return classVariableType;
            }
        }

        BlockStatement stmtBlock = module.getStatementBlock();
        return stmtBlock == null ? null : resolveLocalVarInBlock(stmtBlock, varName, module);
    }

    private ClassNode resolveClassVariableType(ClassNode classNode, String varName, ModuleNode module) {
        if (classNode.getLineNumber() < 0) {
            return null;
        }

        ClassNode methodVariableType = resolveMethodVariableType(classNode.getMethods(), varName, module);
        return methodVariableType != null
                ? methodVariableType
                : resolveMethodVariableType(classNode.getDeclaredConstructors(), varName, module);
    }

    private ClassNode resolveMethodVariableType(List<? extends MethodNode> methods, String varName, ModuleNode module) {
        for (MethodNode method : methods) {
            ClassNode type = resolveLocalVarInBlock(getMethodBlock(method), varName, module);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private BlockStatement getMethodBlock(MethodNode method) {
        Statement code = method.getCode();
        return (code instanceof BlockStatement block) ? block : null;
    }

    private ClassNode resolveLocalVarInBlock(BlockStatement block, String varName, ModuleNode module) {
        if (block == null) return null;
        for (Statement stmt : block.getStatements()) {
            ClassNode resolved = resolveVarFromStatement(stmt, varName, module);
            if (resolved != null) return resolved;
        }
        return null;
    }

    /**
     * Check a single statement for a variable declaration matching the name,
     * and resolve its type — including chained method calls.
     */
    private ClassNode resolveVarFromStatement(Statement stmt, String varName, ModuleNode module) {
        if (!(stmt instanceof ExpressionStatement exprStmt)) return null;
        if (!(exprStmt.getExpression() instanceof DeclarationExpression decl)) return null;

        Expression left = decl.getLeftExpression();
        if (!(left instanceof VariableExpression varExpr)) return null;
        if (!varName.equals(varExpr.getName())) return null;

        Expression initializer = decl.getRightExpression();
        if (initializer == null) return null;

        // "def x = new Foo()" → use the constructor type
        if (initializer instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }

        // "def x = new Foo().bar()" → resolve chain
        if (initializer instanceof MethodCallExpression methodCall) {
            return resolveMethodCallReturnType(methodCall, module);
        }

        // Typed declaration (e.g., "String x = ...")
        ClassNode originType = varExpr.getOriginType();
        if (originType != null && !JAVA_LANG_OBJECT.equals(originType.getName())) {
            return originType;
        }

        ClassNode initType = initializer.getType();
        if (initType != null && !JAVA_LANG_OBJECT.equals(initType.getName())) {
            return initType;
        }
        return null;
    }

    /**
     * Resolve the return type of a method call expression by looking at the receiver type
     * and finding the method's return type via JDT.
     * Handles: {@code new Foo().bar()}, {@code someVar.bar()}, {@code Foo.staticBar()}.
     */
    private ClassNode resolveMethodCallReturnType(MethodCallExpression methodCall, ModuleNode module) {
        String methodName = methodCall.getMethodAsString();
        if (methodName == null) {
            return null;
        }

        ClassNode receiverClassNode = helpfulClassNode(resolveReceiverClassNode(methodCall.getObjectExpression(), module));
        if (receiverClassNode == null) {
            return null;
        }

        for (MethodNode mn : receiverClassNode.getMethods()) {
            if (methodName.equals(mn.getName())) {
                ClassNode returnType = mn.getReturnType();
                if (!isUnhelpfulClassNode(returnType)) {
                    return returnType;
                }
            }
        }
        return null;
    }

    private ClassNode resolveReceiverClassNode(Expression objectExpr, ModuleNode module) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof MethodCallExpression nestedCall) {
            return resolveMethodCallReturnType(nestedCall, module);
        }
        if (objectExpr instanceof VariableExpression varExpr) {
            return resolveVariableReceiverClassNode(varExpr, module);
        }
        return null;
    }

    private ClassNode resolveVariableReceiverClassNode(VariableExpression varExpr, ModuleNode module) {
        String receiverVarName = varExpr.getName();
        if ("this".equals(receiverVarName)) {
            return null;
        }

        ClassNode receiverClassNode = resolveLocalVariableClassNode(module, receiverVarName);
        return receiverClassNode != null ? receiverClassNode : helpfulClassNode(varExpr.getType());
    }

    /**
     * Find a method by name in the type hierarchy (type + supertypes).
     */
    private IMethod findMethodByNameInHierarchy(IType type, String name) 
            throws JavaModelException {
        IMethod method = findMethodByName(type, name);
        if (method != null) {
            return method;
        }

        ITypeHierarchy hierarchy = TypeHierarchyCache.getSupertypeHierarchy(type);
        if (hierarchy != null) {
            IType[] supers = hierarchy.getAllSupertypes(type);
            for (IType superType : supers) {
                method = findMethodByName(superType, name);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }

    private boolean isUnhelpfulClassNode(ClassNode classNode) {
        return helpfulClassNode(classNode) == null;
    }

    private ClassNode helpfulClassNode(ClassNode classNode) {
        return classNode == null || JAVA_LANG_OBJECT.equals(classNode.getName()) ? null : classNode;
    }

    private record SelectionContext(ModuleNode module, IJavaProject javaProject, String source, String sourceUri,
            String word, int offset, boolean startsLowerCase) {
    }

    /**
     * Resolve the type of a field or property in a ClassNode.
     */
    private ClassNode resolveClassMemberType(ClassNode classNode, String name) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (name.equals(prop.getName())) return prop.getType();
        }
        for (FieldNode field : classNode.getFields()) {
            if (name.equals(field.getName())) return field.getType();
        }
        return null;
    }

    // =========================================================================
    // 3c. Local variable type resolution
    // =========================================================================

    /**
     * Check if the word is a local variable and resolve its type to a JDT IType.
     * This allows codeSelect on a variable name to return the variable's type.
     */
    private IJavaElement resolveLocalVariableType(ModuleNode module, IJavaProject project, String word) {
        return resolveLocalVariableType(module, project, word, null);
    }

    private IJavaElement resolveLocalVariableType(ModuleNode module, IJavaProject project, String word,
            String sourceUri) {
        // Only for lowercase identifiers (variable names)
        if (word.isEmpty() || Character.isUpperCase(word.charAt(0))) {
            return null;
        }

        try {
            ClassNode varType = resolveLocalVariableClassNode(module, word);
            if (varType != null && !JAVA_LANG_OBJECT.equals(varType.getName())) {
                IType jdtType = resolveClassNodeToIType(varType, module, project, sourceUri);
                if (jdtType != null) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] Local variable '" + word
                            + "' resolved to type: " + jdtType.getFullyQualifiedName());
                    return jdtType;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveLocalVariableType failed for " + word, e);
        }
        return null;
    }

    /**
     * Search for a type by simple name, checking default Groovy auto-imports
     * and java.lang.
     */
    private IType searchTypeBySimpleName(IJavaProject project, ModuleNode module, String word) {
        return searchTypeBySimpleName(project, module, word, null);
    }

    private IType searchTypeBySimpleName(IJavaProject project, ModuleNode module, String word, String sourceUri) {
        // Only try if the word starts with uppercase (likely a type name)
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
            return null;
        }

        // Check common auto-import packages
        try {
            IType type = resolveTypeByPackages(project, word, DEFAULT_AUTO_PACKAGES, sourceUri);
            if (type != null) {
                return type;
            }

            // Check star imports from the module
            for (ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType importedType = ScopedTypeLookupSupport.findType(project, pkgName + word, sourceUri);
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
