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
    getConfigNameForPlatform,
    inferProjectPathFromEntries,
    isJdtWorkspaceUri,
    normalizeFsPath,
    pathStartsWith,
} from './utils';
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

interface ClasspathNotificationPayload {
    projectUri: string;
    projectPath?: string;
    entries: string[];
}

interface JavaClasspathTarget {
    requestUri: string;
    projectUri: string;
    projectPath?: string;
    source: string;
}

/** Pre-fetched classpath data from the Java extension (filled before LS starts). */
let preFetchedClasspathData: ClasspathNotificationPayload[] = [];
/** Cached Java extension API (obtained during wait phase). */
let cachedJavaApi: any = null;

/** Scheme for virtual source documents from JARs/JDK. */
const GROOVY_SOURCE_SCHEME = 'groovy-source';
const GRADLE_CLASSPATH_LINE_PREFIX = 'GROOVY_LS_CP::';
const MINIMUM_JAVA_MAJOR = 21;

/**
 * Cached project root map to avoid repeatedly scanning the workspace for
 * build.gradle / pom.xml on every classpath event.  Invalidated by the
 * file system watcher for build files so it stays up-to-date.
 */
let cachedProjectRootMap: Map<string, string> | undefined;
/** Timestamp of the last cache refresh — used for staleness checks. */
let projectRootMapTimestamp = 0;

/**
 * Content provider for groovy-source:// virtual documents.
 * When VS Code opens a groovy-source URI (e.g., from go-to-definition on a binary type),
 * this provider asks the language server to resolve the source content.
 */
class GroovySourceContentProvider implements vscode.TextDocumentContentProvider {
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
}

/** Server status states */
type ServerState = 'WaitingForJava' | 'Starting' | 'Importing' | 'Compiling' | 'Ready' | 'Error' | 'Stopped';

interface StatusNotification {
    state: ServerState;
    message?: string;
}

function toFileSystemPath(uriValue: string): string | undefined {
    try {
        return vscode.Uri.parse(uriValue).fsPath;
    } catch {
        return undefined;
    }
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
        '{**/build.gradle,**/pom.xml}',
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
}

function resolveProjectPathFromUri(
    uriValue: string,
    projectRootMap: Map<string, string>
): string | undefined {
    const fsPath = toFileSystemPath(uriValue);
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
    const projectUri = vscode.Uri.file(projectPath);
    const patterns = [
        'src/main/java/**/*.java',
        'src/main/groovy/**/*.groovy',
        'src/test/java/**/*.java',
        'src/test/groovy/**/*.groovy',
    ];

    for (const pattern of patterns) {
        const matches = await workspace.findFiles(
            new vscode.RelativePattern(projectUri, pattern),
            '**/build/**',
            1
        );
        if (matches.length > 0) {
            return matches[0].toString();
        }
    }

    return undefined;
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
            updateStatusBar('Starting', 'Restarting...');
            isRestarting = true;
            if (client) {
                await client.stop();
            }
            await startLanguageServer(context);
        })
    );

    // Wait for the Red Hat Java extension to be fully ready before starting.
    // This prevents the Groovy LS from processing files before classpath is resolved,
    // which would show spurious errors for unresolved types/imports.
    updateStatusBar('WaitingForJava');
    const javaReady = await waitForJavaExtension();
    if (!javaReady) {
        outputChannel.appendLine('Java extension not available — starting Groovy LS without classpath delegation.');
    }

    await startLanguageServer(context);
}

/**
 * Wait for the Red Hat Java extension to activate and finish importing projects.
 * Returns true if the Java extension is ready, false if it's unavailable or timed out.
 */
