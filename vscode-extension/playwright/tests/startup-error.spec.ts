import { expect, test } from '@playwright/test';
import { createWorkspaceCopy, launchVsCode, waitForGroovyStatus } from '../support/vscodeHarness';

test('shows an error status when no Java runtime is available', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({
        workspacePath: workspace.workspacePath,
        env: {
            JAVA_HOME: '/tmp/definitely-missing-java-home',
            PATH: '/tmp/definitely-missing-bin',
        },
    });

    try {
        const statusLabel = await waitForGroovyStatus(session.page, /Groovy: Error/, 120_000);

        expect(statusLabel).toContain('Groovy: Error');
        expect(statusLabel).toContain('JDK not found');
    } finally {
        await session.close();
        await workspace.dispose();
    }
});

test('opens the output channel from the error status bar item', async () => {
    const workspace = createWorkspaceCopy();
    const session = await launchVsCode({
        workspacePath: workspace.workspacePath,
        env: {
            JAVA_HOME: '/tmp/definitely-missing-java-home',
            PATH: '/tmp/definitely-missing-bin',
        },
    });

    try {
        await waitForGroovyStatus(session.page, /Groovy: Error/, 120_000);

        await session.page.locator(String.raw`#TomaszRup\.groovy-spock-support`).click();

        await expect(session.page.getByText('activate.commands-registered', { exact: false })).toBeVisible({ timeout: 30_000 });
        await expect(session.page.getByText('classpath delegation enabled', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        await workspace.dispose();
    }
});