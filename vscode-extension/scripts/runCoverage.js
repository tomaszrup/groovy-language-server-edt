const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const projectRoot = path.resolve(__dirname, '..');
const coverageDir = path.join(projectRoot, 'coverage');
const tempCoverageDir = path.join(coverageDir, 'tmp');
const compiledDir = path.join(projectRoot, '.coverage-out');

main();

function main() {
    resetCoverageWorkspace();

    run(process.execPath, [
        require.resolve('typescript/bin/tsc'),
        '-p',
        'tsconfig.json',
        '--outDir',
        '.coverage-out',
        '--declaration',
        'false',
        '--declarationMap',
        'false',
    ]);

    run(
        process.execPath,
        [
            path.join(projectRoot, 'node_modules', 'mocha', 'bin', '_mocha'),
            '.coverage-out/test/**/*.test.js',
        ],
        {
            env: {
                ...process.env,
                NODE_V8_COVERAGE: tempCoverageDir,
            },
        }
    );

    run(process.execPath, [
        path.join(projectRoot, 'node_modules', 'c8', 'bin', 'c8.js'),
        'report',
        '--temp-directory',
        tempCoverageDir,
        '--report-dir',
        coverageDir,
        '--all',
        '--exclude-after-remap',
        '--src',
        'src',
        '--exclude',
        'src/test/**',
        '--exclude',
        'dist/**',
        '--exclude',
        'out/**',
        '--reporter',
        'text-summary',
        '--reporter',
        'html',
        '--reporter',
        'lcov',
        '--reporter',
        'json-summary',
    ]);

    fs.rmSync(compiledDir, { recursive: true, force: true });
}

function resetCoverageWorkspace() {
    fs.rmSync(compiledDir, { recursive: true, force: true });
    fs.rmSync(coverageDir, { recursive: true, force: true });
    fs.mkdirSync(tempCoverageDir, { recursive: true });
}

function run(command, args, options = {}) {
    const result = spawnSync(command, args, {
        cwd: projectRoot,
        stdio: 'inherit',
        ...options,
    });

    if (result.status !== 0) {
        process.exit(result.status ?? 1);
    }
}