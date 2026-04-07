import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { expect, Page } from '@playwright/test';
import { downloadAndUnzipVSCode, resolveCliPathFromVSCodeExecutablePath } from '@vscode/test-electron';
import { ElectronApplication, _electron as electron } from 'playwright';

const BLOCKING_NOTIFICATION_PATTERNS = [
    /collect usage data/i,
    /open the repository/i,
];

const DISMISSIBLE_NOTIFICATION_PATTERNS = [
    /Opening Java Projects/i,
];

const SAMPLE_CLASSPATH_READY_PATTERNS = [
    /Sent usable classpath for 1\/1 project\(s\)/i,
    /Sent groovy\/classpathBatchComplete to server \(delivered 1 project\(s\) on attempt \d+\)/i,
];

const VSCODE_VERSION = '1.85.0';
const JAVA_EXTENSION_ID = 'redhat.java';
const GROOVY_STATUS_ITEM_ID = 'TomaszRup.groovy-spock-support';

type UserSettingValue = string | boolean | number;

const DEFAULT_USER_SETTINGS: Record<string, UserSettingValue> = {
    'java.compile.nullAnalysis.mode': 'disabled',
    'workbench.startupEditor': 'none',
    'editor.gotoLocation.multipleDefinitions': 'goto',
    'editor.gotoLocation.multipleTypeDefinitions': 'goto',
    'git.openRepositoryInParentFolders': 'never',
    'redhat.telemetry.enabled': false,
    'telemetry.telemetryLevel': 'off',
};

let vscodeExecutablePathPromise: Promise<string> | undefined;
let sharedExtensionsDirPromise: Promise<string> | undefined;

export interface LaunchVsCodeOptions {
    workspacePath?: string;
    launchArgs?: string[];
    env?: NodeJS.ProcessEnv;
    installJavaExtension?: boolean;
    userSettings?: Record<string, UserSettingValue>;
}

export interface VsCodeSession {
    app: ElectronApplication;
    page: Page;
    userDataDir: string;
    extensionsDir: string;
    close(): Promise<void>;
}

export interface TemporaryWorkspaceCopy {
    workspacePath: string;
    writeFile(relativePath: string, content: string): string;
    seedGradleWrapper(): void;
    dispose(): void;
}

export function getSampleWorkspacePath(): string {
    return path.resolve(getExtensionRoot(), '..', 'sample');
}

export function createWorkspaceCopy(sourceWorkspacePath = getSampleWorkspacePath()): TemporaryWorkspaceCopy {
    const workspacePath = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-vscode-workspace-'));
    fs.cpSync(sourceWorkspacePath, workspacePath, { recursive: true });

    return {
        workspacePath,
        writeFile(relativePath: string, content: string): string {
            const targetPath = path.join(workspacePath, relativePath);
            fs.mkdirSync(path.dirname(targetPath), { recursive: true });
            fs.writeFileSync(targetPath, content);
            return targetPath;
        },
        seedGradleWrapper(): void {
            copyGradleWrapper(workspacePath);
        },
        dispose(): void {
            fs.rmSync(workspacePath, { recursive: true, force: true });
        },
    };
}

export function createTemporaryWorkspace(): TemporaryWorkspaceCopy {
    const workspacePath = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-vscode-workspace-'));

    return {
        workspacePath,
        writeFile(relativePath: string, content: string): string {
            const targetPath = path.join(workspacePath, relativePath);
            fs.mkdirSync(path.dirname(targetPath), { recursive: true });
            fs.writeFileSync(targetPath, content);
            return targetPath;
        },
        seedGradleWrapper(): void {
            copyGradleWrapper(workspacePath);
        },
        dispose(): void {
            fs.rmSync(workspacePath, { recursive: true, force: true });
        },
    };
}

