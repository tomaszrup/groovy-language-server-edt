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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LogOutputStreamTest {

    @Test
    void writeIgnoresCarriageReturnAndFlushClearsBuffer() throws Exception {
        byte[] bytes = "hello\r\nworld".getBytes(StandardCharsets.UTF_8);

        try (LogOutputStream stream = new LogOutputStream(LogOutputStream.Level.INFO)) {
            stream.write(bytes, 0, bytes.length);
            stream.flush();

            assertEquals("", stream.getBufferedText());
        }
    }

    @Test
    void writeWithOffsetOnlyAppendsSelectedRange() throws Exception {
        byte[] bytes = "XXabcYY".getBytes(StandardCharsets.UTF_8);

        try (LogOutputStream stream = new LogOutputStream(LogOutputStream.Level.ERROR)) {
            stream.write(bytes, 2, 3);

            assertEquals("abc", stream.getBufferedText());
        }
    }
}

