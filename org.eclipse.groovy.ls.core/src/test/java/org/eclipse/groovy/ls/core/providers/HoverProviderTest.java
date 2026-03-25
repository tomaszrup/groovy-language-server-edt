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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for pure-text and AST utility methods in {@link HoverProvider}.
 */
class HoverProviderTest {

    private HoverProvider provider;
    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @BeforeEach
    void setUp() {
        provider = new HoverProvider(new DocumentManager());
    }

    // ---- positionToOffset ----

    @Test
    void positionToOffsetFirstLine() throws Exception {
        assertEquals(3, invokePositionToOffset("hello\nworld", new Position(0, 3)));
    }

    @Test
    void positionToOffsetSecondLine() throws Exception {
        assertEquals(9, invokePositionToOffset("hello\nworld", new Position(1, 3)));
    }

    @Test
    void positionToOffsetClamped() throws Exception {
        assertEquals(2, invokePositionToOffset("hi", new Position(0, 99)));
    }

    // ---- simpleName ----

    @Test
    void simpleNameFromFqn() throws Exception {
        assertEquals("String", invokeSimpleName("java.lang.String"));
    }

    @Test
    void simpleNameAlreadySimple() throws Exception {
        assertEquals("Foo", invokeSimpleName("Foo"));
    }

    @Test
    void simpleNameNull() throws Exception {
        assertNull(invokeSimpleName(null));
    }

    // ---- extractWordAt ----

    @Test
    void extractWordAtMiddle() throws Exception {
        assertEquals("world", invokeExtractWordAt("hello world", 7));
    }

    @Test
    void extractWordAtStart() throws Exception {
        assertEquals("hello", invokeExtractWordAt("hello world", 0));
    }

    @Test
    void extractWordAtNonIdentifier() throws Exception {
        // Backward scan from a space finds adjacent word; use isolated non-identifier
        assertNull(invokeExtractWordAt("  +  ", 2)); // '+' surrounded by spaces
    }

    @Test
    void extractWordAtEndOfContent() throws Exception {
        assertEquals("foo", invokeExtractWordAt("foo", 2));
    }

    // ---- isInRange ----

    @Test
    void isInRangeTrue() throws Exception {
        ModuleNode module = parseModule("class Foo {\n  void bar() {}\n}", "file:///IsInRange.groovy");
        ClassNode cls = findClass(module, "Foo");
        assertTrue(invokeIsInRange(cls, 1)); // line 1 is within class Foo
    }

    @Test
    void isInRangeFalse() throws Exception {
        ModuleNode module = parseModule("class Foo {\n}\nclass Bar {}", "file:///IsInRange2.groovy");
        ClassNode foo = findClass(module, "Foo");
        // Line 3 (where Bar is) should be outside Foo if Foo ends at line 2
        int barLine = findClass(module, "Bar").getLineNumber();
        // Use a line that's clearly outside Foo
        boolean result = invokeIsInRange(foo, barLine + 10);
        // If Foo's last line < barLine + 10, should be false
        assertTrue(!result || foo.getLastLineNumber() >= barLine + 10);
    }

    // ---- buildClassHover ----

    @Test
    void buildClassHoverSimpleClass() throws Exception {
        ModuleNode module = parseModule("class Foo {}", "file:///ClassHover.groovy");
        ClassNode cls = findClass(module, "Foo");
        String hover = invokeBuildClassHover(cls);
        assertNotNull(hover);
        assertTrue(hover.contains("class"));
        assertTrue(hover.contains("Foo"));
    }

