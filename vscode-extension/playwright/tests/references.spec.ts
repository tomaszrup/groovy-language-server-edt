import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    waitForSampleClasspathReady,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('opens the references peek from a Groovy symbol', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await waitForSampleClasspathReady(session.page, true);

        await openFile(session.page, 'src/test/groovy/com/example/sample/OthererName.groovy:3:9');
        await openReferencesPeek(session.page);

        const peek = session.page.locator('.peekview-widget');
        await expect(peek).toBeVisible({ timeout: 30_000 });
        await expect(peek).toContainText('References (1)', { timeout: 30_000 });
        await expect(peek).toContainText('trait OthererName {', { timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

async function openReferencesPeek(page: import('@playwright/test').Page, attempts = 6): Promise<void> {
    const peek = page.locator('.peekview-widget');

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        await page.keyboard.press('Shift+F12');

        try {
            await expect(peek).toBeVisible({ timeout: attempt === attempts ? 30_000 : 10_000 });
            return;
        } catch (error) {
            if (attempt === attempts) {
                throw error;
            }

            await page.keyboard.press('Escape');
            await page.waitForTimeout(1_500);
        }
    }
}
