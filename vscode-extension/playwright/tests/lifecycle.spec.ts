import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    getGroovyStatusAriaLabel,
    launchVsCode,
    runCommand,
    waitForGroovyReady,
    waitForGroovyStatus,
} from '../support/vscodeHarness';

test.describe('Groovy extension lifecycle', () => {
    test('reaches ready state in the sample workspace', async () => {
        const workspace = createWorkspaceCopy();
        const session = await launchVsCode({ workspacePath: workspace.workspacePath });

        try {
            const statusLabel = await waitForGroovyReady(session.page);

            expect(statusLabel).toContain('Groovy: Ready');
            expect(statusLabel).toContain('Groovy Language Server is ready');
        } finally {
            await session.close();
            await workspace.dispose();
        }
    });

    test('restarts from the command palette and returns to ready', async () => {
        const workspace = createWorkspaceCopy();
        const session = await launchVsCode({ workspacePath: workspace.workspacePath });

        try {
            await waitForGroovyReady(session.page);

            await runCommand(session.page, 'Groovy: Restart Language Server');

            const transitionalStatus = await waitForGroovyStatus(session.page, /Groovy: (Starting|Importing)/, 120_000);
            expect(transitionalStatus).toMatch(/Groovy: (Starting|Importing)/);

            const readyStatus = await waitForGroovyReady(session.page);
            expect(readyStatus).toContain('Groovy: Ready');
            expect(await getGroovyStatusAriaLabel(session.page)).toContain('Groovy Language Server is ready');
        } finally {
            await session.close();
            await workspace.dispose();
        }
    });
});