import * as fs from 'node:fs';
import * as path from 'node:path';
import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    waitForSampleClasspathReady,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('applies member completion for a Groovy method', async () => {
    const workspace = createWorkspaceCopy();
    const scratchFilePath = path.join(
        workspace.workspacePath,
        'src',
        'test',
        'groovy',
        'com',
        'example',
        'sample',
        'CompletionScratch.groovy'
    );

    fs.writeFileSync(
        scratchFilePath,
        [
            'package com.example.sample',
            '',
            'class Sample {',
            '    String greet(String name) { "hi ${name}" }',
            '}',
            '',
            'def value = new Sample()',
            'value.gr',
            '',
        ].join('\n')
    );

    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await waitForSampleClasspathReady(session.page, true);

        await openFile(session.page, 'src/test/groovy/com/example/sample/CompletionScratch.groovy:8:9');
        await session.page.keyboard.press('Control+Space');

        const suggestWidget = session.page.locator('.suggest-widget');
        await expect(suggestWidget).toBeVisible({ timeout: 30_000 });
        await expect(suggestWidget).toContainText('greet', { timeout: 30_000 });

        const greetRow = session.page.locator('.suggest-widget .monaco-list-row', {
            hasText: 'greet',
        }).first();
        await expect(greetRow).toBeVisible({ timeout: 30_000 });
        await greetRow.click();

        await session.page.keyboard.press('Enter');

        await expect(session.page.locator('.view-lines')).toContainText('value.greet(', { timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('applies known-type completion for a workspace class', async () => {
    const workspace = createWorkspaceCopy();
    const scratchFilePath = path.join(
        workspace.workspacePath,
        'src',
        'test',
        'groovy',
        'com',
        'example',
        'sample',
        'KnownTypeCompletionScratch.groovy'
    );

    fs.writeFileSync(
        scratchFilePath,
        [
            'package com.example.sample',
            '',
            'def value = new Bab',
            '',
        ].join('\n')
    );

    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await waitForSampleClasspathReady(session.page, true);

        await openFile(session.page, 'src/test/groovy/com/example/sample/KnownTypeCompletionScratch.groovy:3:20');
        await expect(session.page.locator('.tabs-container .tab.active')).toContainText('KnownTypeCompletionScratch.groovy', {
            timeout: 30_000,
        });
        await waitForCompletionSuggestion(session.page, 'Bababxa');

        await session.page.keyboard.press('Enter');

        await expect(session.page.getByText('def value = new Bababxa', { exact: false })).toBeVisible({ timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});

async function waitForCompletionSuggestion(
    page: import('@playwright/test').Page,
    expectedText: string,
    attempts = 6
): Promise<void> {
    const suggestWidget = page.locator('.suggest-widget');

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        await page.keyboard.press('Control+Space');
        await expect(suggestWidget).toBeVisible({ timeout: 30_000 });

        try {
            await expect(suggestWidget).toContainText(expectedText, { timeout: 10_000 });
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