async function waitForJavaExtension(): Promise<boolean> {
    const javaExtension = vscode.extensions.getExtension('redhat.java');
    if (!javaExtension) {
        outputChannel.appendLine('[java-ext] Red Hat Java extension not installed.');
        return false;
    }

    outputChannel.appendLine('[java-ext] Waiting for Red Hat Java extension to be ready...');

    // Activate the Java extension if needed
    let javaApi: any;
    try {
        javaApi = javaExtension.isActive ? javaExtension.exports : await javaExtension.activate();
    } catch (e) {
        outputChannel.appendLine(`[java-ext] Failed to activate: ${e}`);
        return false;
    }

    if (!javaApi) {
        outputChannel.appendLine('[java-ext] Java extension API is null.');
        return false;
    }

    outputChannel.appendLine('[java-ext] Activated. API version: ' + (javaApi.apiVersion ?? 'unknown'));
    outputChannel.appendLine('[java-ext] Server mode: ' + (javaApi.serverMode ?? 'unknown'));

    // Wait for Standard mode to be ready (projects imported, indices built).
    // If the Java server is already in Standard mode when we activate, the
    // serverReady() promise may never resolve (it was meant to be awaited
    // *before* the server finishes).  Use a short timeout in that case.
    if (typeof javaApi.serverReady === 'function') {
        const alreadyStandard = javaApi.serverMode === 'Standard';
        const timeoutMs = alreadyStandard ? 5_000 : 120_000;
        outputChannel.appendLine(
            `[java-ext] Waiting for serverReady() (mode=${javaApi.serverMode ?? 'unknown'}, timeout=${timeoutMs}ms)...`);
        updateStatusBar('WaitingForJava', 'Waiting for Java: importing projects...');
        const ready = await Promise.race([
            javaApi.serverReady().then(() => 'ready' as const),
            new Promise<'timeout'>(resolve =>
                setTimeout(() => resolve('timeout'), timeoutMs)),
        ]);
        if (ready === 'ready') {
            outputChannel.appendLine('[java-ext] Server is ready (Standard mode, imports done).');
        } else {
            outputChannel.appendLine(
                `[java-ext] serverReady() timed out (alreadyStandard=${alreadyStandard}) — proceeding.`);
        }
    } else {
        outputChannel.appendLine('[java-ext] serverReady() not available — waiting 10s as fallback.');
        await new Promise(resolve => setTimeout(resolve, 10000));
    }

    outputChannel.appendLine('[java-ext] Java extension is ready.');

    // Pre-fetch classpath entries now while LS hasn't started yet.
    // This way we can send them immediately after client.start() completes,
    // before the server triggers its initial build.
    cachedJavaApi = javaApi;
    await preFetchClasspaths(javaApi);

    return true;
}

/**
 * Pre-fetch classpath entries from the Java extension before the Groovy LS starts.
 * Stores results in preFetchedClasspathData for immediate sending after client.start().
 */
async function preFetchClasspaths(javaApi: any): Promise<void> {
    updateStatusBar('WaitingForJava', 'Waiting for Java: fetching classpath...');
    outputChannel.appendLine('[java-ext] Pre-fetching classpath entries...');
    preFetchedClasspathData = [];

    const javaHomePath = resolveJavaHome() ?? '';
    const javaHomeNorm = javaHomePath.replaceAll('\\', '/').toLowerCase();

    try {
        const projectRootMap = await collectProjectRootMap();
        const projectUris: string[] =
            (await vscode.commands.executeCommand('java.project.getAll')) ?? [];

        outputChannel.appendLine(`[java-ext] Pre-fetch: java.project.getAll returned ${projectUris.length} project(s)`);

        const fetchProjectClasspath = async (projectUri: string): Promise<ClasspathNotificationPayload | undefined> => {
            if (isJdtWorkspaceUri(projectUri)) {
                outputChannel.appendLine(`[java-ext] Pre-fetch: skipping jdt_ws project ${projectUri}`);
                return undefined;
            }

            try {
                let result: { classpaths: string[]; modulepaths: string[] } | null = null;
                if (typeof javaApi.getClasspaths === 'function') {
                    result = await javaApi.getClasspaths(projectUri, { scope: 'test' });
                }

                const rawEntries = result
                    ? [...(result.classpaths ?? []), ...(result.modulepaths ?? [])]
                    : [];
                const filteredEntries = rawEntries.filter(entry => !isJdkEntry(entry, javaHomeNorm));

                if (filteredEntries.length === 0) {
                    return undefined;
                }

                const projectPath = resolveProjectPathFromUri(projectUri, projectRootMap)
                    ?? inferProjectPathFromEntries(filteredEntries, projectRootMap);

                outputChannel.appendLine(
                    `[java-ext] Pre-fetched ${filteredEntries.length} entries for ${projectUri}`
                    + (projectPath ? ` (projectPath=${projectPath})` : '')
                );

                return {
                    projectUri,
                    projectPath,
                    entries: filteredEntries,
                };
            } catch (e) {
                outputChannel.appendLine(`[java-ext] Pre-fetch failed for ${projectUri}: ${e}`);
                return undefined;
            }
        };

        const BATCH_SIZE = 20;
        for (let i = 0; i < projectUris.length; i += BATCH_SIZE) {
            const batch = projectUris.slice(i, i + BATCH_SIZE);
            const results = await Promise.allSettled(batch.map(fetchProjectClasspath));
            for (const result of results) {
                if (result.status === 'fulfilled' && result.value) {
                    preFetchedClasspathData.push(result.value);
                }
            }
        }

        outputChannel.appendLine(
            `[java-ext] Pre-fetch complete: ${preFetchedClasspathData.length} project(s) with classpath data`
        );
    } catch (e) {
        outputChannel.appendLine(`[java-ext] Pre-fetch failed: ${e}`);
    }
}

