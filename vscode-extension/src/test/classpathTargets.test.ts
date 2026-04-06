import { strict as assert } from 'assert';
import {
    buildJavaProjectTargets,
    collectKnownTargetPaths,
    createWorkspaceFolderFallbackTarget,
} from '../classpathTargets';

describe('classpathTargets', () => {
    it('skips jdt_ws projects and the root workspace folder when subprojects exist', () => {
        const workspaceRootPath = '/workspace/root';
        const projectUris = [
            'file:///workspace/root',
            'file:///workspace/root/app',
            'file:///workspace/root/lib',
            'file:///tmp/jdt_ws/root',
        ];
        const projectRootMap = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
            ['/workspace/root/app', '/workspace/root/app'],
            ['/workspace/root/lib', '/workspace/root/lib'],
        ]);

        const result = buildJavaProjectTargets(projectUris, projectRootMap, workspaceRootPath);

        assert.deepEqual(result.targets.map(target => target.projectPath), [
            '/workspace/root/app',
            '/workspace/root/lib',
        ]);
        assert.equal(result.nonRootProjectCount, 2);
        assert.deepEqual(result.skippedRootWorkspaceUris, ['file:///workspace/root']);
        assert.deepEqual(result.skippedJdtWorkspaceUris, ['file:///tmp/jdt_ws/root']);
    });

    it('keeps the root workspace project when it is the only non-jdt project', () => {
        const projectRootMap = new Map<string, string>([
            ['/workspace/root', '/workspace/root'],
        ]);

        const result = buildJavaProjectTargets(
            ['file:///workspace/root', 'file:///tmp/jdt_ws/root'],
            projectRootMap,
            '/workspace/root'
        );

        assert.equal(result.targets.length, 1);
        assert.equal(result.nonRootProjectCount, 0);
        assert.equal(result.targets[0].projectPath, '/workspace/root');
        assert.deepEqual(result.skippedRootWorkspaceUris, []);
    });

    it('collects normalized known paths from explicit project paths and project URIs', () => {
        const knownPaths = collectKnownTargetPaths([
            {
                requestUri: 'file:///workspace/root/app/src/App.groovy',
                projectUri: 'file:///workspace/root/app',
                projectPath: '/workspace/root/app',
                source: 'java.project.getAll',
            },
            {
                requestUri: 'file:///workspace/root/lib/src/Lib.groovy',
                projectUri: 'file:///workspace/root/lib',
                source: 'build-file-scan',
            },
        ]);

        assert.deepEqual(Array.from(knownPaths).sort(), [
            '/workspace/root/app',
            '/workspace/root/lib',
        ]);
    });

    it('creates workspace-folder fallback targets with representative uris when available', () => {
        assert.deepEqual(
            createWorkspaceFolderFallbackTarget(
                'file:///workspace/root',
                '/workspace/root',
                'file:///workspace/root/src/main/groovy/App.groovy'
            ),
            {
                requestUri: 'file:///workspace/root/src/main/groovy/App.groovy',
                projectUri: 'file:///workspace/root',
                projectPath: '/workspace/root',
                source: 'workspace-folder-fallback',
            }
        );
    });
});