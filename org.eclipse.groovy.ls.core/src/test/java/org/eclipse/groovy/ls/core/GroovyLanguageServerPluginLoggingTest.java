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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroovyLanguageServerPluginLoggingTest {

    @AfterEach
    void tearDown() {
        GroovyLanguageServerPlugin.setLanguageServer(null);
    }

    @Test
    void logInfoSendsLogMessageToConnectedClient() {
        LanguageClient client = mock(LanguageClient.class);
        GroovyLanguageServer server = new GroovyLanguageServer();
        server.connect(client);
        GroovyLanguageServerPlugin.setLanguageServer(server);

        GroovyLanguageServerPlugin.logInfo("hello");

        ArgumentCaptor<MessageParams> captor = ArgumentCaptor.forClass(MessageParams.class);
        verify(client).logMessage(captor.capture());
        assertEquals(MessageType.Log, captor.getValue().getType());
        assertEquals("hello", captor.getValue().getMessage());
    }

    @Test
    void logWarningSendsWarningMessageToConnectedClient() {
        LanguageClient client = mock(LanguageClient.class);
        GroovyLanguageServer server = new GroovyLanguageServer();
        server.connect(client);
        GroovyLanguageServerPlugin.setLanguageServer(server);

        GroovyLanguageServerPlugin.logWarning("careful");

        ArgumentCaptor<MessageParams> captor = ArgumentCaptor.forClass(MessageParams.class);
        verify(client).logMessage(captor.capture());
        assertEquals(MessageType.Warning, captor.getValue().getType());
        assertEquals("careful", captor.getValue().getMessage());
    }

    @Test
    void logErrorWithThrowableIncludesThrowableTypeAndMessage() {
        LanguageClient client = mock(LanguageClient.class);
        GroovyLanguageServer server = new GroovyLanguageServer();
        server.connect(client);
        GroovyLanguageServerPlugin.setLanguageServer(server);

        IllegalStateException failure = new IllegalStateException("boom");
        GroovyLanguageServerPlugin.logError("failed", failure);

        ArgumentCaptor<MessageParams> captor = ArgumentCaptor.forClass(MessageParams.class);
        verify(client).logMessage(captor.capture());
        assertEquals(MessageType.Error, captor.getValue().getType());
        assertTrue(captor.getValue().getMessage().contains("failed"));
        assertTrue(captor.getValue().getMessage().contains("IllegalStateException"));
        assertTrue(captor.getValue().getMessage().contains("boom"));
    }
}

