/*---------------------------------------------------------------------------------------------
 *  Copyright (c) 2026 Groovy Language Server Contributors.
 *  Licensed under the EPL-2.0. See LICENSE in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as path from 'node:path';
import * as fs from 'node:fs';
import * as os from 'node:os';
import { execFile, ExecFileSyncOptionsWithStringEncoding } from 'node:child_process';
import * as vscode from 'vscode';
import { workspace, ExtensionContext, window, commands, OutputChannel, StatusBarItem, StatusBarAlignment, ThemeColor } from 'vscode';
import {
    inferProjectPathFromEntries,
    isJdtWorkspaceUri,
    normalizeFsPath,
    pathStartsWith,
    uriToFsPath,
} from './utils';
import { prepareWritableConfigDir } from './serverConfig';
import {
    InitialClasspathStartupTracker,
    type PendingStartupClasspathTargetHandle,
    type PendingStartupClasspathSnapshot,
    type StartupClasspathTarget,
} from './startupTracking';
import {
    getInitialClasspathImportRefreshAction,
    getInitialClasspathRetryAction,
} from './startupRetryPolicy';
import { detectWorkspaceRestoreCorruption } from './workspaceRecovery';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    Executable,
    TransportKind,
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let outputChannel: OutputChannel;
let statusBarItem: StatusBarItem;
let isRestarting = false;
let activeServerSession: ServerSessionScope | undefined;

interface ClasspathNotificationPayload {
    projectUri: string;
    projectPath?: string;
    entries: string[];
    hasJarEntries: boolean;
}

type JavaClasspathTarget = StartupClasspathTarget;

interface ServerSessionScope {
    client?: LanguageClient;
    disposed: boolean;
    disposables: vscode.Disposable[];
}

/** Cached Java extension API (obtained during wait phase). */
let cachedJavaApi: any = null;
const sentClasspathFingerprints = new Map<string, string>();

function createServerSessionScope(): ServerSessionScope {
    return {
        disposed: false,
        disposables: [],
    };
}

function addSessionDisposables(scope: ServerSessionScope, ...disposables: vscode.Disposable[]): void {
    if (scope.disposed) {
        for (const disposable of disposables) {
            disposable.dispose();
        }
        return;
    }
    scope.disposables.push(...disposables);
}

function isServerSessionActive(scope: ServerSessionScope, languageClient?: LanguageClient): boolean {
    if (scope.disposed || activeServerSession !== scope) {
        return false;
    }
    return !languageClient || client === languageClient;
}

function disposeServerSessionScope(scope: ServerSessionScope | undefined = activeServerSession): void {
    if (!scope || scope.disposed) {
        return;
    }

    scope.disposed = true;
    for (let i = scope.disposables.length - 1; i >= 0; i--) {
        try {
            scope.disposables[i].dispose();
        } catch (e) {
            outputChannel?.appendLine(`Error disposing Groovy session resource: ${e}`);
        }
    }
    scope.disposables.length = 0;

    if (activeServerSession === scope) {
        activeServerSession = undefined;
    }
}

async function stopLanguageServer(): Promise<void> {
    const existingSession = activeServerSession;
    const existingClient = client;

    disposeServerSessionScope(existingSession);

    if (existingClient) {
        try {
            await existingClient.stop();
        } catch (e) {
            outputChannel.appendLine(`Error stopping language server: ${e}`);
        }
    }

    if (client === existingClient) {
        client = undefined;
    }
}

class StartupTrace {
    private readonly startedAt = Date.now();
    private lastMarkAt = this.startedAt;

    mark(phase: string, details?: string): void {
        const now = Date.now();
        const sinceLast = now - this.lastMarkAt;
        const total = now - this.startedAt;
        outputChannel.appendLine(
            `[startup] ${phase} (+${sinceLast} ms, ${total} ms total)`
            + (details ? ` - ${details}` : '')
        );
        this.lastMarkAt = now;
    }

    duration(phase: string, phaseStartAt: number, details?: string): void {
        const now = Date.now();
        const total = now - this.startedAt;
        outputChannel.appendLine(
            `[startup] ${phase} completed in ${now - phaseStartAt} ms (${total} ms total)`
            + (details ? ` - ${details}` : '')
        );
    }
}

interface WorkspaceDataPreparation {
    dataDir: string;
    resetWorkspace: boolean;
}

let startupTrace: StartupTrace | undefined;

/** Scheme for virtual source documents from JARs/JDK. */
const GROOVY_SOURCE_SCHEME = 'groovy-source';
const GRADLE_CLASSPATH_LINE_PREFIX = 'GROOVY_LS_CP::';
const REPRESENTATIVE_SOURCE_GLOB = '{src/main/java/**/*.java,src/main/groovy/**/*.groovy,src/test/java/**/*.java,src/test/groovy/**/*.groovy}';
const BUILD_DESCRIPTOR_GLOB = '{**/build.gradle,**/build.gradle.kts,**/settings.gradle,**/settings.gradle.kts,**/pom.xml}';
const MINIMUM_JAVA_MAJOR = 21;
const WORKSPACE_DATA_VERSION = 2;
const WORKSPACE_DATA_STATE_FILE = 'groovy_ws_state.json';

/**
 * Cached project root map to avoid repeatedly scanning the workspace for
 * build.gradle / pom.xml on every classpath event.  Invalidated by the
 * file system watcher for build files so it stays up-to-date.
 */
let cachedProjectRootMap: Map<string, string> | undefined;
/** Timestamp of the last cache refresh — used for staleness checks. */
let projectRootMapTimestamp = 0;
/** Cache representative source lookups per project root to avoid repeated findFiles scans. */
const representativeSourceUriCache = new Map<string, string | null>();

/**
 * Content provider for groovy-source:// virtual documents.
 * When VS Code opens a groovy-source URI (e.g., from go-to-definition on a binary type),
 * this provider asks the language server to resolve the source content.
 */
class GroovySourceContentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
    private readonly _client: LanguageClient
    private readonly _onDidChange = new vscode.EventEmitter<vscode.Uri>();
    readonly onDidChange = this._onDidChange.event;

    constructor(client: LanguageClient) {
        this._client = client;
    }

    async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
        try {
            const content = await this._client.sendRequest<string>(
                'groovy/resolveSource',
                { uri: uri.toString() }
            );
            return content ?? '// Source not available\n';
        } catch (e) {
            outputChannel.appendLine(`[groovy-source] Failed to resolve: ${uri.toString()} — ${e}`);
            return `// Error resolving source: ${e}\n`;
        }
    }

    dispose(): void {
        this._onDidChange.dispose();
    }
}

/** Server status states */
type ServerState = 'WaitingForJava' | 'Starting' | 'Importing' | 'Compiling' | 'Ready' | 'Error' | 'Stopped';

interface StatusNotification {
    state: ServerState;
    message?: string;
}

function beginStartupTrace(): void {
    startupTrace = new StartupTrace();
    startupTrace.mark('startup.begin');
}

function markStartupPhase(phase: string, details?: string): void {
    startupTrace?.mark(phase, details);
}

function logStartupDuration(phase: string, phaseStartAt: number, details?: string): void {
    startupTrace?.duration(phase, phaseStartAt, details);
}

async function collectProjectRootMap(): Promise<Map<string, string>> {
    // Return the cached map if it's fresh (less than 30 s old).
    // Invalidation also happens via file system watcher on build file changes.
    const now = Date.now();
    if (cachedProjectRootMap && (now - projectRootMapTimestamp) < 30_000) {
        return cachedProjectRootMap;
    }

    const rootMap = new Map<string, string>();
    const buildFiles = await vscode.workspace.findFiles(
        BUILD_DESCRIPTOR_GLOB,
        '{**/node_modules/**,**/build/**,**/.gradle/**}'
    );

    for (const buildFile of buildFiles) {
        const projectDir = path.dirname(buildFile.fsPath);
        rootMap.set(normalizeFsPath(projectDir), projectDir);
    }

    cachedProjectRootMap = rootMap;
    projectRootMapTimestamp = now;
    return rootMap;
}

/** Invalidate the cached project root map (called when build files change). */
function invalidateProjectRootMapCache(): void {
    cachedProjectRootMap = undefined;
    projectRootMapTimestamp = 0;
    representativeSourceUriCache.clear();
}

function resolveProjectPathFromUri(
    uriValue: string,
    projectRootMap: Map<string, string>
): string | undefined {
    const fsPath = uriToFsPath(uriValue);
    if (!fsPath) return undefined;

    const normalizedPath = normalizeFsPath(fsPath);
    const exact = projectRootMap.get(normalizedPath);
    if (exact) return exact;

    let bestMatch: string | undefined;
    for (const [projectNorm] of projectRootMap) {
        if (pathStartsWith(normalizedPath, projectNorm)) {
            if (!bestMatch || projectNorm.length > bestMatch.length) {
                bestMatch = projectNorm;
            }
        }
    }

    return bestMatch ? projectRootMap.get(bestMatch) : undefined;
}

