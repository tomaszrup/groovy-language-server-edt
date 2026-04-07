import { defineConfig } from '@playwright/test';

const configuredWorkers = Number(process.env.PLAYWRIGHT_WORKERS ?? '1');
const workers = Number.isFinite(configuredWorkers) && configuredWorkers > 0
    ? Math.floor(configuredWorkers)
    : 1;

export default defineConfig({
    testDir: './playwright/tests',
    fullyParallel: false,
    workers,
    timeout: 6 * 60 * 1000,
    expect: {
        timeout: 2 * 60 * 1000,
    },
    outputDir: 'playwright-results/artifacts',
    reporter: [
        ['list'],
        ['html', { open: 'never', outputFolder: 'playwright-results/html' }],
    ],
    use: {
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
    },
});