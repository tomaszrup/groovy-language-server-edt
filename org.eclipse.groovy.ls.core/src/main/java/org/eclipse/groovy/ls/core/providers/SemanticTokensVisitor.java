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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.codehaus.groovy.ast.stmt.Statement;
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

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String VOID_TYPE = "void";
    private static final String TRAIT_KEYWORD = "trait";
    private static final int[] NO_POSITION = new int[0];

    /** Spock framework block labels that should be highlighted as keywords. */
    private static final Set<String> SPOCK_LABELS = new HashSet<>(Arrays.asList(
            "given", "when", "then", "expect", "where", "and"));

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
        visitPackage(module);
        collectImportTokens(module);
        visitModuleClasses(module);
        visitModuleStatements(module);
    }

    private void visitModuleClasses(ModuleNode module) {
        for (ClassNode classNode : module.getClasses()) {
            if (isSyntheticScriptClass(classNode)) {
                visitSyntheticScriptClass(classNode);
            } else {
                visitClass(classNode);
            }
        }
    }

    private boolean isSyntheticScriptClass(ClassNode classNode) {
        return classNode.isScript() && classNode.getLineNumber() < 1;
    }

    private void visitSyntheticScriptClass(ClassNode classNode) {
        MethodNode runMethod = classNode.getMethod("run", Parameter.EMPTY_ARRAY);
        if (runMethod != null && runMethod.getCode() != null) {
            runMethod.getCode().visit(this);
        }
        for (MethodNode method : classNode.getMethods()) {
            if (!"run".equals(method.getName()) && !"main".equals(method.getName())
                    && method.getLineNumber() > 0) {
                visitMethod(method);
            }
        }
    }

    // ================================================================
    // Spock labels
    // ================================================================

    @Override
    public void visitBlockStatement(BlockStatement block) {
        for (Statement stmt : block.getStatements()) {
            List<String> labels = stmt.getStatementLabels();
            if (labels != null) {
                for (String label : labels) {
                    if (SPOCK_LABELS.contains(label)) {
                        emitSpockLabelToken(stmt, label);
                    }
                }
            }
        }
        super.visitBlockStatement(block);
    }

    private void emitSpockLabelToken(Statement stmt, String label) {
        if (stmt.getLineNumber() < 1) return;
        int stmtLine = stmt.getLineNumber() - 1; // 0-based
        // The label appears on or before the statement line
        for (int searchLine = stmtLine; searchLine >= Math.max(0, stmtLine - 5); searchLine--) {
            int col = findLabelInLine(searchLine, label);
            if (col >= 0) {
                addToken(searchLine, col, label.length(),
                        SemanticTokensProvider.TYPE_KEYWORD, 0);
                return;
            }
        }
    }

    private int findLabelInLine(int line, String label) {
        if (source == null || line < 0) return -1;
        int lineStart = findLineStart(line);
        if (lineStart < 0) return -1;
        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = source.length();
        String lineText = source.substring(lineStart, lineEnd);

        int idx = 0;
        while ((idx = lineText.indexOf(label, idx)) >= 0) {
            if (isLabelMatch(lineText, idx, label.length())) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    private boolean isLabelMatch(String lineText, int idx, int labelLength) {
        boolean leftBound = idx == 0 || !Character.isJavaIdentifierPart(lineText.charAt(idx - 1));
        if (!leftBound) return false;
        int pos = idx + labelLength;
        if (pos >= lineText.length()) return false;
        // skip optional whitespace then expect ':'
        while (pos < lineText.length() && lineText.charAt(pos) == ' ') pos++;
        return pos < lineText.length() && lineText.charAt(pos) == ':';
    }

    private void visitModuleStatements(ModuleNode module) {
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
        // Star imports (e.g., import java.util.*)
        for (ImportNode imp : module.getStarImports()) {
            visitStarImport(imp);
        }
        // Regular imports
        for (ImportNode imp : module.getImports()) {
            visitSingleImport(imp);
        }
        // Static imports
        for (ImportNode imp : module.getStaticImports().values()) {
            visitSingleImport(imp);
        }
        // Static star imports (e.g., import static org.junit.Assert.*)
        for (ImportNode imp : module.getStaticStarImports().values()) {
            visitStaticStarImport(imp);
        }
    }

    /**
     * Emit tokens for a wildcard import like {@code import java.util.*}.
     * The ImportNode's getType() returns a synthetic node with invalid line numbers,
     * so we use getPackageName() and the ImportNode's own line number instead.
     */
    private void visitStarImport(ImportNode imp) {
        if (imp.getLineNumber() < 1) return;
        String packageName = imp.getPackageName();
        if (packageName == null || packageName.isEmpty()) return;
        // Remove trailing dot if present (Groovy's getPackageName() returns "java.util.")
        if (packageName.endsWith(".")) {
            packageName = packageName.substring(0, packageName.length() - 1);
        }
        int line = imp.getLineNumber() - 1;
        emitAllSegmentsAsNamespace(packageName, line);
    }

    /**
     * Emit tokens for a static wildcard import like {@code import static org.junit.Assert.*}.
     * Emits package segments as namespace and the class name as type.
     */
    private void visitStaticStarImport(ImportNode imp) {
        if (imp.getLineNumber() < 1) return;
        ClassNode importedType = imp.getType();
        // Try the normal path first (works when the type has valid positions)
        if (importedType != null && importedType.getLineNumber() > 0) {
            String className = importedType.getNameWithoutPackage();
            int tLine = importedType.getLineNumber() - 1;
            int tCol = importedType.getColumnNumber() - 1;
            if (tCol >= 0 && !className.isEmpty()) {
                emitImportPackageTokens(importedType.getName(), tLine, tCol);
                emitImportTypeToken(className, tLine, tCol);
                return;
            }
        }
        // Fallback: use the ImportNode's own line and the type's FQN
        if (importedType == null) return;
        String fqn = importedType.getName();
        if (fqn == null || fqn.isEmpty()) return;
        int line = imp.getLineNumber() - 1;
        String className = importedType.getNameWithoutPackage();
        String packageName = fqn.contains(".")
                ? fqn.substring(0, fqn.lastIndexOf('.'))
                : null;
        if (packageName != null && !packageName.isEmpty()) {
            emitAllSegmentsAsNamespace(packageName, line);
        }
        if (className != null && !className.isEmpty()) {
            int nameOffset = findNameInLine(line, 0, className);
            if (nameOffset >= 0) {
                addToken(line, nameOffset, className.length(),
                        SemanticTokensProvider.TYPE_TYPE, 0);
            }
        }
    }

    /**
     * Emit namespace tokens for every segment of a dotted name on a given line.
     */
    private void emitAllSegmentsAsNamespace(String dottedName, int line) {
        String[] segments = dottedName.split("\\.");
        int cursor = 0;
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) continue;
            int segOffset = findNameInLine(line, cursor, segment);
            if (segOffset >= 0) {
                addToken(line, segOffset, segment.length(),
                        SemanticTokensProvider.TYPE_NAMESPACE, 0);
                cursor = segOffset + segment.length() + 1;
            }
        }
    }

    private void visitSingleImport(ImportNode imp) {
        if (imp.getLineNumber() < 1) return;
        ClassNode importedType = imp.getType();
        if (importedType == null || importedType.getLineNumber() <= 0) return;

        String className = importedType.getNameWithoutPackage();
        int line = importedType.getLineNumber() - 1;
        int col = importedType.getColumnNumber() - 1;
        if (col < 0 || className.isEmpty()) return;

        emitImportPackageTokens(importedType.getName(), line, col);
        emitImportTypeToken(className, line, col);
    }

    private void emitImportPackageTokens(String fullName, int line, int col) {
        String packageName = fullName.contains(".")
                ? fullName.substring(0, fullName.lastIndexOf('.'))
                : null;
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        int cursor = col;
        String[] segments = packageName.split("\\.");
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            int segOffset = findNameInLine(line, cursor, segment);
            if (segOffset >= 0) {
                addToken(line, segOffset, segment.length(),
                        SemanticTokensProvider.TYPE_NAMESPACE, 0);
                cursor = segOffset + segment.length() + 1;
            }
        }
    }

    private void emitImportTypeToken(String className, int line, int col) {
        if (className == null || className.isEmpty()) {
            return;
        }
        int nameOffset = findNameInLine(line, col, className);
        if (nameOffset >= 0) {
            addToken(line, nameOffset, className.length(),
                    SemanticTokensProvider.TYPE_TYPE, 0);
        }
    }

    // ================================================================
    // Class / Interface / Enum / Trait
    // ================================================================

    @Override
    public void visitClass(ClassNode node) {
        if (node.getLineNumber() < 1) return;
        int tokenType = getClassTokenType(node);
        int modifiers = getClassModifiers(node);
        String name = node.getNameWithoutPackage();
        int nameLine = node.getLineNumber() - 1;
        int nameCol = node.getColumnNumber() - 1;
        int[] resolved = findNameNear(nameLine, nameCol, name, 10);
        if (resolved.length > 0) {
            nameLine = resolved[0];
            int nameOffset = resolved[1];
            addToken(nameLine, nameOffset, name.length(), tokenType, modifiers);
            emitTraitKeywordToken(node, nameLine, nameOffset);
        }

        collectAnnotationTokens(node);
        visitGenerics(node.getGenericsTypes());
        ClassNode superClass = node.getUnresolvedSuperClass();
        if (superClass != null && superClass.getLineNumber() > 0
                && !JAVA_LANG_OBJECT.equals(superClass.getName())) {
            emitTypeReference(superClass);
        }
        visitInterfaces(node.getInterfaces());
        super.visitClass(node);
    }

    private int getClassTokenType(ClassNode node) {
        if (node.isEnum()) {
            return SemanticTokensProvider.TYPE_ENUM;
        }
        if (GroovyTypeKindHelper.isTrait(node)) {
            return SemanticTokensProvider.TYPE_STRUCT;
        }
        if (node.isInterface()) {
            return SemanticTokensProvider.TYPE_INTERFACE;
        }
        return SemanticTokensProvider.TYPE_CLASS;
    }

    private int getClassModifiers(ClassNode node) {
        int modifiers = SemanticTokensProvider.MOD_DECLARATION;
        if (java.lang.reflect.Modifier.isAbstract(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_ABSTRACT;
        }
        if (isDeprecated(node)) {
            modifiers |= SemanticTokensProvider.MOD_DEPRECATED;
        }
        return modifiers;
    }

    private void emitTraitKeywordToken(ClassNode node, int line, int nameOffset) {
        if (!GroovyTypeKindHelper.isTrait(node)) {
            return;
        }
        int kwOffset = findNameInLine(line, 0, TRAIT_KEYWORD);
        if (kwOffset >= 0 && kwOffset < nameOffset) {
            addToken(line, kwOffset, TRAIT_KEYWORD.length(),
                    SemanticTokensProvider.TYPE_TYPE_KEYWORD, 0);
        }
    }

    private void visitInterfaces(ClassNode[] interfaces) {
        if (interfaces == null) {
            return;
        }
        for (ClassNode iface : interfaces) {
            if (iface.getLineNumber() > 0) {
                emitTypeReference(iface);
            }
        }
    }

    // ================================================================
    // Methods & Constructors
    // ================================================================

    @Override
    public void visitMethod(MethodNode node) {
        if (node.getLineNumber() < 1) return;
        if (node.isSynthetic()) return;
        collectAnnotationTokens(node);

        emitMethodReturnType(node.getReturnType());
        String name = node.getName();
        int modifiers = getMethodModifiers(node);

        int nameLine = node.getLineNumber() - 1;
        int nameCol = node.getColumnNumber() - 1;
        int[] resolved = findNameNear(nameLine, nameCol, name, 5);
        if (resolved.length > 0 && !name.equals("<init>") && !name.equals("<clinit>")
                && isValidIdentifier(name)) {
            addToken(resolved[0], resolved[1], name.length(),
                    SemanticTokensProvider.TYPE_METHOD, modifiers);
        }

        visitMethodParameters(node.getParameters());
        visitGenerics(node.getGenericsTypes());
        visitMethodExceptions(node.getExceptions());
        super.visitMethod(node);
    }

    private void emitMethodReturnType(ClassNode returnType) {
        if (returnType != null && returnType.getLineNumber() > 0
                && !VOID_TYPE.equals(returnType.getName())
                && !JAVA_LANG_OBJECT.equals(returnType.getName())) {
            emitTypeReference(returnType);
        }
    }

    private int getMethodModifiers(MethodNode node) {
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
        return modifiers;
    }

    private void visitMethodParameters(Parameter[] params) {
        if (params == null) {
            return;
        }
        for (Parameter param : params) {
            visitParameter(param);
        }
    }

    private void visitMethodExceptions(ClassNode[] exceptions) {
        if (exceptions == null) {
            return;
        }
        for (ClassNode ex : exceptions) {
            if (ex.getLineNumber() > 0) {
                emitTypeReference(ex);
            }
        }
    }

    private void visitParameter(Parameter param) {
        if (param.getLineNumber() < 1) return;

        // Parameter type
        ClassNode paramType = param.getType();
        if (paramType != null && paramType.getLineNumber() > 0
                && !JAVA_LANG_OBJECT.equals(paramType.getName())) {
            emitTypeReference(paramType);
        }

        // Parameter name
        String name = param.getName();
        if (name == null || name.isEmpty()) {
            collectAnnotationTokens(param);
            return;
        }
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
        emitFieldType(node.getType());
        emitFieldNameToken(node);
        visitExpression(node.getInitialExpression());
    }

    private void emitFieldType(ClassNode fieldType) {
        if (fieldType != null && fieldType.getLineNumber() > 0) {
            emitTypeReference(fieldType);
        }
    }

    private void emitFieldNameToken(FieldNode node) {
        int[] resolved = findNameNear(node.getLineNumber() - 1, node.getColumnNumber() - 1,
                node.getName(), 3);
        if (resolved.length == 0) {
            return;
        }

        addToken(resolved[0], resolved[1], node.getName().length(), getFieldTokenType(node),
                getFieldModifiers(node));
    }

    private int getFieldTokenType(FieldNode node) {
        return node.isEnum()
                ? SemanticTokensProvider.TYPE_ENUM_MEMBER
                : SemanticTokensProvider.TYPE_PROPERTY;
    }

    private int getFieldModifiers(FieldNode node) {
        int modifiers = SemanticTokensProvider.MOD_DECLARATION;
        if (java.lang.reflect.Modifier.isStatic(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_STATIC;
        }
        if (java.lang.reflect.Modifier.isFinal(node.getModifiers())) {
            modifiers |= SemanticTokensProvider.MOD_READONLY;
        }
        if (isDeprecated(node)) {
            modifiers |= SemanticTokensProvider.MOD_DEPRECATED;
        }
        return modifiers;
    }

    private void visitExpression(Expression expr) {
        if (expr != null) {
            expr.visit(this);
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
            if (col >= 0 && !name.isEmpty()) {
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
            if (col >= 0 && !name.isEmpty()) {
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
        } else if (accessedVar instanceof FieldNode field) {
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
        Expression leftExpr = expr.getLeftExpression();
        if (leftExpr instanceof VariableExpression varExpr) {
            emitDeclarationType(varExpr);
            emitDeclarationName(varExpr);
        }
        visitExpression(expr.getRightExpression());
    }

    private void emitDeclarationType(VariableExpression varExpr) {
        ClassNode declaredType = varExpr.getOriginType();
        if (declaredType != null && declaredType.getLineNumber() > 0
                && !JAVA_LANG_OBJECT.equals(declaredType.getName())) {
            emitTypeReference(declaredType);
        }
    }

    private void emitDeclarationName(VariableExpression varExpr) {
        if (varExpr.getLineNumber() <= 0) {
            return;
        }
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
            if (expr instanceof MapEntryExpression mapEntry) {
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
            visitAnnotationMemberValues(ann);
        }
    }

    private void visitAnnotationMemberValues(AnnotationNode ann) {
        if (ann == null || ann.getMembers() == null || ann.getMembers().isEmpty()) {
            return;
        }
        for (Expression memberValue : ann.getMembers().values()) {
            if (memberValue != null) {
                memberValue.visit(this);
            }
        }
    }

    private void collectAnnotationTokens(AnnotatedNode node) {
        List<AnnotationNode> annotations = node.getAnnotations();
        if (annotations == null || annotations.isEmpty()) return;

        for (AnnotationNode ann : annotations) {
            emitAnnotationToken(node, ann);
        }
    }

    private void emitAnnotationToken(AnnotatedNode node, AnnotationNode ann) {
        ClassNode annType = ann.getClassNode();
        if (annType == null) {
            return;
        }
        String name = annType.getNameWithoutPackage();
        if (name == null || name.isEmpty()) {
            return;
        }

        int[] anchor = getAnnotationAnchor(node, ann);
        if (anchor.length == 0) {
            return;
        }

        int[] annotationStart = findAnnotationStartNear(anchor[0], anchor[1], name);
        if (annotationStart.length > 0) {
            addToken(annotationStart[0], annotationStart[1], name.length() + 1,
                    SemanticTokensProvider.TYPE_TYPE, 0);
        }
    }

    private int[] getAnnotationAnchor(AnnotatedNode node, AnnotationNode ann) {
        if (ann.getLineNumber() > 0) {
            return new int[]{ann.getLineNumber() - 1, Math.max(0, ann.getColumnNumber() - 1)};
        }
        if (node.getLineNumber() > 0) {
            return new int[]{node.getLineNumber() - 1, Math.max(0, node.getColumnNumber() - 1)};
        }
        return NO_POSITION;
    }

    private int[] findAnnotationStartNear(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty() || line < 0) return NO_POSITION;

        int col = findAnnotationStartInLine(line, startCol, name);
        if (col >= 0) {
            return new int[]{line, col};
        }

        col = findAnnotationStartInLine(line, 0, name);
        if (col >= 0) {
            return new int[]{line, col};
        }

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

        return NO_POSITION;
    }

    private int findAnnotationStartInLine(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty() || line < 0) return -1;

        int lineStart = findLineStart(line);
        if (lineStart < 0) {
            return -1;
        }

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
            visitGenericType(gt);
        }
    }

    private void visitGenericType(GenericsType gt) {
        if (gt.getLineNumber() < 1) {
            return;
        }

        emitGenericTypeToken(gt);
        visitUpperBounds(gt.getUpperBounds());
        ClassNode lowerBound = gt.getLowerBound();
        if (lowerBound != null && lowerBound.getLineNumber() > 0) {
            emitTypeReference(lowerBound);
        }
    }

    private void emitGenericTypeToken(GenericsType gt) {
        ClassNode typeNode = gt.getType();
        if (typeNode == null) {
            return;
        }
        String name = typeNode.getNameWithoutPackage();
        int line = gt.getLineNumber() - 1;
        int col = gt.getColumnNumber() - 1;
        if (col < 0 || name.isEmpty()) {
            return;
        }

        if (gt.isPlaceholder()) {
            addToken(line, col, name.length(),
                    SemanticTokensProvider.TYPE_TYPE_PARAMETER,
                    SemanticTokensProvider.MOD_DECLARATION);
        } else {
            addToken(line, col, name.length(), SemanticTokensProvider.TYPE_TYPE, 0);
        }
    }

    private void visitUpperBounds(ClassNode[] upperBounds) {
        if (upperBounds == null) {
            return;
        }
        for (ClassNode bound : upperBounds) {
            if (bound.getLineNumber() > 0) {
                emitTypeReference(bound);
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
     * Try to find the exact column of a name on a given line, searching forward
     * from the given start column. This is needed because AST node positions
     * sometimes point to the start of the whole declaration (e.g., the keyword)
     * rather than the name itself.
     */
    private int findNameInLine(int line, int startCol, String name) {
        if (source == null || name == null || name.isEmpty() || line < 0 || startCol < 0) {
            return -1;
        }

        int lineStart = findLineStart(line);
        if (lineStart < 0) {
            return -1;
        }

        int searchStart = lineStart + startCol;
        if (searchStart >= source.length()) return startCol;

        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = source.length();

        return findWholeWordInRange(name, lineStart, lineEnd, searchStart);
    }

    private int[] findNameNear(int line, int col, String name, int lookAheadLines) {
        int offset = findNameInLine(line, col, name);
        if (offset >= 0) {
            return new int[]{line, offset};
        }

        for (int tryLine = line + 1; tryLine <= line + lookAheadLines; tryLine++) {
            offset = findNameInLine(tryLine, 0, name);
            if (offset >= 0) {
                return new int[]{tryLine, offset};
            }
        }
        return NO_POSITION;
    }

    private int findLineStart(int line) {
        int lineStart = 0;
        int currentLine = 0;
        while (currentLine < line && lineStart < source.length()) {
            if (source.charAt(lineStart) == '\n') {
                currentLine++;
            }
            lineStart++;
        }
        return currentLine == line ? lineStart : -1;
    }

    private int findWholeWordInRange(String name, int lineStart, int lineEnd, int searchStart) {
        int idx = searchStart;
        while (idx <= lineEnd - name.length()) {
            int found = source.indexOf(name, idx);
            if (found < 0 || found > lineEnd - name.length()) {
                break;
            }

            if (isWholeWordMatch(found, name.length())) {
                return found - lineStart;
            }
            idx = found + 1;
        }
        return -1;
    }

    private boolean isWholeWordMatch(int found, int length) {
        boolean leftBound = (found == 0) || !Character.isJavaIdentifierPart(source.charAt(found - 1));
        boolean rightBound = (found + length >= source.length())
                || !Character.isJavaIdentifierPart(source.charAt(found + length));
        return leftBound && rightBound;
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

        tokens.sort(Comparator.comparingInt((RawToken t) -> t.line)
                .thenComparingInt(t -> t.column));

        List<RawToken> nonOverlapping = removeOverlappingTokens(deduplicateByPosition(tokens));
        return deltaEncode(nonOverlapping);
    }

    private List<RawToken> deduplicateByPosition(List<RawToken> sortedTokens) {
        List<RawToken> deduped = new ArrayList<>();
        RawToken previous = null;
        for (RawToken token : sortedTokens) {
            if (isNewTokenPosition(previous, token)) {
                deduped.add(token);
                previous = token;
            } else if (isDecoratorPreferred(previous, token)) {
                deduped.set(deduped.size() - 1, token);
                previous = token;
            }
        }
        return deduped;
    }

    private boolean isNewTokenPosition(RawToken previous, RawToken token) {
        return previous == null || token.line != previous.line || token.column != previous.column;
    }

    private boolean isDecoratorPreferred(RawToken previous, RawToken token) {
        return token.tokenType == SemanticTokensProvider.TYPE_DECORATOR
                && previous.tokenType != SemanticTokensProvider.TYPE_DECORATOR;
    }

    private List<RawToken> removeOverlappingTokens(List<RawToken> deduped) {
        List<RawToken> nonOverlapping = new ArrayList<>(deduped.size());
        RawToken last = null;
        for (RawToken token : deduped) {
            if (isNonOverlapping(last, token) || !isCoveredByDecorator(last, token)) {
                nonOverlapping.add(token);
                last = token;
            }
        }
        return nonOverlapping;
    }

    private boolean isNonOverlapping(RawToken last, RawToken token) {
        return last == null || token.line != last.line || token.column >= last.column + last.length;
    }

    private boolean isCoveredByDecorator(RawToken last, RawToken token) {
        boolean insideLast = token.column >= last.column
                && (token.column + token.length) <= (last.column + last.length);
        return insideLast
                && last.tokenType == SemanticTokensProvider.TYPE_DECORATOR
                && token.tokenType != SemanticTokensProvider.TYPE_DECORATOR;
    }

    private List<Integer> deltaEncode(List<RawToken> nonOverlapping) {
        List<Integer> data = new ArrayList<>(nonOverlapping.size() * 5);
        int prevLine = 0;
        int prevCol = 0;
        for (RawToken token : nonOverlapping) {
            int deltaLine = token.line - prevLine;
            int deltaCol = deltaLine == 0 ? token.column - prevCol : token.column;

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