async function findRepresentativeSourceUri(projectPath: string): Promise<string | undefined> {
    const normalizedProjectPath = normalizeFsPath(projectPath);
    if (representativeSourceUriCache.has(normalizedProjectPath)) {
        return representativeSourceUriCache.get(normalizedProjectPath) ?? undefined;
    }

    const projectUri = vscode.Uri.file(projectPath);
    const matches = await workspace.findFiles(
        new vscode.RelativePattern(projectUri, REPRESENTATIVE_SOURCE_GLOB),
        '**/build/**',
        1
    );
    const sourceUri = matches[0]?.toString();
    representativeSourceUriCache.set(normalizedProjectPath, sourceUri ?? null);
    return sourceUri;
}

function findGradleWrapper(startDir: string): string | undefined {
    const exeName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    let currentDir = path.resolve(startDir);

    while (true) {
        const candidate = path.join(currentDir, exeName);
        if (fs.existsSync(candidate)) {
            return candidate;
        }

        const parent = path.dirname(currentDir);
        if (parent === currentDir) {
            return undefined;
        }
        currentDir = parent;
    }
}

function extractPathFromClasspathEntry(entry: any): string {
    const rawPath = entry?.path;
    if (typeof rawPath === 'string') {
        if (rawPath.startsWith('file:/')) {
            try {
                return vscode.Uri.parse(rawPath).fsPath;
            } catch {
                return rawPath;
            }
        }
        return rawPath;
    }

    if (rawPath && typeof rawPath.path === 'string') {
        return rawPath.path;
    }

    if (rawPath && typeof rawPath.fsPath === 'string') {
        return rawPath.fsPath;
    }

    return '';
}

/**
 * Check if a classpath entry is inside a JDK installation (already have JRE_CONTAINER).
 */
function isJdkEntry(entryPath: string, javaHomeNorm: string): boolean {
    const norm = entryPath.replaceAll('\\', '/').toLowerCase();
    if (javaHomeNorm && norm.startsWith(javaHomeNorm)) return true;
    // Heuristic: JDK-like paths not in dependency caches
    if ((norm.includes('/jdk-') || norm.includes('/jdk/') || norm.includes('/jre/'))
        && !norm.includes('.gradle/') && !norm.includes('.m2/')) {
        return true;
    }
    return false;
}

/**
 * Extract JAR paths and output dirs from classpathEntries returned by
 * java.project.getSettings with the 'org.eclipse.jdt.ls.core.vm.location'
 * and 'org.eclipse.jdt.ls.core.classpathEntries' keys.
 * Each entry has: { kind: number, path: string, entryKind: number, ... }
 * kind 1=source, 2=container, 3=library, 4=output
 */
function extractEntriesFromSettings(
    settingsResult: any
): { jars: string[]; outputDirs: string[] } {
    const jars: string[] = [];
    const outputDirs: string[] = [];
    const entries: any[] =
        settingsResult?.['org.eclipse.jdt.ls.core.classpathEntries'] ?? [];
    for (const entry of entries) {
        const p = extractPathFromClasspathEntry(entry);
        if (!p) continue;
        if (p.toLowerCase().endsWith('.jar')) {
            jars.push(p);
        } else if (entry.kind === 4 || entry.entryKind === 4) {
            // kind=4 is output
            outputDirs.push(p);
        } else if (entry.kind === 3 || entry.entryKind === 3) {
            // kind=3 is library (could be a folder lib)
            jars.push(p);
        }
    }
    return { jars, outputDirs };
}

async function resolveGradleClasspath(projectPath: string): Promise<string[]> {
    const normalizedProjectPath = path.resolve(projectPath);
    const wrapper = findGradleWrapper(normalizedProjectPath);
    if (!wrapper) {
        outputChannel.appendLine(`[java-ext]   Gradle fallback skipped (wrapper not found) for ${projectPath}`);
        return [];
    }

    const initScriptPath = path.join(
        os.tmpdir(),
        `groovy-ls-classpath-${Date.now()}-${Math.random().toString(16).slice(2)}.gradle`
    );

    const initScript = `allprojects {
    tasks.register('groovyLsPrintClasspath') {
        doLast {
            ['testCompileClasspath', 'testRuntimeClasspath', 'compileClasspath', 'runtimeClasspath'].each { cfgName ->
                def cfg = project.configurations.findByName(cfgName)
                if (cfg != null && cfg.canBeResolved) {
                    try {
                        cfg.resolve().each { f ->
                            println('${GRADLE_CLASSPATH_LINE_PREFIX}' + f.absolutePath)
                        }
                    } catch (Throwable ignored) {
                        // ignore and continue with other configs
                    }
                }
            }
        }
    }
}
`;

    try {
        fs.writeFileSync(initScriptPath, initScript, { encoding: 'utf8' });
        const gradleArgs = [
            '-q',
            '-I', initScriptPath,
            '-p', normalizedProjectPath,
            'groovyLsPrintClasspath',
        ];

        const execOptions: ExecFileSyncOptionsWithStringEncoding = {
            cwd: path.dirname(wrapper),
            encoding: 'utf8',
            windowsHide: true,
            timeout: 120000,
            maxBuffer: 16 * 1024 * 1024,
            stdio: ['ignore', 'pipe', 'pipe'],
        };

        // Use async execFile to avoid blocking the VS Code extension host
        // (single-threaded) for up to 120 seconds during Gradle resolution.
        const stdout = await new Promise<string>((resolve, reject) => {
            const useShell = process.platform === 'win32' && wrapper.toLowerCase().endsWith('.bat');
            const opts = { ...execOptions, shell: useShell };
            execFile(wrapper, gradleArgs, opts, (error, stdout, stderr) => {
                if (error) {
                    (error as any).stderr = stderr;
                    reject(toError(error));
                } else {
                    resolve(stdout);
                }
            });
        });

        const lines = stdout.split(/\r?\n/)
            .map(line => line.trim())
            .filter(line => line.startsWith(GRADLE_CLASSPATH_LINE_PREFIX))
            .map(line => line.substring(GRADLE_CLASSPATH_LINE_PREFIX.length).trim())
            .filter(line => line.length > 0 && fs.existsSync(line));

        const unique = Array.from(new Set(lines));
        outputChannel.appendLine(
            `[java-ext]   Gradle fallback resolved ${unique.length} entries for ${projectPath}`
        );
        return unique;
    } catch (error: any) {
        let stderr = '';
        if (typeof error?.stderr === 'string') {
            stderr = error.stderr.trim();
        } else if (error?.stderr && typeof error.stderr.toString === 'function') {
            stderr = error.stderr.toString('utf8').trim();
        }

        outputChannel.appendLine(
            `[java-ext]   Gradle fallback failed for ${projectPath}: ${error?.message ?? error}`
        );
        if (stderr.length > 0) {
            outputChannel.appendLine(`[java-ext]   Gradle fallback stderr: ${stderr}`);
        }
        if (error?.status !== undefined && error?.status !== null) {
            outputChannel.appendLine(`[java-ext]   Gradle fallback exit code: ${error.status}`);
        }
        return [];
    } finally {
        try {
            fs.unlinkSync(initScriptPath);
        } catch {
            // ignore temp cleanup issues
        }
    }
}

function updateStatusBar(state: ServerState, message?: string): void {
    switch (state) {
        case 'WaitingForJava':
            statusBarItem.text = '$(sync~spin) Groovy: Waiting for Java';
            statusBarItem.tooltip = message ?? 'Waiting for Red Hat Java extension to finish importing projects...';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Starting':
            statusBarItem.text = '$(sync~spin) Groovy: Starting';
            statusBarItem.tooltip = message ?? 'Groovy Language Server is starting...';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Importing':
            statusBarItem.text = '$(sync~spin) Groovy: Importing';
            statusBarItem.tooltip = message ?? 'Importing project dependencies...';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Compiling':
            statusBarItem.text = '$(sync~spin) Groovy: Compiling';
            statusBarItem.tooltip = message ?? 'Building workspace...';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Ready':
            statusBarItem.text = '$(check) Groovy: Ready';
            statusBarItem.tooltip = 'Groovy Language Server is ready';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Error':
            statusBarItem.text = '$(error) Groovy: Error';
            statusBarItem.tooltip = message ?? 'Groovy Language Server encountered an error';
            statusBarItem.backgroundColor = new ThemeColor('statusBarItem.errorBackground');
            statusBarItem.command = 'groovy.showOutputChannel';
            break;
        case 'Stopped':
            statusBarItem.text = '$(circle-slash) Groovy: Stopped';
            statusBarItem.tooltip = 'Groovy Language Server is not running';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.command = 'groovy.restartServer';
            break;
    }
    statusBarItem.show();
}

