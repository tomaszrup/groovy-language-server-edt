package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;

import org.eclipse.core.resources.IResource;
import org.eclipse.groovy.ls.core.DocumentManager;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;

class DefinitionProviderMemberResolutionTest {

    @Test
    void resolveDirectMemberLocationResolvesInnerTypeByName() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IType innerType = mock(IType.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getType("Inner")).thenReturn(innerType);
        when(innerType.exists()).thenReturn(true);
        when(innerType.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Outer.groovy"));
        when(resource.getName()).thenReturn("Outer.groovy");
        when(innerType.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(0);
        when(nameRange.getLength()).thenReturn(5);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveDirectMemberLocation", IType.class, String.class, java.util.Map.class, java.util.Map.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "Inner", new HashMap<>(), new HashMap<>());

        assertNotNull(result);
        assertEquals("file:///workspace/Outer.groovy", result.getUri());
    }

    @Test
    void resolveDirectMemberLocationResolvesDeclaredMethod() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IMethod methodElement = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getType("work")).thenReturn(null);
        when(receiverType.getMethods()).thenReturn(new IMethod[] { methodElement });
        when(receiverType.getFields()).thenReturn(new IField[0]);
        when(methodElement.getElementName()).thenReturn("work");
        when(methodElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Demo.groovy"));
        when(resource.getName()).thenReturn("Demo.groovy");
        when(methodElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(10);
        when(nameRange.getLength()).thenReturn(4);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveDirectMemberLocation", IType.class, String.class, java.util.Map.class, java.util.Map.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "work", new HashMap<>(), new HashMap<>());

        assertNotNull(result);
        assertEquals("file:///workspace/Demo.groovy", result.getUri());
    }

    @Test
    void resolveDeclaredFieldLocationResolvesDeclaredField() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IField fieldElement = mock(IField.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getFields()).thenReturn(new IField[] { fieldElement });
        when(fieldElement.getElementName()).thenReturn("value");
        when(fieldElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Fields.groovy"));
        when(resource.getName()).thenReturn("Fields.groovy");
        when(fieldElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(5);
        when(nameRange.getLength()).thenReturn(5);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveDeclaredFieldLocation", IType.class, String.class, java.util.Map.class, java.util.Map.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "value", new HashMap<>(), new HashMap<>());

        assertNotNull(result);
        assertEquals("file:///workspace/Fields.groovy", result.getUri());
    }

    @Test
    void resolveHierarchyMemberLocationFindsMethodOnSuperType() throws Exception {
        TypeHierarchyCache.clear();

        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IType superType = mock(IType.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod methodElement = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getHandleIdentifier()).thenReturn("receiver-handle");
        when(receiverType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(receiverType)).thenReturn(new IType[] { superType });
        when(superType.getMethods()).thenReturn(new IMethod[] { methodElement });
        when(superType.getFields()).thenReturn(new IField[0]);
        when(methodElement.getElementName()).thenReturn("work");
        when(methodElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Base.groovy"));
        when(resource.getName()).thenReturn("Base.groovy");
        when(methodElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(0);
        when(nameRange.getLength()).thenReturn(4);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveHierarchyMemberLocation", IType.class, String.class, java.util.Map.class, java.util.Map.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "work", new HashMap<>(), new HashMap<>());

        assertNotNull(result);
        assertEquals("file:///workspace/Base.groovy", result.getUri());
    }

    @Test
    void resolveMemberLocationReturnsDirectMemberLocation() throws Exception {
        TypeHierarchyCache.clear();

        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IMethod methodElement = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getType("work")).thenReturn(null);
        when(receiverType.getMethods()).thenReturn(new IMethod[] { methodElement });
        when(receiverType.getFields()).thenReturn(new IField[0]);
        when(methodElement.getElementName()).thenReturn("work");
        when(methodElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Direct.groovy"));
        when(resource.getName()).thenReturn("Direct.groovy");
        when(methodElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(0);
        when(nameRange.getLength()).thenReturn(4);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveMemberLocation", IType.class, String.class, String.class, String.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "work", "file:///workspace/Direct.groovy", "class Direct {}\n");

        assertNotNull(result);
        assertEquals("file:///workspace/Direct.groovy", result.getUri());
    }

    @Test
    void resolveMemberLocationFallsBackToHierarchy() throws Exception {
        TypeHierarchyCache.clear();

        DefinitionProvider provider = new DefinitionProvider(new DocumentManager());
        IType receiverType = mock(IType.class);
        IType superType = mock(IType.class);
        ITypeHierarchy hierarchy = mock(ITypeHierarchy.class);
        IMethod methodElement = mock(IMethod.class);
        IResource resource = mock(IResource.class);
        ISourceRange nameRange = mock(ISourceRange.class);

        when(receiverType.getType("work")).thenReturn(null);
        when(receiverType.getMethods()).thenReturn(new IMethod[0]);
        when(receiverType.getFields()).thenReturn(new IField[0]);
        when(receiverType.getHandleIdentifier()).thenReturn("member-wrapper-handle");
        when(receiverType.newSupertypeHierarchy(null)).thenReturn(hierarchy);
        when(hierarchy.getAllSupertypes(receiverType)).thenReturn(new IType[] { superType });
        when(superType.getMethods()).thenReturn(new IMethod[] { methodElement });
        when(superType.getFields()).thenReturn(new IField[0]);
        when(methodElement.getElementName()).thenReturn("work");
        when(methodElement.getResource()).thenReturn(resource);
        when(resource.getLocationURI()).thenReturn(URI.create("file:///workspace/Hierarchy.groovy"));
        when(resource.getName()).thenReturn("Hierarchy.groovy");
        when(methodElement.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(0);
        when(nameRange.getLength()).thenReturn(4);

        Method method = DefinitionProvider.class.getDeclaredMethod(
                "resolveMemberLocation", IType.class, String.class, String.class, String.class);
        method.setAccessible(true);

        Location result = (Location) method.invoke(provider, receiverType, "work", "file:///workspace/Child.groovy", "class Child extends Base {}\n");

        assertNotNull(result);
        assertEquals("file:///workspace/Hierarchy.groovy", result.getUri());
    }
}