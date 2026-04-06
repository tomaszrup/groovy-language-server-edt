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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class BinaryTypeDeclarationLocatorTest {

    @Test
    void findDeclarationRangeFindsDirectFieldDeclarationWithinTypeBody() throws Exception {
        String source = """
                class Demo {
                    String value = 'x'
                }
                """;
        IField field = mockField("value", "Demo");

        Range range = BinaryTypeDeclarationLocator.findDeclarationRange(source, field, "value");

        assertEquals(1, range.getStart().getLine());
        assertEquals(11, range.getStart().getCharacter());
    }

    @Test
    void findDeclarationRangeSkipsLocalVariableNamedLikeField() throws Exception {
        String source = """
                class Demo {
                    String value = null
                    void run() {
                        def value = 'local'
                    }
                }
                """;
        IField field = mockField("value", "Demo");

        Range range = BinaryTypeDeclarationLocator.findDeclarationRange(source, field, "value");

        assertEquals(1, range.getStart().getLine());
        assertEquals(11, range.getStart().getCharacter());
    }

    @Test
    void findDeclarationRangeFallsBackToClassRangeWhenFieldIsMissing() throws Exception {
        String source = "class Demo { }";
        IField field = mockField("missing", "Demo");

        Range range = BinaryTypeDeclarationLocator.findDeclarationRange(source, field, "missing");

        assertEquals(0, range.getStart().getLine());
        assertEquals(0, range.getStart().getCharacter());
    }

    private IField mockField(String fieldName, String declaringTypeName) throws Exception {
        IField field = mock(IField.class);
        IType type = mock(IType.class);
        ISourceRange nameRange = mock(ISourceRange.class);
        when(field.getElementName()).thenReturn(fieldName);
        when(field.getAncestor(IJavaElement.TYPE)).thenReturn(type);
        when(type.getElementName()).thenReturn(declaringTypeName);
        when(field.getNameRange()).thenReturn(nameRange);
        when(nameRange.getOffset()).thenReturn(-1);
        return field;
    }
}