export async function activate(context: ExtensionContext): Promise<void> {
    outputChannel = window.createOutputChannel('Groovy Language Server');
    outputChannel.appendLine('Activating Groovy Language Server extension...');
    beginStartupTrace();

    // Create status bar item (right-aligned, high priority to appear near the left of right section)
    statusBarItem = window.createStatusBarItem(StatusBarAlignment.Left, 0);
    statusBarItem.name = 'Groovy Language Server';
    context.subscriptions.push(
        outputChannel,
        statusBarItem,
        commands.registerCommand('groovy.triggerSuggestAndSignatureHelp', async () => {
            await vscode.commands.executeCommand('editor.action.triggerSuggest');
            await vscode.commands.executeCommand('editor.action.triggerParameterHints');
        }),
        commands.registerCommand('groovy.showOutputChannel', () => {
            outputChannel.show(true);
        }),
        commands.registerCommand('groovy.codeLensNoop', () => {
            return undefined;
        }),
        commands.registerCommand('groovy.showReferences', async (uri: string, position: { line: number; character: number }, locations: Array<{ uri: string; range: { start: { line: number; character: number }; end: { line: number; character: number } } }>) => {
            const vsUri = vscode.Uri.parse(uri);
            const vsPos = new vscode.Position(position.line, position.character);
            const vsLocations = (locations || []).map(loc =>
                new vscode.Location(
                    vscode.Uri.parse(loc.uri),
                    new vscode.Range(
                        new vscode.Position(loc.range.start.line, loc.range.start.character),
                        new vscode.Position(loc.range.end.line, loc.range.end.character)
                    )
                )
            );
            await vscode.commands.executeCommand('editor.action.showReferences', vsUri, vsPos, vsLocations);
        }),
        commands.registerCommand('groovy.restartServer', async () => {
            outputChannel.appendLine('Restarting Groovy Language Server...');
            beginStartupTrace();
            updateStatusBar('Starting', 'Restarting...');
            isRestarting = true;
            await stopLanguageServer();
            await startLanguageServer(context);
        })
    );
    markStartupPhase('activate.commands-registered');

    // Activate the Java extension up front so classpath delegation can start
    // in the background, but do not block Groovy LS startup on project import.
    updateStatusBar('WaitingForJava', 'Activating Java support...');
    const javaReady = await activateJavaExtension();
    markStartupPhase(
        'activate.java-extension',
        javaReady ? 'classpath delegation enabled' : 'starting without delegated classpath'
    );
    if (!javaReady) {
        outputChannel.appendLine('Java extension not available — starting Groovy LS without classpath delegation.');
    }

    await startLanguageServer(context);
    markStartupPhase('activate.complete');
}

/**
 * Activate the Red Hat Java extension so classpath delegation can be used.
 * Returns true if the Java extension API is available.
 */
async function activateJavaExtension(): Promise<boolean> {
    const waitStart = Date.now();
    markStartupPhase('java.activate.begin');
    const javaExtension = vscode.extensions.getExtension('redhat.java');
    if (!javaExtension) {
        outputChannel.appendLine('[java-ext] Red Hat Java extension not installed.');
        logStartupDuration('java.activate', waitStart, 'extension unavailable');
        return false;
    }

    outputChannel.appendLine('[java-ext] Activating Red Hat Java extension...');

    // Activate the Java extension if needed
    let javaApi: any;
    try {
        javaApi = javaExtension.isActive ? javaExtension.exports : await javaExtension.activate();
        markStartupPhase('java.extension-activated', `mode=${javaApi?.serverMode ?? 'unknown'}`);
    } catch (e) {
        outputChannel.appendLine(`[java-ext] Failed to activate: ${e}`);
        logStartupDuration('java.activate', waitStart, 'activation failed');
        return false;
    }

    if (!javaApi) {
        outputChannel.appendLine('[java-ext] Java extension API is null.');
        logStartupDuration('java.activate', waitStart, 'api unavailable');
        return false;
    }

    outputChannel.appendLine('[java-ext] Activated. API version: ' + (javaApi.apiVersion ?? 'unknown'));
    outputChannel.appendLine('[java-ext] Server mode: ' + (javaApi.serverMode ?? 'unknown'));
    cachedJavaApi = javaApi;
    logStartupDuration('java.activate', waitStart, 'java extension activated');

    return true;
}

export async function deactivate(): Promise<void> {
    await stopLanguageServer();
    updateStatusBar('Stopped');
}

