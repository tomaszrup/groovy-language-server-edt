import { expect, test } from '@playwright/test';
import { createWorkspaceCopy, launchVsCode, runCommand, waitForGroovyReady } from '../support/vscodeHarness';

test('shows the Groovy output channel with startup logs', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);

        await runCommand(session.page, 'Groovy: Show Output Channel');

        await expect(session.page.getByText('groovy/classpathBatchComplete', { exact: false })).toBeVisible({ timeout: 30_000 });
        await expect(session.page.getByText('[status] Ready', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('opens the Groovy output channel from the status bar item', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);

        await session.page.locator(String.raw`#TomaszRup\.groovy-spock-support`).click();

        await expect(session.page.getByText('[status] Ready', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});