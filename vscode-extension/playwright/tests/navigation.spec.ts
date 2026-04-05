import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('opens SpringBootTest external library source from a Groovy import', async () => {
    test.setTimeout(10 * 60 * 1000);
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await goToDefinitionByCtrlClick(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:3:49',
            'SpringBootTest',
            'SpringBootTest.java'
        );

        const activeTab = session.page.locator('.tabs-container .tab.active');
        await expect(activeTab).toContainText('SpringBootTest.java', { timeout: 30_000 });
        await expect(session.page.locator('.tabs-container .tab')).toHaveCount(2, { timeout: 30_000 });
        await expect(session.page.getByText('@BootstrapWith(SpringBootTestContextBootstrapper.class)', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('opens Spring external library source from a Groovy import', async () => {
    test.setTimeout(10 * 60 * 1000);
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await goToDefinitionByCtrlClick(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:4:36',
            'ApplicationContext',
            'ApplicationContext.java'
        );

        const activeTab = session.page.locator('.tabs-container .tab.active');
        await expect(activeTab).toContainText('ApplicationContext.java', { timeout: 30_000 });
        await expect(session.page.locator('.tabs-container .tab')).toHaveCount(2, { timeout: 30_000 });
        await expect(session.page.getByText('public interface ApplicationContext extends', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('opens Spock external library source from a Groovy import', async () => {
    test.setTimeout(10 * 60 * 1000);
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await goToDefinitionByCtrlClick(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:6:19',
            'Specification',
            'Specification.java'
        );

        const activeTab = session.page.locator('.tabs-container .tab.active');
        await expect(activeTab).toContainText('Specification.java', { timeout: 30_000 });
        await expect(session.page.locator('.tabs-container .tab')).toHaveCount(2, { timeout: 30_000 });
        await expect(session.page.getByText('Base class for Spock specifications.', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

async function goToDefinitionByCtrlClick(
    page: import('@playwright/test').Page,
    location: string,
    symbolText: string,
    expectedTabName: string,
    attempts = 3
): Promise<void> {
    let lastError: unknown;

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        await openFile(page, location);

        const symbol = page.getByText(symbolText, { exact: true }).nth(0);
        await expect(symbol).toBeVisible({ timeout: 30_000 });
        await symbol.click({ modifiers: [process.platform === 'darwin' ? 'Meta' : 'Control'] });

        try {
            await expect(page.locator('.tabs-container .tab.active')).toContainText(expectedTabName, {
                timeout: attempt === attempts ? 30_000 : 5_000,
            });
            return;
        } catch (error) {
            lastError = error;

            if (attempt === attempts) {
                throw error;
            }

            await page.keyboard.press('Escape');
            await page.waitForTimeout(1_500);
        }
    }

    throw lastError;
}