export async function launchVsCode(options: LaunchVsCodeOptions = {}): Promise<VsCodeSession> {
    const vscodeExecutablePath = await ensureVsCodeExecutablePath();
    const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-vscode-user-'));
    writeUserSettings(userDataDir, options.userSettings);
    const useSharedExtensionsDir = options.installJavaExtension !== false;
    const launchEnv: Record<string, string> = {
        ...process.env,
        DONT_PROMPT_WSL_INSTALL: '1',
    };
    if (options.env) {
        for (const [key, value] of Object.entries(options.env)) {
            if (value !== undefined) {
                launchEnv[key] = value;
            }
        }
    }
    const extensionsDir = useSharedExtensionsDir
        ? await ensureSharedExtensionsDir(vscodeExecutablePath)
        : fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-vscode-ext-'));

    const app = await electron.launch({
        executablePath: vscodeExecutablePath,
        args: [
            options.workspacePath ?? getSampleWorkspacePath(),
            '--user-data-dir', userDataDir,
            '--extensions-dir', extensionsDir,
            `--extensionDevelopmentPath=${getExtensionRoot()}`,
            '--disable-workspace-trust',
            '--skip-welcome',
            '--skip-release-notes',
            '--disable-updates',
            '--disable-telemetry',
            ...(options.launchArgs ?? []),
        ],
        env: launchEnv,
    });

    const page = await app.firstWindow();
    await waitForWorkbench(page);

    return {
        app,
        page,
        userDataDir,
        extensionsDir,
        async close(): Promise<void> {
            await closeElectronApp(app);
            await removeDirectoryWithRetries(userDataDir);
            if (!useSharedExtensionsDir) {
                await removeDirectoryWithRetries(extensionsDir);
            }
        },
    };
}

export async function waitForWorkbench(page: Page): Promise<void> {
    await page.locator('.monaco-workbench').waitFor({ state: 'visible', timeout: 120_000 });
}

export async function waitForGroovyReady(page: Page, timeout = 240_000): Promise<string> {
    return waitForGroovyStatus(page, /Groovy: Ready/, timeout);
}

export async function waitForGroovyStatus(page: Page, expected: string | RegExp, timeout = 180_000): Promise<string> {
    const poller = expect.poll(
        async () => getGroovyStatusAriaLabel(page),
        {
            timeout,
            message: `Timed out waiting for Groovy status ${String(expected)}`,
        }
    );

    if (typeof expected === 'string') {
        await poller.toContain(expected);
    } else {
        await poller.toMatch(expected);
    }

    return getGroovyStatusAriaLabel(page);
}

export async function getGroovyStatusAriaLabel(page: Page): Promise<string> {
    const locator = page.locator(getGroovyStatusSelector());
    await expect(locator).toHaveCount(1, { timeout: 120_000 });
    return (await locator.getAttribute('aria-label')) ?? '';
}

export async function runCommand(page: Page, commandLabel: string): Promise<void> {
    await page.keyboard.press('F1');
    const quickInput = page.locator('.quick-input-widget input.input').first();
    await expect(quickInput).toBeVisible({ timeout: 30_000 });
    await quickInput.fill(`>${commandLabel}`);

    const firstPick = page.locator('.quick-input-list .monaco-list-row').first();
    await expect(firstPick).toBeVisible({ timeout: 30_000 });
    await expect(firstPick).toHaveAttribute('aria-label', new RegExp(escapeRegex(commandLabel)));

    await page.keyboard.press('Enter');
    await expect(quickInput).toBeHidden({ timeout: 30_000 });
}

export async function openFile(page: Page, query: string): Promise<void> {
    await page.keyboard.press('Control+P');
    const quickInput = page.locator('.quick-input-widget input.input').first();
    await expect(quickInput).toBeVisible({ timeout: 30_000 });
    await quickInput.fill(query);

    const firstPick = page.locator('.quick-input-list .monaco-list-row').first();
    await expect(firstPick).toBeVisible({ timeout: 30_000 });

    await page.keyboard.press('Enter');
    await expect(quickInput).toBeHidden({ timeout: 30_000 });
    await page.waitForTimeout(1_200);
}

