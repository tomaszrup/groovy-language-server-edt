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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.Arrays;
import java.util.Collections;

/**
 * Detects unused imports in a Groovy source file by walking the AST
 * and tracking which imported types are actually referenced.
 * <p>
 * This works at the AST CONVERSION level (no classpath needed). It checks:
 * <ul>
 *   <li>Type references in variable declarations, parameters, return types</li>
 *   <li>Extends / implements clauses</li>
 *   <li>Annotations</li>
 *   <li>Cast expressions</li>
 *   <li>Constructor calls (new Foo())</li>
 *   <li>Class expressions (Foo.class)</li>
 *   <li>Static method call targets</li>
 *   <li>Generics type arguments</li>
 *   <li>Catch exception types</li>
 * </ul>
 * <p>
 * Imports of types from {@code java.lang} and {@code groovy.lang} are
 * always considered "used" since Groovy auto-imports them; reporting them
 * as unused would be noise (but they are technically redundant — a future
 * enhancement could flag them differently).
 */
public class UnusedImportDetector {

    /**
     * Packages that Groovy auto-imports — imports from these are always
     * considered used (or rather, redundant but not "unused" in the
     * traditional sense). We don't flag them to reduce noise.
     */
    private static final Set<String> AUTO_IMPORTED_PACKAGES = new HashSet<>(Arrays.asList(
            "java.lang",
            "java.util",
            "java.io",
            "java.net",
            "groovy.lang",
            "groovy.util",
            "java.math.BigDecimal",
            "java.math.BigInteger"
    ));

    /**
     * Information about an import and whether it's used.
     */
    public static class ImportInfo {
        public final ImportNode node;
        public final String simpleName;
        public final String fullName;
        public final int line;       // 0-based
        public final int column;     // 0-based
        public boolean used;

        ImportInfo(ImportNode node) {
            this.node = node;
            this.line = node.getLineNumber() - 1;
            this.column = node.getColumnNumber() - 1;
            this.used = false;

            if (node.getType() != null) {
                this.simpleName = node.getType().getNameWithoutPackage();
                this.fullName = node.getType().getName();
            } else {
                this.simpleName = node.getFieldName();
                this.fullName = node.getClassName() + "." + node.getFieldName();
            }
        }
    }

    /**
     * Analyze a module's imports and return diagnostics for unused ones.
     *
     * @param ast     the Groovy module AST
     * @param content the source text (to compute accurate ranges)
     * @return list of warning diagnostics for unused imports
     */
    public static List<Diagnostic> detectUnusedImports(ModuleNode ast, String content) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (ast == null) return diagnostics;

        // 1. Collect all explicit imports (skip star imports — can't easily determine usage)
        List<ImportInfo> imports = new ArrayList<>();
        for (ImportNode imp : ast.getImports()) {
            if (imp.getLineNumber() < 1) continue;
            if (imp.getType() == null) continue;
            // Skip auto-imported packages
            String pkg = imp.getType().getPackageName();
            if (pkg != null && AUTO_IMPORTED_PACKAGES.contains(pkg)) continue;
            imports.add(new ImportInfo(imp));
        }

        // Also check static imports
        for (ImportNode imp : ast.getStaticImports().values()) {
            if (imp.getLineNumber() < 1) continue;
            imports.add(new ImportInfo(imp));
        }

        if (imports.isEmpty()) return diagnostics;

        // 2. Collect all type names referenced in the AST
        Set<String> referencedSimpleNames = new HashSet<>();
        Set<String> referencedFullNames = new HashSet<>();

        TypeReferenceCollector collector = new TypeReferenceCollector(referencedSimpleNames, referencedFullNames);
        collector.visitModule(ast);

        // 3. Check each import against collected references
        for (ImportInfo info : imports) {
            if (referencedSimpleNames.contains(info.simpleName)
                    || referencedFullNames.contains(info.fullName)) {
                info.used = true;
            }
        }

