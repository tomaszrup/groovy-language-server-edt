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

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.transform.trait.Traits;

final class GroovyTypeKindHelper {

    private GroovyTypeKindHelper() {
    }

    static boolean isTrait(ClassNode node) {
        if (node == null) {
            return false;
        }

        try {
            if (Traits.isTrait(node)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            for (AnnotationNode ann : node.getAnnotations()) {
                String annName = ann.getClassNode().getName();
                if ("Trait".equals(annName)
                        || "groovy.transform.Trait".equals(annName)
                        || annName.endsWith(".Trait")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}