async function startLanguageServer(context: ExtensionContext): Promise<void> {
    const startPhase = Date.now();
    markStartupPhase('server.launch.begin');
    isRestarting = false;
    sentClasspathFingerprints.clear();
    const javaExecutable = await resolveJavaExecutableOrShowError();
    if (!javaExecutable) {
        return;
    }
    markStartupPhase('server.java-resolved', javaExecutable);

    outputChannel.appendLine(`Using Java: ${javaExecutable}`);

    // 2. Locate the server installation
    const serverDir = resolveServerDir(context);
    if (!serverDir) {
        updateStatusBar('Error', 'Server binaries not found');
        window.showErrorMessage(
            'Groovy Language Server: Server binaries not found. ' +
            'The extension may need to be rebuilt.'
        );
        return;
    }

    outputChannel.appendLine(`Server directory: ${serverDir}`);

    // 3. Find the Equinox launcher JAR
    const launcherJar = findLauncherJar(serverDir);
    if (!launcherJar) {
        updateStatusBar('Error', 'Equinox launcher JAR not found');
        window.showErrorMessage(
            'Groovy Language Server: org.eclipse.equinox.launcher JAR not found in server/plugins/.'
        );
        return;
    }

    outputChannel.appendLine(`Launcher JAR: ${launcherJar}`);

    // 3b. Validate critical plugin JARs are not corrupt (truncated downloads).
    // A corrupt JAR (e.g. truncated during download from JFrog) causes Equinox
    // to fail with a ZipException and exit code 13 — a confusing error with no
    // actionable message.  Check the most critical JARs upfront.
    if (!validateCriticalPluginJars(serverDir)) {
        return;
    }

    // 4. Copy the bundled Equinox config into VS Code's writable storage.
    // The extension install directory may be read-only, and Equinox needs to
    // write framework metadata into the configuration area on startup.
    const storagePath = context.storageUri?.fsPath ?? context.globalStorageUri.fsPath;
    const configPreparation = prepareWritableConfigDir({
        serverDir,
        storagePath,
        platform: process.platform,
        extensionVersion: String(context.extension.packageJSON.version ?? 'unknown'),
    });
    const configDir = configPreparation.configDir;
    outputChannel.appendLine(`Bundled config directory: ${configPreparation.bundledConfigDir}`);
    outputChannel.appendLine(`Config directory: ${configDir}`);
    if (configPreparation.resetConfig) {
        outputChannel.appendLine(`Reset writable Equinox config: ${configDir} (${configPreparation.reason})`);
        markStartupPhase('server.config-reset', configPreparation.reason);
    } else {
        outputChannel.appendLine(`Reusing writable Equinox config: ${configDir}`);
        markStartupPhase('server.config-reused', configDir);
    }

    // 5. Build the workspace data directory.
    // Preserve the Eclipse workspace across healthy restarts so JDT and OSGi
    // caches can be reused. Invalidate only when the persisted workspace
    // schema changes or the marker is missing/corrupt.
    const workspaceData = prepareWorkspaceDataDir(context);
    const dataDir = workspaceData.dataDir;

    // 6. Build JVM arguments
    const vmargs = workspace.getConfiguration('groovy').get<string>('ls.vmargs', '-Xmx2G');
    const args: string[] = [];

    // JVM module access flags (required for modern JDKs, including JDK 21)
    args.push(
        '--add-modules=ALL-SYSTEM',
        '--add-opens', 'java.base/java.util=ALL-UNNAMED',
        '--add-opens', 'java.base/java.lang=ALL-UNNAMED'
    );

    // VM args from settings
    if (vmargs) {
        args.push(...vmargs.split(/\s+/).filter(a => a.length > 0));
    }

    // Eclipse / Equinox system properties, launcher JAR, and Equinox configuration
    if (workspaceData.resetWorkspace) {
        args.push('-Dosgi.clean=true');
        markStartupPhase('server.workspace-reset', dataDir);
    } else {
        markStartupPhase('server.workspace-reused', dataDir);
    }
    args.push(
        '-Declipse.application=org.eclipse.groovy.ls.core.id1',
        '-Declipse.product=org.eclipse.groovy.ls.core.product',
        '-Dosgi.bundles.defaultStartLevel=4',
        '-Dosgi.checkConfiguration=true',
        '-Dfile.encoding=UTF-8',
        '-jar', launcherJar,
        '-configuration', configDir,
        '-data', dataDir
    );

    outputChannel.appendLine(`Launch args: java ${args.join(' ')}`);

    // 7. Define server options
    const serverOptions: ServerOptions = {
        command: javaExecutable,
        args: args,
        transport: TransportKind.stdio,
    } as Executable;

    // 8. Define client options
    const session = createServerSessionScope();
    activeServerSession = session;
    const groovyWatcher = workspace.createFileSystemWatcher('**/*.groovy');
    const javaWatcher = workspace.createFileSystemWatcher('**/*.java');
    const buildDescriptorWatcher = workspace.createFileSystemWatcher(BUILD_DESCRIPTOR_GLOB);
    addSessionDisposables(session, groovyWatcher, javaWatcher, buildDescriptorWatcher);

    // Build initializationOptions from user settings
    const initOptions = buildInitializationOptions();

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'groovy' },
            { scheme: 'untitled', language: 'groovy' },
            { scheme: GROOVY_SOURCE_SCHEME, language: 'groovy' },
            { scheme: GROOVY_SOURCE_SCHEME, language: 'java' },
        ],
        initializationOptions: initOptions,
        synchronize: {
            fileEvents: [groovyWatcher, javaWatcher, buildDescriptorWatcher],
        },
        outputChannel: outputChannel,
        traceOutputChannel: outputChannel,
        middleware: {
            executeCommand: async (command, args, next) => {
                // Handle client-side commands directly instead of forwarding to server
                if (command === 'groovy.showReferences' || command === 'groovy.codeLensNoop') {
                    return vscode.commands.executeCommand(command, ...(args || []));
                }
                return next(command, args);
            },
        },
    };

    // Invalidate the cached project root map when build files change so that
    // subsequent classpath events pick up new or removed subprojects.
    addSessionDisposables(
        session,
        buildDescriptorWatcher.onDidChange(() => invalidateProjectRootMapCache()),
        buildDescriptorWatcher.onDidCreate(() => invalidateProjectRootMapCache()),
        buildDescriptorWatcher.onDidDelete(() => invalidateProjectRootMapCache())
    );

    // 9. Create and start the language client
    client = new LanguageClient(
        'groovy',
        'Groovy Language Server',
        serverOptions,
        clientOptions
    );
    session.client = client;

    outputChannel.appendLine('Starting Groovy Language Server...');
    try {
        await client.start();
        markStartupPhase('server.client-started');
    } catch (e) {
        updateStatusBar('Error', `Failed to start: ${e}`);
        outputChannel.appendLine(`Failed to start Groovy Language Server: ${e}`);
        disposeServerSessionScope(session);
        window.showErrorMessage(
            `Groovy Language Server failed to start: ${e instanceof Error ? e.message : String(e)}. ` +
            'Check the output channel for details.'
        );
        client = undefined;
        return;
    }
    outputChannel.appendLine('Groovy Language Server started successfully.');

    // Monitor client state for unexpected crashes
    addSessionDisposables(
        session,
        client.onDidChangeState((e) => {
            if (e.newState === 1 /* Stopped */ && e.oldState === 2 /* Running */) {
                if (isRestarting || !isServerSessionActive(session, client)) {
                    return;
                }
                outputChannel.appendLine('Groovy Language Server stopped unexpectedly.');
                updateStatusBar('Error', 'Server stopped unexpectedly');
                window.showErrorMessage(
                    'Groovy Language Server stopped unexpectedly. ' +
                    'Use "Groovy: Restart Language Server" to restart.',
                    'Restart'
                ).then(choice => {
                    if (choice === 'Restart') {
                        vscode.commands.executeCommand('groovy.restartServer');
                    }
                });
            }
        })
    );

    // Register the virtual document content provider for groovy-source:// URIs
    const sourceProvider = new GroovySourceContentProvider(client);
    addSessionDisposables(
        session,
        sourceProvider,
        vscode.workspace.registerTextDocumentContentProvider(GROOVY_SOURCE_SCHEME, sourceProvider)
    );
    outputChannel.appendLine('Registered groovy-source:// content provider.');

    // Listen for custom status notifications from the server
    addSessionDisposables(
        session,
        client.onNotification('groovy/status', (params: StatusNotification) => {
            if (!isServerSessionActive(session, client)) {
                return;
            }
            outputChannel.appendLine(`[status] ${params.state}${params.message ? ': ' + params.message : ''}`);
            updateStatusBar(params.state, params.message);
        })
    );

    // Push initial configuration (formatter + inlay hints) to the server
    const sendGroovyConfiguration = () => {
        const config = workspace.getConfiguration('groovy');
        client?.sendNotification('workspace/didChangeConfiguration', {
            settings: {
                groovy: {
                    ls: {
                        logLevel: config.get<string>('ls.logLevel', 'error'),
                    },
                    format: {
                        settingsUrl: config.get<string>('format.settingsUrl') ?? null,
                        enabled: config.get<boolean>('format.enabled', true),
                    },
                    inlayHints: {
                        variableTypes: {
                            enabled: config.get<boolean>('inlayHints.variableTypes.enabled', true),
                        },
                        parameterNames: {
                            enabled: config.get<boolean>('inlayHints.parameterNames.enabled', true),
                        },
                        closureParameterTypes: {
                            enabled: config.get<boolean>('inlayHints.closureParameterTypes.enabled', true),
                        },
                        methodReturnTypes: {
                            enabled: config.get<boolean>('inlayHints.methodReturnTypes.enabled', true),
                        },
                    },
                },
            },
        });
    };
    sendGroovyConfiguration();

    // Watch for configuration changes and forward to server
    addSessionDisposables(
        session,
        workspace.onDidChangeConfiguration((e) => {
            if (isServerSessionActive(session, client)
                && (e.affectsConfiguration('groovy.ls.logLevel') || e.affectsConfiguration('groovy.format') || e.affectsConfiguration('groovy.inlayHints'))) {
                sendGroovyConfiguration();
            }
        })
    );

    // ---- Delegate classpath resolution to Red Hat Java extension ----
    await initJavaExtensionClasspath(session, client);
    logStartupDuration('server.launch', startPhase, 'language client ready');
}

function prepareWorkspaceDataDir(context: ExtensionContext): WorkspaceDataPreparation {
    const workspaceStoragePath = context.storageUri?.fsPath ?? context.globalStorageUri.fsPath;
    const dataDir = path.join(workspaceStoragePath, 'groovy_ws');
    const stateFile = path.join(workspaceStoragePath, WORKSPACE_DATA_STATE_FILE);
    let resetWorkspace = false;
    let resetReason = '';

    if (fs.existsSync(stateFile)) {
        try {
            const state = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { version?: number };
            if (state.version !== WORKSPACE_DATA_VERSION) {
                resetWorkspace = true;
                resetReason = `workspace data version ${state.version ?? 'unknown'} -> ${WORKSPACE_DATA_VERSION}`;
            }
        } catch {
            resetWorkspace = true;
            resetReason = 'invalid workspace data marker';
        }
    } else {
        resetWorkspace = fs.existsSync(dataDir);
        resetReason = 'missing workspace data marker';
    }

    if (!resetWorkspace) {
        const corruptionReason = detectWorkspaceRestoreCorruption(dataDir);
        if (corruptionReason) {
            resetWorkspace = true;
            resetReason = corruptionReason;
        }
    }

    if (resetWorkspace && fs.existsSync(dataDir)) {
        try {
            fs.rmSync(dataDir, { recursive: true, force: true });
            outputChannel.appendLine(`Reset persisted workspace data: ${dataDir} (${resetReason})`);
        } catch (e) {
            outputChannel.appendLine(`Warning: failed to reset workspace data: ${e}`);
        }
    }

    fs.mkdirSync(dataDir, { recursive: true });
    fs.writeFileSync(
        stateFile,
        JSON.stringify({ version: WORKSPACE_DATA_VERSION, updatedAt: new Date().toISOString() }, null, 2),
        'utf8'
    );

    if (!resetWorkspace) {
        outputChannel.appendLine(`Reusing persisted workspace data: ${dataDir}`);
    }

    return { dataDir, resetWorkspace };
}

/**
 * Hook into the Red Hat Java extension to register event listeners for
 * ongoing classpath changes. The initial classpath was already pre-fetched
 * and sent immediately after client.start().
 */
