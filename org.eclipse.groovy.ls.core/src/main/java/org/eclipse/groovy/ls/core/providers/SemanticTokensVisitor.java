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
import java.util.Comparator;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
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
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Walks the Groovy AST and collects semantic tokens.
 * <p>
 * Extends {@link ClassCodeVisitorSupport} to visit all node types in the
 * Groovy AST tree. For each significant node, a {@link RawToken} is recorded
 * with its position, token type, and modifier bitmask. After the walk, the
 * tokens are sorted by position and delta-encoded into the integer array
 * format required by the LSP Semantic Tokens protocol.
 * <p>
 * Token types and modifiers correspond to the indices defined in
 * {@link SemanticTokensProvider#TOKEN_TYPES} and
 * {@link SemanticTokensProvider#TOKEN_MODIFIERS}.
 */
public class SemanticTokensVisitor extends ClassCodeVisitorSupport {

    /**
     * Raw token before delta encoding. Stores absolute position.
     */
    private static class RawToken {
        final int line;       // 0-based
        final int column;     // 0-based
        final int length;
        final int tokenType;
        final int modifiers;

        RawToken(int line, int column, int length, int tokenType, int modifiers) {
            this.line = line;
            this.column = column;
            this.length = length;
            this.tokenType = tokenType;
            this.modifiers = modifiers;
        }
    }

    private final List<RawToken> tokens = new ArrayList<>();
    private final String source;
    private final Range range; // optional restriction range (null = full document)
    private SourceUnit sourceUnit;

    /**
     * @param source the full document source text
     * @param range  optional LSP range to restrict tokens to (null = full)
     */
    public SemanticTokensVisitor(String source, Range range) {
        this.source = source;
        this.range = range;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    // ================================================================
    // Entry point
    // ================================================================

    /**
     * Visit the entire module (compilation unit). This is the main entry point.
     */
    public void visitModule(ModuleNode module) {
        this.sourceUnit = module.getContext();

        // 1. Visit package declaration
        visitPackage(module);

        // 2. Visit imports
        collectImportTokens(module);

        // 3. Visit all classes (including inner classes)
        for (ClassNode classNode : module.getClasses()) {
            // Skip synthetic script class that wraps top-level code
            if (classNode.isScript() && classNode.getLineNumber() < 1) {
                // Script body — visit the run() method statements directly
                MethodNode runMethod = classNode.getMethod("run", Parameter.EMPTY_ARRAY);
                if (runMethod != null && runMethod.getCode() != null) {
                    runMethod.getCode().visit(this);
                }
                // Also visit script-level methods
                for (MethodNode method : classNode.getMethods()) {
                    if (!"run".equals(method.getName()) && !"main".equals(method.getName())
                            && method.getLineNumber() > 0) {
                        visitMethod(method);
                    }
                }
                continue;
            }
            visitClass(classNode);
        }

        // 4. Visit script-level statements (code outside classes)
        BlockStatement statementBlock = module.getStatementBlock();
        if (statementBlock != null) {
            statementBlock.visit(this);
        }
    }

    // ================================================================
    // Package & Import visitors
    // ================================================================

    private void visitPackage(ModuleNode module) {
        if (module.getPackageName() != null && module.getPackage() != null) {
            ASTNode pkg = module.getPackage();
            if (pkg.getLineNumber() > 0) {
                // Emit the package name as a namespace token
                String packageName = module.getPackageName();
                // Remove trailing dot
                if (packageName.endsWith(".")) {
                    packageName = packageName.substring(0, packageName.length() - 1);
                }
                // The package keyword takes 7 chars + space, so the name starts after "package "
                // We need to find the actual column of the package name in the source
                int line = pkg.getLineNumber() - 1; // 0-based
                int col = pkg.getColumnNumber() - 1; // 0-based
                // Emit the entire package path as namespace
                addToken(line, col, packageName.length(),
                        SemanticTokensProvider.TYPE_NAMESPACE, 0);
            }
        }
    }

    private void collectImportTokens(ModuleNode module) {
        // Star imports
        for (ImportNode imp : module.getStarImports()) {
            visitSingleImport(imp);
        }
        // Regular imports
        for (ImportNode imp : module.getImports()) {
            visitSingleImport(imp);
        }
        // Static imports
        for (ImportNode imp : module.getStaticImports().values()) {
            visitSingleImport(imp);
        }
        // Static star imports
        for (ImportNode imp : module.getStaticStarImports().values()) {
            visitSingleImport(imp);
        }
    }

    private void visitSingleImport(ImportNode imp) {
        if (imp.getLineNumber() < 1) return;

        ClassNode importedType = imp.getType();
        if (importedType != null && importedType.getLineNumber() > 0) {
            String className = importedType.getNameWithoutPackage();
            String fullName = importedType.getName(); // e.g. "spock.lang.Specification"
            int line = importedType.getLineNumber() - 1;
            int col = importedType.getColumnNumber() - 1;

            if (col >= 0 && className.length() > 0) {
                // Emit namespace tokens for each package segment in the qualified path.
                // For "spock.lang.Specification", emit "spock" and "lang" as namespace,
                // and "Specification" as type — matching Java's semantic token behavior
                // where the whole qualified path appears in the same color.
                String packageName = fullName.contains(".")
                        ? fullName.substring(0, fullName.lastIndexOf('.'))
                        : null;
                if (packageName != null && !packageName.isEmpty()) {
                    // Walk through the package segments on the source line
                    int cursor = col;
                    String[] segments = packageName.split("\\.");
                    for (String segment : segments) {
                        int segOffset = findNameInLine(line, cursor, segment);
                        if (segOffset >= 0) {
                            addToken(line, segOffset, segment.length(),
                                    SemanticTokensProvider.TYPE_NAMESPACE, 0);
                            cursor = segOffset + segment.length() + 1; // +1 for the dot
                        }
                    }
                }

                // Emit the class name as a type token
                int nameOffset = findNameInLine(line, col, className);
                if (nameOffset >= 0) {
                    addToken(line, nameOffset, className.length(),
                            SemanticTokensProvider.TYPE_TYPE, 0);
                }
            }
        }
    }

    // ================================================================
    // Class / Interface / Enum / Trait
    // ================================================================

    @Override
    public void visitClass(ClassNode node) {
        if (node.getLineNumber() < 1) return;

        // Determine the token type
        int tokenType;
        if (node.isEnum()) {
            tokenType = SemanticTokensProvider.TYPE_ENUM;
        } else if (GroovyTypeKindHelper.isTrait(node)) {
            tokenType = SemanticTokensProvider.TYPE_STRUCT; // trait → struct
        } else if (node.isInterface()) {
            tokenType = SemanticTokensProvider.TYPE_INTERFACE;
        } else {
            tokenType = SemanticTokensProvider.TYPE_CLASS;
        }

        // Modifiers
        int modifiers = SemanticTokensProvider.MOD_DECLARATION;
        if (java.lang.reflect.Modifier.isAbstract(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_ABSTRACT;
        }
        if (isDeprecated(node)) {
            modifiers |= SemanticTokensProvider.MOD_DEPRECATED;
        }

        // Emit the class name token
        String name = node.getNameWithoutPackage();
        // Find the name position — it follows the keyword (class/interface/enum/trait)
        int nameLine = node.getLineNumber() - 1;
        int nameCol = node.getColumnNumber() - 1;
        // Try to locate the actual name in the source line.
        // The Groovy AST may report the annotation line for annotated classes,
        // so if we don't find the name on the reported line, search nearby lines.
        int nameOffset = findNameInLine(nameLine, nameCol, name);
        if (nameOffset < 0) {
            for (int tryLine = nameLine + 1; tryLine <= nameLine + 10; tryLine++) {
                nameOffset = findNameInLine(tryLine, 0, name);
                if (nameOffset >= 0) {
                    nameLine = tryLine;
                    break;
                }
            }
        }
        if (nameOffset >= 0) {
            addToken(nameLine, nameOffset, name.length(), tokenType, modifiers);

            // Emit the "trait" keyword as a typeKeyword semantic token so it
            // gets the storage.type colour (blue) — TextMate does not reliably
            // colour it in all contexts.
            if (GroovyTypeKindHelper.isTrait(node)) {
                int kwOffset = findNameInLine(nameLine, 0, "trait");
                if (kwOffset >= 0 && kwOffset < nameOffset) {
                    addToken(nameLine, kwOffset, "trait".length(),
                            SemanticTokensProvider.TYPE_TYPE_KEYWORD, 0);
                }
            }
        }

        // Visit annotations on the class
        collectAnnotationTokens(node);

        // Visit generics
        visitGenerics(node.getGenericsTypes());

        // Visit superclass reference
        ClassNode superClass = node.getUnresolvedSuperClass();
        if (superClass != null && superClass.getLineNumber() > 0
                && !"java.lang.Object".equals(superClass.getName())) {
            emitTypeReference(superClass);
        }

        // Visit implemented interfaces
        ClassNode[] interfaces = node.getInterfaces();
        if (interfaces != null) {
            for (ClassNode iface : interfaces) {
                if (iface.getLineNumber() > 0) {
                    emitTypeReference(iface);
                }
            }
        }

        // Recurse into class body
        super.visitClass(node);
    }

    // ================================================================
    // Methods & Constructors
    // ================================================================

    @Override
    public void visitMethod(MethodNode node) {
        if (node.getLineNumber() < 1) return;
        // Skip synthetic methods (generated by compiler)
        if (node.isSynthetic()) return;

        // Annotations
        collectAnnotationTokens(node);

        // Return type
        ClassNode returnType = node.getReturnType();
        if (returnType != null && returnType.getLineNumber() > 0
                && !"void".equals(returnType.getName())
                && !"java.lang.Object".equals(returnType.getName())) {
            emitTypeReference(returnType);
        }

        // Method name
        String name = node.getName();
        int modifiers = SemanticTokensProvider.MOD_DECLARATION;
        if (java.lang.reflect.Modifier.isStatic(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_STATIC;
        }
        if (java.lang.reflect.Modifier.isAbstract(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_ABSTRACT;
        }
        if (isDeprecated(node)) {
            modifiers |= SemanticTokensProvider.MOD_DEPRECATED;
        }

        int nameLine = node.getLineNumber() - 1;
        int nameCol = node.getColumnNumber() - 1;
        int nameOffset = findNameInLine(nameLine, nameCol, name);
        // AST may report annotation line; search nearby lines
        if (nameOffset < 0) {
            for (int tryLine = nameLine + 1; tryLine <= nameLine + 5; tryLine++) {
                nameOffset = findNameInLine(tryLine, 0, name);
                if (nameOffset >= 0) {
                    nameLine = tryLine;
                    break;
                }
            }
        }
        if (nameOffset >= 0 && !name.equals("<init>") && !name.equals("<clinit>")
                && isValidIdentifier(name)) {
            addToken(nameLine, nameOffset, name.length(),
                    SemanticTokensProvider.TYPE_METHOD, modifiers);
        }

        // Parameters
        Parameter[] params = node.getParameters();
        if (params != null) {
            for (Parameter param : params) {
                visitParameter(param);
            }
        }

        // Visit generics on method
        visitGenerics(node.getGenericsTypes());

        // Exceptions
        ClassNode[] exceptions = node.getExceptions();
        if (exceptions != null) {
            for (ClassNode ex : exceptions) {
                if (ex.getLineNumber() > 0) {
                    emitTypeReference(ex);
                }
            }
        }

        // Visit body
        super.visitMethod(node);
    }

    private void visitParameter(Parameter param) {
        if (param.getLineNumber() < 1) return;

        // Parameter type
        ClassNode paramType = param.getType();
        if (paramType != null && paramType.getLineNumber() > 0
                && !"java.lang.Object".equals(paramType.getName())) {
            emitTypeReference(paramType);
        }

        // Parameter name
        String name = param.getName();
        int line = param.getLineNumber() - 1;
        int col = param.getColumnNumber() - 1;
        int nameOffset = findNameInLine(line, col, name);
        if (nameOffset >= 0) {
            addToken(line, nameOffset, name.length(),
                    SemanticTokensProvider.TYPE_PARAMETER,
                    SemanticTokensProvider.MOD_DECLARATION);
        }

        // Visit annotations on the parameter
        collectAnnotationTokens(param);
    }

    // ================================================================
    // Fields & Properties
    // ================================================================

    @Override
    public void visitField(FieldNode node) {
        if (node.getLineNumber() < 1) return;
        if (node.isSynthetic()) return;

        collectAnnotationTokens(node);

        // Field type
        ClassNode fieldType = node.getType();
        if (fieldType != null && fieldType.getLineNumber() > 0) {
            emitTypeReference(fieldType);
        }

        // Field name
        int tokenType = SemanticTokensProvider.TYPE_PROPERTY;
        int modifiers = SemanticTokensProvider.MOD_DECLARATION;

        if (node.isEnum()) {
            tokenType = SemanticTokensProvider.TYPE_ENUM_MEMBER;
        }
        if (java.lang.reflect.Modifier.isStatic(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_STATIC;
        }
        if (java.lang.reflect.Modifier.isFinal(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_READONLY;
        }
        if (isDeprecated(node)) {
            modifiers |= SemanticTokensProvider.MOD_DEPRECATED;
        }

        String name = node.getName();
        int line = node.getLineNumber() - 1;
        int col = node.getColumnNumber() - 1;
        int nameOffset = findNameInLine(line, col, name);
        // AST may report annotation line; search nearby lines
        if (nameOffset < 0) {
            for (int tryLine = line + 1; tryLine <= line + 3; tryLine++) {
                nameOffset = findNameInLine(tryLine, 0, name);
                if (nameOffset >= 0) {
                    line = tryLine;
                    break;
                }
            }
        }
        if (nameOffset >= 0) {
            addToken(line, nameOffset, name.length(), tokenType, modifiers);
        }

        // Visit initializer expression
        Expression init = node.getInitialExpression();
        if (init != null) {
            init.visit(this);
        }
    }

    @Override
    public void visitProperty(PropertyNode node) {
        // PropertyNode wraps a FieldNode — visitField handles its field
        visitField(node.getField());
    }

    // ================================================================
    // Expressions
    // ================================================================

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        // Visit the object expression (receiver)
        call.getObjectExpression().visit(this);

        // Emit the method name
        Expression method = call.getMethod();
        if (method instanceof ConstantExpression && method.getLineNumber() > 0) {
            String name = method.getText();
            int line = method.getLineNumber() - 1;
            int col = method.getColumnNumber() - 1;
            if (col >= 0 && name.length() > 0) {
                int modifiers = 0;
                addToken(line, col, name.length(),
                        SemanticTokensProvider.TYPE_METHOD, modifiers);
            }
        }

        // Visit arguments
        call.getArguments().visit(this);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        // Emit the method name with static modifier
        if (call.getLineNumber() > 0) {
            String name = call.getMethod();
            int line = call.getLineNumber() - 1;
            int col = call.getColumnNumber() - 1;
            if (col >= 0 && name.length() > 0) {
                addToken(line, col, name.length(),
                        SemanticTokensProvider.TYPE_METHOD,
                        SemanticTokensProvider.MOD_STATIC);
            }
        }

        // Visit arguments
        call.getArguments().visit(this);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        // Emit the type being constructed
        ClassNode constructedType = call.getType();
        if (constructedType != null && call.getLineNumber() > 0) {
            emitTypeReference(constructedType);
        }

        // Visit arguments
        call.getArguments().visit(this);
    }

    @Override
    public void visitVariableExpression(VariableExpression expr) {
        if (expr.getLineNumber() < 1) return;

        String name = expr.getName();
        // Skip 'this' and 'super' — handled by TextMate as keywords
        if ("this".equals(name) || "super".equals(name)) return;

        int line = expr.getLineNumber() - 1;
        int col = expr.getColumnNumber() - 1;
        if (col < 0 || name.isEmpty()) return;

        // Classify based on what the variable resolves to
        Variable accessedVar = expr.getAccessedVariable();
        int tokenType;
        int modifiers = 0;

        if (accessedVar instanceof Parameter) {
            tokenType = SemanticTokensProvider.TYPE_PARAMETER;
        } else if (accessedVar instanceof FieldNode) {
            FieldNode field = (FieldNode) accessedVar;
            if (field.isEnum()) {
                tokenType = SemanticTokensProvider.TYPE_ENUM_MEMBER;
            } else {
                tokenType = SemanticTokensProvider.TYPE_PROPERTY;
            }
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                modifiers |= SemanticTokensProvider.MOD_STATIC;
            }
            if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                modifiers |= SemanticTokensProvider.MOD_READONLY;
            }
        } else {
            tokenType = SemanticTokensProvider.TYPE_VARIABLE;
        }

        addToken(line, col, name.length(), tokenType, modifiers);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expr) {
        if (expr.getLineNumber() < 1) return;

        // Visit the type on the left side
        Expression leftExpr = expr.getLeftExpression();
        if (leftExpr instanceof VariableExpression) {
            VariableExpression varExpr = (VariableExpression) leftExpr;

            // Emit the type reference if explicitly typed
            ClassNode declaredType = varExpr.getOriginType();
            if (declaredType != null && declaredType.getLineNumber() > 0
                    && !"java.lang.Object".equals(declaredType.getName())) {
                emitTypeReference(declaredType);
            }

            // Emit the variable name as a declaration
            if (varExpr.getLineNumber() > 0) {
                String name = varExpr.getName();
                int line = varExpr.getLineNumber() - 1;
                int col = varExpr.getColumnNumber() - 1;
                if (col >= 0 && !name.isEmpty()) {
                    int modifiers = SemanticTokensProvider.MOD_DECLARATION;
                    if (java.lang.reflect.Modifier.isFinal(varExpr.getModifiers())) {
                        modifiers |= SemanticTokensProvider.MOD_READONLY;
                    }
                    addToken(line, col, name.length(),
                            SemanticTokensProvider.TYPE_VARIABLE, modifiers);
                }
            }
        }

        // Visit the right-hand side (initializer)
        Expression rightExpr = expr.getRightExpression();
        if (rightExpr != null) {
            rightExpr.visit(this);
        }
    }

    @Override
    public void visitClosureExpression(ClosureExpression expr) {
        if (expr.getLineNumber() < 1) return;

        // Visit closure parameters
        Parameter[] params = expr.getParameters();
        if (params != null) {
            for (Parameter param : params) {
                if (param.getLineNumber() > 0) {
                    visitParameter(param);
                }
            }
        }

        // Visit closure body
        if (expr.getCode() != null) {
            expr.getCode().visit(this);
        }
    }

    @Override
    public void visitClassExpression(ClassExpression expr) {
        if (expr.getLineNumber() < 1) return;
        emitTypeReference(expr.getType());
    }

    @Override
    public void visitPropertyExpression(PropertyExpression expr) {
        // Visit the object expression
        expr.getObjectExpression().visit(this);

        // Emit the property name
        Expression property = expr.getProperty();
        if (property instanceof ConstantExpression && property.getLineNumber() > 0) {
            String name = property.getText();
            int line = property.getLineNumber() - 1;
            int col = property.getColumnNumber() - 1;
            if (col >= 0 && !name.isEmpty()) {
                addToken(line, col, name.length(),
                        SemanticTokensProvider.TYPE_PROPERTY, 0);
            }
        }
    }

    @Override
    public void visitArgumentlistExpression(ArgumentListExpression ale) {
        // Visit each argument — check for named arguments (MapEntryExpression)
        for (Expression expr : ale.getExpressions()) {
            if (expr instanceof MapEntryExpression) {
                MapEntryExpression mapEntry = (MapEntryExpression) expr;
                // Named argument key
                Expression key = mapEntry.getKeyExpression();
                if (key instanceof ConstantExpression && key.getLineNumber() > 0) {
                    String name = key.getText();
                    int line = key.getLineNumber() - 1;
                    int col = key.getColumnNumber() - 1;
                    if (col >= 0 && !name.isEmpty()) {
                        addToken(line, col, name.length(),
                                SemanticTokensProvider.TYPE_PARAMETER, 0);
                    }
                }
                // Visit the value expression
                mapEntry.getValueExpression().visit(this);
            } else {
                expr.visit(this);
            }
        }
    }

    // ================================================================
    // Annotations
    // ================================================================

    @Override
    public void visitAnnotations(AnnotatedNode node) {
        // Annotation decorator tokens are handled explicitly in collectAnnotationTokens.
        // Keep visiting annotation member values so nested expressions are still tokenized,
        // but avoid default traversal classifying annotation names as TYPE tokens.
        List<AnnotationNode> annotations = node.getAnnotations();
        if (annotations == null || annotations.isEmpty()) return;

        for (AnnotationNode ann : annotations) {
            if (ann == null || ann.getMembers() == null || ann.getMembers().isEmpty()) continue;
            for (Expression memberValue : ann.getMembers().values()) {
                if (memberValue != null) {
                    memberValue.visit(this);
                }
            }
        }
    }

    private void collectAnnotationTokens(AnnotatedNode node) {
        List<AnnotationNode> annotations = node.getAnnotations();
        if (annotations == null || annotations.isEmpty()) return;

        for (AnnotationNode ann : annotations) {
            ClassNode annType = ann.getClassNode();
            if (annType == null) continue;
            String name = annType.getNameWithoutPackage();
            if (name == null || name.isEmpty()) continue;

            int reportedLine;
            int reportedCol;
            if (ann.getLineNumber() > 0) {
                reportedLine = ann.getLineNumber() - 1;
                reportedCol = Math.max(0, ann.getColumnNumber() - 1);
            } else if (node.getLineNumber() > 0) {
                reportedLine = node.getLineNumber() - 1;
                reportedCol = Math.max(0, node.getColumnNumber() - 1);
            } else {
                continue;
            }

            int[] annotationStart = findAnnotationStartNear(reportedLine, reportedCol, name);
            if (annotationStart == null) {
                continue;
            }

            addToken(annotationStart[0], annotationStart[1], name.length() + 1,
                    SemanticTokensProvider.TYPE_TYPE, 0);
        }
    }

    private int[] findAnnotationStartNear(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty() || line < 0) return null;

        // 1) First try the reported line from the reported column.
        int col = findAnnotationStartInLine(line, startCol, name);
        if (col >= 0) {
            return new int[]{line, col};
        }

        // 2) Retry the whole reported line.
        col = findAnnotationStartInLine(line, 0, name);
        if (col >= 0) {
            return new int[]{line, col};
        }

        // 3) AST positions can be off when annotations precede declarations;
        //    search nearby lines and pick the closest match.
        final int MAX_OFFSET = 4;
        for (int offset = 1; offset <= MAX_OFFSET; offset++) {
            int upLine = line - offset;
            if (upLine >= 0) {
                col = findAnnotationStartInLine(upLine, 0, name);
                if (col >= 0) {
                    return new int[]{upLine, col};
                }
            }

            int downLine = line + offset;
            col = findAnnotationStartInLine(downLine, 0, name);
            if (col >= 0) {
                return new int[]{downLine, col};
            }
        }

        return null;
    }

    private int findAnnotationStartInLine(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty() || line < 0) return -1;

        int lineStart = 0;
        int currentLine = 0;
        while (currentLine < line && lineStart < source.length()) {
            if (source.charAt(lineStart) == '\n') {
                currentLine++;
            }
            lineStart++;
        }
        if (currentLine != line) return -1;

        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = source.length();

        int searchFrom = Math.max(lineStart, lineStart + Math.max(0, startCol - 1));
        String annotationText = "@" + name;

        int idx = searchFrom;
        while (idx <= lineEnd - annotationText.length()) {
            int found = source.indexOf(annotationText, idx);
            if (found < 0 || found > lineEnd - annotationText.length()) {
                break;
            }

            int right = found + annotationText.length();
            boolean rightBound = (right >= source.length()) || !Character.isJavaIdentifierPart(source.charAt(right));
            if (rightBound) {
                return found - lineStart;
            }

            idx = found + 1;
        }

        return -1;
    }

    // ================================================================
    // Generics
    // ================================================================

    private void visitGenerics(GenericsType[] generics) {
        if (generics == null) return;

        for (GenericsType gt : generics) {
            if (gt.getLineNumber() < 1) continue;

            ClassNode typeNode = gt.getType();
            if (typeNode != null) {
                String name = typeNode.getNameWithoutPackage();
                int line = gt.getLineNumber() - 1;
                int col = gt.getColumnNumber() - 1;
                if (col >= 0 && !name.isEmpty()) {
                    // If it's a placeholder (like T, E, K, V), it's a type parameter
                    if (gt.isPlaceholder()) {
                        addToken(line, col, name.length(),
                                SemanticTokensProvider.TYPE_TYPE_PARAMETER,
                                SemanticTokensProvider.MOD_DECLARATION);
                    } else {
                        addToken(line, col, name.length(),
                                SemanticTokensProvider.TYPE_TYPE, 0);
                    }
                }
            }

            // Visit upper bounds
            ClassNode[] upperBounds = gt.getUpperBounds();
            if (upperBounds != null) {
                for (ClassNode bound : upperBounds) {
                    if (bound.getLineNumber() > 0) {
                        emitTypeReference(bound);
                    }
                }
            }

            // Visit lower bound
            ClassNode lowerBound = gt.getLowerBound();
            if (lowerBound != null && lowerBound.getLineNumber() > 0) {
                emitTypeReference(lowerBound);
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Emit a type reference token (for use in variable declarations, extends, implements, etc.)
     */
    private void emitTypeReference(ClassNode typeNode) {
        if (typeNode == null || typeNode.getLineNumber() < 1) return;

        String name = typeNode.getNameWithoutPackage();
        int line = typeNode.getLineNumber() - 1;
        int col = typeNode.getColumnNumber() - 1;

        if (col < 0 || name.isEmpty()) return;

        // Remove inner class separators for display
        int dollarIdx = name.indexOf('$');
        if (dollarIdx > 0) {
            name = name.substring(dollarIdx + 1);
        }

        int modifiers = 0;
        // Mark standard library types
        String fullName = typeNode.getName();
        if (fullName.startsWith("java.") || fullName.startsWith("groovy.")
                || fullName.startsWith("javax.") || fullName.startsWith("org.codehaus.groovy.")) {
            modifiers |= SemanticTokensProvider.MOD_DEFAULT_LIB;
        }

        addToken(line, col, name.length(), SemanticTokensProvider.TYPE_TYPE, modifiers);

        // Visit nested generics (e.g., List<String>)
        visitGenerics(typeNode.getGenericsTypes());
    }

    /**
     * Check if a node is annotated with @Deprecated.
     */
    private boolean isDeprecated(AnnotatedNode node) {
        List<AnnotationNode> annotations = node.getAnnotations();
        if (annotations == null) return false;
        for (AnnotationNode ann : annotations) {
            String name = ann.getClassNode().getName();
            if ("java.lang.Deprecated".equals(name) || "Deprecated".equals(name)
                    || "groovy.lang.Deprecated".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a name is a valid Java/Groovy identifier (not a Spock string method name, etc.).
     */
    private boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Check if the character at a given line and column in the source is the expected char.
     */
    private boolean isCharAt(int line, int col, char expected) {
        if (source == null || line < 0 || col < 0) return false;
        int lineStart = 0;
        int currentLine = 0;
        while (currentLine < line && lineStart < source.length()) {
            if (source.charAt(lineStart) == '\n') {
                currentLine++;
            }
            lineStart++;
        }
        int pos = lineStart + col;
        if (pos < 0 || pos >= source.length()) return false;
        return source.charAt(pos) == expected;
    }

    /**
     * Try to find the exact column of a name on a given line, searching forward
     * from the given start column. This is needed because AST node positions
     * sometimes point to the start of the whole declaration (e.g., the keyword)
     * rather than the name itself.
     */
    private int findNameInLine(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty()) return -1;
        if (line < 0 || startCol < 0) return -1;

        // Find the beginning of the line in the source
        int lineStart = 0;
        int currentLine = 0;
        while (currentLine < line && lineStart < source.length()) {
            if (source.charAt(lineStart) == '\n') {
                currentLine++;
            }
            lineStart++;
        }

        // Search region: from startCol on this line to end of line
        int searchStart = lineStart + startCol;
        if (searchStart >= source.length()) return startCol;

        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = source.length();

        // Search for the name as a whole word
        int idx = searchStart;
        while (idx <= lineEnd - name.length()) {
            int found = source.indexOf(name, idx);
            if (found < 0 || found > lineEnd - name.length()) break;

            // Check word boundaries
            boolean leftBound = (found == 0) || !Character.isJavaIdentifierPart(source.charAt(found - 1));
            boolean rightBound = (found + name.length() >= source.length())
                    || !Character.isJavaIdentifierPart(source.charAt(found + name.length()));

            if (leftBound && rightBound) {
                return found - lineStart; // return column relative to line start
            }

            idx = found + 1;
        }

        // Name not found on this line — return -1 to signal failure
        // (caller must handle this; do NOT fall back to startCol as that
        // can create phantom tokens at wrong positions, e.g. overlapping
        // annotation strings when the AST reports the annotation line
        // for a class declaration)
        return -1;
    }

    /**
     * Record a token at the given absolute position.
     */
    private void addToken(int line, int column, int length, int tokenType, int modifiers) {
        if (line < 0 || column < 0 || length <= 0) return;

        // Range filter — skip tokens outside the requested range
        if (range != null) {
            Position start = range.getStart();
            Position end = range.getEnd();
            if (line < start.getLine() || line > end.getLine()) return;
            if (line == start.getLine() && column < start.getCharacter()) return;
            if (line == end.getLine() && column >= end.getCharacter()) return;
        }

        tokens.add(new RawToken(line, column, length, tokenType, modifiers));
    }

    /**
     * Get the delta-encoded token data as required by the LSP protocol.
     * <p>
     * Format: each token is represented by 5 integers:
     * <pre>
     *   [deltaLine, deltaStartChar, length, tokenType, tokenModifiers]
     * </pre>
     * Tokens must be sorted by (line, column). Delta values are relative to
     * the previous token (or 0,0 for the first token).
     */
    public List<Integer> getEncodedTokens() {
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by line, then column
        tokens.sort(Comparator.comparingInt((RawToken t) -> t.line)
                .thenComparingInt(t -> t.column));

        // Remove duplicate tokens at the same position.
        // Prefer decorator tokens when clashes happen at the same start.
        List<RawToken> deduped = new ArrayList<>();
        RawToken prev = null;
        for (RawToken t : tokens) {
            if (prev == null || t.line != prev.line || t.column != prev.column) {
                deduped.add(t);
                prev = t;
            } else if (t.tokenType == SemanticTokensProvider.TYPE_DECORATOR
                    && prev.tokenType != SemanticTokensProvider.TYPE_DECORATOR) {
                deduped.set(deduped.size() - 1, t);
                prev = t;
            }
        }

        // Remove overlapping tokens on the same line when a decorator already
        // covers the span (e.g., both '@Name' and 'Name' are emitted).
        List<RawToken> nonOverlapping = new ArrayList<>(deduped.size());
        RawToken last = null;
        for (RawToken t : deduped) {
            if (last == null || t.line != last.line || t.column >= last.column + last.length) {
                nonOverlapping.add(t);
                last = t;
                continue;
            }

            boolean tInsideLast = t.column >= last.column
                    && (t.column + t.length) <= (last.column + last.length);
            if (tInsideLast && last.tokenType == SemanticTokensProvider.TYPE_DECORATOR
                    && t.tokenType != SemanticTokensProvider.TYPE_DECORATOR) {
                continue;
            }

            nonOverlapping.add(t);
            last = t;
        }

        // Delta-encode
        List<Integer> data = new ArrayList<>(nonOverlapping.size() * 5);
        int prevLine = 0;
        int prevCol = 0;

        for (RawToken token : nonOverlapping) {
            int deltaLine = token.line - prevLine;
            int deltaCol = (deltaLine == 0) ? (token.column - prevCol) : token.column;

            data.add(deltaLine);
            data.add(deltaCol);
            data.add(token.length);
            data.add(token.tokenType);
            data.add(token.modifiers);

            prevLine = token.line;
            prevCol = token.column;
        }

        return data;
    }
}
