import { strict as assert } from 'assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { createExtensionHarness } from './extensionHarness';

describe('extension startup', () => {
    afterEach(function () {
        (this as any).harness?.cleanup();
    });

    it('surfaces missing server binaries during startup', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-startup-missing-'));
        const context = createExtensionContext(tempDir, tempDir);
        harness.setConfiguration({ 'java.home': createJdk(tempDir) });

        try {
            await harness.testing.startLanguageServer(context);
            assert.equal(harness.errorMessages.some(message => message.includes('Server binaries not found')), true);
        } finally {
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });

    it('starts the language client and wires the baseline session resources', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-startup-success-'));
        createServerDir(tempDir);
        const context = createExtensionContext(tempDir, tempDir);
        harness.setConfiguration({
            'java.home': createJdk(tempDir),
            'ls.vmargs': '-Xmx3G',
        });

        try {
            await harness.testing.startLanguageServer(context);

            const client = harness.testing.getClient();
            assert.notEqual(client, undefined);
            assert.equal(harness.registeredProviders.length, 1);
            assert.equal(client.notifications.some((entry: any) => entry.method === 'workspace/didChangeConfiguration'), true);
            assert.equal(harness.outputLines.some(line => line.includes('Groovy Language Server started successfully.')), true);
        } finally {
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });

    it('handles language client startup failures', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-startup-failure-'));
        createServerDir(tempDir);
        const context = createExtensionContext(tempDir, tempDir);
        harness.setConfiguration({ 'java.home': createJdk(tempDir) });
        const originalStart = harness.FakeLanguageClient.prototype.start;
        harness.FakeLanguageClient.prototype.start = async function (): Promise<void> {
            throw new Error('boom');
        };

        try {
            await harness.testing.startLanguageServer(context);
            assert.equal(harness.errorMessages.some(message => message.includes('failed to start')), true);
        } finally {
            harness.FakeLanguageClient.prototype.start = originalStart;
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });

    it('delegates classpath updates from the Java extension and completes the initial batch', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const session = harness.testing.createServerSessionScope();
        const client = new harness.FakeLanguageClient('groovy', 'Groovy Language Server', {}, {});
        harness.testing.setActiveServerSession(session);
        harness.testing.setClient(client);
        harness.vscode.workspace.workspaceFolders = [{
            uri: {
                fsPath: '/workspace/app',
                toString: () => 'file:///workspace/app',
            },
        }];
        harness.vscode.workspace.textDocuments = [{
            languageId: 'groovy',
            uri: { scheme: 'file', toString: () => 'file:///workspace/app/src/main/groovy/App.groovy' },
        }];
        harness.vscode.workspace.findFiles = async () => [{
            fsPath: '/workspace/app/build.gradle',
            toString: () => 'file:///workspace/app/build.gradle',
        }];
        harness.setCommandHandler('java.project.getAll', () => ['file:///workspace/app']);

        const javaApi: any = {
            serverMode: 'Standard',
            apiVersion: '1.0.0',
            async getClasspaths(): Promise<{ classpaths: string[]; modulepaths: string[] }> {
                return {
                    classpaths: ['/workspace/app/libs/app.jar'],
                    modulepaths: [],
                };
            },
            onDidClasspathUpdate(listener: (uri: { toString(): string }) => Promise<void>): { dispose(): void } {
                javaApi.classpathListener = listener;
                return { dispose(): void {} };
            },
            onDidProjectsImport(listener: (uris: Array<{ toString(): string }>) => void): { dispose(): void } {
                javaApi.importListener = listener;
                return { dispose(): void {} };
            },
            onDidServerModeChange(listener: (mode: string) => void): { dispose(): void } {
                javaApi.serverModeListener = listener;
                return { dispose(): void {} };
            },
        };
        harness.testing.setCachedJavaApi(javaApi);

        await harness.testing.initJavaExtensionClasspath(session, client);
        await harness.flush();

        assert.equal(client.notifications.some((entry: any) => entry.method === 'groovy/classpathUpdate'), true);
        assert.equal(client.notifications.some((entry: any) => entry.method === 'groovy/classpathBatchComplete'), true);

        await javaApi.classpathListener({ toString: () => 'file:///workspace/app' });
        await harness.flush();
        assert.equal(harness.outputLines.some(line => line.includes('Classpath updated for: file:///workspace/app')), true);
    });

    it('activates the extension and exercises the registered command and client callbacks', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-activate-'));
        createServerDir(tempDir);
        harness.setConfiguration({ 'java.home': createJdk(tempDir) });
        harness.setCommandHandler('editor.action.triggerSuggest', () => undefined);
        harness.setCommandHandler('editor.action.triggerParameterHints', () => undefined);
        harness.setCommandHandler('vscode.executeReferenceProvider', () => []);
        harness.setErrorMessageResponse('Restart');
        harness.registerExtension('redhat.java', {
            isActive: true,
            exports: {
                apiVersion: '1.0.0',
                serverMode: 'Standard',
            },
        });

        const context = {
            ...createExtensionContext(tempDir, tempDir),
            subscriptions: {
                push(): void {
                    return undefined;
                },
            },
        };

        try {
            await (require('../extension') as typeof import('../extension')).activate(context as any);
            await harness.invokeRegisteredCommand('groovy.triggerSuggestAndSignatureHelp');
            await harness.invokeRegisteredCommand('groovy.showOutputChannel');
            await harness.invokeRegisteredCommand('groovy.codeLensNoop');
            await harness.invokeRegisteredCommand(
                'groovy.showReferences',
                'file:///workspace/app/src/main/groovy/App.groovy',
                { line: 1, character: 2 },
                []
            );
            await harness.invokeRegisteredCommand('groovy.restartServer');

            const client = harness.testing.getClient();
            client.emitNotification('groovy/status', { state: 'Ready', message: 'done' });
            client.emitStateChange({ oldState: 2, newState: 1 });
            await harness.flush();

            assert.equal(harness.commandsExecuted.some(entry => entry.command === 'editor.action.triggerSuggest'), true);
            assert.equal(harness.commandsExecuted.some(entry => entry.command === 'editor.action.showReferences'), true);
            assert.equal(harness.commandsExecuted.some(entry => entry.command === 'groovy.restartServer'), true);
        } finally {
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });
});