async function initJavaExtensionClasspath(
    session: ServerSessionScope,
    lsClient: LanguageClient
): Promise<void> {
    // Use the cached Java API from the wait phase
    const javaApi = cachedJavaApi;
    if (!javaApi) {
        outputChannel.appendLine('[java-ext] No cached Java API. Classpath delegation disabled.');
        return;
    }

    outputChannel.appendLine('[java-ext] Setting up classpath change listeners...');
    let initialClasspathBatchCompleteSent = false;
    let initialClasspathFetchStarted = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    const initialClasspathTracker = new InitialClasspathStartupTracker();
    const isActive = (): boolean => isServerSessionActive(session, lsClient);
    const clearRetryTimer = (): void => {
        if (retryTimer) {
            clearTimeout(retryTimer);
            retryTimer = undefined;
        }
    };

    addSessionDisposables(session, new vscode.Disposable(() => {
        initialClasspathBatchCompleteSent = true;
        initialClasspathTracker.reset();
        clearRetryTimer();
    }));

    const sendInitialClasspathBatchComplete = (reason: string): void => {
        if (initialClasspathBatchCompleteSent || !isActive()) {
            return;
        }
        initialClasspathBatchCompleteSent = true;
        clearRetryTimer();
        lsClient.sendNotification('groovy/classpathBatchComplete', {});
        outputChannel.appendLine(`[java-ext] Sent groovy/classpathBatchComplete to server (${reason}).`);
        markStartupPhase('java.classpath-batch-complete', reason);
    };
    const replaceDiscoveredInitialTargets = (
        targets: JavaClasspathTarget[],
        baselineSnapshot: PendingStartupClasspathSnapshot
    ): void => {
        initialClasspathTracker.replaceDiscoveredTargets(targets, baselineSnapshot);
    };
    const getPendingInitialTargetsSnapshot = (): PendingStartupClasspathSnapshot =>
        initialClasspathTracker.getPendingTargetsSnapshot();
    const clearPendingInitialTargets = (): void => {
        initialClasspathTracker.clearPendingTargets();
    };
    const mergePendingInitialTargets = (
        targets: JavaClasspathTarget[]
    ): Array<PendingStartupClasspathTargetHandle | undefined> => initialClasspathTracker.mergePendingTargets(targets);
    const markImportedInitialTargets = (targets: JavaClasspathTarget[]): void => {
        initialClasspathTracker.markTargetsImportConfirmed(targets);
    };
    const enableEmptyImportFallback = (): void => {
        initialClasspathTracker.enableEmptyImportFallback();
    };
    const reconcilePendingInitialTargets = (
        attemptedSnapshot: PendingStartupClasspathSnapshot,
        remainingTargets: JavaClasspathTarget[]
    ): void => {
        initialClasspathTracker.reconcilePendingTargets(attemptedSnapshot, remainingTargets);
    };
    const shouldSendInitialClasspathBatchComplete = (): boolean =>
        initialClasspathTracker.shouldSendInitialClasspathBatchComplete();
    const runInitialClasspathAttempt = async <T>(operation: () => Promise<T>): Promise<T> => {
        return initialClasspathTracker.runSerializedBatchAttempt(operation);
    };
    const resolveInitialStartupTarget = (
        handle: PendingStartupClasspathTargetHandle,
        reason: string
    ): void => {
        if (initialClasspathBatchCompleteSent || !isActive()) {
            return;
        }
        if (initialClasspathTracker.resolvePendingTarget(handle)) {
            sendInitialClasspathBatchComplete(reason);
        }
    };

    // Resolve Java home for filtering JDK entries from classpath
    const javaHomePath = resolveJavaHome() ?? '';
    const javaHomeNorm = javaHomePath.replaceAll('\\', '/').toLowerCase();

    // Function to fetch classpath for a single project URI.
    // Strategy:
    //  1. getClasspaths (test scope) — standard API
    //  2. If no JARs → getProjectSettings with classpathEntries (reads JDT model directly)
    //  3. Send whatever we get to the Groovy LS

    /** Fetch raw classpath entries using getClasspaths and getProjectSettings fallbacks. */
    const fetchRawEntries = async (
        target: JavaClasspathTarget
    ): Promise<{ rawEntries: string[]; hasJars: boolean }> => {
        if (!isActive()) {
            return { rawEntries: [], hasJars: false };
        }

        let result: { classpaths: string[]; modulepaths: string[] } | null = null;

        if (typeof javaApi.getClasspaths === 'function') {
            try {
                result = await javaApi.getClasspaths(target.requestUri, { scope: 'test' });
            } catch (error_: any) {
                outputChannel.appendLine(`[java-ext]   getClasspaths(test) failed: ${error_.message ?? error_}`);
            }
        }

        outputChannel.appendLine(`[java-ext-tr] ${target.requestUri} = ${result?.modulepaths}`);

        let rawEntries: string[] = [];
        if (result) {
            rawEntries = [...(result.classpaths ?? []), ...(result.modulepaths ?? [])];
        }

        let hasJars = rawEntries.some(e => e.toLowerCase().endsWith('.jar'));

        // Approach 2: getProjectSettings fallback
        if ((rawEntries.length === 0 || !hasJars) && typeof javaApi.getProjectSettings === 'function') {
            try {
                const settingsResult = await javaApi.getProjectSettings(target.requestUri, [
                    'org.eclipse.jdt.ls.core.classpathEntries',
                ]);
                const extracted = extractEntriesFromSettings(settingsResult);
                const merged = [...rawEntries, ...extracted.jars, ...extracted.outputDirs];
                rawEntries = Array.from(new Set(merged));
                hasJars = rawEntries.some(e => e.toLowerCase().endsWith('.jar'));
                outputChannel.appendLine(
                    `[java-ext]   getProjectSettings fallback: +${extracted.jars.length} jars, +${extracted.outputDirs.length} output dirs`
                );
            } catch (error_: any) {
                outputChannel.appendLine(`[java-ext]   getProjectSettings failed: ${error_.message ?? error_}`);
            }
        }

        // Approach 3: Gradle wrapper fallback (async — does not block extension host)
        if ((rawEntries.length === 0 || !hasJars) && target.projectPath) {
            const gradleEntries = await resolveGradleClasspath(target.projectPath);
            if (!isActive()) {
                return { rawEntries: [], hasJars: false };
            }
            if (gradleEntries.length > 0) {
                rawEntries = Array.from(new Set([...rawEntries, ...gradleEntries]));
                hasJars = rawEntries.some(e => e.toLowerCase().endsWith('.jar'));
            }
        }

        return { rawEntries, hasJars };
    };

    /** Filter JDK entries and send classpath to the language server. */
    const filterAndSendClasspath = (
        rawEntries: string[],
        target: JavaClasspathTarget,
        projectRootMap: Map<string, string>
    ): { delivered: boolean; resolved: boolean; hasJars: boolean } => {
        if (!isActive()) {
            return { delivered: false, resolved: false, hasJars: false };
        }

        const filteredEntries: string[] = [];
        let skippedJdk = 0;

        for (const entry of rawEntries) {
            if (isJdkEntry(entry, javaHomeNorm)) {
                skippedJdk++;
            } else {
                filteredEntries.push(entry);
            }
        }

        if (skippedJdk > 0) {
            outputChannel.appendLine(`[java-ext]   Filtered out ${skippedJdk} JDK entries`);
        }
        outputChannel.appendLine(`[java-ext]   → ${filteredEntries.length} entries after filtering`);

        if (filteredEntries.length === 0) {
            return { delivered: false, resolved: false, hasJars: false };
        }

        const finalHasJars = filteredEntries.some(e => e.toLowerCase().endsWith('.jar'));
        const resolvedProjectPath = target.projectPath
            ?? resolveProjectPathFromUri(target.projectUri, projectRootMap)
            ?? resolveProjectPathFromUri(target.requestUri, projectRootMap)
            ?? inferProjectPathFromEntries(filteredEntries, projectRootMap);

        const sent = sendClasspathUpdateNotification(lsClient, {
            projectUri: target.projectUri,
            projectPath: resolvedProjectPath,
            entries: filteredEntries,
            hasJarEntries: finalHasJars,
        });
        outputChannel.appendLine(
            `[java-ext]   ${sent ? 'Sent' : 'Skipped duplicate'} ${filteredEntries.length} classpath entries` +
            (finalHasJars ? ' (includes JARs)' : ' (output dirs only, no JARs)')
            + (resolvedProjectPath ? `, projectPath=${resolvedProjectPath}` : '')
        );
        return {
            delivered: true,
            // Track the stronger resolved state separately from initial batch
            // delivery so later refreshes can still distinguish output-only
            // classpaths from fully imported jar-bearing ones.
            resolved: initialClasspathTracker.isStartupClasspathResolved(target, finalHasJars),
            hasJars: finalHasJars,
        };
    };

    const fetchClasspathForProject = async (
        target: JavaClasspathTarget,
        projectRootMap: Map<string, string>
    ): Promise<{ delivered: boolean; resolved: boolean; hasJars: boolean }> => {
        if (!isActive()) {
            return { delivered: false, resolved: false, hasJars: false };
        }

        try {
            outputChannel.appendLine(
                `[java-ext] Fetching classpath for project: ${target.projectUri}`
                + ` (query=${target.requestUri}, source=${target.source})`
            );

            const { rawEntries, hasJars } = await fetchRawEntries(target);
            if (!isActive()) {
                return { delivered: false, resolved: false, hasJars: false };
            }

            outputChannel.appendLine(
                `[java-ext]   getClasspaths: ${rawEntries.length} entries, hasJars=${hasJars}`
            );
            for (const e of rawEntries) {
                outputChannel.appendLine(`[java-ext]     ${e}`);
            }

            if (rawEntries.length > 0) {
                return filterAndSendClasspath(rawEntries, target, projectRootMap);
            }

            outputChannel.appendLine(`[java-ext]   → no usable entries`);
            return { delivered: false, resolved: false, hasJars: false };
        } catch (e) {
            outputChannel.appendLine(`[java-ext]   → error: ${e}`);
            return { delivered: false, resolved: false, hasJars: false };
        }
    };

    const fetchClasspathBatch = async (
        targets: JavaClasspathTarget[],
        projectRootMap: Map<string, string>
    ): Promise<{ delivered: number; resolved: number; remainingTargets: JavaClasspathTarget[] }> => {
        if (!isActive()) {
            return { delivered: 0, resolved: 0, remainingTargets: targets };
        }

        if (targets.length > 0) {
            outputChannel.appendLine(`[java-ext] Total ${targets.length} project(s) to fetch classpath for`);
        }

        let delivered = 0;
        let resolved = 0;
        const remainingTargets: JavaClasspathTarget[] = [];
        const batchSize = 20;
        for (let i = 0; i < targets.length; i += batchSize) {
            if (!isActive()) {
                return { delivered, resolved, remainingTargets };
            }
            const batch = targets.slice(i, i + batchSize);
            const results = await Promise.allSettled(batch.map(target => fetchClasspathForProject(target, projectRootMap)));
            if (!isActive()) {
                return { delivered, resolved, remainingTargets };
            }
            for (let j = 0; j < results.length; j++) {
                const result = results[j];
                if (result.status !== 'fulfilled') {
                    remainingTargets.push(batch[j]);
                    continue;
                }
                if (result.value.delivered) {
                    delivered++;
                }
                if (result.value.resolved) {
                    resolved++;
                }
                if (!result.value.delivered) {
                    remainingTargets.push(batch[j]);
                }
            }
        }

        outputChannel.appendLine(
            `[java-ext] Sent usable classpath for ${delivered}/${targets.length} project(s)` +
            ` (${resolved} fully resolved, ${remainingTargets.length} still awaiting initial delivery)`
        );
        return { delivered, resolved, remainingTargets };
    };

    /** Check whether a project URI should be included based on JDT workspace and root-folder heuristics. */
    const shouldIncludeProject = (uri: string, wsRootPath: string, nonRootCount: number): boolean => {
        if (isJdtWorkspaceUri(uri)) {
            outputChannel.appendLine(`[java-ext] Skipping jdt_ws Java project: ${uri}`);
            return false;
        }
        try {
            const projPath = normalizeFsPath(vscode.Uri.parse(uri).fsPath);
            if (projPath === wsRootPath && nonRootCount > 0) {
                outputChannel.appendLine(`[java-ext] Skipping root workspace folder (has ${nonRootCount} subprojects): ${uri}`);
                return false;
            }
        } catch { /* ignore parse errors */ }
        return true;
    };

    /** Scan projectRootMap for projects not already known and add them as targets. */
    const addBuildFileScanTargets = async (
        targets: JavaClasspathTarget[],
        knownPaths: Set<string>,
        projectRootMap: Map<string, string>,
        wsRootPath: string
    ): Promise<void> => {
        for (const [projDirNorm, projDir] of projectRootMap) {
            if (!isActive()) {
                return;
            }
            if (projDirNorm === wsRootPath || knownPaths.has(projDirNorm)) continue;
            const sourceUri = await findRepresentativeSourceUri(projDir);
            if (!isActive()) {
                return;
            }
            if (!sourceUri) {
                outputChannel.appendLine(`[java-ext] Skipping probe for ${projDir} (no source file found)`);
                continue;
            }
            const projUri = vscode.Uri.file(projDir).toString();
            outputChannel.appendLine(`[java-ext] Probing project not in getAll(): ${projUri} (query=${sourceUri})`);
            targets.push({ requestUri: sourceUri, projectUri: projUri, projectPath: projDir, source: 'build-file-scan' });
            knownPaths.add(projDirNorm);
        }
    };

    // Fetch classpath for all Java projects known to JDT LS.
    // Returns the number of projects whose initial usable classpath was delivered.
    const fetchAllClasspaths = async (): Promise<number> => {
        if (!isActive()) {
            return 0;
        }

        const pendingTargetsBaseline = getPendingInitialTargetsSnapshot();
        try {
            const projectRootMap = await collectProjectRootMap();
            if (!isActive()) {
                return 0;
            }

            const projectUris: string[] =
                (await vscode.commands.executeCommand('java.project.getAll')) ?? [];
            if (!isActive()) {
                return 0;
            }
            outputChannel.appendLine(`[java-ext] java.project.getAll returned ${projectUris.length} project(s):`);
            for (const uri of projectUris) {
                outputChannel.appendLine(`[java-ext]   - ${uri}`);
            }
            if (projectUris.length === 0) {
                outputChannel.appendLine('[java-ext] No Java projects found yet. Will retry on events.');
                return 0;
            }

            const wsRootPath = workspace.workspaceFolders?.[0]?.uri.fsPath
                ?.replaceAll('\\', '/').replace(/\/$/, '').toLowerCase() ?? '';

            const nonRootCount = projectUris.filter(uri => {
                if (isJdtWorkspaceUri(uri)) return false;
                try { return normalizeFsPath(vscode.Uri.parse(uri).fsPath) !== wsRootPath; }
                catch { return true; }
            }).length;

            const javaProjects = projectUris.filter(uri => shouldIncludeProject(uri, wsRootPath, nonRootCount));

            const targets: JavaClasspathTarget[] = javaProjects.map(uri => ({
                requestUri: uri,
                projectUri: uri,
                projectPath: resolveProjectPathFromUri(uri, projectRootMap),
                source: 'java.project.getAll',
            }));

            const knownPaths = new Set(targets.map(target => {
                try {
                    if (target.projectPath) return normalizeFsPath(target.projectPath);
                    return normalizeFsPath(vscode.Uri.parse(target.projectUri).fsPath);
                } catch { return ''; }
            }));

            await addBuildFileScanTargets(targets, knownPaths, projectRootMap, wsRootPath);
            replaceDiscoveredInitialTargets(targets, pendingTargetsBaseline);
            const attemptedTargetsSnapshot = getPendingInitialTargetsSnapshot();
            const batchResult = await fetchClasspathBatch(attemptedTargetsSnapshot.targets, projectRootMap);
            reconcilePendingInitialTargets(attemptedTargetsSnapshot, batchResult.remainingTargets);
            return batchResult.delivered;
        } catch (e) {
            outputChannel.appendLine(`[java-ext] Failed to enumerate projects: ${e}`);
            return fetchClasspathFallback();
        }
    };

    /** Fallback: try workspace folders directly. */
    const fetchClasspathFallback = async (): Promise<number> => {
        if (!isActive()) {
            return 0;
        }

        const pendingTargetsBaseline = getPendingInitialTargetsSnapshot();
        const folders = workspace.workspaceFolders;
        if (!folders) return 0;
        const projectRootMap = await collectProjectRootMap();
        if (!isActive()) {
            return 0;
        }
        const targets: JavaClasspathTarget[] = [];
        for (const folder of folders) {
            const representativeUri = await findRepresentativeSourceUri(folder.uri.fsPath);
            if (!isActive()) {
                return 0;
            }
            targets.push({
                requestUri: representativeUri ?? folder.uri.toString(),
                projectUri: folder.uri.toString(),
                projectPath: folder.uri.fsPath,
                source: 'workspace-folder-fallback',
            });
        }
        replaceDiscoveredInitialTargets(targets, pendingTargetsBaseline);
        const attemptedTargetsSnapshot = getPendingInitialTargetsSnapshot();
        const batchResult = await fetchClasspathBatch(attemptedTargetsSnapshot.targets, projectRootMap);
        reconcilePendingInitialTargets(attemptedTargetsSnapshot, batchResult.remainingTargets);
        return batchResult.delivered;
    };

    const fetchPendingInitialTargets = async (): Promise<number> => {
        if (!isActive()) {
            return 0;
        }

        if (initialClasspathTracker.getPendingTargetCount() === 0) {
            return fetchAllClasspaths();
        }

        const projectRootMap = await collectProjectRootMap();
        if (!isActive()) {
            return 0;
        }
        // Snapshot the attempted batch so projects added by async import/classpath
        // events during the fetch remain pending for later retries.
        const attemptedTargetsSnapshot = getPendingInitialTargetsSnapshot();
        const batchResult = await fetchClasspathBatch(attemptedTargetsSnapshot.targets, projectRootMap);
        reconcilePendingInitialTargets(attemptedTargetsSnapshot, batchResult.remainingTargets);
        return batchResult.delivered;
    };

    /** Force project configuration updates for all Java projects. */
    const forceProjectConfigurationUpdates = async (): Promise<void> => {
        if (!isActive()) {
            return;
        }

        outputChannel.appendLine('[java-ext] Initial classpath batch incomplete. Forcing project configuration update...');
        try {
            const projectUris: string[] =
                (await vscode.commands.executeCommand('java.project.getAll')) ?? [];
            if (!isActive()) {
                return;
            }
            for (const uri of projectUris) {
                if (!isActive()) {
                    return;
                }
                if (isJdtWorkspaceUri(uri)) continue;
                try {
                    outputChannel.appendLine(`[java-ext]   Requesting config update for: ${uri}`);
                    await vscode.commands.executeCommand('java.projectConfiguration.update', vscode.Uri.parse(uri));
                } catch (e) {
                    outputChannel.appendLine(`[java-ext]   Config update failed for ${uri}: ${e}`);
                }
            }
        } catch (e) {
            outputChannel.appendLine(`[java-ext] Failed to update configs: ${e}`);
        }
    };

    // Retry wrapper: Java extension may not have finished Gradle/Maven import
    // when we first fetch. Retry with increasing delays until every discovered
    // project has delivered an initial usable classpath.
    // On first failure, trigger java.projectConfiguration.update to force dependency resolution.
    const fetchWithRetry = async (attempt: number = 1, maxAttempts: number = 6): Promise<void> => {
        if (initialClasspathBatchCompleteSent || !isActive()) {
            return;
        }
        const delivered = await runInitialClasspathAttempt(() =>
            attempt === 1 ? fetchAllClasspaths() : fetchPendingInitialTargets()
        );
        if (!isActive()) {
            return;
        }
        const retryAction = getInitialClasspathRetryAction({
            attempt,
            maxAttempts,
            canSendBatchComplete: shouldSendInitialClasspathBatchComplete(),
        });
        if (retryAction === 'batch-complete') {
            sendInitialClasspathBatchComplete(`delivered ${delivered} project(s) on attempt ${attempt}`);
            return;
        }
        if (retryAction === 'max-attempts') {
            clearPendingInitialTargets();
            sendInitialClasspathBatchComplete(`max attempts reached (${attempt})`);
            return;
        }

        if (retryAction === 'force-config-and-retry') {
            await forceProjectConfigurationUpdates();
        }

        const delay = Math.min(attempt * 5000, 30000); // 5s, 10s, 15s, 20s, 25s
        outputChannel.appendLine(
            `[java-ext] Still waiting on ${initialClasspathTracker.getPendingTargetCount()} project(s) ` +
            `(attempt ${attempt}/${maxAttempts}). Retrying in ${delay / 1000}s...`
        );
        if (!isActive()) {
            return;
        }
        retryTimer = setTimeout(() => {
            if (!isActive()) {
                return;
            }
            retryTimer = undefined;
            fetchWithRetry(attempt + 1, maxAttempts).catch(e =>
                outputChannel.appendLine(`[java-ext] fetchWithRetry error: ${e}`)
            );
        }, delay);
    };

    const startInitialClasspathFetch = (reason: string, delay: number = 0): void => {
        if (initialClasspathBatchCompleteSent || initialClasspathFetchStarted || !isActive()) {
            return;
        }

        initialClasspathFetchStarted = true;
        outputChannel.appendLine(`[java-ext] Starting initial classpath fetch (${reason})...`);

        const runFetch = () => {
            retryTimer = undefined;
            if (!isActive()) {
                return;
            }
            fetchWithRetry().catch(e => outputChannel.appendLine(`[java-ext] initial classpath fetch error: ${e}`));
        };

        if (delay > 0) {
            retryTimer = setTimeout(runFetch, delay);
        } else {
            runFetch();
        }
    };

    // Listen for classpath changes from the Java extension
    if (typeof javaApi.onDidClasspathUpdate === 'function') {
        addSessionDisposables(
            session,
            javaApi.onDidClasspathUpdate(async (uri: vscode.Uri) => {
                if (!isActive()) {
                    return;
                }
                outputChannel.appendLine(`[java-ext] Classpath updated for: ${uri.toString()}`);
                if (isJdtWorkspaceUri(uri.toString())) {
                    outputChannel.appendLine('[java-ext] Ignoring classpath update for jdt_ws project.');
                    return;
                }
                // Re-fetch only the changed project (not all — each project has isolated classpath)
                const projectRootMap = await collectProjectRootMap();
                if (!isActive()) {
                    return;
                }
                const updatedTarget = {
                    requestUri: uri.toString(),
                    projectUri: uri.toString(),
                    projectPath: resolveProjectPathFromUri(uri.toString(), projectRootMap),
                    source: 'onDidClasspathUpdate',
                };
                let updatedTargetHandle: PendingStartupClasspathTargetHandle | undefined;
                if (!initialClasspathBatchCompleteSent) {
                    [updatedTargetHandle] = mergePendingInitialTargets([updatedTarget]);
                }
                const result = await fetchClasspathForProject(updatedTarget, projectRootMap);
                if (!result.delivered || !updatedTargetHandle) {
                    return;
                }
                resolveInitialStartupTarget(
                    updatedTargetHandle,
                    `classpath updated for ${updatedTarget.projectUri}`
                );
            })
        );
        outputChannel.appendLine('[java-ext] Registered onDidClasspathUpdate listener.');
    }

    // Listen for project import completion
    if (typeof javaApi.onDidProjectsImport === 'function') {
        addSessionDisposables(
            session,
            javaApi.onDidProjectsImport((uris: vscode.Uri[]) => {
                if (!isActive()) {
                    return;
                }
                outputChannel.appendLine(
                    `[java-ext] Projects imported (${uris.length}) — refreshing classpath...`
                );
                const importRefreshAction = getInitialClasspathImportRefreshAction({
                    importedProjectCount: uris.length,
                });
                if (importRefreshAction === 're-enumerate-projects') {
                    if (!initialClasspathBatchCompleteSent) {
                        enableEmptyImportFallback();
                    }
                    runInitialClasspathAttempt(fetchAllClasspaths)
                        .then(() => {
                            if (!isActive() || initialClasspathBatchCompleteSent) {
                                return;
                            }
                            if (shouldSendInitialClasspathBatchComplete()) {
                                sendInitialClasspathBatchComplete('projects imported (empty payload fallback)');
                            }
                        })
                        .catch(e => outputChannel.appendLine(`[java-ext] onDidProjectsImport fallback error: ${e}`));
                    return;
                }

                (async () => {
                    const projectRootMap = await collectProjectRootMap();
                    if (!isActive()) {
                        return;
                    }
                    const importedTargets = uris
                        .filter(importedUri => !isJdtWorkspaceUri(importedUri.toString()))
                        .map(importedUri => ({
                            requestUri: importedUri.toString(),
                            projectUri: importedUri.toString(),
                            projectPath: resolveProjectPathFromUri(importedUri.toString(), projectRootMap),
                            source: 'onDidProjectsImport' as const,
                        }));
                    let importedTargetHandles: Array<PendingStartupClasspathTargetHandle | undefined> = [];
                    if (!initialClasspathBatchCompleteSent) {
                        markImportedInitialTargets(importedTargets);
                        importedTargetHandles = mergePendingInitialTargets(importedTargets);
                    }
                    for (let i = 0; i < importedTargets.length; i++) {
                        const target = importedTargets[i];
                        const targetHandle = importedTargetHandles[i];
                        if (!isActive()) {
                            return;
                        }
                        const result = await fetchClasspathForProject(target, projectRootMap);
                        if (result.delivered && targetHandle) {
                            resolveInitialStartupTarget(
                                targetHandle,
                                `project imported for ${target.projectUri}`
                            );
                        }
                    }
                })().catch(e => outputChannel.appendLine(`[java-ext] onDidProjectsImport error: ${e}`));
            })
        );
        outputChannel.appendLine('[java-ext] Registered onDidProjectsImport listener.');
    }

    // Also listen for the standard Java extension server ready event
    if (typeof javaApi.onDidServerModeChange === 'function') {
        addSessionDisposables(
            session,
            javaApi.onDidServerModeChange((mode: string) => {
                if (!isActive()) {
                    return;
                }
                outputChannel.appendLine(`[java-ext] Server mode changed: ${mode}`);
                if (mode === 'Standard') {
                    startInitialClasspathFetch('server mode standard', 3000);
                }
            })
        );
    }

    // Initial classpath fetch starts immediately and retries until Java import
    // finishes. This is the single source of truth for the initial
    // groovy/classpathBatchComplete notification.
    startInitialClasspathFetch('extension initialization');
}

