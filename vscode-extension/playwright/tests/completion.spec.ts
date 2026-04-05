import * as fs from 'node:fs';
import * as path from 'node:path';
import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
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

        await openFile(session.page, 'src/test/groovy/com/example/sample/KnownTypeCompletionScratch.groovy:3:20');
        await session.page.keyboard.press('Control+Space');

        const suggestWidget = session.page.locator('.suggest-widget');
        await expect(suggestWidget).toBeVisible({ timeout: 30_000 });
        await expect(suggestWidget).toContainText('Bababxa', { timeout: 30_000 });

        await session.page.keyboard.press('Enter');

        await expect(session.page.locator('.view-lines')).toContainText('new Bababxa', { timeout: 30_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});