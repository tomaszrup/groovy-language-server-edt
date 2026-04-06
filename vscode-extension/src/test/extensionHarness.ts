import * as path from 'node:path';
import mockRequire = require('mock-require');

type ExecFileCallback = (error: Error | null, stdout: string, stderr: string) => void;
type ExecFileImplementation = (
    file: string,
    args: string[],
    options: unknown,
    callback: ExecFileCallback
) => void;

export interface ExtensionHarness {
    readonly commandsExecuted: Array<{ command: string; args: unknown[] }>;
    readonly errorMessages: string[];
    readonly outputLines: string[];
    readonly registeredProviders: Array<{ scheme: string; provider: unknown }>;
    readonly statusBarItem: FakeStatusBarItem;
    readonly testing: any;
    readonly vscode: any;
    readonly FakeLanguageClient: typeof FakeLanguageClient;
    cleanup(): void;
    flush(): Promise<void>;
    invokeRegisteredCommand(command: string, ...args: unknown[]): Promise<unknown>;
    registerExtension(id: string, extension: any): void;
    setCommandHandler(command: string, handler: (...args: unknown[]) => unknown): void;
    setConfiguration(values: Record<string, unknown>): void;
    setExecFileImplementation(implementation: ExecFileImplementation): void;
    setErrorMessageResponse(response: string | undefined): void;
}

class FakeDisposable {
    constructor(private readonly onDispose: () => void = () => undefined) {}

    dispose(): void {
        this.onDispose();
    }
}

class FakeEventEmitter<T> {
    private listeners: Array<(value: T) => void> = [];

    readonly event = (listener: (value: T) => void): FakeDisposable => {
        this.listeners.push(listener);
        return new FakeDisposable(() => {
            this.listeners = this.listeners.filter(candidate => candidate !== listener);
        });
    };

    fire(value: T): void {
        for (const listener of [...this.listeners]) {
            listener(value);
        }
    }

    dispose(): void {
        this.listeners = [];
    }
}

class FakeWatcher extends FakeDisposable {
    readonly onDidChange = () => new FakeDisposable();
    readonly onDidCreate = () => new FakeDisposable();
    readonly onDidDelete = () => new FakeDisposable();
}

class FakeStatusBarItem extends FakeDisposable {
    text = '';
    tooltip: string | undefined;
    backgroundColor: unknown;
    command: string | undefined;
    name = '';
    showCount = 0;

    show(): void {
        this.showCount++;
    }
}

class FakeLanguageClient {
    static instances: FakeLanguageClient[] = [];

    readonly notifications: Array<{ method: string; payload: unknown }> = [];
    private readonly stateHandlers: Array<(event: { oldState: number; newState: number }) => void> = [];
    private readonly notificationHandlers = new Map<string, (payload: unknown) => void>();
    startError: Error | undefined;
    stopError: Error | undefined;

    constructor(
        readonly id: string,
        readonly name: string,
        readonly serverOptions: unknown,
        readonly clientOptions: unknown
    ) {
        FakeLanguageClient.instances.push(this);
    }

    async start(): Promise<void> {
        if (this.startError) {
            throw this.startError;
        }
    }

    async stop(): Promise<void> {
        if (this.stopError) {
            throw this.stopError;
        }
    }

    sendNotification(method: string, payload: unknown): void {
        this.notifications.push({ method, payload });
    }

    onDidChangeState(handler: (event: { oldState: number; newState: number }) => void): FakeDisposable {
        this.stateHandlers.push(handler);
        return new FakeDisposable();
    }

    onNotification(method: string, handler: (payload: unknown) => void): FakeDisposable {
        this.notificationHandlers.set(method, handler);
        return new FakeDisposable();
    }

    emitStateChange(event: { oldState: number; newState: number }): void {
        for (const handler of this.stateHandlers) {
            handler(event);
        }
    }

    emitNotification(method: string, payload: unknown): void {
        this.notificationHandlers.get(method)?.(payload);
    }
}