// ---- Helper functions ----

/**
 * Resolve the Java home directory from settings or environment.
 */
function resolveJavaHome(): string | undefined {
    // 1. Check VS Code setting
    const configuredHome = workspace.getConfiguration('groovy').get<string>('java.home');
    if (configuredHome && fs.existsSync(configuredHome)) {
        return configuredHome;
    }

    // 2. Check JAVA_HOME environment variable
    const envHome = process.env['JAVA_HOME'];
    if (envHome && fs.existsSync(envHome)) {
        return envHome;
    }

    // 3. Try to find java on PATH
    const pathJava = process.env['PATH']?.split(path.delimiter)
        .find(dir => {
            const javaBin = path.join(dir, process.platform === 'win32' ? 'java.exe' : 'java');
            return fs.existsSync(javaBin);
        });

    if (pathJava) {
        const javaBinPath = path.join(pathJava, process.platform === 'win32' ? 'java.exe' : 'java');
        // Resolve symlinks to find the real location
        let resolvedBin: string;
        try {
            resolvedBin = fs.realpathSync(javaBinPath);
        } catch {
            resolvedBin = javaBinPath;
        }
        // Walk up from bin/ to get java home
        const binDir = path.dirname(resolvedBin);
        const home = path.dirname(binDir);
        if (fs.existsSync(path.join(home, 'bin'))) {
            return home;
        }
        return binDir; // fallback
    }

    return undefined;
}

