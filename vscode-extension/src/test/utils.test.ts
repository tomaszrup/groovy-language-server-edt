import { strict as assert } from 'assert';
import {
    getConfigNameForPlatform,
    inferProjectPathFromEntries,
    isJdtWorkspaceUri,
    normalizeFsPath,
    pathStartsWith,
} from '../utils';

describe('utils', () => {
    it('normalizes fs paths', () => {
        assert.equal(normalizeFsPath('C:\\Work\\Proj\\'), 'c:/work/proj');
        assert.equal(normalizeFsPath('/Users/Dev/Project///'), '/users/dev/project');
    });

    it('detects jdt workspace URIs', () => {
        assert.equal(isJdtWorkspaceUri('file:///tmp/jdt_ws/project/src/Foo.groovy'), true);
        assert.equal(isJdtWorkspaceUri('file:///tmp/workspace/src/Foo.groovy'), false);
    });

    it('checks path prefix with segment boundaries', () => {
        assert.equal(pathStartsWith('/a/b/c', '/a/b'), true);
        assert.equal(pathStartsWith('/a/b', '/a/b'), true);
        assert.equal(pathStartsWith('/a/beta', '/a/b'), false);
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

    it('maps platform to config dir name', () => {
        assert.equal(getConfigNameForPlatform('win32'), 'config_win');
        assert.equal(getConfigNameForPlatform('darwin'), 'config_mac');
        assert.equal(getConfigNameForPlatform('linux'), 'config_linux');
    });
});
