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

import org.codehaus.groovy.ast.ASTNode;
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
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.model.GroovyCompilationUnit;
import org.codehaus.jdt.groovy.model.ICodeSelectHelper;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.groovy.ls.core.providers.TraitMemberResolver;
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
            String source = null;
            try {
                source = unit.getSource();
            } catch (Exception e) {
                // ignore
            }
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
            String fqnFromAST = resolveTypeFromAST(module, word, offset, source);
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

        } catch (Throwable e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] Failed", e);
        }

        return new IJavaElement[0];
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
    private String resolveTypeFromAST(ModuleNode module, String word, int offset, String source) {
        // Check class declarations themselves (extends/implements)
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;

            // Extends clause
            ClassNode superClass = classNode.getUnresolvedSuperClass();
            if (superClass != null && superClass.getNameWithoutPackage().equals(word)) {
                String name = superClass.getName();
                if (!name.contains(".")) {
                    // Unresolved — try imports first
                    String fqn = resolveFromImports(module, word);
                    if (fqn != null) return fqn;
                    // Try same package
                    String pkg = module.getPackageName();
                    if (pkg != null && !pkg.isEmpty()) {
                        if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                        return pkg + "." + word;
                    }
                }
                return name;
            }

            // Implements
            ClassNode[] interfaces = classNode.getInterfaces();
            if (interfaces != null) {
                for (ClassNode iface : interfaces) {
                    if (iface.getNameWithoutPackage().equals(word)) {
                        String name = iface.getName();
                        if (!name.contains(".")) {
                            // Unresolved — try imports first
                            String fqn = resolveFromImports(module, word);
                            if (fqn != null) return fqn;
                            // Try same package
                            String pkg = module.getPackageName();
                            if (pkg != null && !pkg.isEmpty()) {
                                // Remove trailing dot if present
                                if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                                return pkg + "." + word;
                            }
                        }
                        return name;
                    }
                }
            }

            // Check field types
            for (FieldNode field : classNode.getFields()) {
                ClassNode fieldType = field.getType();
                if (fieldType != null && fieldType.getNameWithoutPackage().equals(word)) {
                    return resolveClassNodeName(module, fieldType, word);
                }
            }

            // Check method return types and parameter types
            for (MethodNode method : classNode.getMethods()) {
                // Return type
                ClassNode returnType = method.getReturnType();
                if (returnType != null && returnType.getNameWithoutPackage().equals(word)) {
                    return resolveClassNodeName(module, returnType, word);
                }

                // Parameters
                for (Parameter param : method.getParameters()) {
                    ClassNode paramType = param.getType();
                    if (paramType != null && paramType.getNameWithoutPackage().equals(word)) {
                        return resolveClassNodeName(module, paramType, word);
                    }
                }
            }

            // Check property types
            for (PropertyNode prop : classNode.getProperties()) {
                ClassNode propType = prop.getType();
                if (propType != null && propType.getNameWithoutPackage().equals(word)) {
                    return resolveClassNodeName(module, propType, word);
                }
            }
        }

        // Use a visitor to find ClassExpressions and ConstructorCallExpressions
        TypeAtOffsetFinder finder = new TypeAtOffsetFinder(offset, word, module);
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            finder.visitClass(classNode);
            if (finder.resolvedFQN != null) {
                return finder.resolvedFQN;
            }
        }

        return null;
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
            for (IType type : types) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect]   type: " + type.getElementName()
                        + " fields=" + type.getFields().length + " methods=" + type.getMethods().length);

                // Check methods
                IMethod[] methods = type.getMethods();
                if (methods != null) {
                    for (IMethod method : methods) {
                        if (method.getElementName().equals(word)) {
                            return method;
                        }
                    }
                }

                // Check fields
                org.eclipse.jdt.core.IField[] fields = type.getFields();
                if (fields != null) {
                    for (org.eclipse.jdt.core.IField field : fields) {
                        if (field.getElementName().equals(word)) {
                            return field;
                        }
                    }
                }
            }

            // Check members inherited from traits/interfaces implemented by the enclosing class
            IJavaElement traitMember = resolveTraitMemberDeclaration(unit, module, types, word, offset, source);
            if (traitMember != null) {
                return traitMember;
            }
        } catch (JavaModelException e) {
            GroovyLanguageServerPlugin.logError("[codeSelect] resolveLocalDeclaration failed", e);
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

            IType traitType = findTypeInUnit(types, ifaceSimple);
            GroovyLanguageServerPlugin.logInfo("[codeSelect] findTypeInUnit(" + ifaceSimple + ") = "
                    + (traitType != null ? traitType.getElementName() : "null"));

            if (traitType == null) {
                // Cross-file: search the full project for the trait type
                try {
                    IJavaProject javaProject = unit.getJavaProject();
                    if (javaProject != null) {
                        String fqn = iface.getName();
                        if (fqn != null && fqn.contains(".")) {
                            traitType = javaProject.findType(fqn);
                        }
                        // Try same package as the owning class
                        if (traitType == null) {
                            String ownerPkg = owner.getPackageName();
                            if (ownerPkg != null && !ownerPkg.isEmpty()) {
                                traitType = javaProject.findType(ownerPkg + "." + ifaceSimple);
                            }
                        }
                        // Try Groovy auto-import packages
                        if (traitType == null) {
                            String[] autoPackages = { "groovy.lang.", "groovy.util.",
                                    "java.lang.", "java.util." };
                            for (String pkg : autoPackages) {
                                traitType = javaProject.findType(pkg + ifaceSimple);
                                if (traitType != null) break;
                            }
                        }
                        // Try explicit imports from the module
                        if (traitType == null && module != null) {
                            for (org.codehaus.groovy.ast.ImportNode imp : module.getImports()) {
                                ClassNode impType = imp.getType();
                                if (impType != null && ifaceSimple.equals(impType.getNameWithoutPackage())) {
                                    traitType = javaProject.findType(impType.getName());
                                    if (traitType != null) break;
                                }
                            }
                        }
                        // Try star imports from the module
                        if (traitType == null && module != null) {
                            for (org.codehaus.groovy.ast.ImportNode starImport : module.getStarImports()) {
                                String pkgName = starImport.getPackageName();
                                if (pkgName != null) {
                                    traitType = javaProject.findType(pkgName + ifaceSimple);
                                    if (traitType != null) break;
                                }
                            }
                        }
                    }
                } catch (JavaModelException e) {
                    // ignore
                }
            }

            if (traitType != null) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait IType: " + traitType.getElementName()
                        + " fields=" + traitType.getFields().length
                        + " methods=" + traitType.getMethods().length);
                // Log field names for debugging
                for (org.eclipse.jdt.core.IField f : traitType.getFields()) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect]   field: " + f.getElementName());
                }
                for (IMethod m : traitType.getMethods()) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect]   method: " + m.getElementName());
                }

                // Direct field/property by name
                org.eclipse.jdt.core.IField traitField = findFieldByName(traitType, word);
                if (traitField != null) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field: " + word);
                    return traitField;
                }

                // Property accessor fallback (Groovy trait property may surface as getX()/setX())
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

                // JDT IType didn't expose the member — return the IType itself
                // so callers can at least navigate to the trait
                GroovyLanguageServerPlugin.logInfo("[codeSelect] JDT IType has no member '" + word
                        + "', checking AST directly...");
            }

            // AST-based fallback: check the Groovy AST directly for the trait's members
            // This is needed because at CONVERSION phase, JDT may not expose trait properties
            ClassNode resolvedTraitNode = findTraitClassNodeInModule(module, ifaceSimple);
            if (resolvedTraitNode != null) {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] AST trait node: " + resolvedTraitNode.getName()
                        + " fields=" + resolvedTraitNode.getFields().size()
                        + " props=" + resolvedTraitNode.getProperties().size()
                        + " methods=" + resolvedTraitNode.getMethods().size());

                // Check properties in the AST
                for (PropertyNode prop : resolvedTraitNode.getProperties()) {
                    if (word.equals(prop.getName())) {
                        GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait property in AST: " + word);
                        if (traitType != null) {
                            org.eclipse.jdt.core.IField f = findFieldByName(traitType, word);
                            if (f != null) return f;
                            return traitType;
                        }
                        IType unitType = findTypeInUnit(types, ifaceSimple);
                        if (unitType != null) return unitType;
                        break;
                    }
                }

                // Check fields in the AST
                for (FieldNode field : resolvedTraitNode.getFields()) {
                    if (word.equals(field.getName())) {
                        GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field in AST: " + word);
                        if (traitType != null) return traitType;
                        IType unitType = findTypeInUnit(types, ifaceSimple);
                        if (unitType != null) return unitType;
                        break;
                    }
                }

                // Check methods in the AST
                for (MethodNode method : resolvedTraitNode.getMethods()) {
                    if (word.equals(method.getName())) {
                        GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait method in AST: " + word);
                        if (traitType != null) return traitType;
                        IType unitType = findTypeInUnit(types, ifaceSimple);
                        if (unitType != null) return unitType;
                        break;
                    }
                }

                // Check $Trait$FieldHelper — at CONVERSION phase, trait fields
                // are moved to this inner class with mangled names
                ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(resolvedTraitNode, module);
                if (helperNode != null) {
                    GroovyLanguageServerPlugin.logInfo("[codeSelect] FieldHelper node: " + helperNode.getName()
                            + " fields=" + helperNode.getFields().size()
                            + " methods=" + helperNode.getMethods().size());
                    for (FieldNode hField : helperNode.getFields()) {
                        GroovyLanguageServerPlugin.logInfo("[codeSelect]   helper field: " + hField.getName());
                    }
                    for (FieldNode hField : helperNode.getFields()) {
                        if (TraitMemberResolver.isTraitFieldMatch(hField.getName(), word)) {
                            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait field in FieldHelper: "
                                    + hField.getName() + " matches '" + word + "'");
                            // Try to resolve the field's declared type (e.g., ApplicationContext)
                            // so that dot-completion can list its members
                            ClassNode fieldTypeNode = hField.getType();
                            if (fieldTypeNode != null) {
                                GroovyLanguageServerPlugin.logInfo("[codeSelect] FieldHelper field type: " + fieldTypeNode.getName());
                                IType fieldIType = resolveClassNodeToIType(fieldTypeNode, module, unit.getJavaProject());
                                if (fieldIType != null) {
                                    GroovyLanguageServerPlugin.logInfo("[codeSelect] Resolved field type IType: " + fieldIType.getFullyQualifiedName());
                                    return fieldIType;
                                }
                            }
                            // Fallback: return the trait type for navigation
                            if (traitType != null) return traitType;
                            IType unitType = findTypeInUnit(types, ifaceSimple);
                            if (unitType != null) return unitType;
                            break;
                        }
                    }
                    // Also check FieldHelper methods (getter/setter for the field)
                    String cap = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                    for (MethodNode hMethod : helperNode.getMethods()) {
                        String mName = hMethod.getName();
                        if (mName.equals("get" + cap) || mName.equals("set" + cap) || mName.equals("is" + cap)) {
                            GroovyLanguageServerPlugin.logInfo("[codeSelect] Found trait accessor in FieldHelper: " + mName);
                            // Try to resolve the accessor's return type
                            if (mName.startsWith("get") || mName.startsWith("is")) {
                                ClassNode retType = hMethod.getReturnType();
                                if (retType != null) {
                                    IType retIType = resolveClassNodeToIType(retType, module, unit.getJavaProject());
                                    if (retIType != null) return retIType;
                                }
                            }
                            if (traitType != null) return traitType;
                            IType unitType = findTypeInUnit(types, ifaceSimple);
                            if (unitType != null) return unitType;
                            break;
                        }
                    }
                }
            } else {
                GroovyLanguageServerPlugin.logInfo("[codeSelect] Could not find trait '" + ifaceSimple + "' in AST module");
            }
        }

        return null;
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
        if (typeNode == null || project == null) return null;
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) return null;

            // Try FQN directly
            if (typeName.contains(".")) {
                IType t = project.findType(typeName);
                if (t != null) return t;
            }

            // Try imports
            if (module != null) {
                for (ImportNode imp : module.getImports()) {
                    ClassNode impType = imp.getType();
                    if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                        IType t = project.findType(impType.getName());
                        if (t != null) return t;
                    }
                }
                // Star imports
                for (ImportNode starImport : module.getStarImports()) {
                    String pkgName = starImport.getPackageName();
                    if (pkgName != null) {
                        IType t = project.findType(pkgName + typeName);
                        if (t != null) return t;
                    }
                }
            }

            // Try same package
            if (module != null) {
                String pkg = module.getPackageName();
                if (pkg != null && !pkg.isEmpty()) {
                    if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                    IType t = project.findType(pkg + "." + typeName);
                    if (t != null) return t;
                }
            }

            // Try auto-import packages
            String[] autoPackages = { "java.lang.", "java.util.", "java.io.", "java.net.",
                    "groovy.lang.", "groovy.util.", "java.math." };
            for (String pkg : autoPackages) {
                IType t = project.findType(pkg + typeName);
                if (t != null) return t;
            }
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
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
            if (start > 0 && end >= start && line1Based >= start && line1Based <= end) {
                if (best == null || start >= best.getLineNumber()) {
                    best = classNode;
                }
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
        String[] autoPackages = {
            "java.lang.",
            "java.util.",
            "java.io.",
            "java.net.",
            "groovy.lang.",
            "groovy.util.",
            "java.math.",
        };

        try {
            for (String pkg : autoPackages) {
                IType type = project.findType(pkg + word);
                if (type != null) {
                    return type;
                }
            }

            // Check star imports from the module
            for (ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType type = project.findType(pkgName + word);
                    if (type != null) {
                        return type;
                    }
                }
            }
        } catch (JavaModelException e) {
            // ignore
        }

        return null;
    }

    /**
     * Extract the identifier word at the given offset.
     */
    private String extractWordAt(String content, int offset) {
        if (offset < 0 || offset >= content.length()) {
            // Check if we're right at the end of an identifier
            if (offset > 0 && offset <= content.length()
                    && Character.isJavaIdentifierPart(content.charAt(offset - 1))) {
                offset = offset - 1;
            } else {
                return null;
            }
        }

        // If we're not on an identifier char, try one position back
        if (!Character.isJavaIdentifierPart(content.charAt(offset))) {
            if (offset > 0 && Character.isJavaIdentifierPart(content.charAt(offset - 1))) {
                offset = offset - 1;
            } else {
                return null;
            }
        }

        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        int end = offset;
        while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
            end++;
        }
        if (start == end) return null;
        return content.substring(start, end);
    }

    /**
     * AST visitor that finds ClassExpression / ConstructorCallExpression nodes
     * at a given offset and resolves them to FQNs.
     */
    private static class TypeAtOffsetFinder extends ClassCodeVisitorSupport {
        private final int targetOffset;
        private final String targetWord;
        private final ModuleNode module;
        String resolvedFQN;

        TypeAtOffsetFinder(int offset, String word, ModuleNode module) {
            this.targetOffset = offset;
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