async function resolveJavaExecutableOrShowError(): Promise<string | undefined> {
    const javaHome = resolveJavaHome();
    if (!javaHome) {
        updateStatusBar('Error', 'JDK not found');
        window.showErrorMessage(
            `Groovy Language Server requires JDK ${MINIMUM_JAVA_MAJOR}+. ` +
            'Set "groovy.java.home" in settings or the JAVA_HOME environment variable.'
        );
        return undefined;
    }

    const javaExecutable = getJavaExecutable(javaHome);
    if (!fs.existsSync(javaExecutable)) {
        updateStatusBar('Error', 'Java executable not found');
        window.showErrorMessage(
            `Java executable not found at: ${javaExecutable}. ` +
            'Please verify your "groovy.java.home" setting or JAVA_HOME.'
        );
        return undefined;
    }

    const javaMajorVersion = await getJavaMajorVersion(javaExecutable);
    if (javaMajorVersion !== undefined && javaMajorVersion < MINIMUM_JAVA_MAJOR) {
        updateStatusBar('Error', `JDK ${MINIMUM_JAVA_MAJOR}+ required`);
        window.showErrorMessage(
            `Groovy Language Server requires JDK ${MINIMUM_JAVA_MAJOR}+ and found JDK ${javaMajorVersion}. ` +
            'Update "groovy.java.home" or JAVA_HOME to a Java 21 installation.'
        );
        return undefined;
    }

    return javaExecutable;
}

