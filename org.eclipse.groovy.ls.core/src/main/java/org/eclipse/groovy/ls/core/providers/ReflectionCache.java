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

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.groovy.ast.ModuleNode;
import org.eclipse.jdt.core.ICompilationUnit;

/**
 * Thread-safe cache for reflected {@code getModuleNode()} method handles.
 * <p>
 * The groovy-eclipse plugin's {@code GroovyCompilationUnit} exposes
 * {@code getModuleNode()} which is not on the compile classpath. Every call
 * to {@code Class.getMethod("getModuleNode")} performs a linear scan of the
 * class's methods. In a large workspace where this is called on every hover,
 * completion, semantic-token, and diagnostics request, the aggregate cost is
 * significant. Caching the {@link Method} handle per concrete class eliminates
 * repeated lookups.
 */
public final class ReflectionCache {

    private ReflectionCache() {
        // utility class
    }

    /**
     * Lazily-populated cache: concrete class → reflected {@code getModuleNode()} method.
     * Uses {@link ConcurrentHashMap} so no synchronization is needed on the hot path.
     * The number of distinct classes is tiny (usually just 1: GroovyCompilationUnit),
     * so memory impact is negligible.
     */
    private static final ConcurrentHashMap<Class<?>, Method> MODULE_NODE_METHODS =
            new ConcurrentHashMap<>(4);

    /**
     * Sentinel value stored when a class does NOT have getModuleNode()
     * (e.g., a plain Java ICompilationUnit). Avoids repeated
     * NoSuchMethodException for non-Groovy compilation units.
     */
    private static final Method NO_METHOD;
    static {
        try {
            NO_METHOD = ReflectionCache.class.getDeclaredMethod("sentinel");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    @SuppressWarnings("unused")
    private static void sentinel() {
        // never called — used only as a cache sentinel
    }

    /**
     * Invoke {@code getModuleNode()} on a JDT compilation unit via cached
     * reflection. Returns the {@link ModuleNode} or {@code null} if the
     * unit is not a GroovyCompilationUnit or if the method fails.
     *
     * @param unit the JDT compilation unit (may be a GroovyCompilationUnit)
     * @return the Groovy AST, or {@code null}
     */
    public static ModuleNode getModuleNode(ICompilationUnit unit) {
        if (unit == null) {
            return null;
        }
        Method method = MODULE_NODE_METHODS.computeIfAbsent(unit.getClass(), clazz -> {
            try {
                return clazz.getMethod("getModuleNode");
            } catch (NoSuchMethodException e) {
                return NO_METHOD;
            }
        });
        if (method == NO_METHOD) {
            return null;
        }
        try {
            Object result = method.invoke(unit);
            if (result instanceof ModuleNode moduleNode) {
                return moduleNode;
            }
        } catch (Exception e) {
            // Reflection failure — ignore and return null
        }
        return null;
    }
}
