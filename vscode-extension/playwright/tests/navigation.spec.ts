import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    goToDefinition,
    launchVsCode,
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

        await goToDefinition(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:3:49',
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

        await goToDefinition(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:4:36',
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

        await goToDefinition(
            session.page,
            'src/test/groovy/com/example/sample/SampleApplicationSpec.groovy:6:19',
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