export async function goToDefinition(page: Page, location: string, expectedTabName: string, attempts = 2): Promise<void> {
    let lastError: unknown;

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        await openFile(page, location);
        await runCommand(page, 'Go to Definition');

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

export async function waitForBlockingNotificationsToClear(page: Page, timeout = 120_000): Promise<void> {
    const startedAt = Date.now();

    while (Date.now() - startedAt < timeout) {
        const notificationTexts = await page.locator('.notifications-toasts .notification-list-item').evaluateAll(
            nodes => nodes.map(node => node.textContent ?? '')
        );
        const { blocking, dismissible } = classifyDevHostNotifications(notificationTexts);

        if (blocking.length === 0) {
            if (dismissible.length > 0) {
                await page.keyboard.press('Escape').catch(() => undefined);
                await page.waitForTimeout(300);
            }
            return;
        }

        await page.keyboard.press('Escape').catch(() => undefined);
        await page.waitForTimeout(1_000);
    }

    throw new Error('Timed out waiting for blocking dev-host notifications to clear');
}

export async function waitForSampleClasspathReady(page: Page, closePanel = false): Promise<void> {
    await runCommand(page, 'Groovy: Show Output Channel');
    const usableClasspathLog = page.getByText('Sent usable classpath for 1/1 project(s)', { exact: false });
    const deliveredBatchCompleteLog = page.getByText(
        /Sent groovy\/classpathBatchComplete to server \(delivered 1 project\(s\) on attempt \d+\)/i
    );

    await expect.poll(
        async () => {
            const usableClasspathVisible = await usableClasspathLog.isVisible().catch(() => false);
            if (usableClasspathVisible) {
                return true;
            }
            return deliveredBatchCompleteLog.isVisible().catch(() => false);
        },
        {
            timeout: 60_000,
            message: 'Timed out waiting for sample workspace classpath delivery logs',
        }
    ).toBe(true);

    if (closePanel) {
        await page.keyboard.press('Control+J');
    }
}

function classifyDevHostNotifications(texts: string[]): { blocking: string[]; dismissible: string[] } {
    const blocking: string[] = [];
    const dismissible: string[] = [];

    for (const text of texts) {
        if (BLOCKING_NOTIFICATION_PATTERNS.some(pattern => pattern.test(text))) {
            blocking.push(text);
            continue;
        }
        if (DISMISSIBLE_NOTIFICATION_PATTERNS.some(pattern => pattern.test(text))) {
            dismissible.push(text);
        }
    }

    return { blocking, dismissible };
}

function getExtensionRoot(): string {
    return process.cwd();
}

async function closeElectronApp(app: ElectronApplication): Promise<void> {
    try {
        await app.evaluate(({ app: electronApp }) => electronApp.exit(0));
    } catch {
        // Ignore disconnect errors while the dev host is exiting.
    }

    try {
        await app.close();
    } catch {
        // VS Code may already be gone after the forced exit path above.
    }
}

async function removeDirectoryWithRetries(dirPath: string, attempts = 8, delayMs = 250): Promise<void> {
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            fs.rmSync(dirPath, { recursive: true, force: true });
            return;
        } catch (error) {
            const code = (error as NodeJS.ErrnoException).code;
            if (!isRetryableCleanupErrorCode(code) || attempt === attempts) {
                return;
            }

            await new Promise(resolve => setTimeout(resolve, delayMs * attempt));
        }
    }
}

function isRetryableCleanupErrorCode(code: string | undefined): boolean {
    return code === 'EBUSY' || code === 'ENOTEMPTY' || code === 'EPERM';
}

function getRepositoryRoot(): string {
    return path.resolve(getExtensionRoot(), '..');
}

function copyGradleWrapper(workspacePath: string): void {
    const repositoryRoot = getRepositoryRoot();
    const gradlewSourcePath = path.join(repositoryRoot, 'gradlew');
    const gradlewWindowsSourcePath = path.join(repositoryRoot, 'gradlew.bat');
    const wrapperSourceDir = path.join(repositoryRoot, 'gradle', 'wrapper');
    const gradlewTargetPath = path.join(workspacePath, 'gradlew');

    fs.copyFileSync(gradlewSourcePath, gradlewTargetPath);
    fs.chmodSync(gradlewTargetPath, fs.statSync(gradlewSourcePath).mode);

    if (fs.existsSync(gradlewWindowsSourcePath)) {
        fs.copyFileSync(gradlewWindowsSourcePath, path.join(workspacePath, 'gradlew.bat'));
    }

    fs.cpSync(wrapperSourceDir, path.join(workspacePath, 'gradle', 'wrapper'), { recursive: true });
}

