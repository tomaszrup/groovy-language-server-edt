import * as fs from 'node:fs';
import * as path from 'node:path';
import {
    normalizeFsPath,
    pathStartsWith,
    uriToFsPath,
} from './utils';

export const GRADLE_CLASSPATH_LINE_PREFIX = 'GROOVY_LS_CP::';

export function resolveProjectPathFromUri(
    uriValue: string,
    projectRootMap: Map<string, string>
): string | undefined {
    const fsPath = uriToFsPath(uriValue);
    if (!fsPath) {
        return undefined;
    }

    const normalizedPath = normalizeFsPath(fsPath);
    const exact = projectRootMap.get(normalizedPath);
    if (exact) {
        return exact;
    }

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

export function findGradleWrapper(startDir: string): string | undefined {
    const executableName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    let currentDir = path.resolve(startDir);

    while (true) {
        const candidate = path.join(currentDir, executableName);
        if (fs.existsSync(candidate)) {
            return candidate;
        }

        const parentDir = path.dirname(currentDir);
        if (parentDir === currentDir) {
            return undefined;
        }
        currentDir = parentDir;
    }
}

export function extractPathFromClasspathEntry(entry: unknown): string {
    const rawPath = getProperty(entry, 'path');
    if (typeof rawPath === 'string') {
        return uriToFsPath(rawPath) ?? rawPath;
    }

    if (rawPath && typeof rawPath === 'object') {
        const nestedPath = getProperty(rawPath, 'path');
        if (typeof nestedPath === 'string') {
            return nestedPath;
        }

        const nestedFsPath = getProperty(rawPath, 'fsPath');
        if (typeof nestedFsPath === 'string') {
            return nestedFsPath;
        }
    }

    return '';
}

export function isJdkEntry(entryPath: string, javaHomeNorm: string): boolean {
    const normalizedEntryPath = entryPath.replaceAll('\\', '/').toLowerCase();
    if (javaHomeNorm && normalizedEntryPath.startsWith(javaHomeNorm)) {
        return true;
    }

    return (normalizedEntryPath.includes('/jdk-')
        || normalizedEntryPath.includes('/jdk/')
        || normalizedEntryPath.includes('/jre/'))
        && !normalizedEntryPath.includes('.gradle/')
        && !normalizedEntryPath.includes('.m2/');
}

export function extractEntriesFromSettings(
    settingsResult: unknown
): { jars: string[]; outputDirs: string[] } {
    const jars: string[] = [];
    const outputDirs: string[] = [];
    const entries = getProperty(settingsResult, 'org.eclipse.jdt.ls.core.classpathEntries');

    if (!Array.isArray(entries)) {
        return { jars, outputDirs };
    }

    for (const entry of entries) {
        const entryPath = extractPathFromClasspathEntry(entry);
        if (!entryPath) {
            continue;
        }

        const kind = getNumericProperty(entry, 'kind');
        const entryKind = getNumericProperty(entry, 'entryKind');
        if (entryPath.toLowerCase().endsWith('.jar')) {
            jars.push(entryPath);
        } else if (kind === 4 || entryKind === 4) {
            outputDirs.push(entryPath);
        } else if (kind === 3 || entryKind === 3) {
            jars.push(entryPath);
        }
    }

    return { jars, outputDirs };
}

export function buildGradleClasspathInitScript(
    linePrefix: string = GRADLE_CLASSPATH_LINE_PREFIX
): string {
    return `allprojects {
    tasks.register('groovyLsPrintClasspath') {
        doLast {
            ['testCompileClasspath', 'testRuntimeClasspath', 'compileClasspath', 'runtimeClasspath'].each { cfgName ->
                def cfg = project.configurations.findByName(cfgName)
                if (cfg != null && cfg.canBeResolved) {
                    try {
                        cfg.resolve().each { f ->
                            println('${linePrefix}' + f.absolutePath)
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
}

export function extractGradleClasspathEntries(
    stdout: string,
    existsSync: (pathValue: string) => boolean,
    linePrefix: string = GRADLE_CLASSPATH_LINE_PREFIX
): string[] {
    const lines = stdout.split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line.startsWith(linePrefix))
        .map(line => line.substring(linePrefix.length).trim())
        .filter(line => line.length > 0 && existsSync(line));

    return Array.from(new Set(lines));
}

function getProperty(value: unknown, propertyName: string): unknown {
    if (!value || typeof value !== 'object') {
        return undefined;
    }
    return (value as Record<string, unknown>)[propertyName];
}

function getNumericProperty(value: unknown, propertyName: string): number | undefined {
    const propertyValue = getProperty(value, propertyName);
    return typeof propertyValue === 'number' ? propertyValue : undefined;
}