function createExtensionContext(rootDir: string, extensionPath: string) {
    const storagePath = path.join(rootDir, 'storage');
    fs.mkdirSync(storagePath, { recursive: true });
    return {
        storageUri: { fsPath: storagePath },
        globalStorageUri: { fsPath: storagePath },
        extensionPath,
        extension: {
            packageJSON: {
                version: '1.2.37',
            },
        },
    };
}

function createJdk(rootDir: string): string {
    const jdkDir = path.join(rootDir, 'jdk');
    const binDir = path.join(jdkDir, 'bin');
    fs.mkdirSync(binDir, { recursive: true });
    fs.writeFileSync(path.join(binDir, 'java'), '', 'utf8');
    return jdkDir;
}

function createServerDir(rootDir: string): string {
    const serverDir = path.join(rootDir, 'server');
    const pluginsDir = path.join(serverDir, 'plugins');
    const configDir = path.join(serverDir, 'config_linux');
    fs.mkdirSync(pluginsDir, { recursive: true });
    fs.mkdirSync(configDir, { recursive: true });
    fs.writeFileSync(path.join(pluginsDir, 'org.eclipse.equinox.launcher_1.7.0.jar'), 'jar', 'utf8');
    fs.writeFileSync(path.join(pluginsDir, 'org.eclipse.groovy.ls.core_1.0.0.jar'), 'x'.repeat(60_000), 'utf8');
    fs.writeFileSync(path.join(configDir, 'config.ini'), 'osgi.bundles=alpha\n', 'utf8');
    return serverDir;
}