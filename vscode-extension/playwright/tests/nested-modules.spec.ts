import { expect, test } from '@playwright/test';
import {
    createTemporaryWorkspace,
    launchVsCode,
    openFile,
    runCommand,
    waitForBlockingNotificationsToClear,
    waitForGroovyReady,
} from '../support/vscodeHarness';

test('resolves classpath for a deeply nested sibling Gradle module', async () => {
    test.setTimeout(10 * 60 * 1000);
    const workspace = createTemporaryWorkspace();
    workspace.seedGradleWrapper();

    workspace.writeFile(
        'settings.gradle',
        [
            "rootProject.name = 'nested-sample'",
            "include ':modules:platform:shared'",
            "include ':modules:apps:service'",
            '',
        ].join('\n')
    );

    workspace.writeFile(
        'build.gradle',
        [
            'subprojects {',
            "    apply plugin: 'java'",
            "    apply plugin: 'groovy'",
            '',
            '    repositories {',
            '        mavenCentral()',
            '    }',
            '',
            '    dependencies {',
            '        implementation localGroovy()',
            '    }',
            '}',
            '',
        ].join('\n')
    );

    workspace.writeFile('modules/platform/shared/build.gradle', '');
    workspace.writeFile(
        'modules/platform/shared/src/main/groovy/com/example/shared/SharedGreeter.groovy',
        [
            'package com.example.shared',
            '',
            'class SharedGreeter {',
            '    String greet(String name) {',
            '        "Hello, ${name}"',
            '    }',
            '}',
            '',
        ].join('\n')
    );

    workspace.writeFile(
        'modules/apps/service/build.gradle',
        "dependencies { implementation project(':modules:platform:shared') }\n"
    );
    workspace.writeFile(
        'modules/apps/service/src/main/groovy/com/example/service/ServiceRunner.groovy',
        [
            'package com.example.service',
            '',
            'import com.example.shared.SharedGreeter',
            '',
            'class ServiceRunner {',
            '    def run() {',
            '        new SharedGreeter()',
            '    }',
            '}',
            '',
        ].join('\n')
    );

    const session = await launchVsCode({ workspacePath: workspace.workspacePath });

    try {
        await waitForGroovyReady(session.page);
        await waitForBlockingNotificationsToClear(session.page);

        await openFile(
            session.page,
            'modules/apps/service/src/main/groovy/com/example/service/ServiceRunner.groovy'
        );
        await expect(session.page.locator('.tabs-container .tab.active')).toContainText('ServiceRunner.groovy', {
            timeout: 30_000,
        });

        await runCommand(session.page, 'Groovy: Show Output Channel');

        await expect(session.page.getByText(/projectPath=.*modules[\\/]apps[\\/]service/)).toBeVisible({
            timeout: 60_000,
        });
        await expect(session.page.getByText(/Sent usable classpath for \d+\/\d+ project\(s\)/)).toBeVisible({
            timeout: 60_000,
        });
        await expect(session.page.getByText('[status] Ready', { exact: false })).toBeVisible({ timeout: 60_000 });
    } finally {
        await session.close();
        workspace.dispose();
    }
});
