package org.eclipse.groovy.ls.core.providers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.jdt.core.IMethod;
import org.junit.jupiter.api.Test;

class JdtParameterNameResolverTest {

    @Test
    void resolveRecoversWorkspaceInterfaceParameterNamesFromArgsPlaceholders() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;", "I"});
        when(method.getParameterNames()).thenReturn(new String[] {"args0", "args1"});
        when(method.isConstructor()).thenReturn(false);
        when(method.getElementName()).thenReturn("greet");
        when(method.getSource()).thenReturn("void greet(String person, int count);\n");

        assertArrayEquals(new String[] {"person", "count"}, JdtParameterNameResolver.resolve(method));
    }

    @Test
    void resolveRecoversParameterNamesFromSyntheticPPlaceholders() throws Exception {
        IMethod method = mock(IMethod.class);
        when(method.getParameterTypes()).thenReturn(new String[] {"QString;"});
        when(method.getParameterNames()).thenReturn(new String[] {"p0"});
        when(method.isConstructor()).thenReturn(false);
        when(method.getElementName()).thenReturn("hello");
        when(method.getSource()).thenReturn("String hello(String name);\n");

        assertArrayEquals(new String[] {"name"}, JdtParameterNameResolver.resolve(method));
    }
}