    @Test
    void buildClassHoverInterface() throws Exception {
        ModuleNode module = parseModule("interface Greeter { void greet() }",
                "file:///InterfaceHover.groovy");
        ClassNode cls = findClass(module, "Greeter");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("interface"));
        assertTrue(hover.contains("Greeter"));
    }

    @Test
    void buildClassHoverEnum() throws Exception {
        ModuleNode module = parseModule("enum Color { RED, GREEN, BLUE }",
                "file:///EnumHover.groovy");
        ClassNode cls = findClass(module, "Color");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("enum"));
        assertTrue(hover.contains("Color"));
    }

    @Test
    void buildClassHoverWithExtends() throws Exception {
        ModuleNode module = parseModule("""
                class Base {}
                class Child extends Base {}
                """, "file:///ExtendsHover.groovy");
        ClassNode cls = findClass(module, "Child");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("extends"));
        assertTrue(hover.contains("Base"));
    }

    @Test
    void buildClassHoverWithImplements() throws Exception {
        ModuleNode module = parseModule("""
                interface Greeter { void greet() }
                class Impl implements Greeter { void greet() {} }
                """, "file:///ImplHover.groovy");
        ClassNode cls = findClass(module, "Impl");
        String hover = invokeBuildClassHover(cls);
        assertTrue(hover.contains("implements"));
        assertTrue(hover.contains("Greeter"));
    }

    // ---- buildMethodHover ----

    @Test
    void buildMethodHoverNoParams() throws Exception {
        ModuleNode module = parseModule("class Foo { String getName() { 'hi' } }",
                "file:///MethodHover1.groovy");
        MethodNode method = findMethod(module, "Foo", "getName");
        String hover = invokeBuildMethodHover(method);
        assertNotNull(hover);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("getName"));
    }

    @Test
    void buildMethodHoverWithParams() throws Exception {
        ModuleNode module = parseModule("class Foo { void greet(String name, int count) {} }",
                "file:///MethodHover2.groovy");
        MethodNode method = findMethod(module, "Foo", "greet");
        String hover = invokeBuildMethodHover(method);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("name"));
        assertTrue(hover.contains("int"));
        assertTrue(hover.contains("count"));
    }

    // ---- buildFieldHover ----

    @Test
    void buildFieldHover() throws Exception {
        ModuleNode module = parseModule("class Foo { private int count = 0 }",
                "file:///FieldHover.groovy");
        FieldNode field = findField(module, "Foo", "count");
        String hover = invokeBuildFieldHover(field);
        assertNotNull(hover);
        assertTrue(hover.contains("int"));
        assertTrue(hover.contains("count"));
    }

    // ---- buildPropertyHover ----

    @Test
    void buildPropertyHover() throws Exception {
        ModuleNode module = parseModule("class Foo { String name }",
                "file:///PropHover.groovy");
        PropertyNode prop = findProperty(module, "Foo", "name");
        String hover = invokeBuildPropertyHover(prop);
        assertNotNull(hover);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("name"));
        assertTrue(hover.contains("(property)"));
    }

    // ---- Additional edge cases ----

    @Test
    void positionToOffsetThirdLine() throws Exception {
        assertEquals(14, invokePositionToOffset("hello\nworld\nabc", new Position(2, 2)));
    }

    @Test
    void positionToOffsetEmptyContent() throws Exception {
        assertEquals(0, invokePositionToOffset("", new Position(0, 0)));
    }

    @Test
    void simpleNameEmptyString() throws Exception {
        assertEquals("", invokeSimpleName(""));
    }

    @Test
    void simpleNameDotOnly() throws Exception {
        assertEquals("", invokeSimpleName("just."));
    }

    @Test
    void extractWordAtWithUnderscore() throws Exception {
        assertEquals("my_var", invokeExtractWordAt("int my_var = 5", 6));
    }

    @Test
    void buildClassHoverTrait() throws Exception {
        ModuleNode module = parseModule("trait Flyable { void fly() {} }",
                "file:///TraitHover.groovy");
        ClassNode cls = findClass(module, "Flyable");
        String hover = invokeBuildClassHover(cls);
        assertNotNull(hover);
        assertTrue(hover.contains("Flyable"));
    }

    @Test
    void buildMethodHoverStaticMethod() throws Exception {
        ModuleNode module = parseModule("class Util { static String encode(String input) { input } }",
                "file:///StaticHover.groovy");
        MethodNode method = findMethod(module, "Util", "encode");
        String hover = invokeBuildMethodHover(method);
        assertNotNull(hover);
        assertTrue(hover.contains("encode"));
        assertTrue(hover.contains("String"));
    }

    @Test
    void buildFieldHoverStaticFinal() throws Exception {
        ModuleNode module = parseModule("class Config { static final String NAME = 'app' }",
                "file:///StaticFieldHover.groovy");
        FieldNode field = findField(module, "Config", "NAME");
        String hover = invokeBuildFieldHover(field);
        assertNotNull(hover);
        assertTrue(hover.contains("NAME"));
    }

    @Test
    void getHoverFromGroovyASTForFieldInClass() throws Exception {
        String uri = "file:///HoverASTField.groovy";
        String content = "class Config {\n  String host = 'localhost'\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 12));
        assertNotNull(hover);

        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTReturnsNullForMissingDocument() throws Exception {
        HoverProvider hp = new HoverProvider(new DocumentManager());

        Hover hover = invokeGetHoverFromGroovyAST(hp, "file:///NonExistent.groovy", new Position(0, 0));
        assertNull(hover);
    }

    @Test
    void getHoverFromGroovyASTForEnum() throws Exception {
        String uri = "file:///HoverASTEnum.groovy";
        String content = "enum Status { ACTIVE, INACTIVE }";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(0, 8));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("Status"));

        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTForInterface() throws Exception {
        String uri = "file:///HoverASTIface.groovy";
        String content = "interface Greeter { void greet(String name) }";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(0, 14));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("Greeter"));

        dm.didClose(uri);
    }

    // ---- buildASTHover ----

    @Test
    void buildASTHoverReturnsHoverForValidText() throws Exception {
        Hover hover = invokeBuildASTHover("```groovy\nclass Foo\n```");
        assertNotNull(hover);
        assertNotNull(hover.getContents().getRight());
        assertTrue(hover.getContents().getRight().getValue().contains("Foo"));
    }

    @Test
    void buildASTHoverReturnsNullForEmpty() throws Exception {
        assertNull(invokeBuildASTHover(""));
        assertNull(invokeBuildASTHover(null));
    }

    // ---- getHoverFromGroovyAST (integration-level via DocumentManager) ----

    @Test
    void getHoverFromGroovyASTFindsClassAtCursorPosition() throws Exception {
        String uri = "file:///HoverASTClass.groovy";
        String content = "class MyService {\n  void run() {}\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        // Position at "MyService" — line 0, col 6 (inside "MyService")
        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(0, 8));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("MyService"));

        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTFindsMethodAtCursorPosition() throws Exception {
        String uri = "file:///HoverASTMethod.groovy";
        String content = "class Svc {\n  String process(int n) { '' }\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 12));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("process"));

        dm.didClose(uri);
    }

        @Test
        void getHoverFromGroovyASTIncludesClassGroovydoc() throws Exception {
                String uri = "file:///HoverASTClassDoc.groovy";
                String content = """
                                /**
                                 * Service level docs.
                                 */
                                class DocumentedService {
                                    String run() { '' }
                                }
                                """;
                DocumentManager dm = new DocumentManager();
                dm.didOpen(uri, content);
                HoverProvider hp = new HoverProvider(dm);

                Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(3, 10));
                assertNotNull(hover);
                assertTrue(hover.getContents().getRight().getValue().contains("Service level docs."));

                dm.didClose(uri);
        }

        @Test
        void getHoverFromGroovyASTIncludesMemberGroovydoc() throws Exception {
                String uri = "file:///HoverASTMemberDoc.groovy";
                String content = """
                                class DocumentedService {
                                    /**
                                     * Greets callers.
                                     */
                                    String greet(String name) { '' }
                                }
                                """;
                DocumentManager dm = new DocumentManager();
                dm.didOpen(uri, content);
                HoverProvider hp = new HoverProvider(dm);

                Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(4, 12));
                assertNotNull(hover);
                assertTrue(hover.getContents().getRight().getValue().contains("Greets callers."));

                dm.didClose(uri);
        }

    @Test
    void getHoverFromGroovyASTReturnsNullForBlankArea() throws Exception {
        String uri = "file:///HoverASTBlank.groovy";
        String content = "class Svc {\n\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        // Line 1 is blank; no word at cursor
        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 0));
        assertNull(hover);

        dm.didClose(uri);
    }

    // ================================================================
    // JDT Mock Tests — buildHoverContent
    // ================================================================

    @Test
    void buildHoverContentForMockedType() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.getElementName()).thenReturn("MyService");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn("BaseService");
        when(type.getSuperInterfaceNames()).thenReturn(new String[]{"Runnable"});
        when(type.getFullyQualifiedName()).thenReturn("com.example.MyService");

        String hover = invokeBuildHoverContent(type);
        assertNotNull(hover);
        assertTrue(hover.contains("class"));
        assertTrue(hover.contains("MyService"));
        assertTrue(hover.contains("extends"));
        assertTrue(hover.contains("BaseService"));
        assertTrue(hover.contains("implements"));
        assertTrue(hover.contains("Runnable"));
        assertTrue(hover.contains("com.example"));
    }

    @Test
    void buildHoverContentForMockedInterface() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.getElementName()).thenReturn("Greeter");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccInterface);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[]{"Serializable"});
        when(type.getFullyQualifiedName()).thenReturn("com.example.Greeter");

        String hover = invokeBuildHoverContent(type);
        assertNotNull(hover);
        assertTrue(hover.contains("interface"));
        assertTrue(hover.contains("Greeter"));
        assertTrue(hover.contains("Serializable"));
    }

    @Test
    void buildHoverContentForMockedEnum() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.getElementName()).thenReturn("Color");
        when(type.getFlags()).thenReturn(Flags.AccPublic | Flags.AccEnum);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(true);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn("Enum");
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFullyQualifiedName()).thenReturn("com.example.Color");

        String hover = invokeBuildHoverContent(type);
        assertNotNull(hover);
        assertTrue(hover.contains("enum"));
        assertTrue(hover.contains("Color"));
    }

    @Test
    void buildHoverContentForMockedTraitType() throws Exception {
        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.getElementName()).thenReturn("Flyable");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(true);
        when(type.isEnum()).thenReturn(false);
        IAnnotation traitAnn = mock(IAnnotation.class);
        when(traitAnn.getElementName()).thenReturn("Trait");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{traitAnn});
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFullyQualifiedName()).thenReturn("com.example.Flyable");

        String hover = invokeBuildHoverContent(type);
        assertNotNull(hover);
        assertTrue(hover.contains("trait"));
        assertTrue(hover.contains("Flyable"));
    }

    @Test
    void buildHoverContentForMockedMethod() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementType()).thenReturn(IJavaElement.METHOD);
        when(method.getElementName()).thenReturn("process");
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("QString;");
        when(method.getParameterTypes()).thenReturn(new String[]{"I", "QString;"});
        when(method.getParameterNames()).thenReturn(new String[]{"count", "name"});
        when(method.getExceptionTypes()).thenReturn(new String[0]);
        IType declaringType = mock(IType.class);
        when(declaringType.getFullyQualifiedName()).thenReturn("com.example.Service");
        when(method.getDeclaringType()).thenReturn(declaringType);

        String hover = invokeBuildHoverContent(method);
        assertNotNull(hover);
        assertTrue(hover.contains("process"));
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("count"));
        assertTrue(hover.contains("name"));
        assertTrue(hover.contains("com.example.Service"));
    }

    @Test
    void buildHoverContentForMockedConstructor() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementType()).thenReturn(IJavaElement.METHOD);
        when(method.getElementName()).thenReturn("MyClass");
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.isConstructor()).thenReturn(true);
        when(method.getParameterTypes()).thenReturn(new String[]{"QString;"});
        when(method.getParameterNames()).thenReturn(new String[]{"name"});
        when(method.getExceptionTypes()).thenReturn(new String[0]);
        IType declaringType = mock(IType.class);
        when(declaringType.getFullyQualifiedName()).thenReturn("com.example.MyClass");
        when(method.getDeclaringType()).thenReturn(declaringType);

        String hover = invokeBuildHoverContent(method);
        assertNotNull(hover);
        assertTrue(hover.contains("MyClass"));
        assertTrue(hover.contains("name"));
    }

    @Test
    void buildHoverContentForMockedMethodWithExceptions() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getElementType()).thenReturn(IJavaElement.METHOD);
        when(method.getElementName()).thenReturn("riskyOp");
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("V");
        when(method.getParameterTypes()).thenReturn(new String[0]);
        when(method.getParameterNames()).thenReturn(new String[0]);
        when(method.getExceptionTypes()).thenReturn(new String[]{"QIOException;"});
        when(method.getDeclaringType()).thenReturn(null);

        String hover = invokeBuildHoverContent(method);
        assertNotNull(hover);
        assertTrue(hover.contains("throws"));
        assertTrue(hover.contains("IOException"));
    }

    @Test
    void buildHoverContentUsesOpenWorkspaceSourceForTypeJavadoc() throws Exception {
        String uri = "file:///workspace/LiveDemo.groovy";
        String liveContent = """
                /**
                 * Live docs from the open buffer.
                 */
                class LiveDemo {
                }
                """;
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, liveContent);
        HoverProvider hp = new HoverProvider(dm);

        ICompilationUnit cu = mock(ICompilationUnit.class);
        IResource resource = mock(IResource.class);
        when(resource.getLocationURI()).thenReturn(URI.create(uri));
        when(cu.getResource()).thenReturn(resource);
        when(cu.getSource()).thenReturn("/** Stale docs. */ class LiveDemo {}");

        IType type = mock(IType.class);
        when(type.getElementType()).thenReturn(IJavaElement.TYPE);
        when(type.getElementName()).thenReturn("LiveDemo");
        when(type.getFlags()).thenReturn(Flags.AccPublic);
        when(type.isInterface()).thenReturn(false);
        when(type.isEnum()).thenReturn(false);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);
        when(type.getSuperclassName()).thenReturn(null);
        when(type.getSuperInterfaceNames()).thenReturn(new String[0]);
        when(type.getFullyQualifiedName()).thenReturn("com.example.LiveDemo");
        when(type.getClassFile()).thenReturn(null);
        when(type.getCompilationUnit()).thenReturn(cu);
        when(type.getResource()).thenReturn(null);

        String hover = invokeBuildHoverContent(hp, type);
        assertNotNull(hover);
        assertTrue(hover.contains("Live docs from the open buffer."));
        assertFalse(hover.contains("Stale docs."));

        dm.didClose(uri);
    }

    @Test
    void buildHoverContentUsesWorkspaceSourceForMemberJavadoc() throws Exception {
        String source = """
                class Demo {
                    /**
                     * Greets from workspace source.
                     */
                    String greet(String name) { '' }
                }
                """;

        ICompilationUnit cu = mock(ICompilationUnit.class);
        when(cu.getSource()).thenReturn(source);

        IType type = mock(IType.class);
        when(type.getCompilationUnit()).thenReturn(cu);
        when(type.getResource()).thenReturn(null);
        when(type.getFullyQualifiedName()).thenReturn("com.example.Demo");
        when(type.getClassFile()).thenReturn(null);

        IMethod method = mock(IMethod.class);
        when(method.getElementType()).thenReturn(IJavaElement.METHOD);
        when(method.getElementName()).thenReturn("greet");
        when(method.getFlags()).thenReturn(Flags.AccPublic);
        when(method.isConstructor()).thenReturn(false);
        when(method.getReturnType()).thenReturn("QString;");
        when(method.getParameterTypes()).thenReturn(new String[]{"QString;"});
        when(method.getParameterNames()).thenReturn(new String[]{"name"});
        when(method.getExceptionTypes()).thenReturn(new String[0]);
        when(method.getDeclaringType()).thenReturn(type);
        when(method.getAncestor(IJavaElement.TYPE)).thenReturn(type);

        String hover = invokeBuildHoverContent(method);
        assertNotNull(hover);
        assertTrue(hover.contains("Greets from workspace source."));
    }

    @Test
    void buildHoverContentForMockedField() throws Exception {
        IField field = mock(IField.class);
        when(field.getElementType()).thenReturn(IJavaElement.FIELD);
        when(field.getElementName()).thenReturn("count");
        when(field.getFlags()).thenReturn(Flags.AccPrivate);
        when(field.getTypeSignature()).thenReturn("I");
        when(field.getConstant()).thenReturn(null);
        IType declaringType = mock(IType.class);
        when(declaringType.getFullyQualifiedName()).thenReturn("com.example.Counter");
        when(field.getDeclaringType()).thenReturn(declaringType);

        String hover = invokeBuildHoverContent(field);
        assertNotNull(hover);
        assertTrue(hover.contains("int"));
        assertTrue(hover.contains("count"));
        assertTrue(hover.contains("com.example.Counter"));
    }

    @Test
    void buildHoverContentForMockedFieldWithConstant() throws Exception {
        IField field = mock(IField.class);
        when(field.getElementType()).thenReturn(IJavaElement.FIELD);
        when(field.getElementName()).thenReturn("MAX");
        when(field.getFlags()).thenReturn(Flags.AccPublic | Flags.AccStatic | Flags.AccFinal);
        when(field.getTypeSignature()).thenReturn("I");
        when(field.getConstant()).thenReturn(100);
        when(field.getDeclaringType()).thenReturn(null);

        String hover = invokeBuildHoverContent(field);
        assertNotNull(hover);
        assertTrue(hover.contains("MAX"));
        assertTrue(hover.contains("= 100"));
    }

    @Test
    void buildHoverContentForMockedLocalVariable() throws Exception {
        ILocalVariable local = mock(ILocalVariable.class);
        when(local.getElementType()).thenReturn(IJavaElement.LOCAL_VARIABLE);
        when(local.getElementName()).thenReturn("temp");
        when(local.getTypeSignature()).thenReturn("QString;");

        String hover = invokeBuildHoverContent(local);
        assertNotNull(hover);
        assertTrue(hover.contains("String"));
        assertTrue(hover.contains("temp"));
        assertTrue(hover.contains("local variable"));
    }

    @Test
    void buildHoverContentForUnknownElementType() throws Exception {
        IJavaElement element = mock(IJavaElement.class);
        when(element.getElementType()).thenReturn(IJavaElement.PACKAGE_FRAGMENT);
        when(element.getElementName()).thenReturn("com.example");

        String hover = invokeBuildHoverContent(element);
        assertNotNull(hover);
        assertTrue(hover.contains("com.example"));
    }

    // ---- isTrait via mock ----

    @Test
    void isTraitReturnsTrueForTraitAnnotation() throws Exception {
        IType type = mock(IType.class);
        IAnnotation ann = mock(IAnnotation.class);
        when(ann.getElementName()).thenReturn("groovy.transform.Trait");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{ann});

        boolean result = invokeIsTrait(type);
        assertTrue(result);
    }

    @Test
    void isTraitReturnsFalseForNoAnnotations() throws Exception {
        IType type = mock(IType.class);
        when(type.getAnnotations()).thenReturn(new IAnnotation[0]);

        boolean result = invokeIsTrait(type);
        assertFalse(result);
    }

    @Test
    void isTraitReturnsTrueForSimpleTraitAnnotation() throws Exception {
        IType type = mock(IType.class);
        IAnnotation ann = mock(IAnnotation.class);
        when(ann.getElementName()).thenReturn("Trait");
        when(type.getAnnotations()).thenReturn(new IAnnotation[]{ann});

        boolean result = invokeIsTrait(type);
        assertTrue(result);
    }

    // ---- getHover with mock (tests JDT codeSelect path) ----

    @Test
    void getHoverReturnsFallbackWhenNoWorkingCopy() {
        DocumentManager dm = new DocumentManager();
        String uri = "file:///HoverMockNoWC.groovy";
        dm.didOpen(uri, "class Abc {}");
        HoverProvider hp = new HoverProvider(dm);

        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(uri), new Position(0, 8));
        Hover hover = hp.getHover(params);
        // Should try AST fallback; class name hover
        assertNotNull(hover);
        dm.didClose(uri);
    }

    @Test
    void getHoverReturnsNullForNoContent() {
        DocumentManager dm = new DocumentManager();
        HoverProvider hp = new HoverProvider(dm);

        HoverParams params = new HoverParams(
                new TextDocumentIdentifier("file:///Missing.groovy"), new Position(0, 0));
        Hover hover = hp.getHover(params);
        assertNull(hover);
    }

    // ---- resolveTraitMemberHover via AST ----

    @Test
    void getHoverFromGroovyASTForPropertyInClass() throws Exception {
        String uri = "file:///HoverASTProp.groovy";
        String content = "class Config {\n  String host\n}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 12));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("host"));
        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTForTraitMember() throws Exception {
        String uri = "file:///HoverASTTrait.groovy";
        String content = "trait Flyable {\n  void fly() {}\n}\nclass Bird implements Flyable {}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        // Hover on "fly" in the trait itself
        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 9));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("fly"));
        dm.didClose(uri);
    }

    @Test
    void getHoverFromGroovyASTForClassWithExtends() throws Exception {
        String uri = "file:///HoverASTExtends.groovy";
        String content = "class Base {}\nclass Child extends Base {}";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, content);
        HoverProvider hp = new HoverProvider(dm);

        Hover hover = invokeGetHoverFromGroovyAST(hp, uri, new Position(1, 8));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("Child"));
        dm.didClose(uri);
    }

    @Test
    void buildGeneratedAccessorHoverUsesBinaryMemberMetadataForGetter() throws Exception {
        HoverProvider hp = new HoverProvider(new DocumentManager());

        IJavaProject project = mock(IJavaProject.class);
        IType sourceType = mock(IType.class);
        IType binaryType = mock(IType.class);
        ICompilationUnit compilationUnit = mock(ICompilationUnit.class);
        IPackageFragmentRoot root = mock(IPackageFragmentRoot.class);
        IPackageFragment fragment = mock(IPackageFragment.class);
        IOrdinaryClassFile classFile = mock(IOrdinaryClassFile.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod getter = mock(IMethod.class);

        when(sourceType.getCompilationUnit()).thenReturn(compilationUnit);
        when(sourceType.getJavaProject()).thenReturn(project);
        when(sourceType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(sourceType.getMethods()).thenReturn(new IMethod[0]);

        when(project.getPackageFragmentRoots()).thenReturn(new IPackageFragmentRoot[] {root});
        when(root.getKind()).thenReturn(IPackageFragmentRoot.K_BINARY);
        when(root.getPackageFragment("com.example")).thenReturn(fragment);
        when(fragment.getOrdinaryClassFile("Helper.class")).thenReturn(classFile);
        when(classFile.exists()).thenReturn(true);
        when(classFile.getType()).thenReturn(binaryType);
        when(binaryType.exists()).thenReturn(true);
        when(binaryType.getFullyQualifiedName()).thenReturn("com.example.Helper");
        when(binaryType.getMethods()).thenReturn(new IMethod[] {getter});
        when(binaryType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(binaryType)).thenReturn(new IType[0]);

        when(getter.getElementName()).thenReturn("getSomeList");
        when(getter.getReturnType()).thenReturn("QList<QString;>;");
        when(getter.getFlags()).thenReturn(0);
        when(getter.isConstructor()).thenReturn(false);
        when(getter.getParameterTypes()).thenReturn(new String[0]);
        when(getter.getParameterNames()).thenReturn(new String[0]);
        when(getter.getExceptionTypes()).thenReturn(new String[0]);
        when(getter.getDeclaringType()).thenReturn(binaryType);

        Hover hover = invokeBuildGeneratedAccessorHover(hp, sourceType, "getSomeList");

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("getSomeList"));
    }

    @Test
    void buildGeneratedAccessorHoverFallsBackToRecordComponent() throws Exception {
        HoverProvider hp = new HoverProvider(new DocumentManager());
        IType recordType = mock(IType.class);

        when(recordType.getElementName()).thenReturn("Recc");
        when(recordType.getFullyQualifiedName()).thenReturn("com.example.Recc");
        when(recordType.getSource()).thenReturn("public record Recc(java.lang.String something) {}\n");

        Hover hover = invokeBuildGeneratedAccessorHover(hp, recordType, "something");

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("String something()"));
        assertTrue(hover.getContents().getRight().getValue().contains("com.example.Recc"));
    }

    // ================================================================
    // Reflection helpers
    // ================================================================

    private String invokeBuildHoverContent(IJavaElement element) throws Exception {
        return invokeBuildHoverContent(provider, element);
    }

    private String invokeBuildHoverContent(HoverProvider hoverProvider, IJavaElement element) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildHoverContent", IJavaElement.class);
        m.setAccessible(true);
        return (String) m.invoke(hoverProvider, element);
    }

    private boolean invokeIsTrait(IType type) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("isTrait", IType.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, type);
    }

    private int invokePositionToOffset(String content, Position position) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("positionToOffset", String.class, Position.class);
        m.setAccessible(true);
        return (int) m.invoke(provider, content, position);
    }

    private String invokeSimpleName(String fqn) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("simpleName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, fqn);
    }

    private String invokeExtractWordAt(String content, int offset) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("extractWordAt", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, content, offset);
    }

    private boolean invokeIsInRange(org.codehaus.groovy.ast.ASTNode node, int line) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("isInRange",
                org.codehaus.groovy.ast.ASTNode.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, node, line);
    }

    private String invokeBuildClassHover(ClassNode cls) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildClassHover", ClassNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, cls);
    }

    private String invokeBuildMethodHover(MethodNode method) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildMethodHover", MethodNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, method);
    }

    private String invokeBuildFieldHover(FieldNode field) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildFieldHover", FieldNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, field);
    }

    private String invokeBuildPropertyHover(PropertyNode prop) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildPropertyHover", PropertyNode.class);
        m.setAccessible(true);
        return (String) m.invoke(provider, prop);
    }

    private Hover invokeBuildASTHover(String text) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildASTHover", String.class, String.class);
        m.setAccessible(true);
        return (Hover) m.invoke(provider, text, null);
    }

    private Hover invokeGetHoverFromGroovyAST(HoverProvider hp, String uri, Position position) throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("getHoverFromGroovyAST", String.class, Position.class);
        m.setAccessible(true);
        return (Hover) m.invoke(hp, uri, position);
    }

    private Hover invokeBuildGeneratedAccessorHover(HoverProvider hp, IType receiverType, String methodName)
            throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildGeneratedAccessorHover", IType.class, String.class);
        m.setAccessible(true);
        return (Hover) m.invoke(hp, receiverType, methodName);
    }

    // ---- AST helpers ----

    private ModuleNode parseModule(String source, String uri) {
        GroovyCompilerService.ParseResult result = compilerService.parse(uri, source);
        if (!result.hasAST()) {
            throw new AssertionError("Expected AST for fixture: " + uri);
        }
        return result.getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(c -> simpleName.equals(c.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + simpleName));
    }

    private MethodNode findMethod(ModuleNode module, String className, String methodName) {
        ClassNode cls = findClass(module, className);
        return cls.getMethods().stream()
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    private FieldNode findField(ModuleNode module, String className, String fieldName) {
        ClassNode cls = findClass(module, className);
        return cls.getFields().stream()
                .filter(f -> fieldName.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + fieldName));
    }

    private PropertyNode findProperty(ModuleNode module, String className, String propName) {
        ClassNode cls = findClass(module, className);
        return cls.getProperties().stream()
                .filter(p -> propName.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propName));
    }

    // ================================================================
    // resolveTraitMemberHover tests — field and property paths
    // ================================================================

    @Test
    void resolveTraitMemberHoverForTraitField() throws Exception {
        String source = """
                trait HasCount {
                    int counter = 0
                }
                class Impl implements HasCount {}
                """;
        String uri = "file:///TraitFieldHover.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, source);
        HoverProvider hp = new HoverProvider(dm);

        GroovyCompilerService.ParseResult pr = compilerService.parse(uri, source);
        ModuleNode ast = pr.getModuleNode();
        ClassNode impl = findClass(ast, "Impl");

        Method m = HoverProvider.class.getDeclaredMethod(
                "resolveTraitMemberHover", ClassNode.class, ModuleNode.class, String.class, String.class, int.class);
        m.setAccessible(true);
        Hover hover = (Hover) m.invoke(hp, impl, ast, source, "counter", 4);

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("counter"));
        dm.didClose(uri);
    }

    @Test
    void resolveTraitMemberHoverForTraitProperty() throws Exception {
        String source = """
                trait Named {
                    String name
                }
                class Person implements Named {}
                """;
        String uri = "file:///TraitPropHover.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, source);
        HoverProvider hp = new HoverProvider(dm);

        GroovyCompilerService.ParseResult pr = compilerService.parse(uri, source);
        ModuleNode ast = pr.getModuleNode();
        ClassNode person = findClass(ast, "Person");

        Method m = HoverProvider.class.getDeclaredMethod(
                "resolveTraitMemberHover", ClassNode.class, ModuleNode.class, String.class, String.class, int.class);
        m.setAccessible(true);
        Hover hover = (Hover) m.invoke(hp, person, ast, source, "name", 4);

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("name"));
        dm.didClose(uri);
    }

    @Test
    void resolveTraitMemberHoverReturnsNullForOutOfRangeLine() throws Exception {
        String source = "trait T { void foo() {} }\nclass C implements T {}\n";
        String uri = "file:///TraitOOR.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, source);
        HoverProvider hp = new HoverProvider(dm);

        GroovyCompilerService.ParseResult pr = compilerService.parse(uri, source);
        ModuleNode ast = pr.getModuleNode();
        ClassNode c = findClass(ast, "C");

        Method m = HoverProvider.class.getDeclaredMethod(
                "resolveTraitMemberHover", ClassNode.class, ModuleNode.class, String.class, String.class, int.class);
        m.setAccessible(true);
        Hover hover = (Hover) m.invoke(hp, c, ast, source, "foo", 100);

        assertNull(hover);
        dm.didClose(uri);
    }

    @Test
    void resolveTraitMemberHoverReturnsNullForNonMatch() throws Exception {
        String source = "trait T { void foo() {} }\nclass C implements T {}\n";
        String uri = "file:///TraitNoMatch.groovy";
        DocumentManager dm = new DocumentManager();
        dm.didOpen(uri, source);
        HoverProvider hp = new HoverProvider(dm);

        GroovyCompilerService.ParseResult pr = compilerService.parse(uri, source);
        ModuleNode ast = pr.getModuleNode();
        ClassNode c = findClass(ast, "C");

        Method m = HoverProvider.class.getDeclaredMethod(
                "resolveTraitMemberHover", ClassNode.class, ModuleNode.class, String.class, String.class, int.class);
        m.setAccessible(true);
        Hover hover = (Hover) m.invoke(hp, c, ast, source, "nonexistent", 2);

        assertNull(hover);
        dm.didClose(uri);
    }

    // ================================================================
    // buildASTHover tests
    // ================================================================

    @Test
    void buildASTHoverReturnsNullForEmptyText() throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildASTHover", String.class, String.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, "", null));
    }

    @Test
    void buildASTHoverReturnsNullForNullText() throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildASTHover", String.class, String.class);
        m.setAccessible(true);
        assertNull(m.invoke(provider, null, null));
    }

    @Test
    void buildASTHoverReturnsMarkdown() throws Exception {
        Method m = HoverProvider.class.getDeclaredMethod("buildASTHover", String.class, String.class);
        m.setAccessible(true);
        Hover hover = (Hover) m.invoke(provider, "some hover text", null);
        assertNotNull(hover);
        assertEquals("some hover text", hover.getContents().getRight().getValue());
    }
}
