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

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.osgi.framework.BundleContext;

/**
 * OSGi bundle activator for the Groovy Language Server plugin.
 * <p>
 * Manages the plugin lifecycle and provides static logging utilities
 * and access to the language server singleton.
 */
public class GroovyLanguageServerPlugin extends Plugin {

    public static final String PLUGIN_ID = "org.eclipse.groovy.ls.core";

    private static GroovyLanguageServerPlugin instance;
    private static GroovyLanguageServer languageServer;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        setInstance(this);
        logInfo("Groovy Language Server plugin activated.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        clearPluginState();
        super.stop(context);
    }

    private static void setInstance(GroovyLanguageServerPlugin plugin) {
        instance = plugin;
    }

    private static void clearPluginState() {
        instance = null;
        languageServer = null;
    }

    /**
     * Returns the shared plugin instance.
     */
    public static GroovyLanguageServerPlugin getInstance() {
        return instance;
    }

    /**
     * Returns the plugin's logger, or {@code null} if the plugin is not active.
     */
    public static ILog getPluginLog() {
        GroovyLanguageServerPlugin plugin = instance;
        return (plugin != null) ? plugin.getLog() : null;
    }

    static void setLanguageServer(GroovyLanguageServer server) {
        languageServer = server;
    }

    public static GroovyLanguageServer getLanguageServer() {
        return languageServer;
    }

    // ---- Logging helpers ----

    public static void logInfo(String message) {
        log(IStatus.INFO, message, null);
    }

    public static void logWarning(String message) {
        log(IStatus.WARNING, message, null);
    }

    public static void logError(String message) {
        log(IStatus.ERROR, message, null);
    }

    public static void logError(String message, Throwable throwable) {
        log(IStatus.ERROR, message, throwable);
    }

    private static void log(int severity, String message, Throwable throwable) {
        // Log to Eclipse platform log
        ILog logger = getPluginLog();
        if (logger != null) {
            logger.log(new Status(severity, PLUGIN_ID, message, throwable));
        }

        // Also send to client via window/logMessage so it appears in the output channel
        try {
            GroovyLanguageServer server = languageServer;
            if (server != null) {
                LanguageClient client = server.getClient();
                if (client != null) {
                    MessageType type;
                    switch (severity) {
                        case IStatus.ERROR:
                            type = MessageType.Error;
                            break;
                        case IStatus.WARNING:
                            type = MessageType.Warning;
                            break;
                        default:
                            type = MessageType.Log;
                            break;
                    }
                    String fullMessage = message;
                    if (throwable != null) {
                        fullMessage += " — " + throwable.getClass().getName() + ": " + throwable.getMessage();
                    }
                    client.logMessage(new MessageParams(type, fullMessage));
                }
            }
        } catch (Exception e) {
            // Don't recurse — just swallow
        }
    }
}
