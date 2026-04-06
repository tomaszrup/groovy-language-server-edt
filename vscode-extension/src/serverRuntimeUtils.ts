import * as path from 'node:path';

const DEFAULT_REQUEST_QUEUE_SIZE = 64;
const DEFAULT_BACKGROUND_QUEUE_SIZE = 128;
const CRITICAL_PLUGIN_PREFIXES = [
    'org.eclipse.jdt.core_',
    'org.eclipse.jdt.groovy.core_',
    'org.eclipse.groovy.ls.core_',
    'org.codehaus.groovy_',
];
const MINIMUM_CRITICAL_JAR_SIZE_BYTES = 50_000;

export interface FileSystemLike {
    existsSync(pathValue: string): boolean;
    realpathSync(pathValue: string): string;
    readdirSync(pathValue: string): string[];
    statSync(pathValue: string): { size: number };
}

export interface ResolveJavaHomeOptions {
    configuredHome?: string;
    envHome?: string;
    pathValue?: string;
    platform: NodeJS.Platform;
    fileSystem: Pick<FileSystemLike, 'existsSync' | 'realpathSync'>;
}

export interface InitializationOptionSettings {
    delegatedClasspathStartup: boolean;
    requestPoolSize: number;
    requestQueueSize: number;
    backgroundPoolSize: number;
    backgroundQueueSize: number;
}

export interface CorruptCriticalJarInfo {
    jarName: string;
    size: number;
}

export function resolveJavaHomeFromEnvironment(options: ResolveJavaHomeOptions): string | undefined {
    const executableName = options.platform === 'win32' ? 'java.exe' : 'java';

    if (options.configuredHome && options.fileSystem.existsSync(options.configuredHome)) {
        return options.configuredHome;
    }

    if (options.envHome && options.fileSystem.existsSync(options.envHome)) {
        return options.envHome;
    }

    const pathEntries = options.pathValue?.split(path.delimiter) ?? [];
    const javaBinDir = pathEntries.find(entry => {
        const javaBinaryPath = path.join(entry, executableName);
        return options.fileSystem.existsSync(javaBinaryPath);
    });

    if (!javaBinDir) {
        return undefined;
    }

    const javaBinPath = path.join(javaBinDir, executableName);
    let resolvedBinaryPath: string;
    try {
        resolvedBinaryPath = options.fileSystem.realpathSync(javaBinPath);
    } catch {
        resolvedBinaryPath = javaBinPath;
    }

    const binDir = path.dirname(resolvedBinaryPath);
    const homeDir = path.dirname(binDir);
    if (options.fileSystem.existsSync(path.join(homeDir, 'bin'))) {
        return homeDir;
    }

    return binDir;
}

export function getJavaExecutable(
    javaHome: string,
    platform: NodeJS.Platform,
    existsSync: (pathValue: string) => boolean
): string {
    const executableName = platform === 'win32' ? 'java.exe' : 'java';
    const javaInBinDir = path.join(javaHome, 'bin', executableName);
    if (existsSync(javaInBinDir)) {
        return javaInBinDir;
    }
    return path.join(javaHome, executableName);
}

export function parseJavaMajorVersion(versionText: string): number | undefined {
    const match = /version\s+"([^"]+)"/i.exec(versionText);
    if (!match) {
        return undefined;
    }

    const rawVersion = match[1];
    const majorToken = rawVersion.startsWith('1.')
        ? rawVersion.split('.')[1]
        : rawVersion.split(/[.+-]/)[0];
    const majorVersion = Number.parseInt(majorToken, 10);
    return Number.isNaN(majorVersion) ? undefined : majorVersion;
}

export function buildInitializationOptionsFromSettings(
    settings: InitializationOptionSettings
): Record<string, unknown> {
    const initOptions: Record<string, unknown> = {
        delegatedClasspathStartup: settings.delegatedClasspathStartup,
    };

    if (settings.requestPoolSize > 0) {
        initOptions.lspRequestPoolSize = settings.requestPoolSize;
    }
    if (settings.requestQueueSize !== DEFAULT_REQUEST_QUEUE_SIZE) {
        initOptions.lspRequestQueueSize = settings.requestQueueSize;
    }
    if (settings.backgroundPoolSize > 0) {
        initOptions.lspBackgroundPoolSize = settings.backgroundPoolSize;
    }
    if (settings.backgroundQueueSize !== DEFAULT_BACKGROUND_QUEUE_SIZE) {
        initOptions.lspBackgroundQueueSize = settings.backgroundQueueSize;
    }

    return initOptions;
}

export function resolveServerDirFromExtensionPath(
    extensionPath: string,
    existsSync: (pathValue: string) => boolean
): string | undefined {
    const candidates = [
        path.join(extensionPath, 'server'),
        path.join(extensionPath, '..', 'org.eclipse.groovy.ls.product', 'build', 'product'),
        path.join(extensionPath, '..', 'org.eclipse.groovy.ls.product', 'target', 'repository'),
    ];

    for (const candidate of candidates) {
        if (existsSync(path.join(candidate, 'plugins'))) {
            return candidate;
        }
    }

    return undefined;
}

export function findLauncherJar(
    serverDir: string,
    existsSync: (pathValue: string) => boolean,
    readdirSync: (pathValue: string) => string[]
): string | undefined {
    const pluginsDir = path.join(serverDir, 'plugins');
    if (!existsSync(pluginsDir)) {
        return undefined;
    }

    const launcherJarName = readdirSync(pluginsDir)
        .find(name => name.startsWith('org.eclipse.equinox.launcher_') && name.endsWith('.jar'));
    return launcherJarName ? path.join(pluginsDir, launcherJarName) : undefined;
}

export function findCorruptCriticalPluginJar(
    serverDir: string,
    fileSystem: Pick<FileSystemLike, 'readdirSync' | 'statSync'>
): CorruptCriticalJarInfo | undefined {
    const pluginsDir = path.join(serverDir, 'plugins');
    const criticalJars = fileSystem.readdirSync(pluginsDir).filter(isCriticalPluginJarName);

    for (const jarName of criticalJars) {
        const jarPath = path.join(pluginsDir, jarName);
        try {
            const stat = fileSystem.statSync(jarPath);
            if (stat.size < MINIMUM_CRITICAL_JAR_SIZE_BYTES) {
                return {
                    jarName,
                    size: stat.size,
                };
            }
        } catch {
            // Best effort only; callers can continue with Equinox diagnostics.
        }
    }

    return undefined;
}

function isCriticalPluginJarName(fileName: string): boolean {
    return fileName.endsWith('.jar')
        && CRITICAL_PLUGIN_PREFIXES.some(prefix => fileName.startsWith(prefix));
}