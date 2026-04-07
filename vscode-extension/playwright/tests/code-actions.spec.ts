import * as fs from 'node:fs';
import * as path from 'node:path';
import { expect, test } from '@playwright/test';
import {
    createWorkspaceCopy,
    launchVsCode,
    openFile,
    runCommand,
    waitForBlockingNotificationsToClear,
    waitForGroovyOutputText,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('organize imports removes an unused Groovy import', async () => {
    const workspace = createWorkspaceCopy();
    const scratchFilePath = path.join(
        workspace.workspacePath,
        'src',
        'test',
        'groovy',
        'com',
        'example',
        'sample',
        'CodeActionScratch.groovy'
    );

    fs.writeFileSync(
        scratchFilePath,
        [
            'package com.example.sample',
            '',
            'import java.time.LocalDate',
            '',
            'class CodeActionScratch {',
            '    String name',
            '}',
            '',
        ].join('\n')
    );

    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await openFile(session.page, 'src/test/groovy/com/example/sample/CodeActionScratch.groovy:3:20');
        await expect(session.page.locator('.tabs-container .tab.active')).toContainText('CodeActionScratch.groovy', {
            timeout: 30_000,
        });

        await runCommand(session.page, 'Organize Imports');
        await session.page.keyboard.press('Control+S');

        await expect.poll(() => fs.readFileSync(scratchFilePath, 'utf8'), {
            timeout: 30_000,
            message: 'Timed out waiting for Organize Imports to save CodeActionScratch.groovy',
        }).not.toContain('import java.time.LocalDate');

        const updatedSource = fs.readFileSync(scratchFilePath, 'utf8');
        expect(updatedSource).toContain('class CodeActionScratch');
        expect(updatedSource).toContain('String name');
    } finally {
        await session.close();
        workspace.dispose();
    }
});

test('quick fix creates a missing Groovy class', async () => {
    const workspace = createWorkspaceCopy();
    const scratchFilePath = path.join(
        workspace.workspacePath,
        'src',
        'test',
        'groovy',
        'com',
        'example',
        'sample',
        'QuickFixScratch.groovy'
    );
    const createdFilePath = path.join(
        workspace.workspacePath,
        'bin',
        'test',
        'com',
        'example',
        'sample',
        'Foo.groovy'
    );

    fs.writeFileSync(
        scratchFilePath,
        [
            'package com.example.sample',
            '',
            'class QuickFixScratch {',
            '    Foo value',
            '}',
            '',
        ].join('\n')
    );

    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);
        await waitForGroovyOutputText(session.page, /Sent usable classpath for \d+\/\d+ project\(s\)/);

        await openFile(session.page, 'src/test/groovy/com/example/sample/QuickFixScratch.groovy:4:6');
        await applyQuickFix(session.page, "Create class 'Foo'");

        await expect.poll(() => fs.existsSync(createdFilePath), {
            timeout: 30_000,
            message: 'Timed out waiting for Create class quick fix to create Foo.groovy',
        }).toBe(true);

        expect(fs.readFileSync(createdFilePath, 'utf8')).toContain('class Foo');
    } finally {
        await session.close();
        workspace.dispose();
    }
});

async function applyQuickFix(
    page: import('@playwright/test').Page,
    title: string,
    attempts = 6
): Promise<void> {
    const quickFixRow = page.locator('.context-view .monaco-list-row', {
        hasText: title,
    }).first();
    let lastError: unknown;

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        await page.keyboard.press('Control+.');

        try {
            await expect(quickFixRow).toBeVisible({ timeout: 10_000 });
            await quickFixRow.click();
            return;
        } catch (error) {
            lastError = error;
            await page.keyboard.press('Escape');

            try {
                await runCommand(page, 'Quick Fix...');
                await expect(quickFixRow).toBeVisible({ timeout: 10_000 });
                await quickFixRow.click();
                return;
            } catch (fallbackError) {
                lastError = fallbackError;
            }

            if (attempt === attempts) {
                throw lastError;
            }

            await page.waitForTimeout(1_500);
        }
    }
}
