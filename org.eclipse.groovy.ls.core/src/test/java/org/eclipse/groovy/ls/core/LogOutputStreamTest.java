/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LogOutputStreamTest {

    @Test
    void writeIgnoresCarriageReturnAndFlushClearsBuffer() throws Exception {
        LogOutputStream stream = new LogOutputStream(LogOutputStream.Level.INFO);
        byte[] bytes = "hello\r\nworld".getBytes(StandardCharsets.UTF_8);

        stream.write(bytes, 0, bytes.length);
        stream.flush();

        assertEquals("", getBuffer(stream));
    }

    @Test
    void writeWithOffsetOnlyAppendsSelectedRange() throws Exception {
        LogOutputStream stream = new LogOutputStream(LogOutputStream.Level.ERROR);
        byte[] bytes = "XXabcYY".getBytes(StandardCharsets.UTF_8);

        stream.write(bytes, 2, 3);

        assertEquals("abc", getBuffer(stream));
    }

    private String getBuffer(LogOutputStream stream) throws Exception {
        Field field = LogOutputStream.class.getDeclaredField("buffer");
        field.setAccessible(true);
        return field.get(stream).toString();
    }
}