function getGroovyStatusSelector(): string {
    const escapedId = GROOVY_STATUS_ITEM_ID.replace('.', String.raw`\.`);
    return `#${escapedId}`;
}

function escapeRegex(value: string): string {
    return value.replaceAll(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`);
}

function writeUserSettings(userDataDir: string, userSettings: Record<string, UserSettingValue> | undefined): void {
    const userDir = path.join(userDataDir, 'User');
    const settings = userSettings
        ? {
            ...DEFAULT_USER_SETTINGS,
            ...userSettings,
        }
        : DEFAULT_USER_SETTINGS;
    fs.mkdirSync(userDir, { recursive: true });
    fs.writeFileSync(
        path.join(userDir, 'settings.json'),
        JSON.stringify(settings, null, 2)
    );
}

async function ensureVsCodeExecutablePath(): Promise<string> {
    vscodeExecutablePathPromise ??= acquireInstallLock(
        path.join(getExtensionRoot(), '.playwright-cache', `vscode-${VSCODE_VERSION}.lock`)
    ).then(async releaseLock => {
        try {
            return await downloadAndUnzipVSCode(VSCODE_VERSION);
        } finally {
            releaseLock();
        }
    });
    return vscodeExecutablePathPromise;
}

async function ensureSharedExtensionsDir(vscodeExecutablePath: string): Promise<string> {
    sharedExtensionsDirPromise ??= Promise.resolve().then(() => {
            const extensionsDir = path.join(getExtensionRoot(), '.playwright-cache', 'extensions');
            fs.mkdirSync(extensionsDir, { recursive: true });

            return extensionsDir;
        }).then(async extensionsDir => {
            if (!hasInstalledJavaExtension(extensionsDir)) {
                const releaseLock = await acquireInstallLock(`${extensionsDir}.install.lock`);

                try {
                    if (!hasInstalledJavaExtension(extensionsDir)) {
                        installJavaExtension(vscodeExecutablePath, extensionsDir);
                    }
                } finally {
                    releaseLock();
                }
            }

            return extensionsDir;
        });

    return sharedExtensionsDirPromise;
}

function hasInstalledJavaExtension(extensionsDir: string): boolean {
    return fs.readdirSync(extensionsDir, { withFileTypes: true })
        .some(entry => entry.isDirectory() && entry.name.startsWith(`${JAVA_EXTENSION_ID}-`));
}

async function acquireInstallLock(lockDir: string, timeoutMs = 180_000): Promise<() => void> {
    const startedAt = Date.now();
    fs.mkdirSync(path.dirname(lockDir), { recursive: true });

    while (true) {
        try {
            fs.mkdirSync(lockDir);
            return () => {
                try {
                    fs.rmSync(lockDir, { recursive: true, force: true });
                } catch {
                    // Best effort cleanup only.
                }
            };
        } catch (error) {
            const code = (error as NodeJS.ErrnoException).code;
            if (code !== 'EEXIST') {
                throw error;
            }

            if ((Date.now() - startedAt) >= timeoutMs) {
                throw new Error(`Timed out waiting for Playwright extension install lock: ${lockDir}`);
            }

            await new Promise(resolve => setTimeout(resolve, 500));
        }
    }
}

function installJavaExtension(vscodeExecutablePath: string, extensionsDir: string, attempts = 3): void {
    const cliPath = resolveCliPathFromVSCodeExecutablePath(vscodeExecutablePath);

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            execFileSync(
                cliPath,
                ['--install-extension', JAVA_EXTENSION_ID, '--extensions-dir', extensionsDir],
                {
                    cwd: getExtensionRoot(),
                    env: {
                        ...process.env,
                        DONT_PROMPT_WSL_INSTALL: '1',
                    },
                    stdio: 'inherit',
                }
            );
            return;
        } catch (error) {
            if (hasInstalledJavaExtension(extensionsDir)) {
                return;
            }

            if (attempt === attempts) {
                throw error;
            }
        }
    }
}