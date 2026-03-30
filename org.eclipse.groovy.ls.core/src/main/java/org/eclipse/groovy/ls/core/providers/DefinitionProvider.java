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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyLanguageServerPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
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

    private static final class SourceLookupContext {
        private final Map<String, Boolean> canResolveSourceCache = new HashMap<>();
        private final Map<String, IType> resolvedTypes = new HashMap<>();
        private final Set<String> missingTypes = new HashSet<>();
        private final Map<String, Location> resolvedLocations = new HashMap<>();
        private final Set<String> missingLocations = new HashSet<>();
    }

    private final DocumentManager documentManager;

    private static final String EXT_JAVA = ".java";
    private static final String EXT_GROOVY = ".groovy";
    private static final String STATIC_PREFIX = "static ";

    /**
     * Tracks the project that last successfully resolved a definition so that
     * {@link #navigateViaJdtProject} can try it first on the next call.
     */
    private volatile IProject currentDefinitionProject;

    public DefinitionProvider(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }

    private static String projectCacheKey(org.eclipse.jdt.core.IJavaProject project) {
        if (project == null) {
            return "<null>";
        }
        try {
            String name = project.getElementName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception e) {
            // Ignore and fall back to identity below.
        }
        return Integer.toHexString(System.identityHashCode(project));
    }

    private IType findTypeCached(org.eclipse.jdt.core.IJavaProject project,
            String fqn,
            SourceLookupContext context) throws JavaModelException {
        if (project == null || fqn == null || fqn.isEmpty()) {
            return null;
        }

        String cacheKey = projectCacheKey(project) + ":" + fqn;
        if (context != null) {
            if (context.missingTypes.contains(cacheKey)) {
                return null;
            }
            IType cached = context.resolvedTypes.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        IType resolved = project.findType(fqn);
        if (resolved == null) {
            String binaryFqn = SourceJarHelper.binaryTypeFqn(fqn);
            if (binaryFqn != null && !binaryFqn.equals(fqn)) {
                resolved = project.findType(binaryFqn);
            }
        }
        if (context != null) {
            context.resolvedTypes.remove(cacheKey);
            context.missingTypes.remove(cacheKey);
            if (resolved == null) {
                context.missingTypes.add(cacheKey);
            } else {
                context.resolvedTypes.put(cacheKey, resolved);
            }
        }
        return resolved;
    }

    private Location getCachedLocation(SourceLookupContext context,
            String cacheKey,
            Supplier<Location> resolver) {
        if (context == null) {
            return resolver.get();
        }
        if (context.missingLocations.contains(cacheKey)) {
            return null;
        }
        Location cached = context.resolvedLocations.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Location resolved = resolver.get();
        context.resolvedLocations.remove(cacheKey);
        context.missingLocations.remove(cacheKey);
        if (resolved == null) {
            context.missingLocations.add(cacheKey);
        } else {
            context.resolvedLocations.put(cacheKey, resolved);
        }
        return resolved;
    }

    /**
     * Compute the definition location(s) for the element at the cursor.
     */
    public List<Location> getDefinition(DefinitionParams params) {
        SourceLookupContext sourceLookupContext = new SourceLookupContext();
        String uri = params.getTextDocument().getUri();
        Position position = params.getPosition();

        List<Location> jdtLocations = resolveViaJdt(uri, position, sourceLookupContext);
        if (!jdtLocations.isEmpty()) {
            return jdtLocations;
        }

        // Check if this is a temp source file from a JAR (outside workspace)
        List<Location> tempLocs = getDefinitionFromTempSourceFile(uri, position, sourceLookupContext);
        if (tempLocs != null && !tempLocs.isEmpty()) {
            return tempLocs;
        }

        // Fallback: use Groovy AST for within-file navigation
        return getDefinitionFromGroovyAST(uri, position, sourceLookupContext);
    }

    /**
     * Try to resolve definition via JDT codeSelect.
     */
    private List<Location> resolveViaJdt(String uri, Position position,
            SourceLookupContext sourceLookupContext) {
        List<Location> locations = new ArrayList<>();
        ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
        if (workingCopy == null) {
            return locations;
        }
        try {
            String content = documentManager.getContent(uri);
            if (content == null) {
                return locations;
            }
            Map<String, String> contentCache = new HashMap<>();
            Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
            contentCache.put(uri, content);
            lineIndexCache.put(uri, PositionUtils.buildLineIndex(content));
            int offset = positionToOffset(content, position);
            String word = extractWordAt(content, offset);
            GroovyLanguageServerPlugin.logInfo("[definition] codeSelect at offset " + offset
                    + " word='" + word + "' workingCopy=" + workingCopy.getClass().getName());

            IJavaElement[] elements = documentManager.cachedCodeSelect(workingCopy, offset);
            if (elements != null) {
                resolveElementLocations(
                        elements, locations, contentCache, lineIndexCache, sourceLookupContext);
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Definition JDT failed for " + uri + ", falling back to AST", e);
        }
        return locations;
    }

    /**
     * Convert resolved JDT elements to locations.
     */
    private void resolveElementLocations(IJavaElement[] elements, List<Location> locations) {
        resolveElementLocations(elements, locations, new HashMap<>(), new HashMap<>());
    }

    private void resolveElementLocations(IJavaElement[] elements,
            List<Location> locations,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        resolveElementLocations(elements, locations, contentCache, lineIndexCache, null);
    }

    private void resolveElementLocations(IJavaElement[] elements,
            List<Location> locations,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache,
            SourceLookupContext sourceLookupContext) {
        for (IJavaElement element : elements) {
            GroovyLanguageServerPlugin.logInfo("[definition] resolved: "
                    + element.getElementName() + " (" + element.getClass().getName() + ")");
            Location location = toLocation(element, contentCache, lineIndexCache, sourceLookupContext);
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

    /**
     * Convert a JDT element to its declaration location.
     * Handles both workspace source files and binary types from JARs.
     */
    private Location toLocation(IJavaElement element) {
        return toLocation(element, new HashMap<>(), new HashMap<>());
    }

    private Location toLocation(IJavaElement element,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        return toLocation(element, contentCache, lineIndexCache, null);
    }

    private Location toLocation(IJavaElement element,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache,
            SourceLookupContext sourceLookupContext) {
        try {
            IJavaElement remappedElement = documentManager.remapToWorkingCopyElement(element);
            if (remappedElement != null) {
                element = remappedElement;
            }

            Location resourceLoc = toLocationFromResource(element, contentCache, lineIndexCache);
            if (resourceLoc != null) {
                return resourceLoc;
            }

            IType type = resolveElementType(element);
            if (type == null) {
                return null;
            }

            Location typeLocation = resolveLocationForType(type, sourceLookupContext);
            if (typeLocation == null) {
                return null;
            }

            if (!(element instanceof IType) && typeLocation.getUri() != null
                    && typeLocation.getUri().startsWith("groovy-source:")) {
                String source = SourceJarHelper.resolveSourceContent(typeLocation.getUri());
                if (source != null && !source.isEmpty()) {
                    Range memberRange = BinaryTypeLocationResolver.findDeclarationRange(
                            source, element, element.getElementName());
                    return new Location(typeLocation.getUri(), memberRange);
                }
            }

            return typeLocation;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("Failed to resolve location for " + element.getElementName(), e);
            return null;
        }
    }

    /**
     * Try to resolve a location from the element's workspace resource (source file in the project).
     * Only returns locations for actual source files (.java, .groovy), never for .class files.
     */
    private Location toLocationFromResource(IJavaElement element) throws JavaModelException {
        return toLocationFromResource(element, new HashMap<>(), new HashMap<>());
    }

    private Location toLocationFromResource(IJavaElement element,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) throws JavaModelException {
        IResource resource = element.getResource();
        if (resource == null) {
            ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu != null) {
                resource = cu.getResource();
            }
        }

        String targetUri = documentManager.resolveElementUri(element);
        if (targetUri != null) {
            String name = resource != null ? resource.getName() : null;
            // Skip binary .class files — we want source files only
            if (name != null && name.endsWith(".class")) {
                return null;
            }
            Range range = new Range(new Position(0, 0), new Position(0, 0));
            if (element instanceof ISourceReference sourceRef) {
                ISourceRange nameRange = sourceRef.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0) {
                    range = offsetRangeToLspRange(
                            targetUri, resource, nameRange, contentCache, lineIndexCache);
                }
            }
            return new Location(targetUri, range);
        }

        return null;
    }

    /**
     * Extract the {@link IType} from an element — either it is a type, or we get its declaring type.
     */
    private IType resolveElementType(IJavaElement element) {
        if (element instanceof IType itype) {
            return itype;
        }
        // For methods/fields inside a binary type, get their declaring type
        IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
        if (ancestor instanceof IType ancestorType) {
            return ancestorType;
        }
        return null;
    }

    /**
     * Try all strategies to resolve a location for a binary type using real source only.
     */
    private Location resolveLocationForType(IType type) {
        return resolveLocationForType(type, null);
    }

    private Location resolveLocationForType(IType type, SourceLookupContext sourceLookupContext) {
        String fqn = SourceJarHelper.binaryTypeFqn(type);
        GroovyLanguageServerPlugin.logInfo("[definition] Binary type: " + fqn
                + " — searching for source");

        // 1) Fast, targeted: derive source from the binary type's classpath entry.
        //    Works for sibling project build outputs (build/classes/, build/libs/)
        //    and project output folders (bin/).
        Location binaryDerivedLoc = findSourceFromBinaryRoot(type, fqn, sourceLookupContext);
        if (binaryDerivedLoc != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[definition] Found source via binary root: " + binaryDerivedLoc.getUri());
            return binaryDerivedLoc;
        }

        // 2) Broader: search all workspace projects via Eclipse resource model.
        Location workspaceLoc = findSourceInWorkspace(fqn, sourceLookupContext);
        if (workspaceLoc != null) {
            GroovyLanguageServerPlugin.logInfo("[definition] Found source in workspace: "
                    + workspaceLoc.getUri());
            return workspaceLoc;
        }

        Location binarySourceLoc = BinaryTypeLocationResolver.resolveLocation(
                type, fqn, type.getElementName());
        if (binarySourceLoc != null) {
            return binarySourceLoc;
        }

        GroovyLanguageServerPlugin.logInfo(
                "[definition] No source found for binary type " + fqn);
        return null;
    }

    /**
     * Try to find a sources JAR and read the source directly from it.
     */
    private Location toLocationFromSourcesJar(IType type, String fqn) {
        return BinaryTypeLocationResolver.toLocationFromSourcesJar(type, fqn, type.getElementName());
    }

    /**
     * Try to get source via JDT source attachment (might already be set on the class file).
     */
    private Location toLocationFromJdtAttachment(IType type, String fqn) {
        return BinaryTypeLocationResolver.toLocationFromJdtAttachment(type, fqn, type.getElementName());
    }

    /**
     * Last resort: generate a class stub and build a virtual URI for it.
     */
    private Location toLocationFromStub(IType type, String fqn) {
        String stub = generateClassStub(type);
        if (stub != null) {
            GroovyLanguageServerPlugin.logInfo(
                    "[definition] Generated stub for " + fqn);
            String virtualUri = SourceJarHelper.buildGroovySourceUri(
                    fqn, EXT_GROOVY, null, false, stub);
            return new Location(virtualUri,
                    new Range(new Position(0, 0), new Position(0, 0)));
        }
        return null;
    }

    /**
     * Derive the source file location from the binary type's package fragment root.
     * <p>
     * Strategy 1: For external roots (library entries pointing to build/classes/ or
     * build/libs/), resolve the absolute filesystem path, walk up past "build" to
     * find the project root, and check standard source dirs on disk.
     * <p>
     * Strategy 2: For workspace-internal roots (e.g., a project's /bin output),
     * find the owning IProject's linked folder and check source dirs on disk.
     */
    private Location findSourceFromBinaryRoot(IType type, String fqn) {
        return findSourceFromBinaryRoot(type, fqn, null);
    }

    private Location findSourceFromBinaryRoot(IType type, String fqn,
            SourceLookupContext sourceLookupContext) {
        return getCachedLocation(sourceLookupContext, "binary-root:" + fqn,
                () -> findSourceFromBinaryRootUncached(type, fqn, sourceLookupContext));
    }

    private Location findSourceFromBinaryRootUncached(IType type, String fqn,
            SourceLookupContext sourceLookupContext) {
        try {
            IPackageFragmentRoot pfr =
                    (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (pfr == null) return null;

            org.eclipse.core.runtime.IPath pfrPath = pfr.getPath();
            if (pfrPath == null) return null;

            String pathSuffix = fqn.replace('.', '/');

            // Strategy 1: External library entry (absolute filesystem path).
            // Covers build/classes/java/main and build/libs/*.jar from sibling projects.
            java.io.File binaryFile = resolveExternalPath(pfr);
            if (binaryFile != null) {
                GroovyLanguageServerPlugin.logInfo(
                        "[definition] Binary root path: " + binaryFile.getAbsolutePath());
                java.io.File projectRoot = deriveProjectRoot(binaryFile);
                if (projectRoot != null) {
                    GroovyLanguageServerPlugin.logInfo(
                            "[definition] Derived project root: " + projectRoot.getAbsolutePath());
                    Location loc = findSourceOnDisk(projectRoot, pathSuffix, sourceLookupContext);
                    if (loc != null) return loc;
                }
            }

            // Strategy 2: Workspace-internal root (e.g., project /bin output).
            // Use the owning project's linked folder to find source on disk.
            IProject owningProject = pfr.getJavaProject() != null
                    ? pfr.getJavaProject().getProject() : null;
            if (owningProject != null && owningProject.isOpen()) {
                Location loc = searchLinkedFoldersOnDisk(owningProject, pathSuffix, sourceLookupContext);
                if (loc != null) return loc;
            }

            return null;
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError(
                    "[definition] Error in findSourceFromBinaryRoot for " + fqn, e);
            return null;
        }
    }

    /**
     * Resolve the absolute filesystem path for a package fragment root.
     * Returns null if the root is workspace-internal (no external path).
     */
    private java.io.File resolveExternalPath(IPackageFragmentRoot pfr) {
        // For external roots, getPath() is already an absolute filesystem path
        if (pfr.isExternal()) {
            return pfr.getPath().toFile();
        }
        // For workspace-internal roots, try to get the real filesystem location
        // via the resource model
        if (pfr.getResource() != null && pfr.getResource().getLocation() != null) {
            return pfr.getResource().getLocation().toFile();
        }
        return null;
    }

    /**
     * Search a project's linked folders for source files on disk.
     * Each linked folder may point to a different filesystem directory.
     */
    private Location searchLinkedFoldersOnDisk(IProject project, String pathSuffix) {
        return searchLinkedFoldersOnDisk(project, pathSuffix, null);
    }

    private Location searchLinkedFoldersOnDisk(IProject project, String pathSuffix,
            SourceLookupContext sourceLookupContext) {
        try {
            for (IResource member : project.members()) {
                if (member instanceof org.eclipse.core.resources.IFolder folder) {
                    org.eclipse.core.runtime.IPath loc = folder.getLocation();
                    if (loc != null) {
                        Location result = findSourceOnDisk(loc.toFile(), pathSuffix, sourceLookupContext);
                        if (result != null) return result;
                    }
                }
            }
        } catch (CoreException e) {
            // ignore — best effort
        }
        return null;
    }

    /**
     * Walk up from a binary output directory or JAR to find the project root.
     * Recognizes both Gradle output ("build") and Eclipse output ("bin").
     */
    private java.io.File deriveProjectRoot(java.io.File path) {
        java.io.File current = path;
        for (int i = 0; i < 10 && current != null; i++) {
            String name = current.getName();
            if ("build".equals(name) || "bin".equals(name)) {
                return current.getParentFile();
            }
            current = current.getParentFile();
        }
        return null;
    }

    private Location findSourceInWorkspace(String fqn) {
        return findSourceInWorkspace(fqn, null);
    }

    private Location findSourceInWorkspace(String fqn, SourceLookupContext sourceLookupContext) {
        return getCachedLocation(sourceLookupContext, "workspace:" + fqn,
                () -> findSourceInWorkspaceUncached(fqn));
    }

    private Location findSourceInWorkspaceUncached(String fqn) {
        try {
            String pathSuffix = fqn.replace('.', '/');
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen()) continue;
                Location loc = searchProjectForSource(project, pathSuffix);
                if (loc != null) {
                    return loc;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to search workspace for " + fqn, e);
        }
        return null;
    }

    private static final String[] SRC_PREFIXES = {
        "src/main/groovy/", "src/test/groovy/",
        "src/main/java/", "src/test/java/",
        "src/"
    };

    private Location searchProjectForSource(IProject project, String pathSuffix) throws CoreException {
        String[] extensions = {EXT_GROOVY, EXT_JAVA};
        for (String ext : extensions) {
            Location loc = findFileInSourceDirs(project, "", pathSuffix, ext);
            if (loc != null) {
                return loc;
            }
            // Also look in subproject directories
            for (IResource member : project.members()) {
                if (member instanceof org.eclipse.core.resources.IFolder) {
                    loc = findFileInSourceDirs(project, member.getName() + "/", pathSuffix, ext);
                    if (loc != null) {
                        return loc;
                    }
                }
            }
        }
        return null;
    }

    private Location findFileInSourceDirs(IProject project, String prefix, String pathSuffix, String ext) {
        for (String srcPrefix : SRC_PREFIXES) {
            IFile file = project.getFile(prefix + srcPrefix + pathSuffix + ext);
            if (file != null && file.exists()) {
                String targetUri = file.getLocationURI().toString();
                return new Location(targetUri,
                        new Range(new Position(0, 0), new Position(0, 0)));
            }
        }
        return null;
    }

    private Location findSourceOnDisk(java.io.File projectDir, String pathSuffix) {
        return findSourceOnDisk(projectDir, pathSuffix, null);
    }

    private Location findSourceOnDisk(java.io.File projectDir, String pathSuffix,
            SourceLookupContext sourceLookupContext) {
        String basePath = (projectDir != null) ? projectDir.getAbsolutePath() : "<null>";
        return getCachedLocation(sourceLookupContext,
                "disk:" + basePath + ":" + pathSuffix,
                () -> findSourceOnDiskUncached(projectDir, pathSuffix));
    }

    private Location findSourceOnDiskUncached(java.io.File projectDir, String pathSuffix) {
        if (projectDir == null || !projectDir.isDirectory()) return null;
        String[] extensions = {EXT_GROOVY, EXT_JAVA};
        for (String ext : extensions) {
            for (String srcPrefix : SRC_PREFIXES) {
                java.io.File candidate = new java.io.File(projectDir, srcPrefix + pathSuffix + ext);
                if (candidate.isFile()) {
                    String targetUri = candidate.toURI().toString();
                    return new Location(targetUri,
                            new Range(new Position(0, 0), new Position(0, 0)));
                }
            }
        }
        return null;
    }

    /**
     * Convert a JDT source range (offset/length) to an LSP range (line/column).
     * Reads the target file content to compute the conversion.
     */
    private Range offsetRangeToLspRange(String uri, IResource resource, ISourceRange sourceRange) {
        return offsetRangeToLspRange(uri, resource, sourceRange, new HashMap<>(), new HashMap<>());
    }

    private Range offsetRangeToLspRange(String uri,
            IResource resource,
            ISourceRange sourceRange,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        try {
            String content = getContent(uri, resource, contentCache);
            if (content == null) {
                return new Range(new Position(0, 0), new Position(0, 0));
            }

            int startOffset = sourceRange.getOffset();
            int endOffset = startOffset + sourceRange.getLength();

            PositionUtils.LineIndex lineIndex = lineIndexFor(uri, content, lineIndexCache);
            Position start = lineIndex.offsetToPosition(startOffset);
            Position end = lineIndex.offsetToPosition(endOffset);

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
    private List<Location> getDefinitionFromTempSourceFile(String uri, Position position,
            SourceLookupContext sourceLookupContext) {
        // Only handle groovy-source: virtual documents (handle any normalization form)
        if (!uri.startsWith("groovy-source:")) return Collections.emptyList();

        String content = documentManager.getContent(uri);
        if (content == null) {
            // Fallback: try SourceJarHelper cache by FQN extracted from URI
            String fqn = SourceJarHelper.extractFqnFromUri(uri);
            if (fqn != null) {
                content = SourceJarHelper.getCachedContent(fqn);
            }
        }
        if (content == null) return Collections.emptyList();

        int offset = positionToOffset(content, position);
        String word = extractWordAt(content, offset);
        if (word == null || word.isEmpty()) return Collections.emptyList();

        GroovyLanguageServerPlugin.logInfo(
                "[definition] Virtual source navigation: word='" + word + "' in " + uri);

        // Try to resolve FQN from imports
        String fqn = resolveTypeFromSource(content, word, sourceLookupContext);
        if (fqn == null) return Collections.emptyList();

        GroovyLanguageServerPlugin.logInfo("[definition] Resolved to FQN: " + fqn);

        // Try to find source for this FQN
        List<Location> locations = new ArrayList<>();
        Location loc = navigateToFqn(fqn, word, sourceLookupContext);
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
        return resolveTypeFromSource(content, simpleName, null);
    }

    private String resolveTypeFromSource(String content, String simpleName,
            SourceLookupContext sourceLookupContext) {
        String[] lines = content.split("\n");
        String packageName = parsePackageName(lines);

        String fromImports = resolveFromImports(lines, simpleName, sourceLookupContext);
        if (fromImports != null) {
            return fromImports;
        }

        return resolveFromContext(lines, packageName, simpleName, sourceLookupContext);
    }

    private String parsePackageName(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.substring(8).replace(";", "").trim();
            }
        }
        return null;
    }

    private String resolveFromImports(String[] lines, String simpleName) {
        return resolveFromImports(lines, simpleName, null);
    }

    private String resolveFromImports(String[] lines, String simpleName,
            SourceLookupContext sourceLookupContext) {
        for (String line : lines) {
            String result = tryResolveImportLine(line.trim(), simpleName, sourceLookupContext);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private String tryResolveImportLine(String trimmed, String simpleName) {
        return tryResolveImportLine(trimmed, simpleName, null);
    }

    private String tryResolveImportLine(String trimmed, String simpleName,
            SourceLookupContext sourceLookupContext) {
        if (!trimmed.startsWith("import ")) {
            return null;
        }
        String importLine = trimmed.substring(7).replace(";", "").trim();
        if (importLine.startsWith(STATIC_PREFIX)) {
            // Handle static imports: "import static org.junit.Assert.assertEquals"
            String staticPart = importLine.substring(STATIC_PREFIX.length()).trim();
            // Check if simpleName matches the member name (last segment)
            if (staticPart.endsWith("." + simpleName)) {
                int lastDot = staticPart.lastIndexOf('.');
                if (lastDot > 0) {
                    return staticPart.substring(0, lastDot);
                }
            }
            // Check if simpleName matches the class name
            int lastDot = staticPart.lastIndexOf('.');
            if (lastDot > 0) {
                String classPart = staticPart.substring(0, lastDot);
                if (classPart.endsWith("." + simpleName) || classPart.equals(simpleName)) {
                    return classPart;
                }
            }
            return null;
        }
        if (importLine.endsWith("." + simpleName)) {
            return importLine;
        }
        if (importLine.endsWith(".*")) {
            String pkg = importLine.substring(0, importLine.length() - 2);
            String candidateFqn = pkg + "." + simpleName;
            if (canResolveSource(candidateFqn, sourceLookupContext)) {
                return candidateFqn;
            }
        }
        return null;
    }

    private String resolveFromContext(String[] lines, String packageName, String simpleName) {
        return resolveFromContext(lines, packageName, simpleName, null);
    }

    private String resolveFromContext(String[] lines, String packageName, String simpleName,
            SourceLookupContext sourceLookupContext) {
        if (packageName != null) {
            String samePkg = packageName + "." + simpleName;
            if (canResolveSource(samePkg, sourceLookupContext)) {
                return samePkg;
            }
        }

        String javaLang = "java.lang." + simpleName;
        if (canResolveSource(javaLang, sourceLookupContext)) {
            return javaLang;
        }

        return resolveFromExtendsClause(lines, simpleName);
    }

    private String resolveFromExtendsClause(String[] lines, String simpleName) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("extends ") || trimmed.contains("implements ")) {
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
        return canResolveSource(fqn, null);
    }

    private boolean canResolveSource(String fqn, SourceLookupContext sourceLookupContext) {
        if (sourceLookupContext == null) {
            return canResolveSourceUncached(fqn, null);
        }

        Boolean cached = sourceLookupContext.canResolveSourceCache.get(fqn);
        if (cached != null) return cached;
        boolean result = canResolveSourceUncached(fqn, sourceLookupContext);
        sourceLookupContext.canResolveSourceCache.put(fqn, result);
        return result;
    }

    private boolean canResolveSourceUncached(String fqn, SourceLookupContext sourceLookupContext) {
        // Use JDT's indexed type search — covers JDK, workspace source, and
        // binary types on the classpath.  Much faster than manual filesystem
        // scanning or opening src.zip.

        // Try the project that last resolved successfully first
        IProject remembered = currentDefinitionProject;
        if (remembered != null && remembered.isOpen()) {
            try {
                org.eclipse.jdt.core.IJavaProject jp = JavaCore.create(remembered);
                if (jp != null && jp.exists() && findTypeCached(jp, fqn, sourceLookupContext) != null) {
                    return true;
                }
            } catch (Exception e) { /* ignore */ }
        }

        // Check remaining projects
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen() || project.equals(remembered)) continue;
                org.eclipse.jdt.core.IJavaProject javaProject = JavaCore.create(project);
                if (javaProject != null && javaProject.exists()) {
                    IType type = findTypeCached(javaProject, fqn, sourceLookupContext);
                    if (type != null) {
                        currentDefinitionProject = project;
                        return true;
                    }
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
        return navigateToFqn(fqn, simpleName, null);
    }

    private Location navigateToFqn(String fqn, String simpleName,
            SourceLookupContext sourceLookupContext) {
        Location wsLoc = findSourceInWorkspace(fqn, sourceLookupContext);
        if (wsLoc != null) {
            return wsLoc;
        }

        Location jdtLoc = navigateViaJdtProject(fqn, simpleName, sourceLookupContext);
        if (jdtLoc != null) {
            return jdtLoc;
        }

        return navigateViaJdkSource(fqn, simpleName);
    }

    private Location navigateViaJdtProject(String fqn, String simpleName) {
        return navigateViaJdtProject(fqn, simpleName, null);
    }

    private Location navigateViaJdtProject(String fqn, String simpleName,
            SourceLookupContext sourceLookupContext) {
        try {
            // Try the project that last succeeded first to avoid iterating all 50+.
            IProject remembered = currentDefinitionProject;
            if (remembered != null && remembered.isOpen()) {
                Location loc = navigateViaProject(remembered, fqn, simpleName, sourceLookupContext);
                if (loc != null) return loc;
            }

            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (!project.isOpen() || project.equals(remembered)) continue;
                Location loc = navigateViaProject(project, fqn, simpleName, sourceLookupContext);
                if (loc != null) {
                    currentDefinitionProject = project;
                    return loc;
                }
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to resolve FQN: " + fqn, e);
        }
        return null;
    }

    private Location navigateViaProject(IProject project, String fqn, String simpleName) throws JavaModelException {
        return navigateViaProject(project, fqn, simpleName, null);
    }

    private Location navigateViaProject(IProject project, String fqn, String simpleName,
            SourceLookupContext sourceLookupContext) throws JavaModelException {
        org.eclipse.jdt.core.IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) {
            return null;
        }
        IType type = findTypeCached(javaProject, fqn, sourceLookupContext);
        if (type == null) {
            return null;
        }

        return BinaryTypeLocationResolver.resolveLocation(type, fqn, simpleName);
    }

    private Location navigateTypeFromSourcesJar(IType type, String fqn, String simpleName) {
        return BinaryTypeLocationResolver.toLocationFromSourcesJar(type, fqn, simpleName);
    }

    private Location navigateViaJdkSource(String fqn, String simpleName) {
        String jdkSource = SourceJarHelper.readSourceFromJdkSrcZip(fqn);
        if (jdkSource == null) {
            return null;
        }
        String virtualUri = SourceJarHelper.buildGroovySourceUri(
                fqn, EXT_JAVA, null, true, jdkSource);
        Range range = BinaryTypeLocationResolver.findClassDeclarationRange(jdkSource, simpleName);
        return new Location(virtualUri, range);
    }

    /**
     * Determine whether a source entry in the JAR is Groovy or Java.
     */
    private String determineSourceExtension(java.io.File sourcesJar, String fqn) {
        return BinaryTypeLocationResolver.determineSourceExtension(sourcesJar, fqn);
    }

    /**
     * Find the range of the class declaration in source code.
     */
    private Range findClassDeclarationRange(String source, String simpleName) {
        return BinaryTypeLocationResolver.findClassDeclarationRange(source, simpleName);
    }

    // ---- Groovy AST fallback definition ----

    /**
     * Provide go-to-definition using the Groovy AST when JDT is not available.
     * Finds declarations of the symbol under the cursor within the same file.
     */
    private List<Location> getDefinitionFromGroovyAST(String uri, Position position) {
        return getDefinitionFromGroovyAST(uri, position, null);
    }

    private List<Location> getDefinitionFromGroovyAST(String uri, Position position,
            SourceLookupContext sourceLookupContext) {
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
        Location ownerResult = resolveInOwnerClass(ast, word, uri, targetLine);
        if (ownerResult != null) {
            locations.add(ownerResult);
            return locations;
        }

        // 2) Fallback: whole-file symbol scan
        Location scanResult = scanAllClassesForSymbol(ast, word, uri);
        if (scanResult != null) {
            locations.add(scanResult);
            return locations;
        }

        // 3) Check if the word is a method call after a dot — resolve via JDT
        Location dotCallResult = resolveAstDotMethodCall(
                ast, content, offset, word, uri, sourceLookupContext);
        if (dotCallResult != null) {
            locations.add(dotCallResult);
            return locations;
        }

        // 4) Check if the word is a local variable — navigate to its declaration
        Location localVarResult = resolveLocalVariableDeclaration(ast, word, uri);
        if (localVarResult != null) {
            locations.add(localVarResult);
            return locations;
        }

        // 5) Check static imports — resolve the member's containing class via JDT
        Location staticImportResult = resolveStaticImportMember(ast, word, sourceLookupContext);
        if (staticImportResult != null) {
            locations.add(staticImportResult);
            return locations;
        }

        return locations;
    }

    /**
     * Try to resolve the symbol in the enclosing (owner) class and its interfaces/traits.
     * Returns a Location if found, or null.
     */
    private Location resolveInOwnerClass(ModuleNode ast, String word, String uri, int targetLine) {
        ClassNode owner = findEnclosingClass(ast, targetLine);
        if (owner == null) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] No enclosing class found at line " + targetLine);
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[definition-ast] Enclosing class: " + owner.getName()
                + " (line " + owner.getLineNumber() + "-" + owner.getLastLineNumber() + ")"
                + " interfaces=" + (owner.getInterfaces() != null ? owner.getInterfaces().length : 0));

        Location ownerMember = findMemberDeclarationInClass(owner, word, uri);
        if (ownerMember != null) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast] Found in owner class: " + ownerMember.getUri());
            return ownerMember;
        }

        return resolveInInterfaces(owner, word, uri, ast);
    }

    /**
     * Iterate over the owner class's interfaces/traits looking for the symbol.
     * Returns a Location if found, or null.
     */
    private Location resolveInInterfaces(ClassNode owner, String word, String uri, ModuleNode ast) {
        ClassNode[] interfaces = owner.getInterfaces();
        if (interfaces == null) {
            return null;
        }

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
                return traitMember;
            }

            // Check $Trait$FieldHelper — at CONVERSION phase, trait fields
            // are moved here with mangled names like <fqn_underscored>__<fieldName>
            Location helperResult = resolveInTraitFieldHelper(ifaceDecl, word, memberUri, ast);
            if (helperResult != null) {
                return helperResult;
            }

            if (ifaceDecl.getNameWithoutPackage().equals(word)) {
                Location ifaceLoc = astNodeToLocation(memberUri, ifaceDecl);
                if (ifaceLoc != null) {
                    return ifaceLoc;
                }
            }
        }

        return null;
    }

    /**
     * Check the $Trait$FieldHelper inner class for trait field declarations and accessors.
     * Returns a Location pointing to the trait declaration if a match is found, or null.
     */
    private Location resolveInTraitFieldHelper(ClassNode ifaceDecl, String word, String memberUri, ModuleNode ast) {
        ClassNode helperNode = TraitMemberResolver.findFieldHelperNode(ifaceDecl, ast);
        if (helperNode == null) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[definition-ast] FieldHelper: "
                + helperNode.getName()
                + " fields=" + helperNode.getFields().size()
                + " methods=" + helperNode.getMethods().size());

        Location fieldResult = resolveTraitFieldHelper(helperNode, word, memberUri, ifaceDecl);
        if (fieldResult != null) {
            return fieldResult;
        }

        return resolveTraitAccessors(helperNode, word, memberUri, ifaceDecl);
    }

    /**
     * Check FieldHelper fields with demangled matching.
     * Returns a Location to the trait declaration if found, or null.
     */
    private Location resolveTraitFieldHelper(ClassNode helperNode, String word, String memberUri, ClassNode ifaceDecl) {
        for (FieldNode hField : helperNode.getFields()) {
            GroovyLanguageServerPlugin.logInfo("[definition-ast]   helper field: " + hField.getName());
            if (TraitMemberResolver.isTraitFieldMatch(hField.getName(), word)) {
                GroovyLanguageServerPlugin.logInfo("[definition-ast] Found in FieldHelper: "
                        + hField.getName() + " matches '" + word + "'");
                // Navigate to the original trait declaration, not the helper
                // The trait itself is the best target
                Location traitLoc = astNodeToLocation(memberUri, ifaceDecl);
                if (traitLoc != null) {
                    return traitLoc;
                }
            }
        }
        return null;
    }

    /**
     * Check FieldHelper methods for getter/setter/is-accessor matching.
     * Returns a Location to the trait declaration if found, or null.
     */
    private Location resolveTraitAccessors(ClassNode helperNode, String word, String memberUri, ClassNode ifaceDecl) {
        String cap = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        for (MethodNode hMethod : helperNode.getMethods()) {
            String mName = hMethod.getName();
            if (mName.equals("get" + cap) || mName.equals("set" + cap) || mName.equals("is" + cap)) {
                GroovyLanguageServerPlugin.logInfo("[definition-ast] Found accessor in FieldHelper: " + mName);
                Location traitLoc = astNodeToLocation(memberUri, ifaceDecl);
                if (traitLoc != null) {
                    return traitLoc;
                }
            }
        }
        return null;
    }

    /**
     * Whole-file scan: iterate all classes in the AST looking for a symbol matching the word.
     * Checks class names, methods, fields, properties, and inner classes.
     * Returns a Location if found, or null.
     */
    // =========================================================================
    // Dot method call resolution for go-to-definition
    // =========================================================================

    /**
     * When the cursor is on a method name after a dot (e.g., {@code foo.value()}),
     * resolve the receiver expression's type via JDT and navigate to the method's source.
     */
    private Location resolveAstDotMethodCall(ModuleNode ast, String content, int offset,
                                              String word, String uri) {
        return resolveAstDotMethodCall(ast, content, offset, word, uri, null);
    }

    private Location resolveAstDotMethodCall(ModuleNode ast, String content, int offset,
                                              String word, String uri,
                                              SourceLookupContext sourceLookupContext) {
        try {
            // Check if preceded by a dot
            int wordStart = offset;
            while (wordStart > 0 && Character.isJavaIdentifierPart(content.charAt(wordStart - 1))) {
                wordStart--;
            }
            if (wordStart <= 0 || content.charAt(wordStart - 1) != '.') {
                return null;
            }

            // Find a JDT project for type resolution
            ICompilationUnit workingCopy = documentManager.getWorkingCopy(uri);
            if (workingCopy == null) return null;

            org.eclipse.jdt.core.IJavaProject project = workingCopy.getJavaProject();
            if (project == null || !project.exists()) return null;

            // Extract the full dotted chain before the cursor word.
            // E.g., for "SomeClass.InnerClass.STATIC_MEMBER" with cursor on
            // STATIC_MEMBER, extract ["SomeClass", "InnerClass"] as receiver parts.
            int dotPos = wordStart - 1;
            List<String> receiverParts = new ArrayList<>();
            int pos = dotPos;
            while (pos >= 0) {
                int partEnd = pos; // just before the dot (or start of word)
                int partStart = partEnd - 1;
                while (partStart >= 0 && Character.isJavaIdentifierPart(content.charAt(partStart))) {
                    partStart--;
                }
                partStart++;
                if (partStart < partEnd) {
                    receiverParts.add(0, content.substring(partStart, partEnd));
                    // Check if there's another dot before this part
                    if (partStart > 0 && content.charAt(partStart - 1) == '.') {
                        pos = partStart - 2;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            IType receiverType = null;

            if (!receiverParts.isEmpty()) {
                String firstPart = receiverParts.get(0);
                GroovyLanguageServerPlugin.logInfo("[definition-ast] Dot call: receiverParts="
                        + receiverParts + " word='" + word + "'");

                // Resolve the first part as a type
                ClassNode receiverClassNode = resolveReceiverClassNode(ast, firstPart);
                if (receiverClassNode != null) {
                    receiverType = resolveClassNodeToIType(
                            receiverClassNode, ast, project, sourceLookupContext);
                }

                // Walk remaining parts as inner classes/types
                if (receiverType != null) {
                    for (int i = 1; i < receiverParts.size(); i++) {
                        IType innerType = receiverType.getType(receiverParts.get(i));
                        if (innerType != null && innerType.exists()) {
                            receiverType = innerType;
                        } else {
                            receiverType = null;
                            break;
                        }
                    }
                }
            }

            // Fallback: AST-based resolution for complex receivers like "new Foo().method()"
            if (receiverType == null) {
                receiverType = resolveReceiverTypeFromAst(
                        ast, project, offset, word, content, sourceLookupContext);
            }

            if (receiverType == null) return null;
            Map<String, String> contentCache = new HashMap<>();
            Map<String, PositionUtils.LineIndex> lineIndexCache = new HashMap<>();
            contentCache.put(uri, content);
            lineIndexCache.put(uri, PositionUtils.buildLineIndex(content));

            // If word starts with uppercase, it might be an inner class reference
            // (e.g., SomeClass.InnerClass)
            if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
                IType innerType = receiverType.getType(word);
                if (innerType != null && innerType.exists()) {
                    Location loc = toLocation(innerType, contentCache, lineIndexCache);
                    if (loc != null) return loc;
                }
            }

            // Find the method and navigate to it
            for (org.eclipse.jdt.core.IMethod method : receiverType.getMethods()) {
                if (word.equals(method.getElementName())) {
                    Location loc = toLocation(method, contentCache, lineIndexCache);
                    if (loc != null) return loc;
                }
            }

            // Check fields (e.g., SomeClass.STATIC_MEMBER or SomeClass.InnerClass.FIELD)
            for (org.eclipse.jdt.core.IField field : receiverType.getFields()) {
                if (word.equals(field.getElementName())) {
                    Location loc = toLocation(field, contentCache, lineIndexCache);
                    if (loc != null) return loc;
                }
            }

            // Check supertypes
            org.eclipse.jdt.core.ITypeHierarchy hierarchy = TypeHierarchyCache.getSupertypeHierarchy(receiverType);
            if (hierarchy != null) {
                for (IType superType : hierarchy.getAllSupertypes(receiverType)) {
                    for (org.eclipse.jdt.core.IMethod method : superType.getMethods()) {
                        if (word.equals(method.getElementName())) {
                            Location loc = toLocation(method, contentCache, lineIndexCache);
                            if (loc != null) return loc;
                        }
                    }
                    for (org.eclipse.jdt.core.IField field : superType.getFields()) {
                        if (word.equals(field.getElementName())) {
                            Location loc = toLocation(field, contentCache, lineIndexCache);
                            if (loc != null) return loc;
                        }
                    }
                }
            }

            Location generatedAccessorLoc = resolveGeneratedAccessorLocation(
                    receiverType, word, contentCache, lineIndexCache);
            if (generatedAccessorLoc != null) {
                return generatedAccessorLoc;
            }
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition-ast] resolveAstDotMethodCall failed", e);
        }
        return null;
    }

    private Location resolveGeneratedAccessorLocation(IType receiverType, String memberName)
            throws JavaModelException {
        return resolveGeneratedAccessorLocation(
                receiverType, memberName, new HashMap<>(), new HashMap<>());
    }

    private Location resolveGeneratedAccessorLocation(IType receiverType,
            String memberName,
            Map<String, String> contentCache,
            Map<String, PositionUtils.LineIndex> lineIndexCache)
            throws JavaModelException {
        org.eclipse.jdt.core.IMethod generatedMethod = GeneratedAccessorResolver.findMethod(receiverType, memberName);
        if (generatedMethod != null) {
            IType declaringType = generatedMethod.getDeclaringType();
            if (declaringType != null) {
                return toLocation(declaringType, contentCache, lineIndexCache);
            }
        }

        JavaRecordSourceSupport.RecordComponentInfo component =
                GeneratedAccessorResolver.findRecordComponent(receiverType, memberName);
        if (component != null) {
            return toLocation(receiverType, contentCache, lineIndexCache);
        }

        return null;
    }

    private String getContent(String uri, IResource resource, Map<String, String> contentCache) {
        if (contentCache.containsKey(uri)) {
            return contentCache.get(uri);
        }

        String content = documentManager.getContent(uri);
        if (content == null && resource instanceof org.eclipse.core.resources.IFile ifile) {
            try (java.io.InputStream is = ifile.getContents()) {
                content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                content = null;
            }
        }

        contentCache.put(uri, content);
        return content;
    }

    private PositionUtils.LineIndex lineIndexFor(String uri,
            String content,
            Map<String, PositionUtils.LineIndex> lineIndexCache) {
        return lineIndexCache.computeIfAbsent(
                uri,
                ignored -> PositionUtils.buildLineIndex(content));
    }

    /**
     * Walk the AST to find a MethodCallExpression at the given offset whose method
     * name matches {@code methodName}, then resolve the receiver's type via JDT.
     * This handles complex receiver expressions like {@code new Foo().method()}.
     */
    private IType resolveReceiverTypeFromAst(ModuleNode ast, org.eclipse.jdt.core.IJavaProject project,
                                              int offset, String methodName, String content,
                                              SourceLookupContext sourceLookupContext) {
        MethodCallExpression found = findMethodCallAtOffset(ast, offset, methodName, content);
        if (found == null) return null;

        Expression objectExpr = found.getObjectExpression();
        ClassNode receiverClassNode = resolveObjectExpressionType(objectExpr, ast);
        if (receiverClassNode == null || "java.lang.Object".equals(receiverClassNode.getName())) {
            return null;
        }

        GroovyLanguageServerPlugin.logInfo("[definition-ast] AST fallback: receiver ClassNode='"
                + receiverClassNode.getName() + "' for method '" + methodName + "'");

        return resolveClassNodeToIType(receiverClassNode, ast, project, sourceLookupContext);
    }

    /**
     * Resolve the type of an object expression (the receiver part of a method call).
     */
    private ClassNode resolveObjectExpressionType(Expression objectExpr, ModuleNode ast) {
        if (objectExpr instanceof ConstructorCallExpression ctorCall) {
            return ctorCall.getType();
        }
        if (objectExpr instanceof VariableExpression varExpr) {
            String varName = varExpr.getName();
            if ("this".equals(varName)) return null;
            // Check local variable declarations in the AST
            for (ClassNode classNode : ast.getClasses()) {
                if (classNode.getLineNumber() < 0) continue;
                for (MethodNode method : classNode.getMethods()) {
                    ClassNode type = resolveLocalVarTypeInBlock(getBlock(method), varName);
                    if (type != null) return type;
                }
            }
            org.codehaus.groovy.ast.stmt.BlockStatement stmtBlock = ast.getStatementBlock();
            if (stmtBlock != null) {
                ClassNode type = resolveLocalVarTypeInBlock(stmtBlock, varName);
                if (type != null) return type;
            }
            ClassNode exprType = varExpr.getType();
            if (exprType != null && !"java.lang.Object".equals(exprType.getName())) {
                return exprType;
            }
        }
        if (objectExpr instanceof ClassExpression classExpr) {
            return classExpr.getType();
        }
        return null;
    }

    /**
     * Walk the AST to find a MethodCallExpression at the given offset with the given method name.
     */
    private MethodCallExpression findMethodCallAtOffset(ModuleNode module, int offset,
                                                         String methodName, String content) {
        // Convert offset to 1-based line/column for AST node matching
        Position pos = offsetToPosition(content, offset);
        int targetLine = pos.getLine() + 1;  // AST uses 1-based lines
        int targetCol = pos.getCharacter() + 1;  // AST uses 1-based columns

        final MethodCallExpression[] result = new MethodCallExpression[1];

        ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
            @Override
            protected SourceUnit getSourceUnit() {
                return module.getContext();
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (result[0] != null) return;
                String name = call.getMethodAsString();
                if (methodName.equals(name)) {
                    Expression methodExpr = call.getMethod();
                    int mLine = methodExpr.getLineNumber();
                    int mCol = methodExpr.getColumnNumber();
                    int mLastCol = methodExpr.getLastColumnNumber();
                    if (mLine == targetLine && targetCol >= mCol && targetCol <= mLastCol) {
                        result[0] = call;
                        return;
                    }
                }
                super.visitMethodCallExpression(call);
            }
        };

        for (ClassNode classNode : module.getClasses()) {
            if (result[0] != null) break;
            visitor.visitClass(classNode);
        }

        // Also check module-level statements (Groovy scripts)
        if (result[0] == null) {
            org.codehaus.groovy.ast.stmt.BlockStatement stmtBlock = module.getStatementBlock();
            if (stmtBlock != null) {
                for (Statement stmt : stmtBlock.getStatements()) {
                    if (result[0] != null) break;
                    stmt.visit(visitor);
                }
            }
        }

        return result[0];
    }

    /**
     * Resolve a receiver name to a ClassNode from the AST.
     * Handles constructor calls, local variables, fields/properties.
     */
    private ClassNode resolveReceiverClassNode(ModuleNode ast, String receiverName) {
        // Check if it's a type name (uppercase)
        if (!receiverName.isEmpty() && Character.isUpperCase(receiverName.charAt(0))) {
            for (ClassNode classNode : ast.getClasses()) {
                if (receiverName.equals(classNode.getNameWithoutPackage())) {
                    return classNode;
                }
            }
            // Could be an imported type — return a ClassNode placeholder
            for (org.codehaus.groovy.ast.ImportNode imp : ast.getImports()) {
                ClassNode impType = imp.getType();
                if (impType != null && receiverName.equals(impType.getNameWithoutPackage())) {
                    return impType;
                }
            }
        }

        // Check local variable declarations
        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            for (MethodNode method : classNode.getMethods()) {
                ClassNode type = resolveLocalVarTypeInBlock(getBlock(method), receiverName);
                if (type != null) return type;
            }
        }
        org.codehaus.groovy.ast.stmt.BlockStatement stmtBlock = ast.getStatementBlock();
        if (stmtBlock != null) {
            ClassNode type = resolveLocalVarTypeInBlock(stmtBlock, receiverName);
            if (type != null) return type;
        }

        // Check fields/properties
        for (ClassNode classNode : ast.getClasses()) {
            for (FieldNode field : classNode.getFields()) {
                if (receiverName.equals(field.getName())) return field.getType();
            }
            for (PropertyNode prop : classNode.getProperties()) {
                if (receiverName.equals(prop.getName())) return prop.getType();
            }
        }

        return null;
    }

    private org.codehaus.groovy.ast.stmt.BlockStatement getBlock(MethodNode method) {
        org.codehaus.groovy.ast.stmt.Statement code = method.getCode();
        return (code instanceof org.codehaus.groovy.ast.stmt.BlockStatement block) ? block : null;
    }

    private ClassNode resolveLocalVarTypeInBlock(org.codehaus.groovy.ast.stmt.BlockStatement block,
                                                  String varName) {
        if (block == null) return null;
        for (org.codehaus.groovy.ast.stmt.Statement stmt : block.getStatements()) {
            if (!(stmt instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement exprStmt)) continue;
            if (!(exprStmt.getExpression() instanceof org.codehaus.groovy.ast.expr.DeclarationExpression decl)) continue;
            org.codehaus.groovy.ast.expr.Expression left = decl.getLeftExpression();
            if (!(left instanceof org.codehaus.groovy.ast.expr.VariableExpression varExpr)) continue;
            if (!varName.equals(varExpr.getName())) continue;

            org.codehaus.groovy.ast.expr.Expression init = decl.getRightExpression();
            if (init instanceof org.codehaus.groovy.ast.expr.ConstructorCallExpression ctorCall) {
                return ctorCall.getType();
            }
            ClassNode originType = varExpr.getOriginType();
            if (originType != null && !"java.lang.Object".equals(originType.getName())) {
                return originType;
            }
        }
        return null;
    }

    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module,
                                           org.eclipse.jdt.core.IJavaProject project) {
        return resolveClassNodeToIType(typeNode, module, project, null);
    }

    private IType resolveClassNodeToIType(ClassNode typeNode, ModuleNode module,
                                           org.eclipse.jdt.core.IJavaProject project,
                                           SourceLookupContext sourceLookupContext) {
        if (typeNode == null || project == null) return null;
        try {
            String typeName = typeNode.getName();
            if (typeName == null || typeName.isEmpty()) return null;

            if (typeName.contains(".")) {
                IType type = findTypeCached(project, typeName, sourceLookupContext);
                if (type != null) return type;
                // Groovy AST uses '$' for inner classes, but JDT findType
                // expects '.' — try replacing '$' with '.'
                if (typeName.contains("$")) {
                    type = findTypeCached(project, typeName.replace('$', '.'), sourceLookupContext);
                    if (type != null) return type;
                }
            }

            // Check imports
            for (org.codehaus.groovy.ast.ImportNode imp : module.getImports()) {
                ClassNode impType = imp.getType();
                if (impType != null && typeName.equals(impType.getNameWithoutPackage())) {
                    IType type = findTypeCached(project, impType.getName(), sourceLookupContext);
                    if (type != null) return type;
                }
            }

            // Star imports
            for (org.codehaus.groovy.ast.ImportNode starImport : module.getStarImports()) {
                String pkgName = starImport.getPackageName();
                if (pkgName != null) {
                    IType type = findTypeCached(project, pkgName + typeName, sourceLookupContext);
                    if (type != null) return type;
                }
            }

            // Module package
            String pkg = module.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                if (pkg.endsWith(".")) pkg = pkg.substring(0, pkg.length() - 1);
                IType type = findTypeCached(project, pkg + "." + typeName, sourceLookupContext);
                if (type != null) return type;
            }

            // Auto-import packages
            String[] autoPackages = {"java.lang.", "java.util.", "java.io.",
                    "groovy.lang.", "groovy.util.", "java.math."};
            for (String autoPkg : autoPackages) {
                IType type = findTypeCached(project, autoPkg + typeName, sourceLookupContext);
                if (type != null) return type;
            }
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    // =========================================================================
    // Local variable declaration navigation
    // =========================================================================

    /**
     * Find the declaration of a local variable in the AST and navigate to it.
     */
    private Location resolveLocalVariableDeclaration(ModuleNode ast, String word, String uri) {
        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            for (MethodNode method : classNode.getMethods()) {
                Location loc = findVarDeclInBlock(getBlock(method), word, uri);
                if (loc != null) return loc;
            }
        }
        org.codehaus.groovy.ast.stmt.BlockStatement stmtBlock = ast.getStatementBlock();
        if (stmtBlock != null) {
            Location loc = findVarDeclInBlock(stmtBlock, word, uri);
            if (loc != null) return loc;
        }
        return null;
    }

    private Location findVarDeclInBlock(org.codehaus.groovy.ast.stmt.BlockStatement block,
                                         String varName, String uri) {
        if (block == null) return null;
        for (org.codehaus.groovy.ast.stmt.Statement stmt : block.getStatements()) {
            if (!(stmt instanceof org.codehaus.groovy.ast.stmt.ExpressionStatement exprStmt)) continue;
            if (!(exprStmt.getExpression() instanceof org.codehaus.groovy.ast.expr.DeclarationExpression decl)) continue;
            org.codehaus.groovy.ast.expr.Expression left = decl.getLeftExpression();
            if (!(left instanceof org.codehaus.groovy.ast.expr.VariableExpression varExpr)) continue;
            if (!varName.equals(varExpr.getName())) continue;

            if (varExpr.getLineNumber() >= 1) {
                return astNodeToLocation(uri, varExpr);
            }
        }
        return null;
    }

    /**
     * Resolve a word via static imports (e.g., {@code assertEquals} from
     * {@code import static org.junit.Assert.assertEquals}).
     * Looks up the containing class via JDT and navigates to the member.
     */
    private Location resolveStaticImportMember(ModuleNode ast, String word,
            SourceLookupContext sourceLookupContext) {
        // Check named static imports
        for (ImportNode imp : ast.getStaticImports().values()) {
            String fieldName = imp.getFieldName();
            if (fieldName != null && fieldName.equals(word)) {
                ClassNode type = imp.getType();
                if (type != null) {
                    Location loc = navigateToFqn(
                            type.getName(), type.getNameWithoutPackage(), sourceLookupContext);
                    if (loc != null) return loc;
                }
            }
        }
        // Check static star imports
        for (ImportNode imp : ast.getStaticStarImports().values()) {
            ClassNode type = imp.getType();
            if (type != null) {
                Location loc = navigateToFqn(
                        type.getName(), type.getNameWithoutPackage(), sourceLookupContext);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    private Location scanAllClassesForSymbol(ModuleNode ast, String word, String uri) {
        for (ClassNode classNode : ast.getClasses()) {
            if (classNode.getLineNumber() < 0) continue;
            Location loc = scanClassForSymbol(classNode, word, uri);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    private Location scanClassForSymbol(ClassNode classNode, String word, String uri) {
        if (classNode.getNameWithoutPackage().equals(word)) {
            Location loc = astNodeToLocation(uri, classNode);
            if (loc != null) {
                return loc;
            }
        }
        Location loc = scanMethodsForSymbol(classNode, word, uri);
        if (loc != null) return loc;
        loc = scanFieldsForSymbol(classNode, word, uri);
        if (loc != null) return loc;
        loc = scanPropertiesForSymbol(classNode, word, uri);
        if (loc != null) return loc;
        return scanInnerClassesForSymbol(classNode, word, uri);
    }

    private Location scanMethodsForSymbol(ClassNode classNode, String word, String uri) {
        for (MethodNode method : classNode.getMethods()) {
            if (method.getLineNumber() < 0) continue;
            if (method.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, method);
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private Location scanFieldsForSymbol(ClassNode classNode, String word, String uri) {
        for (FieldNode field : classNode.getFields()) {
            if (field.getLineNumber() < 0) continue;
            if (field.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, field);
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private Location scanPropertiesForSymbol(ClassNode classNode, String word, String uri) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (prop.getField() == null || prop.getField().getLineNumber() < 0) continue;
            if (prop.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, prop.getField());
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private Location scanInnerClassesForSymbol(ClassNode classNode, String word, String uri) {
        java.util.Iterator<org.codehaus.groovy.ast.InnerClassNode> innerIter = classNode.getInnerClasses();
        while (innerIter.hasNext()) {
            ClassNode inner = innerIter.next();
            if (inner.getLineNumber() < 0) continue;
            // getNameWithoutPackage() for inner classes returns "Outer$Inner",
            // so also extract the simple name after the last '$' for matching.
            String nameWithoutPkg = inner.getNameWithoutPackage();
            String simpleName = nameWithoutPkg;
            int dollarIdx = nameWithoutPkg.lastIndexOf('$');
            if (dollarIdx >= 0 && dollarIdx < nameWithoutPkg.length() - 1) {
                simpleName = nameWithoutPkg.substring(dollarIdx + 1);
            }
            if (nameWithoutPkg.equals(word) || simpleName.equals(word)) {
                Location loc = astNodeToLocation(uri, inner);
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private ClassNode findEnclosingClass(ModuleNode module, int targetLine) {
        ClassNode best = null;
        for (ClassNode classNode : module.getClasses()) {
            int start = classNode.getLineNumber();
            int end = classNode.getLastLineNumber();
            if (start > 0 && (best == null || start >= best.getLineNumber())
                    && ((end >= start && targetLine >= start && targetLine <= end)
                        || start <= targetLine)) {
                best = classNode;
            }
        }
        return best;
    }

    private Location findMemberDeclarationInClass(ClassNode classNode, String word, String uri) {
        if (classNode == null) {
            return null;
        }

        Location loc = findMethodDeclaration(classNode, word, uri);
        if (loc != null) {
            return loc;
        }
        loc = findFieldDeclaration(classNode, word, uri);
        if (loc != null) {
            return loc;
        }
        loc = findPropertyDeclaration(classNode, word, uri);
        if (loc != null) {
            return loc;
        }
        // Also check inner classes declared inside this class
        return scanInnerClassesForSymbol(classNode, word, uri);
    }

    private Location findMethodDeclaration(ClassNode classNode, String word, String uri) {
        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, method);
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private Location findFieldDeclaration(ClassNode classNode, String word, String uri) {
        for (FieldNode field : classNode.getFields()) {
            if (field.getName().equals(word)) {
                Location loc = astNodeToLocation(uri, field);
                if (loc != null) {
                    return loc;
                }
            }
        }
        return null;
    }

    private Location findPropertyDeclaration(ClassNode classNode, String word, String uri) {
        for (PropertyNode prop : classNode.getProperties()) {
            if (prop.getName().equals(word)) {
                return resolvePropertyLocation(prop, uri);
            }
        }
        return null;
    }

    private Location resolvePropertyLocation(PropertyNode prop, String uri) {
        if (prop.getField() != null) {
            Location fieldLoc = astNodeToLocation(uri, prop.getField());
            if (fieldLoc != null) {
                return fieldLoc;
            }
        }
        return astNodeToLocation(uri, prop);
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
            appendPackageDeclaration(sb, type);
            appendClassDeclaration(sb, type);
            sb.append(" {\n\n");
            appendFieldStubs(sb, type);
            appendMethodStubs(sb, type);
            sb.append("}\n");
            return sb.toString();
        } catch (Exception e) {
            GroovyLanguageServerPlugin.logError("[definition] Failed to generate stub for " + type.getElementName(), e);
            return null;
        }
    }

    private void appendPackageDeclaration(StringBuilder sb, IType type) {
        String pkg = type.getPackageFragment().getElementName();
        if (pkg != null && !pkg.isEmpty()) {
            sb.append("package ").append(pkg).append("\n\n");
        }
    }

    private void appendClassDeclaration(StringBuilder sb, IType type) throws JavaModelException {
        int flags = type.getFlags();
        if (org.eclipse.jdt.core.Flags.isPublic(flags)) sb.append("public ");
        if (org.eclipse.jdt.core.Flags.isAbstract(flags)) sb.append("abstract ");

        appendStubTypeKind(sb, type);
        sb.append(type.getElementName());
        appendStubSuperclass(sb, type);
        appendStubInterfaces(sb, type);
    }

    private void appendStubTypeKind(StringBuilder sb, IType type) throws JavaModelException {
        if (type.isInterface()) {
            sb.append("interface ");
        } else if (type.isEnum()) {
            sb.append("enum ");
        } else {
            sb.append("class ");
        }
    }

    private void appendStubSuperclass(StringBuilder sb, IType type) throws JavaModelException {
        String superclass = type.getSuperclassName();
        if (superclass != null && !"Object".equals(superclass) && !"java.lang.Object".equals(superclass)) {
            sb.append(" extends ").append(superclass);
        }
    }

    private void appendStubInterfaces(StringBuilder sb, IType type) throws JavaModelException {
        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces != null && interfaces.length > 0) {
            sb.append(type.isInterface() ? " extends " : " implements ");
            sb.append(String.join(", ", interfaces));
        }
    }

    private void appendFieldStubs(StringBuilder sb, IType type) throws JavaModelException {
        for (org.eclipse.jdt.core.IField field : type.getFields()) {
            int fflags = field.getFlags();
            if (!org.eclipse.jdt.core.Flags.isPublic(fflags)
                    && !org.eclipse.jdt.core.Flags.isProtected(fflags)) {
                continue;
            }
            sb.append("    ");
            if (org.eclipse.jdt.core.Flags.isStatic(fflags)) sb.append(STATIC_PREFIX);
            if (org.eclipse.jdt.core.Flags.isFinal(fflags)) sb.append("final ");
            sb.append(org.eclipse.jdt.core.Signature.toString(field.getTypeSignature()));
            sb.append(" ").append(field.getElementName()).append("\n");
        }
    }

    private void appendMethodStubs(StringBuilder sb, IType type) throws JavaModelException {
        for (org.eclipse.jdt.core.IMethod method : type.getMethods()) {
            int mflags = method.getFlags();
            if (!org.eclipse.jdt.core.Flags.isPublic(mflags)
                    && !org.eclipse.jdt.core.Flags.isProtected(mflags)) {
                continue;
            }
            appendSingleMethodStub(sb, method, mflags);
        }
    }

    private void appendSingleMethodStub(StringBuilder sb, org.eclipse.jdt.core.IMethod method, int mflags)
            throws JavaModelException {
        sb.append("\n    ");
        if (org.eclipse.jdt.core.Flags.isStatic(mflags)) sb.append(STATIC_PREFIX);
        if (org.eclipse.jdt.core.Flags.isAbstract(mflags)) sb.append("abstract ");

        String returnType = method.isConstructor() ? "" :
                org.eclipse.jdt.core.Signature.toString(method.getReturnType()) + " ";
        sb.append(returnType).append(method.getElementName()).append("(");

        appendStubParameters(sb, method);
        sb.append(")");
        appendStubExceptions(sb, method);
        sb.append(" { /* compiled code */ }\n");
    }

    private void appendStubParameters(StringBuilder sb, org.eclipse.jdt.core.IMethod method) throws JavaModelException {
        String[] paramNames = JdtParameterNameResolver.resolve(method);
        String[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(org.eclipse.jdt.core.Signature.toString(paramTypes[i]));
            String paramName = paramNames != null && i < paramNames.length ? paramNames[i] : "arg" + i;
            sb.append(" ").append(paramName);
        }
    }

    private void appendStubExceptions(StringBuilder sb, org.eclipse.jdt.core.IMethod method) throws JavaModelException {
        String[] exceptions = method.getExceptionTypes();
        if (exceptions != null && exceptions.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < exceptions.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(org.eclipse.jdt.core.Signature.toString(exceptions[i]));
            }
        }
    }
}