export async function deactivate(): Promise<void> {
    if (client) {
        try {
            await client.stop();
        } catch (e) {
            outputChannel.appendLine(`Error stopping language server: ${e}`);
        }
        client = undefined;
    }
    updateStatusBar('Stopped');
}

async function startLanguageServer(context: ExtensionContext): Promise<void> {
    isRestarting = false;
    const javaExecutable = await resolveJavaExecutableOrShowError();
    if (!javaExecutable) {
        return;
    }

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

    // 4. Determine platform-specific config directory
    const configDir = getConfigDir(serverDir);
    outputChannel.appendLine(`Config directory: ${configDir}`);

    // 5. Build the workspace data directory.
    // Wipe the previous Eclipse workspace data on every start so that stale
    // metadata (linked-folder resource trees, build state, etc.) never
    // accumulates.  All state is derived at runtime (project structure,
    // classpath from Java extension) so nothing precious is lost.
    // This also fixes existing installs where linked folders were created
    // without resource filters, which caused runaway I/O that could break
    // other extensions' persisted state (e.g. VS Code Copilot chat history).
    const workspaceStoragePath = context.storageUri?.fsPath ?? context.globalStorageUri.fsPath;
    const dataDir = path.join(workspaceStoragePath, 'groovy_ws');
    try {
        if (fs.existsSync(dataDir)) {
            fs.rmSync(dataDir, { recursive: true, force: true });
            outputChannel.appendLine(`Cleaned stale workspace data: ${dataDir}`);
        }
    } catch (e) {
        outputChannel.appendLine(`Warning: failed to clean workspace data: ${e}`);
    }
    fs.mkdirSync(dataDir, { recursive: true });

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
    args.push(
        '-Declipse.application=org.eclipse.groovy.ls.core.id1',
        '-Declipse.product=org.eclipse.groovy.ls.core.product',
        '-Dosgi.bundles.defaultStartLevel=4',
        '-Dosgi.checkConfiguration=true',
        '-Dosgi.clean=true',
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
    const groovyWatcher = workspace.createFileSystemWatcher('**/*.groovy');
    const javaWatcher = workspace.createFileSystemWatcher('**/*.java');
    const gradleWatcher = workspace.createFileSystemWatcher('**/build.gradle');
    const pomWatcher = workspace.createFileSystemWatcher('**/pom.xml');
    context.subscriptions.push(groovyWatcher, javaWatcher, gradleWatcher, pomWatcher);

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
            fileEvents: [groovyWatcher, javaWatcher, gradleWatcher, pomWatcher],
        },
        outputChannel: outputChannel,
        traceOutputChannel: outputChannel,
        middleware: {
            executeCommand: async (command, args, next) => {
                // Handle client-side commands directly instead of forwarding to server
                if (command === 'groovy.showReferences') {
                    return vscode.commands.executeCommand(command, ...(args || []));
                }
                return next(command, args);
            },
        },
    };

    // Invalidate the cached project root map when build files change so that
    // subsequent classpath events pick up new or removed subprojects.
    const buildFileWatcher = workspace.createFileSystemWatcher('{**/build.gradle,**/pom.xml}');
    buildFileWatcher.onDidCreate(() => invalidateProjectRootMapCache());
    buildFileWatcher.onDidDelete(() => invalidateProjectRootMapCache());
    context.subscriptions.push(buildFileWatcher);

    // 9. Create and start the language client
    client = new LanguageClient(
        'groovy',
        'Groovy Language Server',
        serverOptions,
        clientOptions
    );

    outputChannel.appendLine('Starting Groovy Language Server...');
    try {
        await client.start();
    } catch (e) {
        updateStatusBar('Error', `Failed to start: ${e}`);
        outputChannel.appendLine(`Failed to start Groovy Language Server: ${e}`);
        window.showErrorMessage(
            `Groovy Language Server failed to start: ${e instanceof Error ? e.message : String(e)}. ` +
            'Check the output channel for details.'
        );
        client = undefined;
        return;
    }
    outputChannel.appendLine('Groovy Language Server started successfully.');

    // Monitor client state for unexpected crashes
    client.onDidChangeState((e) => {
        if (e.newState === 1 /* Stopped */ && e.oldState === 2 /* Running */) {
            if (isRestarting) {
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
    });

    // Register the virtual document content provider for groovy-source:// URIs
    const sourceProvider = new GroovySourceContentProvider(client);
    context.subscriptions.push(
        vscode.workspace.registerTextDocumentContentProvider(GROOVY_SOURCE_SCHEME, sourceProvider)
    );
    outputChannel.appendLine('Registered groovy-source:// content provider.');

    // Listen for custom status notifications from the server
    client.onNotification('groovy/status', (params: StatusNotification) => {
        outputChannel.appendLine(`[status] ${params.state}${params.message ? ': ' + params.message : ''}`);
        updateStatusBar(params.state, params.message);
    });

    // Send pre-fetched classpath immediately — before the server's deferred
    // initial build timer fires. This ensures the first build has full classpath.
    sendPrefetchedClasspaths(client);

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
    context.subscriptions.push(
        workspace.onDidChangeConfiguration((e) => {
            if (client && (e.affectsConfiguration('groovy.ls.logLevel') || e.affectsConfiguration('groovy.format') || e.affectsConfiguration('groovy.inlayHints'))) {
                sendGroovyConfiguration();
            }
        })
    );

    // ---- Delegate classpath resolution to Red Hat Java extension ----
    await initJavaExtensionClasspath(context, client);
}

/**
 * Hook into the Red Hat Java extension to register event listeners for
 * ongoing classpath changes. The initial classpath was already pre-fetched
 * and sent immediately after client.start().
 */
async function initJavaExtensionClasspath(
    context: ExtensionContext,
    lsClient: LanguageClient
): Promise<void> {
    // Use the cached Java API from the wait phase
    const javaApi = cachedJavaApi;
    if (!javaApi) {
        outputChannel.appendLine('[java-ext] No cached Java API. Classpath delegation disabled.');
        return;
    }

    outputChannel.appendLine('[java-ext] Setting up classpath change listeners...');

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
    ): boolean => {
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
            return false;
        }

        const finalHasJars = filteredEntries.some(e => e.toLowerCase().endsWith('.jar'));
        const resolvedProjectPath = target.projectPath
            ?? resolveProjectPathFromUri(target.projectUri, projectRootMap)
            ?? resolveProjectPathFromUri(target.requestUri, projectRootMap)
            ?? inferProjectPathFromEntries(filteredEntries, projectRootMap);

        lsClient.sendNotification('groovy/classpathUpdate', {
            projectUri: target.projectUri,
            projectPath: resolvedProjectPath,
            entries: filteredEntries,
        });
        outputChannel.appendLine(
            `[java-ext]   Sent ${filteredEntries.length} classpath entries` +
            (finalHasJars ? ' (includes JARs)' : ' (output dirs only, no JARs)')
            + (resolvedProjectPath ? `, projectPath=${resolvedProjectPath}` : '')
        );
        return finalHasJars;
    };

    const fetchClasspathForProject = async (
        target: JavaClasspathTarget,
        projectRootMap: Map<string, string>
    ): Promise<boolean> => {
        try {
            outputChannel.appendLine(
                `[java-ext] Fetching classpath for project: ${target.projectUri}`
                + ` (query=${target.requestUri}, source=${target.source})`
            );

            const { rawEntries, hasJars } = await fetchRawEntries(target);

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
            return false;
        } catch (e) {
            outputChannel.appendLine(`[java-ext]   → error: ${e}`);
            return false;
        }
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
            if (projDirNorm === wsRootPath || knownPaths.has(projDirNorm)) continue;
            const sourceUri = await findRepresentativeSourceUri(projDir);
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
    // Returns the number of projects that had actual JAR entries.
    const fetchAllClasspaths = async (): Promise<number> => {
        try {
            const projectRootMap = await collectProjectRootMap();

            const projectUris: string[] =
                (await vscode.commands.executeCommand('java.project.getAll')) ?? [];
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

            if (targets.length > 0) {
                outputChannel.appendLine(`[java-ext] Total ${targets.length} project(s) to fetch classpath for`);
            }

            let fetched = 0;
            // Fetch classpaths in parallel batches of 20 to avoid
            // sequential round-trips for large multi-project workspaces.
            const BATCH_SIZE = 20;
            for (let i = 0; i < targets.length; i += BATCH_SIZE) {
                const batch = targets.slice(i, i + BATCH_SIZE);
                const results = await Promise.allSettled(
                    batch.map(target => fetchClasspathForProject(target, projectRootMap))
                );
                for (const r of results) {
                    if (r.status === 'fulfilled' && r.value) fetched++;
                }
            }
            outputChannel.appendLine(`[java-ext] Sent classpath for ${fetched}/${targets.length} project(s)`);
            return fetched;
        } catch (e) {
            outputChannel.appendLine(`[java-ext] Failed to enumerate projects: ${e}`);
            return fetchClasspathFallback();
        }
    };

    /** Fallback: try workspace folders directly. */
    const fetchClasspathFallback = async (): Promise<number> => {
        const folders = workspace.workspaceFolders;
        if (!folders) return 0;
        const projectRootMap = await collectProjectRootMap();
        let fetched = 0;
        for (const folder of folders) {
            const representativeUri = await findRepresentativeSourceUri(folder.uri.fsPath);
            const ok = await fetchClasspathForProject({
                requestUri: representativeUri ?? folder.uri.toString(),
                projectUri: folder.uri.toString(),
                projectPath: folder.uri.fsPath,
                source: 'workspace-folder-fallback',
            }, projectRootMap);
            if (ok) fetched++;
        }
        return fetched;
    };

    /** Force project configuration updates for all Java projects. */
    const forceProjectConfigurationUpdates = async (): Promise<void> => {
        outputChannel.appendLine('[java-ext] No JARs on first attempt. Forcing project configuration update...');
        try {
            const projectUris: string[] =
                (await vscode.commands.executeCommand('java.project.getAll')) ?? [];
            for (const uri of projectUris) {
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
    // when we first fetch. Retry with increasing delays until we get actual JARs.
    // On first failure, trigger java.projectConfiguration.update to force dependency resolution.
    const fetchWithRetry = async (attempt: number = 1, maxAttempts: number = 6): Promise<void> => {
        const fetched = await fetchAllClasspaths();
        if (fetched > 0 || attempt >= maxAttempts) {
            return;
        }

        if (attempt === 1) {
            await forceProjectConfigurationUpdates();
        }

        const delay = Math.min(attempt * 5000, 30000); // 5s, 10s, 15s, 20s, 25s
        outputChannel.appendLine(
            `[java-ext] No JARs yet (attempt ${attempt}/${maxAttempts}). ` +
            `Retrying in ${delay / 1000}s...`
        );
        setTimeout(() => fetchWithRetry(attempt + 1, maxAttempts).catch(e =>
            outputChannel.appendLine(`[java-ext] fetchWithRetry error: ${e}`)
        ), delay);
    };

    // Listen for classpath changes from the Java extension
    if (typeof javaApi.onDidClasspathUpdate === 'function') {
        context.subscriptions.push(
            javaApi.onDidClasspathUpdate(async (uri: vscode.Uri) => {
                outputChannel.appendLine(`[java-ext] Classpath updated for: ${uri.toString()}`);
                if (isJdtWorkspaceUri(uri.toString())) {
                    outputChannel.appendLine('[java-ext] Ignoring classpath update for jdt_ws project.');
                    return;
                }
                // Re-fetch only the changed project (not all — each project has isolated classpath)
                const projectRootMap = await collectProjectRootMap();
                await fetchClasspathForProject({
                    requestUri: uri.toString(),
                    projectUri: uri.toString(),
                    projectPath: resolveProjectPathFromUri(uri.toString(), projectRootMap),
                    source: 'onDidClasspathUpdate',
                }, projectRootMap);
            })
        );
        outputChannel.appendLine('[java-ext] Registered onDidClasspathUpdate listener.');
    }

    // Listen for project import completion
    if (typeof javaApi.onDidProjectsImport === 'function') {
        context.subscriptions.push(
            javaApi.onDidProjectsImport((uris: vscode.Uri[]) => {
                outputChannel.appendLine(
                    `[java-ext] Projects imported (${uris.length}) — refreshing classpath...`
                );
                if (uris.length === 0) {
                    fetchAllClasspaths();
                    return;
                }

                (async () => {
                    const projectRootMap = await collectProjectRootMap();
                    for (const importedUri of uris) {
                        if (isJdtWorkspaceUri(importedUri.toString())) continue;
                        await fetchClasspathForProject({
                            requestUri: importedUri.toString(),
                            projectUri: importedUri.toString(),
                            projectPath: resolveProjectPathFromUri(importedUri.toString(), projectRootMap),
                            source: 'onDidProjectsImport',
                        }, projectRootMap);
                    }
                })().catch(e => outputChannel.appendLine(`[java-ext] onDidProjectsImport error: ${e}`));
            })
        );
        outputChannel.appendLine('[java-ext] Registered onDidProjectsImport listener.');
    }

    // Also listen for the standard Java extension server ready event
    if (typeof javaApi.onDidServerModeChange === 'function') {
        context.subscriptions.push(
            javaApi.onDidServerModeChange((mode: string) => {
                outputChannel.appendLine(`[java-ext] Server mode changed: ${mode}`);
                if (mode === 'Standard') {
                    // Standard mode is ready — fetch classpath with retries
                    setTimeout(() => fetchWithRetry(), 3000);
                }
            })
        );
    }

    // Initial classpath fetch — serverReady() should have ensured the server
    // is in Standard mode and projects are imported. Fetch with retry as a safety net.
    fetchWithRetry();
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

function sendPrefetchedClasspaths(languageClient: LanguageClient): void {
    if (preFetchedClasspathData.length === 0) {
        return;
    }

    outputChannel.appendLine(`[java-ext] Sending ${preFetchedClasspathData.length} pre-fetched classpath(s) to server...`);
    for (const cp of preFetchedClasspathData) {
        languageClient.sendNotification('groovy/classpathUpdate', {
            projectUri: cp.projectUri,
            projectPath: cp.projectPath,
            entries: cp.entries,
        });
        outputChannel.appendLine(
            `[java-ext]   Sent ${cp.entries.length} entries for ${cp.projectUri}`
            + (cp.projectPath ? ` (projectPath=${cp.projectPath})` : '')
        );
    }

    preFetchedClasspathData = [];
    languageClient.sendNotification('groovy/classpathBatchComplete', {});
    outputChannel.appendLine('[java-ext] Sent groovy/classpathBatchComplete to server.');
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

/**
 * Get the platform-specific Equinox configuration directory.
 */
function getConfigDir(serverDir: string): string {
    return path.join(serverDir, getConfigNameForPlatform(process.platform));
}
