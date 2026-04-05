import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
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

        const referencesLens = session.page.getByText('1 reference', { exact: false }).first();
        await expect(referencesLens).toBeVisible({ timeout: 30_000 });
        await referencesLens.click();

        const peek = session.page.locator('.peekview-widget');
        await expect(peek).toBeVisible({ timeout: 30_000 });
        await expect(peek).toContainText('References (1)', { timeout: 30_000 });
        await expect(peek).toContainText('SampleApplicationSpec.groovy', { timeout: 30_000 });
        await expect(peek).toContainText('implements Trat, OthererName, AppContextTest, SoemethingTest', {
            timeout: 30_000,
        });
    } finally {
        await session.close();
        workspace.dispose();
    }
});