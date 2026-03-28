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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Shared cache for JDT type hierarchies used by multiple providers.
 */
final class TypeHierarchyCache {

    private static final int CACHE_SIZE = 256;
    private static final long TTL_MS = 60_000;
    private static final String SUPER_PREFIX = "super:";
    private static final String FULL_PREFIX = "full:";

    private record CachedHierarchy(ITypeHierarchy hierarchy, long timestampMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestampMs > TTL_MS;
        }
    }

    @SuppressWarnings("serial")
    private static final Map<String, CachedHierarchy> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, CachedHierarchy>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedHierarchy> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private TypeHierarchyCache() {
    }

    static ITypeHierarchy getSupertypeHierarchy(IType type) throws JavaModelException {
        return getHierarchy(type, true);
    }

    static ITypeHierarchy getTypeHierarchy(IType type) throws JavaModelException {
        return getHierarchy(type, false);
    }

    static void clear() {
        CACHE.clear();
    }

    private static ITypeHierarchy getHierarchy(IType type, boolean supertypeOnly)
            throws JavaModelException {
        if (type == null) {
            return null;
        }

        String key = (supertypeOnly ? SUPER_PREFIX : FULL_PREFIX) + getTypeKey(type);
        CachedHierarchy cached = CACHE.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.hierarchy();
        }

        ITypeHierarchy hierarchy = supertypeOnly
                ? type.newSupertypeHierarchy(null)
                : type.newTypeHierarchy(null);
        if (hierarchy != null) {
            CACHE.put(key, new CachedHierarchy(hierarchy, System.currentTimeMillis()));
        }
        return hierarchy;
    }

    private static String getTypeKey(IType type) {
        String handle = type.getHandleIdentifier();
        if (handle != null && !handle.isEmpty()) {
            return handle;
        }
        return type.getFullyQualifiedName();
    }
}