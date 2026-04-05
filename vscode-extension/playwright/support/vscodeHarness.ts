import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { expect, Page } from '@playwright/test';
import { downloadAndUnzipVSCode, resolveCliPathFromVSCodeExecutablePath } from '@vscode/test-electron';
import { ElectronApplication, _electron as electron } from 'playwright';

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
            await app.close();
            fs.rmSync(userDataDir, { recursive: true, force: true });
            if (!useSharedExtensionsDir) {
                fs.rmSync(extensionsDir, { recursive: true, force: true });
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
    await expect.poll(
        async () => page.locator('.notifications-toasts .notification-list-item').evaluateAll(
            nodes => nodes.map(node => node.textContent ?? '')
        ),
        {
            timeout,
            message: 'Timed out waiting for blocking dev-host notifications to clear',
        }
    ).not.toContainEqual(expect.stringMatching(/Opening Java Projects|collect usage data|open the repository/i));
}

function getExtensionRoot(): string {
    return process.cwd();
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
    vscodeExecutablePathPromise ??= downloadAndUnzipVSCode(VSCODE_VERSION);
    return vscodeExecutablePathPromise;
}

async function ensureSharedExtensionsDir(vscodeExecutablePath: string): Promise<string> {
    sharedExtensionsDirPromise ??= Promise.resolve().then(() => {
            const extensionsDir = path.join(getExtensionRoot(), '.playwright-cache', 'extensions');
            fs.mkdirSync(extensionsDir, { recursive: true });

            const hasJavaExtension = fs.readdirSync(extensionsDir, { withFileTypes: true })
                .some(entry => entry.isDirectory() && entry.name.startsWith(`${JAVA_EXTENSION_ID}-`));

            if (!hasJavaExtension) {
                const cliPath = resolveCliPathFromVSCodeExecutablePath(vscodeExecutablePath);
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
            }

            return extensionsDir;
        });

    return sharedExtensionsDirPromise;
}