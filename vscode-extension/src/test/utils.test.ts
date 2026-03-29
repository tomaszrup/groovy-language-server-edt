import { strict as assert } from 'assert';
import {
    getConfigNameForPlatform,
    inferProjectPathFromEntries,
    isJdtWorkspaceUri,
    normalizeFsPath,
    pathStartsWith,
    uriToFsPath,
} from '../utils';

describe('utils', () => {
    it('normalizes fs paths', () => {
        assert.equal(normalizeFsPath('C:\\Work\\Proj\\'), 'c:/work/proj');
        assert.equal(normalizeFsPath('/Users/Dev/Project///'), '/users/dev/project');
    });

    it('normalizes paths with multiple trailing slashes', () => {
        assert.equal(normalizeFsPath('C:\\Work\\Proj\\\\\\'), 'c:/work/proj');
    });

    it('normalizes empty string', () => {
        assert.equal(normalizeFsPath(''), '');
    });

    it('normalizes single character path', () => {
        assert.equal(normalizeFsPath('/'), '');
        assert.equal(normalizeFsPath('C'), 'c');
    });

    it('normalizes UNC paths', () => {
        assert.equal(normalizeFsPath('\\\\server\\share\\dir'), '//server/share/dir');
    });

    it('detects jdt workspace URIs', () => {
        assert.equal(isJdtWorkspaceUri('file:///tmp/jdt_ws/project/src/Foo.groovy'), true);
        assert.equal(isJdtWorkspaceUri('file:///tmp/workspace/src/Foo.groovy'), false);
    });

    it('detects jdt workspace URIs case-insensitively', () => {
        assert.equal(isJdtWorkspaceUri('file:///tmp/JDT_WS/project/src/Foo.groovy'), true);
    });

    it('converts file URIs to filesystem paths', () => {
        assert.equal(uriToFsPath('file:///workspace/app/src/Main.groovy'), '/workspace/app/src/Main.groovy');
        assert.equal(uriToFsPath('file:///C:/Work/Proj/App.groovy'), 'C:/Work/Proj/App.groovy');
        assert.equal(uriToFsPath('file://server/share/folder/Main.groovy'), '//server/share/folder/Main.groovy');
    });

    it('extracts paths from remote hierarchical URIs', () => {
        assert.equal(
            uriToFsPath('vscode-remote://ssh-remote+dev/workspace/app/src/Main.groovy'),
            '/workspace/app/src/Main.groovy'
        );
    });

    it('extracts Windows drive-letter paths from remote hierarchical URIs', () => {
        assert.equal(
            uriToFsPath('vscode-remote://ssh-remote+dev/C%3A/Work/Proj/App.groovy'),
            'C:/Work/Proj/App.groovy'
        );
    });

    it('returns undefined for non-path-bearing URIs', () => {
        assert.equal(uriToFsPath('untitled:Scratch.groovy'), undefined);
    });

    it('checks path prefix with segment boundaries', () => {
        assert.equal(pathStartsWith('/a/b/c', '/a/b'), true);
        assert.equal(pathStartsWith('/a/b', '/a/b'), true);
        assert.equal(pathStartsWith('/a/beta', '/a/b'), false);
    });

    it('pathStartsWith handles empty strings', () => {
        assert.equal(pathStartsWith('', ''), true);
        assert.equal(pathStartsWith('/a/b', ''), true);
        assert.equal(pathStartsWith('', '/a'), false);
    });

    it('infers best project path from classpath entries', () => {
        const map = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
            ['/workspace/root/app', '/workspace/root/app'],
            ['/workspace/other', '/workspace/other'],
        ]);

        const entries = [
            '/workspace/root/app/build/classes/java/main',
            '/workspace/root/app/build/resources/main',
            '/workspace/root/build/generated/sources',
        ];

        assert.equal(inferProjectPathFromEntries(entries, map), '/workspace/root/app');
    });

    it('returns undefined when inference inputs are empty', () => {
        assert.equal(inferProjectPathFromEntries([], new Map()), undefined);
    });

    it('returns undefined when entries match no project', () => {
        const map = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
        ]);
        const entries = ['/other/path/build/classes'];
        assert.equal(inferProjectPathFromEntries(entries, map), undefined);
    });

    it('handles single entry inference', () => {
        const map = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
        ]);
        const entries = ['/workspace/root/build/classes/java/main'];
        assert.equal(inferProjectPathFromEntries(entries, map), '/workspace/root');
    });

    it('maps platform to config dir name', () => {
        assert.equal(getConfigNameForPlatform('win32'), 'config_win');
        assert.equal(getConfigNameForPlatform('darwin'), 'config_mac');
        assert.equal(getConfigNameForPlatform('linux'), 'config_linux');
    });

    it('maps uncommon platforms to linux config', () => {
        assert.equal(getConfigNameForPlatform('freebsd'), 'config_linux');
        assert.equal(getConfigNameForPlatform('aix'), 'config_linux');
        assert.equal(getConfigNameForPlatform('sunos'), 'config_linux');
    });
});
