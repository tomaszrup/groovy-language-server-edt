import { strict as assert } from 'assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { detectWorkspaceRestoreCorruption } from '../workspaceRecovery';

describe('workspaceRecovery', () => {
    let tempDir: string;

    beforeEach(() => {
        tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-workspace-test-'));
    });

    afterEach(() => {
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    it('detects corrupt Eclipse workspace restore logs', () => {
        const metadataDir = path.join(tempDir, '.metadata');
        fs.mkdirSync(metadataDir, { recursive: true });
        fs.writeFileSync(path.join(metadataDir, '.log'), [
            '!ENTRY org.eclipse.core.resources 4 0',
            '!MESSAGE FrameworkEvent ERROR',
            'Caused by: org.eclipse.core.internal.dtree.ObjectNotFoundException: Tree element missing.',
            'at org.eclipse.core.internal.resources.SaveManager.restore(SaveManager.java:834)',
        ].join('\n'), 'utf8');

        assert.equal(
            detectWorkspaceRestoreCorruption(tempDir),
            'corrupt Eclipse workspace restore state detected in .log'
        );
    });

    it('ignores unrelated workspace logs', () => {
        const metadataDir = path.join(tempDir, '.metadata');
        fs.mkdirSync(metadataDir, { recursive: true });
        fs.writeFileSync(path.join(metadataDir, '.log'), 'Regular startup log.\n', 'utf8');

        assert.equal(detectWorkspaceRestoreCorruption(tempDir), undefined);
    });
});
