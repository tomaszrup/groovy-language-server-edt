import { strict as assert } from 'node:assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import {
    buildGradleClasspathInitScript,
    extractEntriesFromSettings,
    extractGradleClasspathEntries,
    extractPathFromClasspathEntry,
    findGradleWrapper,
    GRADLE_CLASSPATH_LINE_PREFIX,
    isJdkEntry,
    resolveProjectPathFromUri,
} from '../classpathUtils';

describe('classpathUtils', () => {
    let tempDir: string;

    beforeEach(() => {
        tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-classpath-utils-'));
    });

    afterEach(() => {
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    it('resolves the longest matching project root from a file uri', () => {
        const projectRootMap = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
            ['/workspace/root/app', '/workspace/root/app'],
        ]);

        const projectPath = resolveProjectPathFromUri(
            'file:///workspace/root/app/src/main/groovy/App.groovy',
            projectRootMap
        );

        assert.equal(projectPath, '/workspace/root/app');
    });

    it('finds the nearest gradle wrapper while walking parent directories', () => {
        const wrapperName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
        const rootDir = path.join(tempDir, 'workspace');
        const nestedDir = path.join(rootDir, 'module', 'src');
        fs.mkdirSync(nestedDir, { recursive: true });
        fs.writeFileSync(path.join(rootDir, wrapperName), '', 'utf8');

        assert.equal(findGradleWrapper(nestedDir), path.join(rootDir, wrapperName));
        assert.equal(findGradleWrapper(tempDir), undefined);
    });

    it('extracts classpath entry paths from supported shapes', () => {
        assert.equal(
            extractPathFromClasspathEntry({ path: 'file:///workspace/lib/example.jar' }),
            '/workspace/lib/example.jar'
        );
        assert.equal(
            extractPathFromClasspathEntry({ path: { path: '/workspace/build/classes/java/main' } }),
            '/workspace/build/classes/java/main'
        );
        assert.equal(
            extractPathFromClasspathEntry({ path: { fsPath: '/workspace/build/resources/main' } }),
            '/workspace/build/resources/main'
        );
        assert.equal(extractPathFromClasspathEntry({}), '');
    });

    it('detects JDK entries while ignoring dependency caches', () => {
        assert.equal(isJdkEntry('/usr/lib/jvm/jdk-21/lib/modules', '/usr/lib/jvm/jdk-21'), true);
        assert.equal(isJdkEntry('/opt/tools/jre/lib/rt.jar', ''), true);
        assert.equal(isJdkEntry('/home/user/.gradle/caches/jdk-21/lib/tools.jar', ''), false);
        assert.equal(isJdkEntry('/home/user/.m2/repository/example.jar', ''), false);
    });

    it('extracts jars and output directories from JDT settings', () => {
        const settings = {
            'org.eclipse.jdt.ls.core.classpathEntries': [
                { path: 'file:///workspace/.gradle/caches/example.jar' },
                { kind: 4, path: '/workspace/build/classes/java/main' },
                { entryKind: 3, path: '/workspace/libs/local-lib' },
                { kind: 1, path: '/workspace/src/main/groovy' },
            ],
        };

        assert.deepEqual(extractEntriesFromSettings(settings), {
            jars: [
                '/workspace/.gradle/caches/example.jar',
                '/workspace/libs/local-lib',
            ],
            outputDirs: ['/workspace/build/classes/java/main'],
        });
    });

    it('builds the Gradle fallback init script with the classpath marker prefix', () => {
        const script = buildGradleClasspathInitScript();

        assert.equal(script.includes("tasks.register('groovyLsPrintClasspath')"), true);
        assert.equal(script.includes(`println('${GRADLE_CLASSPATH_LINE_PREFIX}' + f.absolutePath)`), true);
    });

    it('extracts unique existing Gradle classpath entries from task output', () => {
        const stdout = [
            'random log line',
            `${GRADLE_CLASSPATH_LINE_PREFIX} /workspace/.gradle/caches/a.jar`,
            `${GRADLE_CLASSPATH_LINE_PREFIX}/workspace/build/classes/java/main`,
            `${GRADLE_CLASSPATH_LINE_PREFIX}/workspace/build/classes/java/main`,
            `${GRADLE_CLASSPATH_LINE_PREFIX}/missing/path.jar`,
        ].join('\n');
        const existingPaths = new Set<string>([
            '/workspace/.gradle/caches/a.jar',
            '/workspace/build/classes/java/main',
        ]);

        assert.deepEqual(
            extractGradleClasspathEntries(stdout, pathValue => existingPaths.has(pathValue)),
            [
                '/workspace/.gradle/caches/a.jar',
                '/workspace/build/classes/java/main',
            ]
        );
    });
});