/**
 * Get the full path to the java executable.
 */
function getJavaExecutable(javaHome: string): string {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const binJava = path.join(javaHome, 'bin', exe);
    if (fs.existsSync(binJava)) {
        return binJava;
    }
    // Maybe javaHome IS the bin directory
    return path.join(javaHome, exe);
}

function validateCriticalPluginJars(serverDir: string): boolean {
    const pluginsDir = path.join(serverDir, 'plugins');
    const criticalJars = fs.readdirSync(pluginsDir).filter(
        name => name.endsWith('.jar') && (
            name.startsWith('org.eclipse.jdt.core_') ||
            name.startsWith('org.eclipse.jdt.groovy.core_') ||
            name.startsWith('org.eclipse.groovy.ls.core_') ||
            name.startsWith('org.codehaus.groovy_')
        )
    );
    const minJarSize = 50_000;

    for (const jar of criticalJars) {
        const jarPath = path.join(pluginsDir, jar);
        try {
            const stat = fs.statSync(jarPath);
            if (stat.size >= minJarSize) {
                continue;
            }

            const sizeKB = (stat.size / 1024).toFixed(1);
            outputChannel.appendLine(`WARNING: ${jar} appears corrupt (${sizeKB} KB — expected several MB)`);
            updateStatusBar('Error', `Corrupt JAR: ${jar}`);
            window.showErrorMessage(
                `Groovy Language Server: ${jar} appears corrupt (only ${sizeKB} KB). ` +
                'Please rebuild the server with: ./gradlew :org.eclipse.groovy.ls.product:assembleProduct'
            );
            return false;
        } catch {
            // Best effort only — let Equinox report any deeper JAR issue.
        }
    }

    return true;
}

function buildInitializationOptions(): Record<string, unknown> | undefined {
    const config = workspace.getConfiguration('groovy');
    const initOptions: Record<string, unknown> = {};
    const requestPoolSize = config.get<number>('ls.requestPoolSize', 0);
    const requestQueueSize = config.get<number>('ls.requestQueueSize', 64);
    const backgroundPoolSize = config.get<number>('ls.backgroundPoolSize', 0);
    const backgroundQueueSize = config.get<number>('ls.backgroundQueueSize', 128);

    if (requestPoolSize > 0) {
        initOptions.lspRequestPoolSize = requestPoolSize;
    }
    if (requestQueueSize !== 64) {
        initOptions.lspRequestQueueSize = requestQueueSize;
    }
    if (backgroundPoolSize > 0) {
        initOptions.lspBackgroundPoolSize = backgroundPoolSize;
    }
    if (backgroundQueueSize !== 128) {
        initOptions.lspBackgroundQueueSize = backgroundQueueSize;
    }

    return Object.keys(initOptions).length > 0 ? initOptions : undefined;
}

function sendClasspathUpdateNotification(
    languageClient: LanguageClient,
    payload: ClasspathNotificationPayload
): boolean {
    const projectKey = payload.projectUri || payload.projectPath || 'unknown-project';
    const fingerprint = JSON.stringify({
        projectPath: payload.projectPath ?? '',
        entries: [...payload.entries].sort(),
        hasJarEntries: payload.hasJarEntries,
    });

    if (sentClasspathFingerprints.get(projectKey) === fingerprint) {
        return false;
    }

    sentClasspathFingerprints.set(projectKey, fingerprint);
    languageClient.sendNotification('groovy/classpathUpdate', payload);
    return true;
}

function toError(error: unknown): Error {
    return error instanceof Error ? error : new Error(String(error));
}

async function getJavaMajorVersion(javaExecutable: string): Promise<number | undefined> {
    const execOptions: ExecFileSyncOptionsWithStringEncoding = {
        encoding: 'utf8',
        windowsHide: true,
        timeout: 10000,
        maxBuffer: 1024 * 1024,
        stdio: ['ignore', 'pipe', 'pipe'],
    };

    try {
        const { stdout, stderr } = await new Promise<{ stdout: string; stderr: string }>((resolve, reject) => {
            execFile(javaExecutable, ['-version'], execOptions, (error, stdout, stderr) => {
                if (error) {
                    reject(toError(error));
                } else {
                    resolve({ stdout, stderr });
                }
            });
        });

        const versionText = `${stdout}\n${stderr}`;
        const match = /version\s+"([^"]+)"/i.exec(versionText);
        if (!match) {
            outputChannel.appendLine('[java] Unable to parse java -version output; continuing without a strict version gate.');
            return undefined;
        }

        const rawVersion = match[1];
        const majorToken = rawVersion.startsWith('1.')
            ? rawVersion.split('.')[1]
            : rawVersion.split(/[.+-]/)[0];
        const majorVersion = Number.parseInt(majorToken, 10);
        if (Number.isNaN(majorVersion)) {
            outputChannel.appendLine(`[java] Unable to parse Java major version from: ${rawVersion}`);
            return undefined;
        }

        return majorVersion;
    } catch (e) {
        outputChannel.appendLine(`[java] Failed to inspect Java version for ${javaExecutable}: ${e}`);
        return undefined;
    }
}

/**
 * Resolve the server installation directory.
 * Looks for server/ relative to the extension root.
 */
function resolveServerDir(context: ExtensionContext): string | undefined {
    const candidates = [
        path.join(context.extensionPath, 'server'),
        path.join(context.extensionPath, '..', 'org.eclipse.groovy.ls.product', 'target', 'repository'),
    ];

    for (const candidate of candidates) {
        const pluginsDir = path.join(candidate, 'plugins');
        if (fs.existsSync(pluginsDir)) {
            return candidate;
        }
    }

    return undefined;
}

/**
 * Find the Equinox launcher JAR in the server's plugins directory.
 */
function findLauncherJar(serverDir: string): string | undefined {
    const pluginsDir = path.join(serverDir, 'plugins');
    if (!fs.existsSync(pluginsDir)) {
        return undefined;
    }

    const entries = fs.readdirSync(pluginsDir);
    const launcher = entries.find(name => name.startsWith('org.eclipse.equinox.launcher_') && name.endsWith('.jar'));
    return launcher ? path.join(pluginsDir, launcher) : undefined;
}
