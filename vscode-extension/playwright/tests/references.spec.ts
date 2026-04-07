import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    runCommand,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('opens the references peek from a Groovy symbol', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await openFile(session.page, 'src/test/groovy/com/example/sample/OthererName.groovy:3:9');
        await runCommand(session.page, 'Peek References');

        const peek = session.page.locator('.peekview-widget');
        await expect(peek).toBeVisible({ timeout: 30_000 });
        await expect(peek).toContainText('References (1)', { timeout: 30_000 });
        await expect(peek).toContainText('trait OthererName {', { timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});
