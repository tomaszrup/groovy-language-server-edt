package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class CompletionProviderCoverageTest {

    private final GroovyCompilerService compilerService = new GroovyCompilerService();

    @Test
    void warmTypeIndexMarksProjectAsWarmed() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IJavaSearchScope scope = mock(IJavaSearchScope.class);

        when(project.getElementName()).thenReturn("demo-project");

        try (MockedStatic<SearchEngine> searchEngine = Mockito.mockStatic(SearchEngine.class);
                MockedStatic<JdtSearchSupport> searchSupport = Mockito.mockStatic(JdtSearchSupport.class)) {
            searchEngine.when(() -> SearchEngine.createJavaSearchScope(
                    new IJavaElement[] { project },
                    IJavaSearchScope.SOURCES | IJavaSearchScope.APPLICATION_LIBRARIES))
                    .thenReturn(scope);

            provider.warmTypeIndex(project);
            provider.warmTypeIndex(project);

            Field warmedProjectsField = CompletionProvider.class.getDeclaredField("warmedProjects");
            warmedProjectsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> warmedProjects = (Set<String>) warmedProjectsField.get(provider);
            assertTrue(warmedProjects.contains("demo-project"));
            assertEquals(1, warmedProjects.size());
        }
    }

    @Test
    void warmTypeIndexReturnsImmediatelyForNullProject() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());

        provider.warmTypeIndex(null);

        Field warmedProjectsField = CompletionProvider.class.getDeclaredField("warmedProjects");
        warmedProjectsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> warmedProjects = (Set<String>) warmedProjectsField.get(provider);
        assertTrue(warmedProjects.isEmpty());
    }

    @Test
    void addImportedAnnotationCompletionAddsMatchingAnnotationItem() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType annotationType = mock(IType.class);
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        when(project.findType("com.example.MyAnnotation")).thenReturn(annotationType);
        when(annotationType.exists()).thenReturn(true);
        when(annotationType.isAnnotation()).thenReturn(true);

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addImportedAnnotationCompletion",
                IJavaProject.class,
                String.class,
                String.class,
                List.class,
                Set.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.CompletionProvider$TypeResolutionContext"));
        method.setAccessible(true);
        method.invoke(provider, project, "com.example.MyAnnotation", "My", items, seen, null);

        assertEquals(1, items.size());
        assertEquals("MyAnnotation", items.get(0).getLabel());
        assertEquals(CompletionItemKind.Class, items.get(0).getKind());
        assertEquals("com.example.MyAnnotation", items.get(0).getDetail());
    }

    @Test
    void addImportedAnnotationCompletionSkipsDuplicateSimpleName() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType annotationType = mock(IType.class);
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of("MyAnnotation"));

        when(project.findType("com.example.MyAnnotation")).thenReturn(annotationType);
        when(annotationType.exists()).thenReturn(true);
        when(annotationType.isAnnotation()).thenReturn(true);

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addImportedAnnotationCompletion",
                IJavaProject.class,
                String.class,
                String.class,
                List.class,
                Set.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.CompletionProvider$TypeResolutionContext"));
        method.setAccessible(true);
        method.invoke(provider, project, "com.example.MyAnnotation", "My", items, seen, null);

        assertTrue(items.isEmpty());
    }

    @Test
    void addImportedAnnotationCompletionSkipsPrefixMismatch() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addImportedAnnotationCompletion",
                IJavaProject.class,
                String.class,
                String.class,
                List.class,
                Set.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.CompletionProvider$TypeResolutionContext"));
        method.setAccessible(true);
        method.invoke(provider, project, "com.example.MyAnnotation", "Other", items, seen, null);

        assertTrue(items.isEmpty());
    }

    @Test
    void addImportedAnnotationCompletionSkipsNonAnnotationType() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        IJavaProject project = mock(IJavaProject.class);
        IType ordinaryType = mock(IType.class);
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        when(project.findType("com.example.MyAnnotation")).thenReturn(ordinaryType);
        when(ordinaryType.exists()).thenReturn(true);
        when(ordinaryType.isAnnotation()).thenReturn(false);

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addImportedAnnotationCompletion",
                IJavaProject.class,
                String.class,
                String.class,
                List.class,
                Set.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.CompletionProvider$TypeResolutionContext"));
        method.setAccessible(true);
        method.invoke(provider, project, "com.example.MyAnnotation", "My", items, seen, null);

        assertTrue(items.isEmpty());
    }

    @Test
    void addScopedAstIdentifierCompletionsAddsVisibleLocalVariable() throws Exception {
        DocumentManager documentManager = mock(DocumentManager.class);
        CompletionProvider provider = new CompletionProvider(documentManager);
        String uri = "file:///ScopedCompletion.groovy";
        String source = "class Demo {\n  void run() {\n    String localName = ''\n    loc\n  }\n}";
        ModuleNode ast = parseModule(uri, source);
        List<CompletionItem> items = new ArrayList<>();

        when(documentManager.getGroovyAST(uri)).thenReturn(ast);

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addScopedAstIdentifierCompletions",
                org.eclipse.jdt.core.ICompilationUnit.class,
                String.class,
                Position.class,
                String.class,
                List.class);
        method.setAccessible(true);
        method.invoke(provider, null, uri, new Position(3, 3), "loc", items);

        assertEquals(1, items.size());
        assertEquals("localName", items.get(0).getLabel());
        assertEquals(CompletionItemKind.Variable, items.get(0).getKind());
        assertEquals("String", items.get(0).getDetail());
    }

    @Test
    void addTraitCompletionsForClassAddsJdtAndAstTraitMembers() throws Exception {
        CompletionProvider provider = new CompletionProvider(new DocumentManager());
        String uri = "file:///TraitCompletion.groovy";
        String source = "package demo\ntrait Values {\n  String astProp\n  String astField\n  String astMethod() { '' }\n}\nclass Impl implements Values {}";
        ModuleNode ast = parseModule(uri, source);
        ClassNode implClass = findClass(ast, "Impl");
        ClassNode helperNode = new ClassNode("demo.Values$Trait$FieldHelper", 0, ClassHelper.OBJECT_TYPE);
        helperNode.addField(new FieldNode("demo_Values__helperField", 0, ClassHelper.STRING_TYPE, helperNode, null));
        ast.addClass(helperNode);

        IJavaProject project = mock(IJavaProject.class);
        IType traitType = mock(IType.class);
        IField jdtField = mock(IField.class);
        IMethod jdtMethod = mock(IMethod.class);
        IMethod getterMethod = mock(IMethod.class);
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        when(project.findType("demo.Values")).thenReturn(traitType);
        when(traitType.getElementName()).thenReturn("Values");
        when(traitType.getFields()).thenReturn(new IField[] { jdtField });
        when(jdtField.getElementName()).thenReturn("jdtField");
        when(jdtField.getTypeSignature()).thenReturn("QString;");
        when(traitType.getMethods()).thenReturn(new IMethod[] { jdtMethod, getterMethod });
        when(jdtMethod.getElementName()).thenReturn("jdtMethod");
        when(jdtMethod.getParameterTypes()).thenReturn(new String[0]);
        when(jdtMethod.getReturnType()).thenReturn("QString;");
        when(getterMethod.getElementName()).thenReturn("getJdtProperty");
        when(getterMethod.getParameterTypes()).thenReturn(new String[0]);
        when(getterMethod.getReturnType()).thenReturn("QString;");

        Method method = CompletionProvider.class.getDeclaredMethod(
                "addTraitCompletionsForClass",
                ClassNode.class,
                ModuleNode.class,
                String.class,
                IJavaProject.class,
                Set.class,
                List.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.CompletionProvider$TypeResolutionContext"));
        method.setAccessible(true);
        method.invoke(provider, implClass, ast, "", project, seen, items, null);

        assertTrue(items.stream().anyMatch(item -> "jdtField".equals(item.getLabel())));
        assertTrue(items.stream().anyMatch(item -> "jdtMethod()".equals(item.getInsertText())));
        assertTrue(items.stream().anyMatch(item -> "jdtProperty".equals(item.getLabel())));
        assertTrue(items.stream().anyMatch(item -> "astProp".equals(item.getLabel())));
        assertTrue(items.stream().anyMatch(item -> "astMethod".equals(item.getLabel())));
        assertTrue(items.stream().anyMatch(item -> "helperField".equals(item.getLabel())));
    }

    private ModuleNode parseModule(String uri, String source) {
        return compilerService.parse(uri, source).getModuleNode();
    }

    private ClassNode findClass(ModuleNode module, String simpleName) {
        return module.getClasses().stream()
                .filter(classNode -> simpleName.equals(classNode.getNameWithoutPackage()))
                .findFirst()
                .orElseThrow();
    }
}