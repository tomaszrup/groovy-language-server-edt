import { strict as assert } from 'assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { prepareWorkspaceDataDir } from '../workspaceData';

describe('workspaceData', () => {
    let tempDir: string;

    beforeEach(() => {
        tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-workspace-data-test-'));
    });

    afterEach(() => {
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    it('creates the persisted workspace directory and marker on first run', () => {
        const storagePath = path.join(tempDir, 'storage');

        const result = prepareWorkspaceDataDir({ storagePath });

        assert.equal(result.resetWorkspace, false);
        assert.equal(fs.existsSync(result.dataDir), true);
        assert.equal(
            JSON.parse(fs.readFileSync(path.join(storagePath, 'groovy_ws_state.json'), 'utf8')).version,
            2
        );
    });

    it('resets persisted workspace data when the marker version changes', () => {
        const storagePath = path.join(tempDir, 'storage');
        const first = prepareWorkspaceDataDir({ storagePath });
        fs.writeFileSync(path.join(first.dataDir, 'stale.cache'), 'old', 'utf8');
        fs.writeFileSync(
            path.join(storagePath, 'groovy_ws_state.json'),
            JSON.stringify({ version: 1 }, null, 2),
            'utf8'
        );

        const second = prepareWorkspaceDataDir({ storagePath });

        assert.equal(second.resetWorkspace, true);
        assert.equal(second.reason, 'workspace data version 1 -> 2');
        assert.equal(fs.existsSync(path.join(second.dataDir, 'stale.cache')), false);
    });

    it('resets persisted workspace data when the marker is invalid', () => {
        const storagePath = path.join(tempDir, 'storage');
        const first = prepareWorkspaceDataDir({ storagePath });
        fs.writeFileSync(path.join(first.dataDir, 'stale.cache'), 'old', 'utf8');
        fs.writeFileSync(path.join(storagePath, 'groovy_ws_state.json'), '{invalid json', 'utf8');

        const second = prepareWorkspaceDataDir({ storagePath });

        assert.equal(second.resetWorkspace, true);
        assert.equal(second.reason, 'invalid workspace data marker');
        assert.equal(fs.existsSync(path.join(second.dataDir, 'stale.cache')), false);
    });

    it('resets persisted workspace data when the marker is missing', () => {
        const storagePath = path.join(tempDir, 'storage');
        const dataDir = path.join(storagePath, 'groovy_ws');
        fs.mkdirSync(dataDir, { recursive: true });
        fs.writeFileSync(path.join(dataDir, 'leftover.index'), 'orphan', 'utf8');

        const result = prepareWorkspaceDataDir({ storagePath });

        assert.equal(result.resetWorkspace, true);
        assert.equal(result.reason, 'missing workspace data marker');
        assert.equal(fs.existsSync(path.join(result.dataDir, 'leftover.index')), false);
    });

    it('resets persisted workspace data when restore corruption is detected', () => {
        const storagePath = path.join(tempDir, 'storage');
        const first = prepareWorkspaceDataDir({ storagePath });
        const metadataDir = path.join(first.dataDir, '.metadata');
        fs.mkdirSync(metadataDir, { recursive: true });
        fs.writeFileSync(path.join(first.dataDir, 'stale.cache'), 'old', 'utf8');
        fs.writeFileSync(path.join(metadataDir, '.log'), [
            '!ENTRY org.eclipse.core.resources 4 0',
            '!MESSAGE FrameworkEvent ERROR',
            'Caused by: org.eclipse.core.internal.dtree.ObjectNotFoundException: Tree element missing.',
            'at org.eclipse.core.internal.resources.SaveManager.restore(SaveManager.java:834)',
        ].join('\n'), 'utf8');

        const second = prepareWorkspaceDataDir({ storagePath });

        assert.equal(second.resetWorkspace, true);
        assert.equal(second.reason, 'corrupt Eclipse workspace restore state detected in .log');
        assert.equal(fs.existsSync(path.join(second.dataDir, 'stale.cache')), false);
    });
});