        // 4. Build diagnostics for unused imports
        String[] lines = content.split("\n", -1);
        for (ImportInfo info : imports) {
            if (!info.used) {
                int line = info.line;
                if (line < 0 || line >= lines.length) continue;

                String lineText = lines[line];
                int startCol = 0;
                int endCol = lineText.length();

                Diagnostic diag = new Diagnostic();
                diag.setRange(new Range(
                        new Position(line, startCol),
                        new Position(line, endCol)));
                diag.setSeverity(DiagnosticSeverity.Warning);
                diag.setMessage("The import '" + info.fullName + "' is never used");
                diag.setSource("groovy");
                diag.setCode(CodeActionProvider.DIAG_CODE_UNUSED_IMPORT);
                // Mark as unnecessary (VS Code will show it as faded)
                diag.setTags(Collections.singletonList(DiagnosticTag.Unnecessary));

                diagnostics.add(diag);
            }
        }

        return diagnostics;
    }

    /**
     * Find the set of import line texts that are unused.
     * Used by "Organize imports" code action.
     */
    public static Set<String> findUnusedImportLines(ModuleNode ast, String content) {
        Set<String> unusedLines = new LinkedHashSet<>();
        if (ast == null) return unusedLines;

        String[] lines = content.split("\n", -1);
        List<Diagnostic> diags = detectUnusedImports(ast, content);
        for (Diagnostic d : diags) {
            int line = d.getRange().getStart().getLine();
            if (line >= 0 && line < lines.length) {
                unusedLines.add(lines[line].trim());
            }
        }
        return unusedLines;
    }

    // ================================================================
    // AST visitor that collects all type name references
    // ================================================================

    private static class TypeReferenceCollector extends ClassCodeVisitorSupport {
        private final Set<String> simpleNames;
        private final Set<String> fullNames;
        private SourceUnit sourceUnit;

        TypeReferenceCollector(Set<String> simpleNames, Set<String> fullNames) {
            this.simpleNames = simpleNames;
            this.fullNames = fullNames;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        void visitModule(ModuleNode module) {
            this.sourceUnit = module.getContext();

            for (ClassNode classNode : module.getClasses()) {
                visitClass(classNode);
            }

            BlockStatement block = module.getStatementBlock();
            if (block != null) {
                block.visit(this);
            }
        }

        @Override
        public void visitClass(ClassNode node) {
            // Superclass
            ClassNode superClass = node.getUnresolvedSuperClass();
            if (superClass != null) {
                collectTypeRef(superClass);
            }

            // Interfaces
            ClassNode[] interfaces = node.getInterfaces();
            if (interfaces != null) {
                for (ClassNode iface : interfaces) {
                    collectTypeRef(iface);
                }
            }

            // Generics on class
            collectGenerics(node.getGenericsTypes());

            // Annotations
            collectAnnotations(node.getAnnotations());

            // Properties
            for (PropertyNode prop : node.getProperties()) {
                collectTypeRef(prop.getType());
                collectAnnotations(prop.getAnnotations());
                if (prop.getField() != null) {
                    collectAnnotations(prop.getField().getAnnotations());
                }
            }

            // Fields
            for (FieldNode field : node.getFields()) {
                collectTypeRef(field.getType());
                collectAnnotations(field.getAnnotations());
                if (field.getLineNumber() >= 1 && field.getInitialValueExpression() != null) {
                    field.getInitialValueExpression().visit(this);
                }
            }

            // Methods
            for (MethodNode method : node.getMethods()) {
                if (method.getLineNumber() < 1) continue; // skip synthetic
                visitMethod(method);
            }

            // Constructors
            for (var ctor : node.getDeclaredConstructors()) {
                visitMethod(ctor);
            }

            // Inner classes
            java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter = node.getInnerClasses();
            while (innerIter.hasNext()) {
                visitClass(innerIter.next());
            }
        }

        @Override
        public void visitMethod(MethodNode node) {
            // Return type
            collectTypeRef(node.getReturnType());

            // Annotations
            collectAnnotations(node.getAnnotations());

            // Parameters
            for (Parameter param : node.getParameters()) {
                collectTypeRef(param.getType());
                collectAnnotations(param.getAnnotations());
                collectGenerics(param.getType().getGenericsTypes());
            }

            // Exceptions
            for (ClassNode ex : node.getExceptions()) {
                collectTypeRef(ex);
            }

            // Generics on method
            collectGenerics(node.getGenericsTypes());

            // Method body
            if (node.getCode() != null) {
                node.getCode().visit(this);
            }
        }

        // ---- Expression visitors ----

        @Override
        public void visitVariableExpression(VariableExpression expr) {
            collectTypeRef(expr.getType());
            collectTypeRef(expr.getOriginType());
            super.visitVariableExpression(expr);
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expr) {
            collectTypeRef(expr.getLeftExpression().getType());
            super.visitDeclarationExpression(expr);
        }

        @Override
        public void visitClassExpression(ClassExpression expr) {
            collectTypeRef(expr.getType());
            super.visitClassExpression(expr);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression expr) {
            collectTypeRef(expr.getType());
            super.visitConstructorCallExpression(expr);
        }

        @Override
        public void visitCastExpression(CastExpression expr) {
            collectTypeRef(expr.getType());
            super.visitCastExpression(expr);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression expr) {
            if (expr.getObjectExpression() instanceof ClassExpression) {
                collectTypeRef(((ClassExpression) expr.getObjectExpression()).getType());
            }
            super.visitMethodCallExpression(expr);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression expr) {
            collectTypeRef(expr.getOwnerType());
            // Also record the method name for static imports
            simpleNames.add(expr.getMethod());
            super.visitStaticMethodCallExpression(expr);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expr) {
            if (expr.getObjectExpression() instanceof ClassExpression) {
                collectTypeRef(((ClassExpression) expr.getObjectExpression()).getType());
            }
            // Property name — might match a static import field name
            if (expr.getProperty() instanceof ConstantExpression) {
                simpleNames.add(((ConstantExpression) expr.getProperty()).getText());
            }
            super.visitPropertyExpression(expr);
        }

        @Override
        public void visitClosureExpression(ClosureExpression expr) {
            if (expr.getParameters() != null) {
                for (Parameter param : expr.getParameters()) {
                    collectTypeRef(param.getType());
                }
            }
            super.visitClosureExpression(expr);
        }

        @Override
        public void visitForLoop(ForStatement stmt) {
            collectTypeRef(stmt.getVariableType());
            super.visitForLoop(stmt);
        }

        @Override
        public void visitTryCatchFinally(TryCatchStatement stmt) {
            for (CatchStatement catchStmt : stmt.getCatchStatements()) {
                collectTypeRef(catchStmt.getExceptionType());
            }
            super.visitTryCatchFinally(stmt);
        }

        // ---- Helpers ----

        private void collectTypeRef(ClassNode type) {
            if (type == null) return;

            String name = type.getName();
            if (name == null || name.isEmpty()) return;

            // Skip primitive types and java.lang.Object (used as default)
            if (type.isPrimaryClassNode() || "java.lang.Object".equals(name)) {
                // Still record it — we may need it for imports
            }

            // Record both simple and full names
            fullNames.add(name);
            simpleNames.add(type.getNameWithoutPackage());

            // Also handle arrays
            ClassNode componentType = type.getComponentType();
            if (componentType != null) {
                collectTypeRef(componentType);
            }

            // Generics
            collectGenerics(type.getGenericsTypes());
        }

        private void collectGenerics(GenericsType[] generics) {
            if (generics == null) return;
            for (GenericsType gt : generics) {
                collectTypeRef(gt.getType());
                ClassNode[] bounds = gt.getUpperBounds();
                if (bounds != null) {
                    for (ClassNode bound : bounds) {
                        collectTypeRef(bound);
                    }
                }
                ClassNode lower = gt.getLowerBound();
                if (lower != null) {
                    collectTypeRef(lower);
                }
            }
        }

        private void collectAnnotations(List<AnnotationNode> annotations) {
            if (annotations == null) return;
            for (AnnotationNode ann : annotations) {
                collectTypeRef(ann.getClassNode());
                // Also visit annotation member values
                if (ann.getMembers() != null) {
                    for (Expression value : ann.getMembers().values()) {
                        value.visit(this);
                    }
                }
            }
        }
    }
}
