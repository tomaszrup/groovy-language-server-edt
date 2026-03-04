package org.eclipse.groovy.ls.perf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Deterministic generator for a large synthetic Groovy workspace that
 * mirrors a real enterprise project.
 * <p>
 * Per project:
 * <ul>
 *   <li>~100 Java source files under {@code src/main/java}</li>
 *   <li>3 Groovy traits under {@code src/main/groovy}</li>
 *   <li>100 Groovy (Spock) test files under {@code src/test/groovy}</li>
 * </ul>
 * Additionally, several large stub JARs are generated in a shared
 * {@code libs/} directory and added to every project's classpath.
 * <p>
 * No Groovy source files are placed in {@code src/main/groovy} beyond
 * the traits — all production code is Java.
 */
public class WorkspaceGenerator {

    private static final int DEFAULT_PROJECT_COUNT = 50;
    private static final int JAVA_FILES_PER_PROJECT = 100;
    private static final int TRAITS_PER_PROJECT = 3;
    private static final int GROOVY_TESTS_PER_PROJECT = 100;
    private static final long SEED = 20260304L;

    /** Stub JAR specs: name → number of classes inside. */
    private static final String[][] STUB_JARS = {
            {"guava-33.4.0", "500"},
            {"spring-core-6.2.2", "400"},
            {"spring-context-6.2.2", "350"},
            {"slf4j-api-2.0.16", "60"},
            {"jackson-databind-2.18.3", "300"},
            {"commons-lang3-3.17.0", "200"},
            {"logback-classic-1.5.16", "80"},
            {"httpclient5-5.4.2", "150"},
    };

    private final int projectCount;
    private final Random rng;

    private final List<String> projectNames = new ArrayList<>();
    private final List<FileInfo> representativeFiles = new ArrayList<>();
    /** Absolute paths of generated stub JARs. */
    private final List<Path> jarPaths = new ArrayList<>();

    public WorkspaceGenerator() {
        this(DEFAULT_PROJECT_COUNT);
    }

    public WorkspaceGenerator(int projectCount) {
        this.projectCount = projectCount;
        this.rng = new Random(SEED);
    }

    public List<String> getProjectNames() {
        return projectNames;
    }

    public List<FileInfo> getRepresentativeFiles() {
        return representativeFiles;
    }

