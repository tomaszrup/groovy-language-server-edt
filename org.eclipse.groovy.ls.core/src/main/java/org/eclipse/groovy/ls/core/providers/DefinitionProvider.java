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
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Provides go-to-definition for Groovy documents.
 * <p>
 * Uses JDT's {@link ICompilationUnit#codeSelect(int, int)} to resolve the element
 * at the cursor, then navigates to its declaration source location.
 */
public class DefinitionProvider {

    private final DocumentManager documentManager;

    public DefinitionProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    /**
     * Compute the definition location(s) for the element at the cursor.
     */
    public List<Location> getDefinition(DefinitionParams params) {
        List<Location> locations = new ArrayList<>();

        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy != null) {
            try {
                String content = documentManager.getContent(uri);
                if (content != null) {
                    int offset = positionToOffset(content, position);
                    String word = extractWordAt(content, offset);
                    GroovyLanguageServerPlugin.logInfo("[definition] codeSelect at offset " + offset
                            + " word='" + word + "' workingCopy=" + workingCopy.getClass().getName());

                    // Resolve the element at the offset
                    IJavaElement[] elements = workingCopy.codeSelect(offset, 0);
                    GroovyLanguageServerPlugin.logInfo("[definition] codeSelect returned "
                            + (elements != null ? elements.length : 0) + " element(s)");
                    if (elements != null) {
                        for (IJavaElement element : elements) {
                            GroovyLanguageServerPlugin.logInfo("[definition] resolved: "
                                    + element.getElementName() + " (" + element.getClass().getName() + ")");
                            Location location = toLocation(element);
                            if (location != null) {
                                GroovyLanguageServerPlugin.logInfo("[definition] location: "
                                        + location.getUri() + " " + location.getRange().getStart().getLine()
                                        + ":" + location.getRange().getStart().getCharacter());
                                locations.add(location);
                            } else {
                                GroovyLanguageServerPlugin.logInfo("[definition] toLocation returned null for "
                                        + element.getElementName());
                            }
                        }
                    }
                }
                if (!locations.isEmpty()) {
                    return locations;
                }
            } catch (Throwable t) {
                GroovyLanguageServerPlugin.logError("Definition JDT failed for " + uri + ", falling back to AST", t);
            }
        }

        // Check if this is a temp source file from a JAR (outside workspace)
        // If so, resolve types from imports and navigate to their sources
        List<Location> tempLocs = getDefinitionFromTempSourceFile(uri, position);
        if (tempLocs != null && !tempLocs.isEmpty()) {
            return tempLocs;
        }

