package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.groovy.ls.core.GroovyCompilerService;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;

class DefinitionProviderReceiverResolutionTest {

    @Test
    void extractReceiverPartsSplitsNestedReceiverChain() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        Method method = DefinitionProvider.class.getDeclaredMethod("extractReceiverParts", String.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(provider, "Outer.Inner.value", "Outer.Inner.value".lastIndexOf('.'));

        assertEquals(List.of("Outer", "Inner"), result);
    }

    @Test
    void extractReceiverPartsReturnsEmptyWithoutIdentifierBeforeDot() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        Method method = DefinitionProvider.class.getDeclaredMethod("extractReceiverParts", String.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(provider, ".value", 0);

        assertEquals(List.of(), result);
    }

    @Test
    void resolveReceiverTypeFromPartsResolvesNestedInnerType() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        ModuleNode module = new GroovyCompilerService().parse(
                "file:///Receiver.groovy",
                "package demo\nclass Outer { class Inner {} }").getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType outerType = mock(IType.class);
        IType innerType = mock(IType.class);

        when(project.findType("demo.Outer")).thenReturn(outerType);
        when(outerType.getType("Inner")).thenReturn(innerType);
        when(innerType.exists()).thenReturn(true);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveReceiverTypeFromParts",
                List.class,
                ModuleNode.class,
                org.eclipse.jdt.core.IJavaProject.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, List.of("Outer", "Inner"), module, project, null);

        assertSame(innerType, result);
    }

    @Test
    void resolveReceiverTypeFromPartsReturnsNullWhenInnerTypeMissing() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        ModuleNode module = new GroovyCompilerService().parse(
                "file:///ReceiverMissing.groovy",
                "package demo\nclass Outer { class Inner {} }").getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType outerType = mock(IType.class);
        IType innerType = mock(IType.class);

        when(project.findType("demo.Outer")).thenReturn(outerType);
        when(outerType.getType("Inner")).thenReturn(innerType);
        when(innerType.exists()).thenReturn(false);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveReceiverTypeFromParts",
                List.class,
                ModuleNode.class,
                org.eclipse.jdt.core.IJavaProject.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, List.of("Outer", "Inner"), module, project, null);

        assertNull(result);
    }

    @Test
    void resolveReceiverTypeFromAstResolvesConstructorAssignedVariable() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        String source = "package demo\nclass Helper {}\ndef helper = new Helper()\nhelper.run()";
        ModuleNode module = new GroovyCompilerService().parse("file:///AstReceiver.groovy", source).getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType resolvedType = mock(IType.class);

        when(project.findType("demo.Helper")).thenReturn(resolvedType);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveReceiverTypeFromAst",
                ModuleNode.class,
                org.eclipse.jdt.core.IJavaProject.class,
                int.class,
                String.class,
                String.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, module, project, source.indexOf("run"), "run", source, null);

        assertSame(resolvedType, result);
    }

    @Test
    void resolveReceiverTypeFromAstReturnsNullForMethodCallDerivedVariable() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        String source = "class Factory { def makeHelper() { null } }\ndef helper = new Factory().makeHelper()\nhelper.run()";
        ModuleNode module = new GroovyCompilerService().parse("file:///AstReceiverNull.groovy", source).getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveReceiverTypeFromAst",
                ModuleNode.class,
                org.eclipse.jdt.core.IJavaProject.class,
                int.class,
                String.class,
                String.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, module, project, source.indexOf("run"), "run", source, null);

        assertNull(result);
    }

    @Test
    void resolveDotCallReceiverTypeUsesReceiverPartsChain() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        String source = "package demo\nclass Outer { class Inner {} }\nOuter.Inner.work()";
        ModuleNode module = new GroovyCompilerService().parse("file:///DotChain.groovy", source).getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType outerType = mock(IType.class);
        IType innerType = mock(IType.class);
        int offset = source.indexOf("work");
        int dotPos = source.lastIndexOf('.', offset);

        when(project.findType("demo.Outer")).thenReturn(outerType);
        when(outerType.getType("Inner")).thenReturn(innerType);
        when(innerType.exists()).thenReturn(true);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveDotCallReceiverType",
                ModuleNode.class,
                String.class,
                int.class,
                int.class,
                String.class,
                org.eclipse.jdt.core.IJavaProject.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, module, source, offset, dotPos, "work", project, null);

        assertSame(innerType, result);
    }

    @Test
    void resolveDotCallReceiverTypeFallsBackToAstWhenReceiverPartsAreEmpty() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        String source = "package demo\nclass Helper {}\nnew Helper().run()";
        ModuleNode module = new GroovyCompilerService().parse("file:///DotAst.groovy", source).getModuleNode();
        org.eclipse.jdt.core.IJavaProject project = mock(org.eclipse.jdt.core.IJavaProject.class);
        IType resolvedType = mock(IType.class);
        int offset = source.indexOf("run");
        int dotPos = source.lastIndexOf('.', offset);

        when(project.findType("demo.Helper")).thenReturn(resolvedType);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveDotCallReceiverType",
                ModuleNode.class,
                String.class,
                int.class,
                int.class,
                String.class,
                org.eclipse.jdt.core.IJavaProject.class,
                Class.forName("org.eclipse.groovy.ls.core.providers.DefinitionProvider$SourceLookupContext"));
        method.setAccessible(true);

        Object result = method.invoke(provider, module, source, offset, dotPos, "run", project, null);

        assertSame(resolvedType, result);
    }

    @Test
    void resolveTraitAccessorsUsesTraitLocationForGetter() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        ClassNode helperNode = mock(ClassNode.class);
        org.codehaus.groovy.ast.MethodNode getter = mock(org.codehaus.groovy.ast.MethodNode.class);
        ClassNode traitNode = mock(ClassNode.class);

        when(helperNode.getMethods()).thenReturn(List.of(getter));
        when(getter.getName()).thenReturn("getValue");
        when(traitNode.getLineNumber()).thenReturn(2);
        when(traitNode.getColumnNumber()).thenReturn(1);
        when(traitNode.getLastLineNumber()).thenReturn(2);
        when(traitNode.getLastColumnNumber()).thenReturn(12);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveTraitAccessors", ClassNode.class, String.class, String.class, ClassNode.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, helperNode, "value", "file:///Trait.groovy", traitNode);

        assertNotNull(result);
        assertEquals("file:///Trait.groovy", result.getUri());
        assertEquals(1, result.getRange().getStart().getLine());
    }

    @Test
    void resolveTraitFieldHelperUsesTraitLocationForMatchingField() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        ClassNode helperNode = mock(ClassNode.class);
        org.codehaus.groovy.ast.FieldNode field = mock(org.codehaus.groovy.ast.FieldNode.class);
        ClassNode traitNode = mock(ClassNode.class);

        when(helperNode.getFields()).thenReturn(List.of(field));
        when(field.getName()).thenReturn("demo_Trait__value");
        when(traitNode.getLineNumber()).thenReturn(4);
        when(traitNode.getColumnNumber()).thenReturn(1);
        when(traitNode.getLastLineNumber()).thenReturn(4);
        when(traitNode.getLastColumnNumber()).thenReturn(12);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveTraitFieldHelper", ClassNode.class, String.class, String.class, ClassNode.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, helperNode, "value", "file:///Trait.groovy", traitNode);

        assertNotNull(result);
        assertEquals("file:///Trait.groovy", result.getUri());
        assertEquals(3, result.getRange().getStart().getLine());
    }
}