    /** Absolute paths to the stub JARs generated under {@code libs/}. */
    public List<Path> getJarPaths() {
        return jarPaths;
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    public Path generate(Path root) throws IOException {
        Files.createDirectories(root);

        // ---- Root Gradle files ----
        StringBuilder settings = new StringBuilder("rootProject.name = 'perf-workspace'\n\n");
        for (int p = 0; p < projectCount; p++) {
            projectNames.add("module-" + p);
            settings.append("include 'module-").append(p).append("'\n");
        }
        Files.writeString(root.resolve("settings.gradle"), settings.toString());
        Files.writeString(root.resolve("build.gradle"),
                "// Synthetic workspace root\nsubprojects {\n    apply plugin: 'groovy'\n}\n");

        // ---- Stub JARs ----
        Path libsDir = root.resolve("libs");
        Files.createDirectories(libsDir);
        for (String[] spec : STUB_JARS) {
            Path jar = generateStubJar(libsDir, spec[0], Integer.parseInt(spec[1]));
            jarPaths.add(jar);
        }

        // ---- Per-project generation ----
        for (int p = 0; p < projectCount; p++) {
            generateProject(root, p);
        }

        // ---- Large file for stress tests ----
        generateLargeTestFile(root);

        return root;
    }

    // ========================================================================
    //  Stub JAR generation
    // ========================================================================

    /**
     * Create a JAR containing {@code classCount} compiled stub classes.
     * Each class has a valid class-file header (Java 17 = major 61) so
     * that JDT's indexer accepts it as a real library.
     */
    private Path generateStubJar(Path libsDir, String name, int classCount) throws IOException {
        Path jarPath = libsDir.resolve(name + ".jar");
        // Package hierarchy mirrors the jar name: com.example.<artifact>
        String basePkg = "com/example/" + name.replaceAll("[^a-zA-Z0-9]", "_");

        try (OutputStream fos = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(fos)) {

            for (int i = 0; i < classCount; i++) {
                String pkg = basePkg + "/pkg" + (i / 50);
                String className = pkg + "/Stub" + i + ".class";
                jos.putNextEntry(new JarEntry(className));
                jos.write(buildMinimalClassFile(pkg.replace('/', '.') + ".Stub" + i));
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    /**
     * Emit a valid Java 17 class file with fields and methods so that
     * JDT's indexer sees realistic member counts and generic signatures.
     * <p>
     * Each stub class gets:
     * <ul>
     *   <li>3 fields with varied types (String, List&lt;T&gt;, Map&lt;K,V&gt;)</li>
     *   <li>8 methods with generic signatures, parameters, return types</li>
     * </ul>
     * This makes completion lists, hover, and search index sizes closer
     * to what a real library produces.
     */
    private byte[] buildMinimalClassFile(String fullyQualifiedName) {
        String internalName = fullyQualifiedName.replace('.', '/');

        // We build the constant pool as a list then emit it.
        // Each entry: tag byte + data.  Index 0 is unused (1-based).
        var cpEntries = new java.util.ArrayList<byte[]>();
        var utf8Index = new java.util.LinkedHashMap<String, Integer>();

        // Helper to add a Utf8 entry and return its 1-based index
        // (reuses existing entry if already present)
        class CpHelper {
            int utf8(String s) {
                return utf8Index.computeIfAbsent(s, k -> {
                    byte[] bytes = k.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var entry = new java.io.ByteArrayOutputStream(3 + bytes.length);
                    entry.write(1); // CONSTANT_Utf8
                    entry.write((bytes.length >> 8) & 0xFF);
                    entry.write(bytes.length & 0xFF);
                    entry.write(bytes, 0, bytes.length);
                    cpEntries.add(entry.toByteArray());
                    return cpEntries.size(); // 1-based
                });
            }
            int classRef(String internalClassName) {
                int nameIdx = utf8(internalClassName);
                var entry = new byte[] { 7, (byte)((nameIdx >> 8) & 0xFF), (byte)(nameIdx & 0xFF) };
                cpEntries.add(entry);
                return cpEntries.size();
            }
        }
        var cp = new CpHelper();

        // Core class/super entries
        int thisClass = cp.classRef(internalName);
        int superClass = cp.classRef("java/lang/Object");

        // ---- Field descriptors ----
        String[][] fields = {
            { "name",     "Ljava/lang/String;" },
            { "items",    "Ljava/util/List;",    "Ljava/util/List<Ljava/lang/String;>;" },
            { "metadata", "Ljava/util/Map;",     "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;" },
        };

        // ---- Method descriptors (name, descriptor, optional generic signature) ----
        String[][] methods = {
            { "getName",        "()Ljava/lang/String;",                                    null },
            { "setName",        "(Ljava/lang/String;)V",                                   null },
            { "getItems",       "()Ljava/util/List;",                                      "()Ljava/util/List<Ljava/lang/String;>;" },
            { "addItem",        "(Ljava/lang/String;)Z",                                   null },
            { "process",        "(Ljava/util/Map;)Ljava/lang/Object;",                     "(Ljava/util/Map<Ljava/lang/String;+Ljava/lang/Object;>;)Ljava/lang/Object;" },
            { "transform",      "(Ljava/util/function/Function;)Ljava/lang/Object;",       "<R:Ljava/lang/Object;>(Ljava/util/function/Function<-Ljava/lang/String;+TR;>;)TR;" },
            { "merge",          "(Ljava/util/Collection;Ljava/util/Comparator;)Ljava/util/List;",
                                "<T:Ljava/lang/Object;>(Ljava/util/Collection<+TT;>;Ljava/util/Comparator<-TT;>;)Ljava/util/List<TT;>;" },
            { "toMap",          "()Ljava/util/Map;",                                       "()Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;" },
        };

        // Pre-register all Utf8 entries we'll need
        int sigAttrName = cp.utf8("Signature");
        int codeAttrName = cp.utf8("Code");

        int[][] fieldCpIdx = new int[fields.length][];
        for (int i = 0; i < fields.length; i++) {
            fieldCpIdx[i] = new int[] {
                cp.utf8(fields[i][0]),
                cp.utf8(fields[i][1]),
                fields[i].length > 2 ? cp.utf8(fields[i][2]) : 0
            };
        }

        int[][] methodCpIdx = new int[methods.length][];
        for (int i = 0; i < methods.length; i++) {
            methodCpIdx[i] = new int[] {
                cp.utf8(methods[i][0]),
                cp.utf8(methods[i][1]),
                methods[i][2] != null ? cp.utf8(methods[i][2]) : 0
            };
        }

        int cpCount = cpEntries.size() + 1; // constant_pool_count = last index + 1
        int totalCpBytes = 0;
        for (byte[] e : cpEntries) totalCpBytes += e.length;

        // Estimate buffer size
        var buf = new java.io.ByteArrayOutputStream(256 + totalCpBytes);
        var out = new java.io.DataOutputStream(buf);

        try {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);                  // minor
            out.writeShort(61);                 // major (Java 17)
            out.writeShort(cpCount);

            for (byte[] entry : cpEntries) {
                out.write(entry);
            }

            out.writeShort(0x0021);             // ACC_PUBLIC | ACC_SUPER
            out.writeShort(thisClass);
            out.writeShort(superClass);
            out.writeShort(0);                  // interfaces_count

            // ---- Fields ----
            out.writeShort(fields.length);
            for (int i = 0; i < fields.length; i++) {
                out.writeShort(0x0002);         // ACC_PRIVATE
                out.writeShort(fieldCpIdx[i][0]); // name
                out.writeShort(fieldCpIdx[i][1]); // descriptor
                if (fieldCpIdx[i][2] != 0) {
                    out.writeShort(1);          // attributes_count = 1 (Signature)
                    out.writeShort(sigAttrName);
                    out.writeInt(2);            // attribute_length
                    out.writeShort(fieldCpIdx[i][2]); // signature index
                } else {
                    out.writeShort(0);          // no attributes
                }
            }

            // ---- Methods ----
            out.writeShort(methods.length);
            for (int i = 0; i < methods.length; i++) {
                out.writeShort(0x0001);         // ACC_PUBLIC
                out.writeShort(methodCpIdx[i][0]); // name
                out.writeShort(methodCpIdx[i][1]); // descriptor

                // Count attributes: always Code, optionally Signature
                boolean hasSig = methodCpIdx[i][2] != 0;
                out.writeShort(hasSig ? 2 : 1);

                // Minimal Code attribute: max_stack=1, max_locals=1+params,
                // code = { aconst_null, areturn } or { return } for void
                boolean isVoid = methods[i][1].endsWith(")V");
                byte[] code = isVoid
                        ? new byte[] { (byte) 0xB1 }          // return
                        : new byte[] { 0x01, (byte) 0xB0 };   // aconst_null, areturn
                out.writeShort(codeAttrName);
                out.writeInt(12 + code.length);  // attr length
                out.writeShort(1);               // max_stack
                out.writeShort(10);              // max_locals (generous)
                out.writeInt(code.length);
                out.write(code);
                out.writeShort(0);               // exception_table_length
                out.writeShort(0);               // code attributes_count

                if (hasSig) {
                    out.writeShort(sigAttrName);
                    out.writeInt(2);
                    out.writeShort(methodCpIdx[i][2]);
                }
            }

            out.writeShort(0);                  // class attributes_count
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return buf.toByteArray();
    }

    // ========================================================================
    //  Project generation
    // ========================================================================

    private void generateProject(Path root, int projIdx) throws IOException {
        String projName = "module-" + projIdx;
        String pkg = "com.example." + projName.replace('-', '_');
        Path projDir = root.resolve(projName);

        Path mainJava = projDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path mainGroovy = projDir.resolve("src/main/groovy/" + pkg.replace('.', '/'));
        Path testGroovy = projDir.resolve("src/test/groovy/" + pkg.replace('.', '/'));
        Files.createDirectories(mainJava);
        Files.createDirectories(mainGroovy);
        Files.createDirectories(testGroovy);

        // build.gradle with inter-project dependency
        Files.writeString(projDir.resolve("build.gradle"),
                "dependencies {\n"
                + (projIdx > 0
                        ? "    implementation project(':module-" + (projIdx - 1) + "')\n"
                        : "")
                + "}\n");

        // ---- ~100 Java source files ----
        for (int f = 0; f < JAVA_FILES_PER_PROJECT; f++) {
            String className = "Service" + projIdx + "_" + f;
            Files.writeString(mainJava.resolve(className + ".java"),
                    generateJavaSource(pkg, className, projIdx, f));
        }

        // ---- 3 Groovy traits ----
        for (int t = 0; t < TRAITS_PER_PROJECT; t++) {
            String traitName = "Trait" + projIdx + "_" + t;
            Files.writeString(mainGroovy.resolve(traitName + ".groovy"),
                    generateTrait(pkg, traitName, projIdx, t));

            // First trait of first 10 projects → representative
            if (t == 0 && projIdx < 10) {
                representativeFiles.add(new FileInfo(
                        mainGroovy.resolve(traitName + ".groovy"),
                        pkg + "." + traitName,
                        traitName));
            }
        }

        // ---- 100 Groovy test files ----
        for (int s = 0; s < GROOVY_TESTS_PER_PROJECT; s++) {
            String specName = "Service" + projIdx + "_" + s + "Spec";
            Files.writeString(testGroovy.resolve(specName + ".groovy"),
                    generateSpockSpec(pkg, specName, projIdx, s));
        }
    }

    // ========================================================================
    //  Java source generation (~100 per project)
    // ========================================================================

    private String generateJavaSource(String pkg, String className, int projIdx, int fileIdx) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");

        // Imports
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.HashMap;\n");
        if (projIdx > 0 && fileIdx < 10) {
            String prevPkg = "com.example.module_" + (projIdx - 1);
            sb.append("import ").append(prevPkg).append(".Service").append(projIdx - 1).append("_0;\n");
        }
        sb.append("\n");

        // Class declaration
        boolean isFinal = fileIdx % 5 == 0;
        sb.append("public ").append(isFinal ? "final " : "").append("class ").append(className).append(" {\n\n");

        // Fields (3-7 per class)
        int fieldCount = 3 + (rng.nextInt(5));
        for (int i = 0; i < fieldCount; i++) {
            String type = pickJavaType(i);
            sb.append("    private ").append(type).append(" field").append(i).append(";\n");
        }
        sb.append("\n");

        // Constructor
        sb.append("    public ").append(className).append("() {\n");
        for (int i = 0; i < fieldCount; i++) {
            sb.append("        this.field").append(i).append(" = ").append(defaultValue(i)).append(";\n");
        }
        sb.append("    }\n\n");

        // Getters/setters for first 3 fields
        for (int i = 0; i < Math.min(3, fieldCount); i++) {
            String type = pickJavaType(i);
            String cap = capitalize("field" + i);
            sb.append("    public ").append(type).append(" get").append(cap).append("() { return field").append(i).append("; }\n");
            sb.append("    public void set").append(cap).append("(").append(type).append(" val) { this.field").append(i).append(" = val; }\n\n");
        }

        // Methods (5-10 per class)
        int methodCount = 5 + rng.nextInt(6);
        for (int m = 0; m < methodCount; m++) {
            sb.append(generateJavaMethod(className, m));
        }

        // Cross-project method
        if (projIdx > 0 && fileIdx < 10) {
            sb.append("    public Object crossProjectCall() {\n");
            sb.append("        Service").append(projIdx - 1).append("_0 upstream = new Service").append(projIdx - 1).append("_0();\n");
            sb.append("        return upstream.getField0();\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String generateJavaMethod(String className, int methodIdx) {
        StringBuilder sb = new StringBuilder();
        String methodName = "process" + capitalize(pickNoun(methodIdx));
        String returnType = methodIdx % 2 == 0 ? "String" : "Object";
        String paramType = methodIdx % 3 == 0 ? "Map<String, Object>" : "String";
        String paramName = "input" + methodIdx;

        sb.append("    public ").append(returnType).append(" ").append(methodName)
          .append("(").append(paramType).append(" ").append(paramName).append(") {\n");

        if (paramType.equals("String")) {
            sb.append("        if (").append(paramName).append(" == null) return ").append(returnType.equals("String") ? "\"default\"" : "null").append(";\n");
            sb.append("        String intermediate = ").append(paramName).append(".trim().toLowerCase();\n");
            sb.append("        return \"").append(className).append("-\" + intermediate + \"-\" + field0;\n");
        } else {
            sb.append("        if (").append(paramName).append(" == null) return ").append(returnType.equals("String") ? "\"default\"" : "null").append(";\n");
            sb.append("        return ").append(paramName).append(".toString();\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    // ========================================================================
    //  Groovy trait generation (3 per project)
    // ========================================================================

    private String generateTrait(String pkg, String traitName, int projIdx, int traitIdx) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append("\n\n");
        sb.append("import java.util.List\n");
        sb.append("import java.util.Map\n");
        sb.append("import java.util.concurrent.ConcurrentHashMap\n"); // unused import for diagnostics test
        if (projIdx > 0) {
            String prevPkg = "com.example.module_" + (projIdx - 1);
            sb.append("import ").append(prevPkg).append(".Service").append(projIdx - 1).append("_0\n");
        }
        sb.append("\n");

        sb.append("trait ").append(traitName).append(" {\n\n");

        // Fields
        sb.append("    String auditLog").append(traitIdx).append(" = ''\n");
        sb.append("    List<String> entries").append(traitIdx).append(" = []\n\n");

        // Methods with closures
        sb.append("    def audit").append(traitIdx).append("(String action) {\n");
        sb.append("        auditLog").append(traitIdx).append(" += \"[${new Date()}] ${action}\\n\"\n");
        sb.append("    }\n\n");

        sb.append("    String getAuditSummary").append(traitIdx).append("() {\n");
        sb.append("        return \"Audit entries: ${auditLog").append(traitIdx).append(".readLines().size()}\"\n");
        sb.append("    }\n\n");

        sb.append("    def processItems").append(traitIdx).append("(List<String> items) {\n");
        sb.append("        def result = items.findAll { it.length() > 3 }\n");
        sb.append("            .collect { it.toUpperCase() }\n");
        sb.append("            .sort { a, b -> a <=> b }\n");
        sb.append("        return result\n");
        sb.append("    }\n\n");

        sb.append("    def computeStatistics").append(traitIdx).append("() {\n");
        sb.append("        def count = entries").append(traitIdx).append("?.size() ?: 0\n");
        sb.append("        def label = \"stats-${count}\"\n");
        sb.append("        def data = [count: count, label: label, timestamp: System.currentTimeMillis()]\n");
        sb.append("        return data\n");
        sb.append("    }\n\n");

        // Cross-project reference method
        if (projIdx > 0) {
            sb.append("    def crossProjectTraitCall").append(traitIdx).append("() {\n");
            sb.append("        def upstream = new Service").append(projIdx - 1).append("_0()\n");
            sb.append("        return upstream.getField0()\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ========================================================================
    //  Groovy Spock test generation (100 per project)
    // ========================================================================

    private String generateSpockSpec(String pkg, String specName, int projIdx, int specIdx) {
        String targetClass = "Service" + projIdx + "_" + specIdx;
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append("\n\n");
        sb.append("import spock.lang.Specification\n");
        sb.append("import spock.lang.Subject\n");
        sb.append("import java.util.List\n");
        sb.append("import java.util.Map\n");
        if (projIdx > 0 && specIdx == 0) {
            String prevPkg = "com.example.module_" + (projIdx - 1);
            sb.append("import ").append(prevPkg).append(".Service").append(projIdx - 1).append("_0\n");
        }
        sb.append("\n");

        sb.append("class ").append(specName).append(" extends Specification {\n\n");
        sb.append("    @Subject\n");
        sb.append("    ").append(targetClass).append(" service = new ").append(targetClass).append("()\n\n");

        // Generate 5 test methods per spec
        for (int t = 0; t < 5; t++) {
            String noun = pickNoun(t);
            sb.append("    def 'should ").append(noun.toLowerCase()).append(" correctly (#").append(t).append(")'() {\n");
            sb.append("        given:\n");
            sb.append("        def input = '").append(noun.toLowerCase()).append("-test-value'\n\n");
            sb.append("        when:\n");
            sb.append("        def result = service.process").append(capitalize(noun)).append("(input)\n\n");
            sb.append("        then:\n");
            sb.append("        result != null\n");
            if (t % 2 == 0) {
                sb.append("        result.toString().contains('").append(noun.toLowerCase()).append("')\n");
            }
            sb.append("    }\n\n");
        }

        // Data-driven test
        sb.append("    def 'should handle various inputs for #methodName'() {\n");
        sb.append("        expect:\n");
        sb.append("        service.processData(null) != null || true\n\n");
        sb.append("        where:\n");
        sb.append("        methodName << ['processData', 'processRequest', 'processResponse']\n");
        sb.append("    }\n\n");

        // Test that exercises closures and string interpolation
        sb.append("    def 'should compute statistics'() {\n");
        sb.append("        given:\n");
        sb.append("        def items = ['alpha', 'beta', 'gamma', 'delta', 'ab']\n\n");
        sb.append("        when:\n");
        sb.append("        def filtered = items.findAll { it.length() > 3 }\n");
        sb.append("        def mapped = filtered.collect { it.toUpperCase() }\n\n");
        sb.append("        then:\n");
        sb.append("        mapped.size() >= 1\n");
        sb.append("        mapped.every { it == it.toUpperCase() }\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    // ========================================================================
    //  Large file for scaling benchmarks
    // ========================================================================

    private void generateLargeTestFile(Path root) throws IOException {
        Path dir = root.resolve("module-0/src/test/groovy/com/example/module_0");
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("package com.example.module_0\n\n");
        sb.append("import spock.lang.Specification\n");
        sb.append("import java.util.List\n");
        sb.append("import java.util.Map\n");
        sb.append("import java.util.stream.Collectors\n\n");
        sb.append("/**\n");
        sb.append(" * Extra-large generated Spock specification for stress-testing\n");
        sb.append(" * semantic tokens, folding ranges, and document symbols on\n");
        sb.append(" * files with 500+ lines.\n");
        sb.append(" */\n");
        sb.append("class LargeServiceSpec extends Specification {\n\n");

        // Generate 50 test methods × ~10 lines each ≈ 500 lines
        for (int m = 0; m < 50; m++) {
            sb.append("    def 'operation ").append(m).append(" should process correctly'() {\n");
            sb.append("        given:\n");
            sb.append("        def items = (1..").append(10 + m).append(").collect { \"item-${it}\" }\n\n");
            sb.append("        when:\n");
            sb.append("        def filtered = items.findAll { it.length() > 5 }\n");
            sb.append("        def mapped = filtered.collect { it.toUpperCase() }\n");
            sb.append("        def result = mapped.inject('') { acc, val -> acc + val + ',' }\n\n");
            sb.append("        then:\n");
            sb.append("        result != null\n");
            sb.append("        mapped.size() >= 1\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");

        Path file = dir.resolve("LargeServiceSpec.groovy");
        Files.writeString(file, sb.toString());

        representativeFiles.add(new FileInfo(file, "com.example.module_0.LargeServiceSpec", "LargeServiceSpec"));
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private String pickJavaType(int idx) {
        return switch (idx % 5) {
            case 0 -> "String";
            case 1 -> "int";
            case 2 -> "List<String>";
            case 3 -> "Object";
            case 4 -> "Map<String, Object>";
            default -> "String";
        };
    }

    private String defaultValue(int idx) {
        return switch (idx % 5) {
            case 0 -> "\"\"";
            case 1 -> "0";
            case 2 -> "new ArrayList<>()";
            case 3 -> "null";
            case 4 -> "new HashMap<>()";
            default -> "null";
        };
    }

    private static final String[] NOUNS = {
            "Data", "Request", "Response", "Event", "Message",
            "Record", "Payload", "Config", "State", "Context"
    };

    private String pickNoun(int idx) {
        return NOUNS[idx % NOUNS.length];
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ========================================================================
    //  Data record
    // ========================================================================

    /**
     * Metadata about a generated Groovy file — used by benchmarks to pick targets.
     */
    public record FileInfo(Path path, String fullyQualifiedClass, String simpleClassName) {

        public String toUri() {
            return path.toUri().toString();
        }

        public String readContent() throws IOException {
            return Files.readString(path);
        }
    }
}