export function createExtensionHarness(): ExtensionHarness {
    let configurationValues: Record<string, unknown> = {};
    let errorMessageResponse: string | undefined;
    let execFileImplementation: ExecFileImplementation = (_file, _args, _options, callback) => {
        callback(null, '', 'openjdk version "21.0.4"');
    };

    const outputLines: string[] = [];
    const errorMessages: string[] = [];
    const registeredProviders: Array<{ scheme: string; provider: unknown }> = [];
    const registeredCommands = new Map<string, (...args: unknown[]) => unknown>();
    const commandsExecuted: Array<{ command: string; args: unknown[] }> = [];
    const commandHandlers = new Map<string, (...args: unknown[]) => unknown>();
    const extensions = new Map<string, any>();
    const statusBarItem = new FakeStatusBarItem();
    const outputChannel = {
        appendLine(line: string): void {
            outputLines.push(line);
        },
        show(): void {
            return undefined;
        },
        dispose(): void {
            return undefined;
        },
    };

    const workspaceMock = {
        textDocuments: [] as Array<{ languageId: string; uri: { scheme: string; toString(): string } }>,
        workspaceFolders: [] as Array<{ uri: { fsPath: string; toString(): string } }>,
        async findFiles(): Promise<Array<{ fsPath: string; toString(): string }>> {
            return [];
        },
        createFileSystemWatcher(): FakeWatcher {
            return new FakeWatcher();
        },
        getConfiguration(): { get<T>(section: string, defaultValue?: T): T | undefined } {
            return {
                get<T>(section: string, defaultValue?: T): T | undefined {
                    return (section in configurationValues
                        ? configurationValues[section]
                        : defaultValue) as T | undefined;
                },
            };
        },
        onDidChangeConfiguration(): FakeDisposable {
            return new FakeDisposable();
        },
        registerTextDocumentContentProvider(scheme: string, provider: unknown): FakeDisposable {
            registeredProviders.push({ scheme, provider });
            return new FakeDisposable();
        },
    };

    const commandsMock = {
        async executeCommand(command: string, ...args: unknown[]): Promise<unknown> {
            commandsExecuted.push({ command, args });
            return commandHandlers.get(command)?.(...args);
        },
        registerCommand(command: string, callback: (...args: unknown[]) => unknown): FakeDisposable {
            registeredCommands.set(command, callback);
            return new FakeDisposable();
        },
    };

    const vscodeMock = {
        workspace: workspaceMock,
        window: {
            createOutputChannel(): typeof outputChannel {
                return outputChannel;
            },
            createStatusBarItem(): FakeStatusBarItem {
                return statusBarItem;
            },
            showErrorMessage(message: string): Promise<undefined> {
                errorMessages.push(message);
                return Promise.resolve(errorMessageResponse as undefined);
            },
        },
        commands: commandsMock,
        extensions: {
            getExtension(id: string): unknown {
                return extensions.get(id);
            },
        },
        OutputChannel: class {},
        StatusBarItem: class {},
        StatusBarAlignment: { Left: 1 },
        ThemeColor: class {
            constructor(readonly id: string) {}
        },
        Disposable: FakeDisposable,
        EventEmitter: FakeEventEmitter,
        Position: class {
            constructor(readonly line: number, readonly character: number) {}
        },
        Range: class {
            constructor(readonly start: unknown, readonly end: unknown) {}
        },
        Location: class {
            constructor(readonly uri: unknown, readonly range: unknown) {}
        },
        RelativePattern: class {
            constructor(readonly base: unknown, readonly pattern: string) {}
        },
        Uri: {
            file(fsPath: string): { fsPath: string; toString(): string } {
                const normalized = fsPath.startsWith('/') ? fsPath : `/${fsPath}`;
                return {
                    fsPath,
                    toString(): string {
                        return `file://${normalized}`;
                    },
                };
            },
            parse(value: string): { fsPath: string; toString(): string } {
                if (value.startsWith('file://')) {
                    const url = new URL(value);
                    return { fsPath: decodeURIComponent(url.pathname), toString: () => value };
                }
                return { fsPath: value, toString: () => value };
            },
        },
    };

    mockRequire.stopAll();
    mockRequire('vscode', vscodeMock);
    mockRequire('vscode-languageclient/node', {
        LanguageClient: FakeLanguageClient,
        TransportKind: { stdio: 'stdio' },
    });
    mockRequire('node:child_process', {
        execFile(file: string, args: string[], options: unknown, callback: ExecFileCallback): void {
            execFileImplementation(file, args, options, callback);
        },
    });

    delete require.cache[require.resolve('../extension')];
    const extensionModule = require('../extension');
    extensionModule.__testing.setOutputChannel(outputChannel);
    extensionModule.__testing.setStatusBarItem(statusBarItem);
    extensionModule.__testing.setClient(undefined);
    extensionModule.__testing.setActiveServerSession(undefined);
    extensionModule.__testing.setCachedJavaApi(null);
    extensionModule.__testing.setIsRestarting(false);
    extensionModule.__testing.clearSentClasspathFingerprints();

    return {
        commandsExecuted,
        errorMessages,
        outputLines,
        registeredProviders,
        statusBarItem,
        testing: extensionModule.__testing,
        vscode: vscodeMock,
        FakeLanguageClient,
        cleanup(): void {
            mockRequire.stopAll();
            delete require.cache[require.resolve('../extension')];
            FakeLanguageClient.instances.length = 0;
        },
        async flush(): Promise<void> {
            await new Promise(resolve => setImmediate(resolve));
        },
        async invokeRegisteredCommand(command: string, ...args: unknown[]): Promise<unknown> {
            return registeredCommands.get(command)?.(...args);
        },
        registerExtension(id: string, extension: any): void {
            extensions.set(id, extension);
        },
        setCommandHandler(command: string, handler: (...args: unknown[]) => unknown): void {
            commandHandlers.set(command, handler);
        },
        setConfiguration(values: Record<string, unknown>): void {
            configurationValues = values;
        },
        setExecFileImplementation(implementation: ExecFileImplementation): void {
            execFileImplementation = implementation;
        },
        setErrorMessageResponse(response: string | undefined): void {
            errorMessageResponse = response;
        },
    };
}