const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const extensionRoot = path.resolve(__dirname, '..');
const repositoryRoot = path.resolve(extensionRoot, '..');
const bundledServerDir = path.join(extensionRoot, 'server');
const gradleProductDir = path.join(repositoryRoot, 'org.eclipse.groovy.ls.product', 'build', 'product');

if (hasServerPayload(bundledServerDir) || hasServerPayload(gradleProductDir)) {
    process.exit(0);
}

const gradleWrapper = path.join(
    repositoryRoot,
    process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'
);

if (!fs.existsSync(gradleWrapper)) {
    throw new Error(`Gradle wrapper not found at ${gradleWrapper}`);
}

execFileSync(gradleWrapper, [':org.eclipse.groovy.ls.product:assembleProduct'], {
    cwd: repositoryRoot,
    stdio: 'inherit',
});

if (!hasServerPayload(bundledServerDir) && !hasServerPayload(gradleProductDir)) {
    throw new Error('Groovy Language Server product build completed, but no server payload was found for Playwright.');
}

function hasServerPayload(serverDir) {
    const pluginsDir = path.join(serverDir, 'plugins');
    if (!fs.existsSync(pluginsDir)) {
        return false;
    }

    try {
        return fs.readdirSync(pluginsDir).some(
            entry => entry.startsWith('org.eclipse.equinox.launcher_') && entry.endsWith('.jar')
        );
    } catch {
        return false;
    }
}