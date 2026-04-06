import { strict as assert } from 'node:assert';
import * as path from 'node:path';
import {
    buildInitializationOptionsFromSettings,
    findCorruptCriticalPluginJar,
    findLauncherJar,
    getJavaExecutable,
    parseJavaMajorVersion,
    resolveJavaHomeFromEnvironment,
    resolveServerDirFromExtensionPath,
    type FileSystemLike,
} from '../serverRuntimeUtils';

describe('serverRuntimeUtils', () => {
    it('prefers configured Java home, then JAVA_HOME, then PATH discovery', () => {
        const files = new Set<string>([
            '/configured/jdk',
            '/env/jdk',
            '/usr/bin/java',
            '/resolved/jdk/bin',
        ]);
        const fileSystem = createFileSystem({
            existsSync: pathValue => files.has(pathValue),
            realpathSync: () => '/resolved/jdk/bin/java',
        });

        assert.equal(resolveJavaHomeFromEnvironment({
            configuredHome: '/configured/jdk',
            envHome: '/env/jdk',
            pathValue: '/usr/bin:/other/bin',
            platform: 'linux',
            fileSystem,
        }), '/configured/jdk');

        assert.equal(resolveJavaHomeFromEnvironment({
            configuredHome: '/missing/configured',
            envHome: '/env/jdk',
            pathValue: '/usr/bin:/other/bin',
            platform: 'linux',
            fileSystem,
        }), '/env/jdk');

        assert.equal(resolveJavaHomeFromEnvironment({
            configuredHome: '/missing/configured',
            envHome: '/missing/env',
            pathValue: '/usr/bin:/other/bin',
            platform: 'linux',
            fileSystem,
        }), '/resolved/jdk');
    });

    it('falls back to the bin directory when PATH discovery cannot prove a home dir', () => {
        const fileSystem = createFileSystem({
            existsSync: pathValue => pathValue === '/usr/bin/java',
            realpathSync: () => '/usr/bin/java',
        });

        assert.equal(resolveJavaHomeFromEnvironment({
            pathValue: '/usr/bin:/other/bin',
            platform: 'linux',
            fileSystem,
        }), '/usr/bin');
    });

    it('builds the Java executable path from either a JDK root or bin dir', () => {
        assert.equal(
            getJavaExecutable('/jdk-21', 'linux', pathValue => pathValue === '/jdk-21/bin/java'),
            '/jdk-21/bin/java'
        );
        assert.equal(
            getJavaExecutable('/jdk-21/bin', 'linux', () => false),
            '/jdk-21/bin/java'
        );
    });

    it('parses Java major versions from modern and legacy java -version output', () => {
        assert.equal(
            parseJavaMajorVersion('openjdk version "21.0.4" 2024-07-16\nOpenJDK Runtime Environment'),
            21
        );
        assert.equal(
            parseJavaMajorVersion('java version "1.8.0_392"\nJava(TM) SE Runtime Environment'),
            8
        );
    });

    it('returns undefined when java -version output cannot be parsed', () => {
        assert.equal(parseJavaMajorVersion('openjdk full version 21.0.4'), undefined);
        assert.equal(parseJavaMajorVersion('java version "beta"'), undefined);
    });

    it('emits only non-default initialization options', () => {
        assert.deepEqual(buildInitializationOptionsFromSettings({
            delegatedClasspathStartup: true,
            requestPoolSize: 6,
            requestQueueSize: 64,
            backgroundPoolSize: 2,
            backgroundQueueSize: 256,
        }), {
            delegatedClasspathStartup: true,
            lspRequestPoolSize: 6,
            lspBackgroundPoolSize: 2,
            lspBackgroundQueueSize: 256,
        });
    });

    it('finds the first valid packaged server directory', () => {
        const extensionPath = '/workspace/vscode-extension';
        const productDir = path.join(extensionPath, '..', 'org.eclipse.groovy.ls.product', 'build', 'product');
        const existingDirs = new Set<string>([
            path.join(productDir, 'plugins'),
        ]);

        assert.equal(
            resolveServerDirFromExtensionPath(extensionPath, pathValue => existingDirs.has(pathValue)),
            productDir
        );
    });

    it('locates the Equinox launcher jar in the plugins directory', () => {
        const serverDir = '/workspace/server';
        const pluginsDir = path.join(serverDir, 'plugins');

        assert.equal(
            findLauncherJar(
                serverDir,
                pathValue => pathValue === pluginsDir,
                () => ['a.jar', 'org.eclipse.equinox.launcher_1.7.0.jar']
            ),
            path.join(pluginsDir, 'org.eclipse.equinox.launcher_1.7.0.jar')
        );
    });

    it('returns undefined when the plugins directory is missing', () => {
        assert.equal(
            findLauncherJar('/workspace/server', () => false, () => ['org.eclipse.equinox.launcher_1.7.0.jar']),
            undefined
        );
    });

    it('detects corrupt critical plugin jars by size', () => {
        const serverDir = '/workspace/server';
        const pluginsDir = path.join(serverDir, 'plugins');
        const fileSystem = createFileSystem({
            readdirSync: pathValue => pathValue === pluginsDir
                ? ['org.eclipse.groovy.ls.core_1.0.0.jar', 'other.jar']
                : [],
            statSync: pathValue => ({
                size: pathValue.endsWith('org.eclipse.groovy.ls.core_1.0.0.jar') ? 1024 : 200_000,
            }),
        });

        assert.deepEqual(findCorruptCriticalPluginJar(serverDir, fileSystem), {
            jarName: 'org.eclipse.groovy.ls.core_1.0.0.jar',
            size: 1024,
        });
    });

    it('ignores unreadable or non-critical plugin jars during corruption detection', () => {
        const serverDir = '/workspace/server';
        const pluginsDir = path.join(serverDir, 'plugins');
        const fileSystem = createFileSystem({
            readdirSync: pathValue => pathValue === pluginsDir
                ? ['org.eclipse.groovy.ls.core_1.0.0.jar', 'notes.txt']
                : [],
            statSync: () => {
                throw new Error('broken stat');
            },
        });

        assert.equal(findCorruptCriticalPluginJar(serverDir, fileSystem), undefined);
    });
});

function createFileSystem(overrides: Partial<FileSystemLike>): FileSystemLike {
    return {
        existsSync: () => false,
        realpathSync: pathValue => pathValue,
        readdirSync: () => [],
        statSync: () => ({ size: 0 }),
        ...overrides,
    };
}