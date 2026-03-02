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

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * An {@link OutputStream} that redirects writes to the Eclipse log.
 * <p>
 * Used to capture stray stdout/stderr output and route it to the plugin log
 * instead of corrupting the JSON-RPC transport stream.
 */
public class LogOutputStream extends OutputStream {

    public enum Level {
        INFO, ERROR
    }

    private final Level level;
    private final StringBuilder buffer = new StringBuilder();

    public LogOutputStream(Level level) {
        this.level = level;
    }

    @Override
    public void write(int b) throws IOException {
        if (b == '\n') {
            flush();
        } else if (b != '\r') {
            buffer.append((char) b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    @Override
    public void flush() throws IOException {
        if (!buffer.isEmpty()) {
            String message = buffer.toString();
            buffer.setLength(0);

            ILog log = GroovyLanguageServerPlugin.getPluginLog();
            if (log != null) {
                int severity = (level == Level.ERROR) ? IStatus.ERROR : IStatus.INFO;
                log.log(new Status(severity, GroovyLanguageServerPlugin.PLUGIN_ID, message));
            }
        }
    }
}
