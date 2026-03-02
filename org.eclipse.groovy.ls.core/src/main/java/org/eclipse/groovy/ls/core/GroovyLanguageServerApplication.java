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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Eclipse application entry point for the Groovy Language Server.
 * <p>
 * This is the headless Equinox application that bootstraps the LSP server.
 * It captures stdin/stdout for JSON-RPC transport and blocks the main thread
 * until the server is shut down.
 * <p>
 * Registered via {@code plugin.xml} as application {@code org.eclipse.groovy.ls.core.id1}.
 */
public class GroovyLanguageServerApplication implements IApplication {

    private final Object waitLock = new Object();
    private volatile boolean shutdown = false;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Mark application as running so Equinox doesn't show a splash screen
        context.applicationRunning();

        // Capture stdin/stdout before anything else touches them.
        // The JSON-RPC transport uses these exclusively — any stray output
        // to stdout would corrupt the protocol stream.
        InputStream in = System.in;
        OutputStream out = new FileOutputStream(FileDescriptor.out);

        // Redirect stdout/stderr to the plugin's log to prevent corruption
        // of the JSON-RPC channel
        System.setOut(new PrintStream(new LogOutputStream(LogOutputStream.Level.INFO)));
        System.setErr(new PrintStream(new LogOutputStream(LogOutputStream.Level.ERROR)));

        GroovyLanguageServerPlugin.logInfo("Starting Groovy Language Server...");

        // Create the language server instance
        GroovyLanguageServer server = new GroovyLanguageServer();

        // Build the LSP launcher with JSON-RPC over stdio
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
                server, in, out);

        // Wire the client proxy into the server so it can send notifications
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        // Wire the remote endpoint for custom notifications (e.g., groovy/status)
        server.setRemoteEndpoint(launcher.getRemoteEndpoint());

        // Store the server reference for shutdown
        GroovyLanguageServerPlugin.setLanguageServer(server);

        // Start listening for JSON-RPC messages (non-blocking — runs on I/O threads)
        launcher.startListening();

        GroovyLanguageServerPlugin.logInfo("Groovy Language Server started, listening on stdio.");

        // Block the main thread until shutdown is signaled
        synchronized (waitLock) {
            while (!shutdown) {
                try {
                    waitLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        shutdown = true;
        synchronized (waitLock) {
            waitLock.notifyAll();
        }
    }

    /**
     * Signal the application to exit. Called by the language server
     * when it receives the LSP {@code exit} notification.
     */
    public void exit() {
        stop();
    }
}