        // Fallback: use Groovy AST for within-file navigation
        return getDefinitionFromGroovyAST(uri, position);
    }

    /**
     * Convert a JDT element to its declaration location.
     * Handles both workspace source files and binary types from JARs.
     */
    private Location toLocation(IJavaElement element) {
        try {
            // 1. Try workspace resource (source file in the project)
            IResource resource = element.getResource();
            if (resource == null) {
                ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (cu != null) {
                    resource = cu.getResource();
                }
            }

            if (resource != null && resource.getLocationURI() != null) {
                String targetUri = resource.getLocationURI().toString();
                Range range = new Range(new Position(0, 0), new Position(0, 0));
                if (element instanceof ISourceReference) {
                    ISourceRange nameRange = ((ISourceReference) element).getNameRange();
                    if (nameRange != null && nameRange.getOffset() >= 0) {
                        range = offsetRangeToLspRange(targetUri, resource, nameRange);
                    }
                }
                return new Location(targetUri, range);
            }

            // 2. Binary type (from a JAR) — try to find source in workspace
            //    or use source attachment from the JAR
            IType type = null;
            if (element instanceof IType) {
                type = (IType) element;
            } else {
                // For methods/fields inside a binary type, get their declaring type
                IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
                if (ancestor instanceof IType) {
                    type = (IType) ancestor;
                }
            }

            if (type != null) {
                String fqn = type.getFullyQualifiedName();
                GroovyLanguageServerPlugin.logInfo("[definition] Binary type: " + fqn
                        + " — searching workspace for source");

                // 2a. Search for a matching source file in the workspace
                Location workspaceLoc = findSourceInWorkspace(fqn);
                if (workspaceLoc != null) {
                    GroovyLanguageServerPlugin.logInfo("[definition] Found source in workspace: "
                            + workspaceLoc.getUri());
                    return workspaceLoc;
                }

                // 2b. Try to find sources JAR and read source directly from it
                java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
                if (sourcesJar != null) {
                    String source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
                    if (source != null && !source.isEmpty()) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[definition] Read source from JAR for " + fqn
                                + " (" + source.length() + " chars)");

                        // Determine extension from the entry that was found
                        String ext = ".java";
                        String entryPath = fqn.replace('.', '/') + ".groovy";
                        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(sourcesJar)) {
                            if (zf.getEntry(entryPath) != null) ext = ".groovy";
                        } catch (Exception ignore) {}

                        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                                fqn, ext, sourcesJar.getAbsolutePath(), false, source);
                        Range range = findClassDeclarationRange(source, type.getElementName());
                        return new Location(virtualUri, range);
                    }
                }

                // 2b-fallback. Try JDT source attachment (might already be set)
                IClassFile classFile = type.getClassFile();
                if (classFile != null) {
                    String source = null;
                    try {
                        source = classFile.getSource();
                    } catch (Exception e) {
                        // no source available
                    }
                    if (source != null && !source.isEmpty()) {
                        GroovyLanguageServerPlugin.logInfo(
                                "[definition] Found source via JDT attachment for " + fqn);
                        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                                fqn, ".java", null, false, source);
                        Range range = findClassDeclarationRange(source, type.getElementName());
                        return new Location(virtualUri, range);
                    }
                }

                // 2c. Last resort: generate a class stub
                String stub = generateClassStub(type);
                if (stub != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[definition] Generated stub for " + fqn);
                    String virtualUri = SourceJarHelper.buildGroovySourceUri(
                            fqn, ".groovy", null, false, stub);
                    return new Location(virtualUri,
                            new Range(new Position(0, 0), new Position(0, 0)));
                }

                GroovyLanguageServerPlugin.logInfo(
                        "[definition] No source found for binary type " + fqn);
            }

            return null;

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve location for " + element.getElementName(), e);
            return null;
        }
    }

    /**
     * Search the workspace for a source file that defines the given fully-qualified type.
     * Converts FQN to a path pattern and searches for matching .groovy or .java files.
     */
    private Location findSourceInWorkspace(String fqn) {
        try {
            // Convert FQN like "com.example.MyClass" to path suffix "com/example/MyClass"
            String pathSuffix = fqn.replace('.', '/');
            String[] extensions = {".groovy", ".java"};

            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen()) continue;

                for (String ext : extensions) {
                    // Search for the file in standard source directories
                    String[] srcPrefixes = {
                        "src/main/groovy/", "src/test/groovy/",
                        "src/main/java/", "src/test/java/",
                        "src/"
                    };

                    for (String srcPrefix : srcPrefixes) {
                        IFile file = project.getFile(srcPrefix + pathSuffix + ext);
                        if (file != null && file.exists()) {
                            String targetUri = file.getLocationURI().toString();
                            return new Location(targetUri,
                                    new Range(new Position(0, 0), new Position(0, 0)));
                        }
                    }

                    // Also look in subproject directories
                    IResource[] members = project.members();
                    for (IResource member : members) {
                        if (member instanceof org.eclipse.core.resources.IFolder) {
                            for (String srcPrefix : srcPrefixes) {
                                IFile file = project.getFile(
                                        member.getName() + "/" + srcPrefix + pathSuffix + ext);
                                if (file != null && file.exists()) {
                                    String targetUri = file.getLocationURI().toString();
                                    return new Location(targetUri,
                                            new Range(new Position(0, 0), new Position(0, 0)));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to search workspace for " + fqn, e);
        }
        return null;
    }

    /**
     * Convert a JDT source range (offset/length) to an LSP range (line/column).
     * Reads the target file content to compute the conversion.
     */
    private Range offsetRangeToLspRange(String uri, IResource resource, ISourceRange sourceRange) {
        try {
            // Try to get content from DocumentManager (if open)
            String content = documentManager.getContent(uri);

            // If not open, read from the file system
            if (content == null && resource instanceof org.eclipse.core.resources.IFile) {
                java.io.InputStream is = ((org.eclipse.core.resources.IFile) resource).getContents();
                content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                is.close();
            }

            if (content == null) {
                return new Range(new Position(0, 0), new Position(0, 0));
            }

            int startOffset = sourceRange.getOffset();
            int endOffset = startOffset + sourceRange.getLength();

            Position start = offsetToPosition(content, startOffset);
            Position end = offsetToPosition(content, endOffset);

            return new Range(start, end);

        } catch (Exception e) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
    }

    /**
     * Convert a character offset to an LSP position (line/column).
     */
    private Position offsetToPosition(String content, int offset) {
        int line = 0;
        int col = 0;
        int safeOffset = Math.min(offset, content.length());

        for (int i = 0; i < safeOffset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }

        return new Position(line, col);
    }

    // ---- Temp source file navigation ----

    /**
     * Handle go-to-definition from temp source files (extracted from JARs).
     * Parses imports and type references to resolve the target type,
     * then finds its source from the appropriate sources JAR or JDK src.zip.
     */
    private List<Location> getDefinitionFromTempSourceFile(String uri, Position position) {
        // Only handle groovy-source: virtual documents (handle any normalization form)
        if (!uri.startsWith("groovy-source:")) return null;

        String content = documentManager.getContent(uri);
        if (content == null) {
            // Fallback: try SourceJarHelper cache by FQN extracted from URI
            String fqn = SourceJarHelper.extractFqnFromUri(uri);
            if (fqn != null) {
                content = SourceJarHelper.getCachedContent(fqn);
            }
        }
        if (content == null) return null;

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) return null;

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Virtual source navigation: word='" + word + "' in " + uri);

        // Try to resolve FQN from imports
        String fqn = resolveTypeFromSource(content, word);
        if (fqn == null) return null;

        GroovyLanguageServerPlugin.logInfo("[definition] Resolved to FQN: " + fqn);

        // Try to find source for this FQN
        List<Location> locations = new ArrayList<>();
        Location loc = navigateToFqn(fqn, word);
        if (loc != null) {
            locations.add(loc);
        }
        return locations;
    }

    /**
     * Resolve a simple type name to a fully-qualified name by parsing
     * import statements and package declarations from source content.
     */
    private String resolveTypeFromSource(String content, String simpleName) {
        String[] lines = content.split("\n");
        String packageName = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Parse package
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.substring(8).replace(";", "").trim();
            }

            // Parse imports: "import foo.bar.SimpleName" or "import foo.bar.*"
            if (trimmed.startsWith("import ")) {
                String importLine = trimmed.substring(7).replace(";", "").trim();
                // Skip static imports
                if (importLine.startsWith("static ")) continue;

                if (importLine.endsWith("." + simpleName)) {
                    return importLine;
                }

                // Star import: check if the type exists in this package
                if (importLine.endsWith(".*")) {
                    String pkg = importLine.substring(0, importLine.length() - 2);
                    String candidateFqn = pkg + "." + simpleName;
                    // We'll try this as a candidate
                    // Check if it's a well-known package or if we can find its source
                    if (canResolveSource(candidateFqn)) {
                        return candidateFqn;
                    }
                }
            }
        }

        // Check if the type is in the same package
        if (packageName != null) {
            String samePkg = packageName + "." + simpleName;
            if (canResolveSource(samePkg)) {
                return samePkg;
            }
        }

        // Check java.lang (auto-imported)
        String javaLang = "java.lang." + simpleName;
        if (canResolveSource(javaLang)) {
            return javaLang;
        }

        // Check extends/implements clauses for FQN references
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("extends ") || trimmed.contains("implements ")) {
                // Look for FQN like "foo.bar.ClassName" containing our simpleName
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\b([a-z][\\w.]*\\." + java.util.regex.Pattern.quote(simpleName) + ")\\b")
                        .matcher(trimmed);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }

        return null;
    }

    /**
     * Check if we can find source for a given FQN (in sources JARs, JDK, or workspace).
     */
    private boolean canResolveSource(String fqn) {
        // Quick check: JDK types
        String jdkSource = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        if (jdkSource != null) return true;

        // Check workspace
        Location wsLoc = findSourceInWorkspace(fqn);
        if (wsLoc != null) return true;

        // Check if any project's classpath has this type
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen()) continue;
                org.eclipse.jdt.core.IJavaProject javaProject = JavaCore.create(project);
                if (javaProject != null && javaProject.exists()) {
                    IType type = javaProject.findType(fqn);
                    if (type != null) return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }

        return false;
    }

    /**
     * Navigate to source for a fully-qualified type name.
     * Tries workspace, sources JARs, JDK src.zip, and JDT project types.
     */
    private Location navigateToFqn(String fqn, String simpleName) {
        // 1. Check workspace source
        Location wsLoc = findSourceInWorkspace(fqn);
        if (wsLoc != null) return wsLoc;

        // 2. Try JDT project types (gives us access to SourceJarHelper.findSourcesJar)
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen()) continue;
                org.eclipse.jdt.core.IJavaProject javaProject = JavaCore.create(project);
                if (javaProject == null || !javaProject.exists()) continue;

                IType type = javaProject.findType(fqn);
                if (type != null) {
                    // Use the same binary type resolution as toLocation
                    java.io.File sourcesJar = SourceJarHelper.findSourcesJar(type);
                    if (sourcesJar != null) {
                        String source = SourceJarHelper.readSourceFromJar(sourcesJar, fqn);
                        if (source != null && !source.isEmpty()) {
                            String ext = ".java";
                            String entryPath = fqn.replace('.', '/') + ".groovy";
                            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(sourcesJar)) {
                                if (zf.getEntry(entryPath) != null) ext = ".groovy";
                            } catch (Exception ignore) {}

                            String virtualUri = SourceJarHelper.buildGroovySourceUri(
                                    fqn, ext, sourcesJar.getAbsolutePath(), false, source);
                            Range range = findClassDeclarationRange(source, simpleName);
                            return new Location(virtualUri, range);
                        }
                    }

                    // Try stub as last resort
                    String stub = generateClassStub(type);
                    if (stub != null) {
                        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                                fqn, ".groovy", null, false, stub);
                        return new Location(virtualUri,
                                new Range(new Position(0, 0), new Position(0, 0)));
                    }
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to resolve FQN: " + fqn, e);
        }

        // 3. Try JDK src.zip
        String jdkSource = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        if (jdkSource != null) {
            String virtualUri = SourceJarHelper.buildGroovySourceUri(
                    fqn, ".java", null, true, jdkSource);
            Range range = findClassDeclarationRange(jdkSource, simpleName);
            return new Location(virtualUri, range);
        }

        return null;
    }

    /**
     * Find the range of the class declaration in source code.
     */
    private Range findClassDeclarationRange(String source, String simpleName) {
        int classIdx = source.indexOf("class " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("interface " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("enum " + simpleName);
        if (classIdx < 0) classIdx = source.indexOf("trait " + simpleName);
        if (classIdx >= 0) {
            Position start = offsetToPosition(source, classIdx);
            Position end = offsetToPosition(source, classIdx + simpleName.length() + 6);
            return new Range(start, end);
        }
        return new Range(new Position(0, 0), new Position(0, 0));
    }

    // ---- Groovy AST fallback definition ----

    /**
     * Provide go-to-definition using the Groovy AST when JDT is not available.
     * Finds declarations of the symbol under the cursor within the same file.
     */
    private List<Location> getDefinitionFromGroovyAST(String uri, Position position) {
        List<Location> locations = new ArrayList<>();

        String content = documentManager.getContent(uri);
        if (content == null) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] No content for " + uri);
            return locations;
        }

        ModuleNode ast = documentManager.getGroovyAST(uri);
        if (ast == null) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] No AST for " + uri);
            return locations;
        }

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] No word at offset " + offset);
            return locations;
        }

        int targetLine = position.getLine() + 1;
        GroovyLanguageServerPlugin.logInfo("[definition-ast] word='" + word + "' line=" + targetLine
                + " classes=" + (ast.getClasses() != null ? ast.getClasses().size() : 0));

        // 1) Prefer the enclosing class and its traits/interfaces for member lookups.
        ClassNode owner = findEnclosingClass(ast, targetLine);
        if (owner != null) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] Enclosing class: " + owner.getName()
                    + " (line " + owner.getLineNumber() + "-" + owner.getLastLineNumber() + ")"
                    + " interfaces=" + (owner.getInterfaces() != null ? owner.getInterfaces().length : 0));

            Location ownerMember = findMemberDeclarationInClass(owner, word, uri);
            if (ownerMember != null) {
                GroovyLanguageServerPlugin.logInfo("[definition-ast] Found in owner class: " + ownerMember.getUri());
                locations.add(ownerMember);
                return locations;
            }

            ClassNode[] interfaces = owner.getInterfaces();
            if (interfaces != null) {
                for (ClassNode ifaceRef : interfaces) {
                    GroovyLanguageServerPlugin.logInfo("[definition-ast] Checking interface: "
                            + ifaceRef.getNameWithoutPackage() + " (name=" + ifaceRef.getName() + ")");

                    // Resolve the trait ClassNode, searching across open files
                    ClassNode ifaceDecl = TraitMemberResolver.resolveTraitClassNode(
                            ifaceRef, ast, documentManager);
                    String traitUri = TraitMemberResolver.findTraitDeclarationUri(
                            ifaceRef, uri, ast, documentManager);
                    String memberUri = (traitUri != null) ? traitUri : uri;

                    GroovyLanguageServerPlugin.logInfo("[definition-ast] Resolved iface: "
                            + ifaceDecl.getName()
                            + " fields=" + ifaceDecl.getFields().size()
                            + " props=" + ifaceDecl.getProperties().size()
                            + " methods=" + ifaceDecl.getMethods().size()
                            + " uri=" + memberUri);

                    Location traitMember = findMemberDeclarationInClass(ifaceDecl, word, memberUri);
                    if (traitMember != null) {
                        GroovyLanguageServerPlugin.logInfo("[definition-ast] Found trait member: " + word
                                + " at " + traitMember.getRange().getStart().getLine());
                        locations.add(traitMember);
                        return locations;
                    }

                    // Check $Trait$FieldHelper — at CONVERSION phase, trait fields
                    // are moved here with mangled names like <fqn_underscored>__<fieldName>
                    ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(ifaceDecl, ast);
                    if (helperNode != null) {
                        GroovyLanguageServerPlugin.logInfo("[definition-ast] FieldHelper: "
                                + helperNode.getName()
                                + " fields=" + helperNode.getFields().size()
                                + " methods=" + helperNode.getMethods().size());
                        // Check fields with demangled matching
                        for (FieldNode hField : helperNode.getFields()) {
                            GroovyLanguageServerPlugin.logInfo("[definition-ast]   helper field: " + hField.getName());
                            if (TraitMemberResolver.isTraitFieldMatch(hField.getName(), word)) {
                                GroovyLanguageServerPlugin.logInfo("[definition-ast] Found in FieldHelper: "
                                        + hField.getName() + " matches '" + word + "'");
                                // Navigate to the original trait declaration, not the helper
                                // The trait itself is the best target
                                Location traitLoc = astNodeToLocation(memberUri, ifaceDecl);
                                if (traitLoc != null) {
                                    locations.add(traitLoc);
                                    return locations;
                                }
                            }
                        }
                        // Check methods (getters/setters)
                        String cap = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                        for (MethodNode hMethod : helperNode.getMethods()) {
                            String mName = hMethod.getName();
                            if (mName.equals("get" + cap) || mName.equals("set" + cap) || mName.equals("is" + cap)) {
                                GroovyLanguageServerPlugin.logInfo("[definition-ast] Found accessor in FieldHelper: " + mName);
                                Location traitLoc = astNodeToLocation(memberUri, ifaceDecl);
                                if (traitLoc != null) {
                                    locations.add(traitLoc);
                                    return locations;
                                }
                            }
                        }
                    }

                    if (ifaceDecl.getNameWithoutPackage().equals(word)) {
                        Location ifaceLoc = astNodeToLocation(memberUri, ifaceDecl);
                        if (ifaceLoc != null) {
                            locations.add(ifaceLoc);
                            return locations;
                        }
                    }
                }
            }
        } else {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] No enclosing class found at line " + targetLine);
        }

        // 2) Fallback: whole-file symbol scan
        for (ClassNode classNode : ast.getClasses()) {
            // Skip synthetic script class
            if (classNode.getLineNumber() < 0) continue;

            // Check class name
            if (classNode.getNameWithoutPackage().equals(word)) {
                Location loc = astNodeToLocation(uri, classNode);
                if (loc != null) {
                    locations.add(loc);
                    return locations;
                }
            }

            // Check methods
            for (MethodNode method : classNode.getMethods()) {
                if (method.getLineNumber() < 0) continue;
                if (method.getName().equals(word)) {
                    Location loc = astNodeToLocation(uri, method);
                    if (loc != null) {
                        locations.add(loc);
                        return locations;
                    }
                }
            }

            // Check fields
            for (FieldNode field : classNode.getFields()) {
                if (field.getLineNumber() < 0) continue;
                if (field.getName().equals(word)) {
                    Location loc = astNodeToLocation(uri, field);
                    if (loc != null) {
                        locations.add(loc);
                        return locations;
                    }
                }
            }

            // Check properties
            for (PropertyNode prop : classNode.getProperties()) {
                if (prop.getField() == null || prop.getField().getLineNumber() < 0) continue;
                if (prop.getName().equals(word)) {
                    Location loc = astNodeToLocation(uri, prop.getField());
                    if (loc != null) {
                        locations.add(loc);
                        return locations;
                    }
                }
            }

            // Check inner classes
            java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter = classNode.getInnerClasses();
            while (innerIter.hasNext()) {
                ClassNode inner = innerIter.next();
                if (inner.getLineNumber() < 0) continue;
                if (inner.getNameWithoutPackage().equals(word)) {
                    Location loc = astNodeToLocation(uri, inner);
                    if (loc != null) {
                        locations.add(loc);
                        return locations;
                    }
                }
            }
        }

        return locations;
    }

    private ClassNode findEnclosingClass(ModuleNode module, int targetLine) {
        ClassNode best = null;
        for (ClassNode classNode : module.getClasses()) {
            int start = classNode.getLineNumber();
            int end = classNode.getLastLineNumber();
            if (start > 0 && end >= start && targetLine >= start && targetLine <= end) {
                if (best == null || start >= best.getLineNumber()) {
                    best = classNode;
                }
            } else if (start > 0 && start <= targetLine) {
                if (best == null || start >= best.getLineNumber()) {
                    best = classNode;
                }
            }
        }
        return best;
    }

    private ClassNode findClassBySimpleName(ModuleNode module, String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        for (ClassNode classNode : module.getClasses()) {
            if (simpleName.equals(classNode.getNameWithoutPackage())) {
                return classNode;
            }
        }
        return null;
    }

    private Location findMemberDeclarationInClass(ClassNode classNode, String word, String uri) {
        if (classNode == null) {
            return null;
        }

        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, method);
                if (loc != null) {
                    return loc;
                }
            }
        }

        for (FieldNode field : classNode.getFields()) {
            if (field.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, field);
                if (loc != null) {
                    return loc;
                }
            }
        }

        for (PropertyNode prop : classNode.getProperties()) {
            if (prop.getName().equals(word)) {
                if (prop.getField() != null) {
                    Location fieldLoc = astNodeToLocation(uri, prop.getField());
                    if (fieldLoc != null) {
                        return fieldLoc;
                    }
                }
                Location propLoc = astNodeToLocation(uri, prop);
                if (propLoc != null) {
                    return propLoc;
                }
            }
        }

        return null;
    }

    /**
     * Convert a Groovy AST node to an LSP Location.
     * Groovy AST uses 1-based lines/columns; LSP uses 0-based.
     */
    private Location astNodeToLocation(String uri, ASTNode node) {
        if (node.getLineNumber() < 1) return null;
        Position start = new Position(node.getLineNumber() - 1, Math.max(0, node.getColumnNumber() - 1));
        Position end;
        if (node.getLastLineNumber() >= 1) {
            end = new Position(node.getLastLineNumber() - 1, Math.max(0, node.getLastColumnNumber() - 1));
        } else {
            end = start;
        }
        return new Location(uri, new Range(start, end));
    }

    /**
     * Extract the identifier word at the given offset in the content.
     */
    private String extractWordAt(String content, int offset) {
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

    /**
     * Generate a class stub showing the public API of a binary type.
     * Used as a last resort when no source is available.
     */
    private String generateClassStub(IType type) {
        try {
            StringBuilder sb = new StringBuilder();
            String pkg = type.getPackageFragment().getElementName();
            if (pkg != null && !pkg.isEmpty()) {
                sb.append("package ").append(pkg).append("\n\n");
            }

            // Class declaration
            int flags = type.getFlags();
            if (org.eclipse.jdt.core.Flags.isPublic(flags)) sb.append("public ");
            if (org.eclipse.jdt.core.Flags.isAbstract(flags)) sb.append("abstract ");
            if (type.isInterface()) {
                sb.append("interface ");
            } else if (type.isEnum()) {
                sb.append("enum ");
            } else {
                sb.append("class ");
            }
            sb.append(type.getElementName());

            String superclass = type.getSuperclassName();
            if (superclass != null && !"Object".equals(superclass) && !"java.lang.Object".equals(superclass)) {
                sb.append(" extends ").append(superclass);
            }

            String[] interfaces = type.getSuperInterfaceNames();
            if (interfaces != null && interfaces.length > 0) {
                sb.append(type.isInterface() ? " extends " : " implements ");
                sb.append(String.join(", ", interfaces));
            }

            sb.append(" {\n\n");

            // Fields
            for (org.eclipse.jdt.core.IField field : type.getFields()) {
                int fflags = field.getFlags();
                if (!org.eclipse.jdt.core.Flags.isPublic(fflags)
                        && !org.eclipse.jdt.core.Flags.isProtected(fflags)) continue;
                sb.append("    ");
                if (org.eclipse.jdt.core.Flags.isStatic(fflags)) sb.append("static ");
                if (org.eclipse.jdt.core.Flags.isFinal(fflags)) sb.append("final ");
                sb.append(org.eclipse.jdt.core.Signature.toString(field.getTypeSignature()));
                sb.append(" ").append(field.getElementName()).append("\n");
            }

            // Methods
            for (org.eclipse.jdt.core.IMethod method : type.getMethods()) {
                int mflags = method.getFlags();
                if (!org.eclipse.jdt.core.Flags.isPublic(mflags)
                        && !org.eclipse.jdt.core.Flags.isProtected(mflags)) continue;
                sb.append("\n    ");
                if (org.eclipse.jdt.core.Flags.isStatic(mflags)) sb.append("static ");
                if (org.eclipse.jdt.core.Flags.isAbstract(mflags)) sb.append("abstract ");

                String returnType = method.isConstructor() ? "" :
                        org.eclipse.jdt.core.Signature.toString(method.getReturnType()) + " ";
                sb.append(returnType).append(method.getElementName()).append("(");

                String[] paramNames = method.getParameterNames();
                String[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramNames.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(org.eclipse.jdt.core.Signature.toString(paramTypes[i]));
                    sb.append(" ").append(paramNames[i]);
                }
                sb.append(")");

                String[] exceptions = method.getExceptionTypes();
                if (exceptions != null && exceptions.length > 0) {
                    sb.append(" throws ");
                    for (int i = 0; i < exceptions.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(org.eclipse.jdt.core.Signature.toString(exceptions[i]));
                    }
                }

                sb.append(" { /* compiled code */ }\n");
            }

            sb.append("}\n");
            return sb.toString();

        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to generate stub for " + type.getElementName(), e);
            return null;
        }
    }
}
